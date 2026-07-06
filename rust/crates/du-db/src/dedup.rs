//! Biosample **duplicate-candidate** engine (Tier 1).
//!
//! Multiple researchers contribute overlapping datasets (public academic WGS,
//! FTDNA project-admin uploads), so the same physical individual can land as
//! several `core.biosample` rows under different accessions. Exact-accession
//! dedup (`core.biosample` unique index) catches re-used identifiers; this engine
//! catches the rest by **blocking** samples that share a terminal Y *and* mt
//! haplogroup and **refining** with private-variant set Jaccard.
//!
//! It produces **candidates, never confirmations.** Uniparental markers plus
//! shared private SNPs cannot separate a true duplicate from a sibling or an
//! extended patriline — that needs autosomal IBS0/IBD2 (the Tier-2 step, recorded
//! later in `dedup.duplicate_candidate.verdict`). So every row this engine writes
//! is `status = 'CANDIDATE'`, carrying a *suspicion* score, not a probability.
//! See `documents/proposals/biosample-duplicate-detection.md`.
//!
//! Source-agnostic: it reads the resolved `tree.*` tables (`haplogroup_sample`,
//! `biosample_private_variant`) that both the de-novo loader and the federated
//! discovery path converge into — not `fed.*` directly. Structure mirrors
//! [`crate::discovery`]: an advisory-lock wrapper and a **declarative** recompute
//! (CANDIDATE rows are regenerated and stale ones pruned each run; curator/Tier-2
//! states are never auto-touched).

use crate::DbError;
use serde_json::Value;
use sqlx::PgPool;
use std::collections::HashMap;
use uuid::Uuid;

/// Advisory-lock key guarding concurrent recomputes (daily job vs. manual run).
const DEDUP_ADVISORY_KEY: i64 = 0x4445_4455_50; // "DEDUP"

/// Tier-1 candidate-generation configuration.
#[derive(Debug, Clone)]
pub struct DedupConfig {
    /// A pair that shares a terminal Y but has no shared mt terminal is emitted
    /// only when its private-variant sets overlap at least this much — without mt,
    /// a shared shallow haplogroup is far too coarse (a whole clade) to be a
    /// candidate on its own.
    pub y_only_min_jaccard: f64,
    /// ...and shares at least this many private variants (guards tiny-set Jaccard).
    pub y_only_min_shared: i64,
    /// Score blend: weight on terminal-mt agreement.
    pub w_mt: f64,
    /// Score blend: weight on private-variant Jaccard. (STR-marker agreement is a
    /// planned third signal; `fed.str_profile` is not yet linked to de-novo
    /// samples, so it contributes nothing today and is omitted rather than faked.)
    pub w_jaccard: f64,
}

impl Default for DedupConfig {
    fn default() -> Self {
        Self { y_only_min_jaccard: 0.5, y_only_min_shared: 3, w_mt: 0.4, w_jaccard: 0.6 }
    }
}

/// Outcome of [`recompute_candidates`].
#[derive(Debug, Default, Clone)]
pub struct DedupReport {
    pub males: u64,
    pub multi_blocks: u64,
    pub pairs_evaluated: u64,
    pub candidates_written: u64,
    pub candidates_pruned: u64,
    pub mt_match_pairs: u64,
    pub jaccard_pairs: u64,
    /// IDENTIFIER-tier candidates re-adjudicated against current placement (UNPLACED → CONFIRMED/DISPUTED).
    pub identifier_readjudicated: u64,
}

/// Intersection size and Jaccard of two ascending-sorted id sets.
fn jaccard(a: &[i64], b: &[i64]) -> (usize, f64) {
    let (mut i, mut j, mut inter) = (0usize, 0usize, 0usize);
    while i < a.len() && j < b.len() {
        match a[i].cmp(&b[j]) {
            std::cmp::Ordering::Less => i += 1,
            std::cmp::Ordering::Greater => j += 1,
            std::cmp::Ordering::Equal => {
                inter += 1;
                i += 1;
                j += 1;
            }
        }
    }
    let union = a.len() + b.len() - inter;
    (inter, if union == 0 { 0.0 } else { inter as f64 / union as f64 })
}

/// A scored candidate pair: the suspicion score and per-signal detail.
struct PairScore {
    score: f64,
    mt_match: bool,
    shared: usize,
    jac: f64,
}

/// Decide whether a same-terminal-Y pair is a candidate and score it. `mt_*` is the
/// per-sample terminal mt haplogroup id (None if unplaced). Returns None when the
/// pair fails the emit gate (mt disagreement/absence *and* weak private overlap).
fn evaluate_pair(
    a_v: &[i64],
    b_v: &[i64],
    mt_a: Option<i64>,
    mt_b: Option<i64>,
    cfg: &DedupConfig,
) -> Option<PairScore> {
    let mt_match = matches!((mt_a, mt_b), (Some(x), Some(y)) if x == y);
    let (shared, jac) = jaccard(a_v, b_v);
    let private_ok = shared as i64 >= cfg.y_only_min_shared && jac >= cfg.y_only_min_jaccard;
    if !mt_match && !private_ok {
        return None;
    }
    let score = (cfg.w_mt * if mt_match { 1.0 } else { 0.0 } + cfg.w_jaccard * jac).min(1.0);
    Some(PairScore { score, mt_match, shared, jac })
}

/// Recompute Tier-1 duplicate candidates. Single-flighted by a session advisory
/// lock (a second caller no-ops). Unlocks on every path.
pub async fn recompute_candidates(pool: &PgPool, cfg: &DedupConfig) -> Result<DedupReport, DbError> {
    let mut lock_conn = pool.acquire().await?;
    let locked: bool = sqlx::query_scalar("SELECT pg_try_advisory_lock($1)")
        .bind(DEDUP_ADVISORY_KEY)
        .fetch_one(&mut *lock_conn)
        .await?;
    if !locked {
        return Ok(DedupReport::default());
    }
    let result = async {
        let mut rep = recompute_locked(pool, cfg).await?;
        // Re-adjudicate IDENTIFIER candidates now that placements are current (federated anchors
        // that raised UNPLACED candidates at ingest get confirmed once their sample is placed).
        rep.identifier_readjudicated = readjudicate_identifier_candidates(pool).await?;
        Ok::<_, DbError>(rep)
    }
    .await;
    let _ = sqlx::query("SELECT pg_advisory_unlock($1)")
        .bind(DEDUP_ADVISORY_KEY)
        .execute(&mut *lock_conn)
        .await;
    result
}

async fn recompute_locked(pool: &PgPool, cfg: &DedupConfig) -> Result<DedupReport, DbError> {
    let mut rep = DedupReport::default();

    // Males (samples with a PLACED terminal Y) + their PLACED terminal mt (if any).
    let males: Vec<(Uuid, i64, Option<i64>)> = sqlx::query_as(
        "SELECT y.sample_guid, y.haplogroup_id, m.haplogroup_id \
         FROM tree.haplogroup_sample y \
         LEFT JOIN tree.haplogroup_sample m \
           ON m.sample_guid = y.sample_guid \
          AND m.dna_type = 'MT_DNA'::core.dna_type AND m.status = 'PLACED' \
         WHERE y.dna_type = 'Y_DNA'::core.dna_type AND y.status = 'PLACED' \
           AND y.haplogroup_id IS NOT NULL",
    )
    .fetch_all(pool)
    .await?;
    rep.males = males.len() as u64;

    // Per-sample private-variant sets (Y, ACTIVE), with the shared recurrent-region
    // mask applied so a recurrent miscall shared across samples can't manufacture a
    // false overlap (same FP firewall the discovery + age models use).
    let mask = crate::variant::recurrent_region_mask_sql("v");
    let priv_rows: Vec<(Uuid, Vec<i64>)> = sqlx::query_as(&format!(
        "SELECT bpv.sample_guid, array_agg(bpv.variant_id ORDER BY bpv.variant_id) \
         FROM tree.biosample_private_variant bpv \
         JOIN core.variant v ON v.id = bpv.variant_id \
         WHERE bpv.status = 'ACTIVE' AND bpv.haplogroup_type = 'Y_DNA'::core.dna_type AND {mask} \
         GROUP BY bpv.sample_guid"
    ))
    .fetch_all(pool)
    .await?;
    let priv_sets: HashMap<Uuid, Vec<i64>> = priv_rows.into_iter().collect();

    // Sample metadata for signals + Tier-2 routing (accession, public flag).
    let meta: HashMap<Uuid, (Option<String>, bool)> =
        sqlx::query_as::<_, (Uuid, Option<String>, Option<bool>)>(
            "SELECT sample_guid, accession, is_public FROM core.biosample WHERE NOT deleted",
        )
        .fetch_all(pool)
        .await?
        .into_iter()
        .map(|(g, acc, pubf)| (g, (acc, pubf.unwrap_or(false))))
        .collect();

    // Block by terminal Y haplogroup (the coarse filter); pairs live within a block.
    let mut blocks: HashMap<i64, Vec<usize>> = HashMap::new();
    for (idx, (_, y_hg, _)) in males.iter().enumerate() {
        blocks.entry(*y_hg).or_default().push(idx);
    }

    let empty: Vec<i64> = Vec::new();
    // (sample_a, sample_b, tier, block_key, score, signals) staged for upsert.
    let mut staged: Vec<(Uuid, Uuid, String, f64, Value)> = Vec::new();

    // Deterministic block order (stable logging / reproducibility).
    let mut keys: Vec<i64> = blocks.keys().copied().collect();
    keys.sort_unstable();
    for y_hg in keys {
        let members = &blocks[&y_hg];
        if members.len() < 2 {
            continue;
        }
        rep.multi_blocks += 1;
        for a in 0..members.len() {
            for b in (a + 1)..members.len() {
                let (gi, _, mt_i) = males[members[a]];
                let (gj, _, mt_j) = males[members[b]];
                rep.pairs_evaluated += 1;
                let vi = priv_sets.get(&gi).unwrap_or(&empty);
                let vj = priv_sets.get(&gj).unwrap_or(&empty);
                let Some(ps) = evaluate_pair(vi, vj, mt_i, mt_j, cfg) else { continue };
                if ps.mt_match {
                    rep.mt_match_pairs += 1;
                }
                if ps.jac > 0.0 {
                    rep.jaccard_pairs += 1;
                }
                // Canonical unordered pair (sample_a < sample_b) to satisfy the
                // CHECK + pair-unique constraint.
                let (sa, sb, mt_a, mt_b) = if gi < gj { (gi, gj, mt_i, mt_j) } else { (gj, gi, mt_j, mt_i) };
                let (acc_a, pub_a) = meta.get(&sa).cloned().unwrap_or((None, false));
                let (acc_b, pub_b) = meta.get(&sb).cloned().unwrap_or((None, false));
                let block_key = match (mt_a, mt_b) {
                    (Some(x), Some(y)) if x == y => format!("Y={y_hg};MT={x}"),
                    _ => format!("Y={y_hg}"),
                };
                let signals = serde_json::json!({
                    "y_haplogroup_id": y_hg,
                    "mt_a": mt_a, "mt_b": mt_b, "mt_match": ps.mt_match,
                    "shared_private": ps.shared, "jaccard": ps.jac,
                    "private_a": priv_sets.get(&sa).map(|v| v.len()).unwrap_or(0),
                    "private_b": priv_sets.get(&sb).map(|v| v.len()).unwrap_or(0),
                    "accession_a": acc_a, "accession_b": acc_b,
                    "both_public": pub_a && pub_b,
                    // Tier-2 routing hint: both public WGS → ingest-time autosomal
                    // fingerprint; otherwise Edge-mediated or unconfirmable.
                    "tier2_route": if pub_a && pub_b { "AUTOSOMAL_PUBLIC" } else { "EDGE_OR_UNCONFIRMABLE" },
                });
                staged.push((sa, sb, block_key, ps.score, signals));
            }
        }
    }

    // Declarative upsert + prune, mirroring the discovery engine.
    let mut tx = pool.begin().await?;
    let mut kept: Vec<i64> = Vec::new();
    for (sa, sb, block_key, score, signals) in &staged {
        // DO UPDATE only while the row is still an engine-owned CANDIDATE; a
        // curator/Tier-2 decision (CONFIRMED_DUPLICATE/RELATIVE/...) is never
        // overwritten, and its id stays out of `kept` so the prune leaves it.
        let id: Option<i64> = sqlx::query_scalar(
            "INSERT INTO dedup.duplicate_candidate \
                (sample_a, sample_b, tier, block_key, score, signals, status) \
             VALUES ($1, $2, 'UNIPARENTAL', $3, $4, $5, 'CANDIDATE') \
             ON CONFLICT (sample_a, sample_b) DO UPDATE SET \
                tier = EXCLUDED.tier, block_key = EXCLUDED.block_key, \
                score = EXCLUDED.score, signals = EXCLUDED.signals, updated_at = now() \
             WHERE dedup.duplicate_candidate.status = 'CANDIDATE' \
               AND dedup.duplicate_candidate.tier = 'UNIPARENTAL' \
             RETURNING id",
        )
        .bind(sa)
        .bind(sb)
        .bind(block_key)
        .bind(score)
        .bind(signals)
        .fetch_optional(&mut *tx)
        .await?;
        if let Some(id) = id {
            kept.push(id);
            rep.candidates_written += 1;
        }
    }

    // Prune only THIS engine's own (UNIPARENTAL) candidates — IDENTIFIER-tier rows are owned
    // by the identifier pipeline and must survive the uniparental recompute.
    rep.candidates_pruned = sqlx::query(
        "DELETE FROM dedup.duplicate_candidate \
         WHERE status = 'CANDIDATE' AND tier = 'UNIPARENTAL' AND id <> ALL($1)",
    )
    .bind(&kept)
    .execute(&mut *tx)
    .await?
    .rows_affected();

    tx.commit().await?;
    Ok(rep)
}

/// A duplicate-candidate row for read surfaces (curator triage).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct CandidateView {
    pub id: i64,
    pub sample_a: Uuid,
    pub sample_b: Uuid,
    pub tier: String,
    pub block_key: Option<String>,
    pub score: f64,
    pub signals: Value,
    pub status: String,
}

/// Fetch a single candidate by id (for the curator panel).
pub async fn candidate(pool: &PgPool, id: i64) -> Result<Option<CandidateView>, DbError> {
    let row = sqlx::query_as::<_, CandidateView>(
        "SELECT id, sample_a, sample_b, tier, block_key, score, signals, status \
         FROM dedup.duplicate_candidate WHERE id = $1",
    )
    .bind(id)
    .fetch_optional(pool)
    .await?;
    Ok(row)
}

/// List candidates (optionally filtered by status), highest suspicion first.
pub async fn list_candidates(
    pool: &PgPool,
    status: Option<&str>,
    limit: i64,
) -> Result<Vec<CandidateView>, DbError> {
    let rows = sqlx::query_as::<_, CandidateView>(
        "SELECT id, sample_a, sample_b, tier, block_key, score, signals, status \
         FROM dedup.duplicate_candidate \
         WHERE ($1::text IS NULL OR status = $1) \
         ORDER BY score DESC, id LIMIT $2",
    )
    .bind(status)
    .bind(limit)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

// ── identifier-tier candidates (shared external id → placement-confirmed) ─────
//
// A shared external identifier (an FTDNA kit, a catalog accession) is a deterministic
// duplicate signal the uniparental engine can't produce — two de-novo tips of one donor
// share NO private variants (each is a per-sample singleton), so the Y+mt block never
// pairs them; the identifier is the only link. But "same kit" ≠ "safe to merge" — a
// mistyped/reused kit would fuse two people. So each identifier collision is adjudicated
// by a DNA signature (terminal-Y concordance) before it becomes a merge:
//   SAME_TERMINAL / PARENT_CHILD / SIBLING → CONFIRMED (same person)
//   DISTANT                                → DISPUTED  (kit matches, DNA disagrees — review)
//   NO_PLACEMENT                           → UNPLACED  (can't confirm yet)
// See proposals/biosample-identifier-dedup.md §5.

/// A biosample pair that share an external identifier — input to placement adjudication.
/// Pre-ordered `sample_a < sample_b` by the caller (matches the table's canonical pair).
#[derive(Debug, Clone)]
pub struct IdentifierPair {
    pub sample_a: Uuid,
    pub sample_b: Uuid,
    pub namespace: String,
    pub value: String,
}

/// An identifier pair after terminal-Y adjudication.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct ClassifiedPair {
    pub sample_a: Uuid,
    pub sample_b: Uuid,
    pub namespace: String,
    pub value: String,
    pub relationship: String,
    pub a_terminal: Option<String>,
    pub b_terminal: Option<String>,
}

impl ClassifiedPair {
    /// CONFIRMED (concordant placement) / DISPUTED (divergent) / UNPLACED (unresolvable).
    pub fn disposition(&self) -> &'static str {
        match self.relationship.as_str() {
            "SAME_TERMINAL" | "PARENT_CHILD" | "SIBLING" => "CONFIRMED",
            "NO_PLACEMENT" => "UNPLACED",
            _ => "DISPUTED",
        }
    }
    /// Queue rank (`list_candidates` orders by score DESC): confirmed on top, disputed last.
    pub fn score(&self) -> f64 {
        match self.disposition() {
            "CONFIRMED" => 0.98,
            "UNPLACED" => 0.60,
            _ => 0.50,
        }
    }
}

/// Adjudicate identifier pairs by terminal-Y concordance (read-only). Each sample's terminal
/// is its `tree.haplogroup_sample` Y placement; relationship is compared against the current
/// parent edges. Pairs with no Y placement come back `NO_PLACEMENT`.
pub async fn classify_identifier_pairs(pool: &PgPool, pairs: &[IdentifierPair]) -> Result<Vec<ClassifiedPair>, DbError> {
    if pairs.is_empty() {
        return Ok(Vec::new());
    }
    let a: Vec<Uuid> = pairs.iter().map(|p| p.sample_a).collect();
    let b: Vec<Uuid> = pairs.iter().map(|p| p.sample_b).collect();
    let ns: Vec<String> = pairs.iter().map(|p| p.namespace.clone()).collect();
    let val: Vec<String> = pairs.iter().map(|p| p.value.clone()).collect();
    let rows = sqlx::query_as::<_, ClassifiedPair>(
        "WITH input AS (SELECT * FROM unnest($1::uuid[],$2::uuid[],$3::text[],$4::text[]) AS t(a,b,ns,val)), \
              term AS (SELECT sample_guid, min(haplogroup_id) tid FROM tree.haplogroup_sample \
                       WHERE dna_type='Y_DNA' GROUP BY sample_guid), \
              par AS (SELECT child_haplogroup_id id, parent_haplogroup_id pid \
                      FROM tree.haplogroup_relationship WHERE valid_until IS NULL) \
         SELECT i.a AS sample_a, i.b AS sample_b, i.ns AS namespace, i.val AS value, \
                CASE WHEN ta.tid IS NULL OR tb.tid IS NULL THEN 'NO_PLACEMENT' \
                     WHEN ta.tid = tb.tid THEN 'SAME_TERMINAL' \
                     WHEN pa.pid = tb.tid OR pb.pid = ta.tid THEN 'PARENT_CHILD' \
                     WHEN pa.pid = pb.pid THEN 'SIBLING' ELSE 'DISTANT' END AS relationship, \
                ha.name AS a_terminal, hb.name AS b_terminal \
         FROM input i \
         LEFT JOIN term ta ON ta.sample_guid = i.a \
         LEFT JOIN term tb ON tb.sample_guid = i.b \
         LEFT JOIN tree.haplogroup ha ON ha.id = ta.tid \
         LEFT JOIN tree.haplogroup hb ON hb.id = tb.tid \
         LEFT JOIN par pa ON pa.id = ta.tid \
         LEFT JOIN par pb ON pb.id = tb.tid",
    )
    .bind(&a)
    .bind(&b)
    .bind(&ns)
    .bind(&val)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

/// Upsert adjudicated pairs as `tier='IDENTIFIER'` candidates. `source` records where the
/// collision was found (`cohort-manifest` | `federation`). Idempotent, and it never overwrites
/// a curator/Tier-2 decision — `DO UPDATE` refreshes the evidence only while the row is still
/// `CANDIDATE`. Returns rows written/refreshed.
pub async fn write_identifier_candidates(pool: &PgPool, pairs: &[ClassifiedPair], source: &str) -> Result<u64, DbError> {
    if pairs.is_empty() {
        return Ok(0);
    }
    let a: Vec<Uuid> = pairs.iter().map(|p| p.sample_a).collect();
    let b: Vec<Uuid> = pairs.iter().map(|p| p.sample_b).collect();
    let ns: Vec<String> = pairs.iter().map(|p| p.namespace.clone()).collect();
    let val: Vec<String> = pairs.iter().map(|p| p.value.clone()).collect();
    let rel: Vec<String> = pairs.iter().map(|p| p.relationship.clone()).collect();
    let disp: Vec<String> = pairs.iter().map(|p| p.disposition().to_string()).collect();
    let score: Vec<f64> = pairs.iter().map(|p| p.score()).collect();
    let at: Vec<Option<String>> = pairs.iter().map(|p| p.a_terminal.clone()).collect();
    let bt: Vec<Option<String>> = pairs.iter().map(|p| p.b_terminal.clone()).collect();
    let n = sqlx::query(
        "INSERT INTO dedup.duplicate_candidate (sample_a, sample_b, tier, block_key, score, signals, status, verdict) \
         SELECT a, b, 'IDENTIFIER', ns || '=' || val, score, \
                jsonb_build_object('namespace',ns,'value',val,'source',$10::text, \
                    'disposition',disp,'relationship',rel,'a_terminal',at,'b_terminal',bt), \
                'CANDIDATE', \
                jsonb_build_object('method','identifier+Y-placement','relationship',rel, \
                    'a_terminal',at,'b_terminal',bt,'disposition',disp) \
         FROM unnest($1::uuid[],$2::uuid[],$3::text[],$4::text[],$5::float8[],$6::text[],$7::text[],$8::text[],$9::text[]) \
              AS t(a,b,ns,val,score,rel,disp,at,bt) \
         ON CONFLICT (sample_a, sample_b) DO UPDATE SET \
            tier = EXCLUDED.tier, block_key = EXCLUDED.block_key, score = EXCLUDED.score, \
            signals = EXCLUDED.signals, verdict = EXCLUDED.verdict, updated_at = now() \
         WHERE dedup.duplicate_candidate.status = 'CANDIDATE'",
    )
    .bind(&a)
    .bind(&b)
    .bind(&ns)
    .bind(&val)
    .bind(&score)
    .bind(&rel)
    .bind(&disp)
    .bind(&at)
    .bind(&bt)
    .bind(source)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n)
}

/// Re-adjudicate open IDENTIFIER-tier candidates against the CURRENT Y placement. A federated
/// anchor raises its collision candidates at ingest, before its sample is placed in the tree, so
/// they start `UNPLACED`; once `tree-samples-recompute` places the sample this upgrades them to
/// `CONFIRMED`/`DISPUTED`. In-place update — preserves `signals.namespace/value/source`, touches
/// only rows still `CANDIDATE`. Returns rows refreshed.
pub async fn readjudicate_identifier_candidates(pool: &PgPool) -> Result<u64, DbError> {
    #[derive(sqlx::FromRow)]
    struct Open {
        sample_a: Uuid,
        sample_b: Uuid,
        namespace: Option<String>,
        value: Option<String>,
    }
    let open: Vec<Open> = sqlx::query_as(
        "SELECT sample_a, sample_b, signals->>'namespace' AS namespace, signals->>'value' AS value \
         FROM dedup.duplicate_candidate WHERE tier = 'IDENTIFIER' AND status = 'CANDIDATE'",
    )
    .fetch_all(pool)
    .await?;
    let pairs: Vec<IdentifierPair> = open
        .into_iter()
        .filter_map(|o| {
            Some(IdentifierPair { sample_a: o.sample_a, sample_b: o.sample_b, namespace: o.namespace?, value: o.value? })
        })
        .collect();
    if pairs.is_empty() {
        return Ok(0);
    }
    let classified = classify_identifier_pairs(pool, &pairs).await?;
    let a: Vec<Uuid> = classified.iter().map(|c| c.sample_a).collect();
    let b: Vec<Uuid> = classified.iter().map(|c| c.sample_b).collect();
    let score: Vec<f64> = classified.iter().map(|c| c.score()).collect();
    let disp: Vec<String> = classified.iter().map(|c| c.disposition().to_string()).collect();
    let rel: Vec<String> = classified.iter().map(|c| c.relationship.clone()).collect();
    let at: Vec<Option<String>> = classified.iter().map(|c| c.a_terminal.clone()).collect();
    let bt: Vec<Option<String>> = classified.iter().map(|c| c.b_terminal.clone()).collect();
    let n = sqlx::query(
        "UPDATE dedup.duplicate_candidate d SET \
            score = u.score, \
            signals = d.signals || jsonb_build_object('disposition',u.disp,'relationship',u.rel,'a_terminal',u.at,'b_terminal',u.bt), \
            verdict = jsonb_build_object('method','identifier+Y-placement','relationship',u.rel,'a_terminal',u.at,'b_terminal',u.bt,'disposition',u.disp), \
            updated_at = now() \
         FROM unnest($1::uuid[],$2::uuid[],$3::float8[],$4::text[],$5::text[],$6::text[],$7::text[]) AS u(a,b,score,disp,rel,at,bt) \
         WHERE d.sample_a = u.a AND d.sample_b = u.b AND d.tier = 'IDENTIFIER' AND d.status = 'CANDIDATE'",
    )
    .bind(&a)
    .bind(&b)
    .bind(&score)
    .bind(&disp)
    .bind(&rel)
    .bind(&at)
    .bind(&bt)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n)
}

// ── resolution + merge ───────────────────────────────────────────────────────

/// Terminal statuses a curator / Tier-2 may assign to a candidate (everything
/// except MERGED, which only [`merge_biosamples`] sets, and CANDIDATE, the engine's).
const RESOLVABLE: &[&str] = &[
    "CONFIRMED_DUPLICATE",
    "RELATIVE",
    "DISTINCT",
    "SUSPECTED_UNCONFIRMABLE",
    "DISMISSED",
];

/// Record a non-merge resolution (curator decision or Tier-2 verdict) on a
/// candidate. Refuses MERGED (that is [`merge_biosamples`]'s job) and any status
/// not in [`RESOLVABLE`]. Returns false if the candidate doesn't exist.
pub async fn resolve_candidate(
    pool: &PgPool,
    id: i64,
    status: &str,
    resolved_by: &str,
    verdict: Option<&Value>,
) -> Result<bool, DbError> {
    if !RESOLVABLE.contains(&status) {
        return Err(DbError::Decode(format!("status {status:?} is not a curator-resolvable status")));
    }
    let n = sqlx::query(
        "UPDATE dedup.duplicate_candidate \
         SET status = $2, resolved_by = $3, resolved_at = now(), \
             verdict = COALESCE($4, verdict), updated_at = now() \
         WHERE id = $1",
    )
    .bind(id)
    .bind(status)
    .bind(resolved_by)
    .bind(verdict)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n > 0)
}

/// How a child table's rows are reconciled when their biosample is merged.
enum Action {
    /// No per-sample unique key — just repoint the merged rows to the survivor.
    Simple,
    /// Per-sample unique key: drop the merged rows that would collide with an
    /// existing survivor row (on these other key cols), then repoint the rest.
    KeepSurvivor(&'static [&'static str]),
    /// Derived/regenerable rows (recomputed by the ibd job) — just delete the
    /// merged side; repointing risks self-pairs / stale ordering for no gain.
    DropMerged,
}

struct Repoint {
    table: &'static str,
    cols: &'static [&'static str],
    action: Action,
}

/// The complete plan for every FK column referencing `core.biosample(sample_guid)`.
/// [`merge_biosamples`] cross-checks this against the live FK set and aborts if the
/// DB has a reference this plan doesn't cover — so a new FK can't silently orphan.
/// `dedup.duplicate_candidate` (sample_a/sample_b) is covered separately (the
/// merged sample's other candidates are resolved, not repointed).
const REPOINTS: &[Repoint] = &[
    // External-identifier index (mig 0059): (namespace, value) is globally unique, so on a
    // collision keep the survivor's row and repoint the rest — merging a duplicate hands its
    // vendor/catalog ids to the survivor.
    Repoint { table: "core.biosample_identifier", cols: &["sample_guid"], action: Action::KeepSurvivor(&["namespace", "value"]) },
    Repoint { table: "fed.pds_submission", cols: &["biosample_guid"], action: Action::Simple },
    Repoint { table: "genomics.biosample_callable_loci", cols: &["sample_guid"], action: Action::Simple },
    // STR profile is 1-per-sample (mig 0053), keyed by sample_guid — keep the
    // survivor's if it already has one, else adopt the merged sample's.
    Repoint { table: "genomics.biosample_str_profile", cols: &["sample_guid"], action: Action::KeepSurvivor(&[]) },
    Repoint { table: "genomics.genotype_data", cols: &["sample_guid"], action: Action::Simple },
    Repoint { table: "genomics.reported_variant_pangenome", cols: &["sample_guid"], action: Action::Simple },
    Repoint { table: "genomics.sequence_library", cols: &["sample_guid"], action: Action::Simple },
    Repoint { table: "ibd.population_breakdown", cols: &["sample_guid"], action: Action::Simple },
    Repoint { table: "tree.haplogroup_sample", cols: &["sample_guid"], action: Action::KeepSurvivor(&["dna_type"]) },
    Repoint {
        table: "tree.biosample_private_variant",
        cols: &["sample_guid"],
        action: Action::KeepSurvivor(&["variant_id", "haplogroup_type"]),
    },
    Repoint {
        table: "ibd.ancestry_analysis",
        cols: &["sample_guid"],
        action: Action::KeepSurvivor(&["analysis_method_id", "population_id"]),
    },
    Repoint { table: "pubs.publication_biosample", cols: &["sample_guid"], action: Action::KeepSurvivor(&["publication_id"]) },
    Repoint { table: "research.subject_biosample", cols: &["sample_guid"], action: Action::KeepSurvivor(&["research_subject_id"]) },
    Repoint { table: "ibd.ibd_discovery_index", cols: &["sample_guid_1", "sample_guid_2"], action: Action::DropMerged },
    Repoint { table: "ibd.match_suggestion", cols: &["target_sample_guid", "suggested_sample_guid"], action: Action::DropMerged },
    Repoint { table: "ibd.population_overlap_score", cols: &["sample_guid_1", "sample_guid_2"], action: Action::DropMerged },
    Repoint { table: "ibd.population_breakdown_cache", cols: &["sample_guid"], action: Action::DropMerged },
];

/// Outcome of [`merge_biosamples`].
#[derive(Debug, Clone)]
pub struct MergeReport {
    pub survivor: Uuid,
    pub merged: Uuid,
    pub rows_repointed: u64,
    pub rows_dropped: u64,
    pub candidates_dismissed: u64,
}

#[derive(sqlx::FromRow)]
struct BioRow {
    sample_guid: Uuid,
    deleted: bool,
    accession: Option<String>,
    alias: Option<String>,
    original_haplogroups: Value,
    source_attrs: Value,
    is_public: Option<bool>,
}

/// Verify [`REPOINTS`] (+ the dedup pair cols) covers every live FK to
/// `core.biosample`. Aborts the merge if the schema has drifted — better a loud
/// failure than a silent orphan.
async fn assert_fk_coverage(tx: &mut sqlx::PgConnection) -> Result<(), DbError> {
    let live: Vec<(String, String)> = sqlx::query_as(
        "SELECT n.nspname || '.' || rel.relname, a.attname \
         FROM pg_constraint c \
         JOIN pg_class rel ON rel.oid = c.conrelid \
         JOIN pg_namespace n ON n.oid = rel.relnamespace \
         JOIN unnest(c.conkey) WITH ORDINALITY k(attnum, ord) ON true \
         JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum \
         WHERE c.contype = 'f' AND c.confrelid = 'core.biosample'::regclass",
    )
    .fetch_all(&mut *tx)
    .await?;
    let mut known: std::collections::HashSet<(String, String)> = REPOINTS
        .iter()
        .flat_map(|r| r.cols.iter().map(move |c| (r.table.to_string(), c.to_string())))
        .collect();
    known.insert(("dedup.duplicate_candidate".into(), "sample_a".into()));
    known.insert(("dedup.duplicate_candidate".into(), "sample_b".into()));
    // The audit table's own back-reference is deliberately NOT repointed: a prior
    // merge's surviving_guid is correctly that survivor, and if it is later itself
    // merged the chain stays traceable via the tombstone's source_attrs.merged_into.
    known.insert(("core.biosample_merge".into(), "surviving_guid".into()));
    let missing: Vec<_> = live.iter().filter(|fk| !known.contains(*fk)).collect();
    if !missing.is_empty() {
        return Err(DbError::Decode(format!(
            "biosample merge plan is stale — uncovered FK(s) to core.biosample: {missing:?}"
        )));
    }
    Ok(())
}

/// Merge `merged` into `survivor`: repoint every FK, fold metadata, tombstone the
/// merged row, resolve the dedup candidate, and write a `core.biosample_merge`
/// audit row — all in one transaction. Refuses if either sample is missing or
/// already deleted, or if the FK plan is stale. `locked` does NOT block a merge
/// (dedup is allowed automation; see the note in the body).
///
/// This is the **only** mutation that acts on a confirmed duplicate; Tier-1 never
/// reaches it. Pass `candidate_id` to mark that candidate MERGED (other open
/// candidates touching the merged sample are dismissed as stale).
pub async fn merge_biosamples(
    pool: &PgPool,
    survivor: Uuid,
    merged: Uuid,
    merged_by: &str,
    candidate_id: Option<i64>,
    mut evidence: Value,
) -> Result<MergeReport, DbError> {
    if survivor == merged {
        return Err(DbError::Decode("cannot merge a biosample into itself".into()));
    }
    let mut tx = pool.begin().await?;
    assert_fk_coverage(&mut tx).await?;

    // Load + validate both rows.
    let rows: Vec<BioRow> = sqlx::query_as(
        "SELECT sample_guid, deleted, accession, alias, \
                COALESCE(original_haplogroups,'[]'::jsonb) AS original_haplogroups, \
                COALESCE(source_attrs,'{}'::jsonb) AS source_attrs, is_public \
         FROM core.biosample WHERE sample_guid = ANY($1)",
    )
    .bind([survivor, merged])
    .fetch_all(&mut *tx)
    .await?;
    let find = |g: Uuid| rows.iter().find(|r| r.sample_guid == g);
    let (Some(surv), Some(merg)) = (find(survivor), find(merged)) else {
        return Err(DbError::Decode("survivor or merged biosample not found".into()));
    };
    for (label, r) in [("survivor", surv), ("merged", merg)] {
        if r.deleted {
            return Err(DbError::Decode(format!("{label} biosample is already deleted/merged")));
        }
        // NB: `locked` is deliberately NOT a bar here. In the legacy Scala model
        // `locked` meant "don't apply publication-driven field updates"; in the Rust
        // model it means "no unattended automation edits". Deduplication is exactly
        // the kind of automation that is allowed to act on locked rows — a confirmed
        // duplicate must be merged regardless of the lock.
    }

    let mut rep = MergeReport { survivor, merged, rows_repointed: 0, rows_dropped: 0, candidates_dismissed: 0 };

    // Repoint every child table per the plan.
    for r in REPOINTS {
        match &r.action {
            Action::Simple => {
                let col = r.cols[0];
                let n = sqlx::query(&format!("UPDATE {} SET {col} = $1 WHERE {col} = $2", r.table))
                    .bind(survivor)
                    .bind(merged)
                    .execute(&mut *tx)
                    .await?
                    .rows_affected();
                rep.rows_repointed += n;
            }
            Action::KeepSurvivor(others) => {
                let col = r.cols[0];
                // Extra AND-clauses that make a merged row a *duplicate* of a survivor
                // row. Empty ⇒ the table is unique on `col` alone (1-per-sample), so the
                // mere existence of a survivor row makes the merged one a duplicate.
                let extra = others
                    .iter()
                    .map(|oc| format!(" AND s.{oc} = m.{oc}"))
                    .collect::<String>();
                let dropped = sqlx::query(&format!(
                    "DELETE FROM {t} m WHERE m.{col} = $2 \
                     AND EXISTS (SELECT 1 FROM {t} s WHERE s.{col} = $1{extra})",
                    t = r.table
                ))
                .bind(survivor)
                .bind(merged)
                .execute(&mut *tx)
                .await?
                .rows_affected();
                let n = sqlx::query(&format!("UPDATE {} SET {col} = $1 WHERE {col} = $2", r.table))
                    .bind(survivor)
                    .bind(merged)
                    .execute(&mut *tx)
                    .await?
                    .rows_affected();
                rep.rows_dropped += dropped;
                rep.rows_repointed += n;
            }
            Action::DropMerged => {
                for col in r.cols {
                    let n = sqlx::query(&format!("DELETE FROM {} WHERE {col} = $1", r.table))
                        .bind(merged)
                        .execute(&mut *tx)
                        .await?
                        .rows_affected();
                    rep.rows_dropped += n;
                }
            }
        }
    }

    // Fold the merged row's metadata into the survivor (union haplogroups, fill
    // missing alias, OR public, append merged guid/accession to source_attrs).
    let merged_accs: Vec<String> = merg.accession.iter().cloned().collect();
    let mut survivor_attrs = surv.source_attrs.clone();
    let attrs_obj = survivor_attrs.as_object_mut().ok_or_else(|| DbError::Decode("source_attrs not an object".into()))?;
    let mut guids = attrs_obj.get("merged_guids").and_then(Value::as_array).cloned().unwrap_or_default();
    guids.push(Value::String(merged.to_string()));
    let mut accs = attrs_obj.get("merged_accessions").and_then(Value::as_array).cloned().unwrap_or_default();
    accs.extend(merged_accs.iter().map(|a| Value::String(a.clone())));
    let fold = serde_json::json!({ "merged_guids": guids, "merged_accessions": accs });

    sqlx::query(
        "UPDATE core.biosample SET \
            alias = COALESCE(alias, $2), \
            original_haplogroups = COALESCE(original_haplogroups,'[]'::jsonb) || $3::jsonb, \
            is_public = COALESCE(is_public, false) OR $4, \
            source_attrs = COALESCE(source_attrs,'{}'::jsonb) || $5::jsonb, \
            updated_at = now() \
         WHERE sample_guid = $1",
    )
    .bind(survivor)
    .bind(&merg.alias)
    .bind(&merg.original_haplogroups)
    .bind(merg.is_public.unwrap_or(false))
    .bind(&fold)
    .execute(&mut *tx)
    .await?;

    // Tombstone the merged row with a pointer to the survivor.
    sqlx::query(
        "UPDATE core.biosample SET deleted = true, updated_at = now(), \
            source_attrs = COALESCE(source_attrs,'{}'::jsonb) || jsonb_build_object('merged_into', $2::text) \
         WHERE sample_guid = $1",
    )
    .bind(merged)
    .bind(survivor.to_string())
    .execute(&mut *tx)
    .await?;

    // Resolve dedup candidates: the justifying one → MERGED; any other open
    // candidate touching the merged sample → DISMISSED (now stale).
    if let Some(cid) = candidate_id {
        sqlx::query(
            "UPDATE dedup.duplicate_candidate SET status = 'MERGED', resolved_by = $2, resolved_at = now() WHERE id = $1",
        )
        .bind(cid)
        .bind(merged_by)
        .execute(&mut *tx)
        .await?;
    }
    rep.candidates_dismissed = sqlx::query(
        "UPDATE dedup.duplicate_candidate \
         SET status = 'DISMISSED', resolved_by = $2, resolved_at = now(), \
             signals = signals || jsonb_build_object('dismissed_reason','sample merged into survivor') \
         WHERE status = 'CANDIDATE' AND (sample_a = $1 OR sample_b = $1) \
           AND id IS DISTINCT FROM $3",
    )
    .bind(merged)
    .bind(merged_by)
    .bind(candidate_id)
    .execute(&mut *tx)
    .await?
    .rows_affected();

    // Audit.
    if let Some(obj) = evidence.as_object_mut() {
        obj.insert("rows_repointed".into(), Value::from(rep.rows_repointed));
        obj.insert("rows_dropped".into(), Value::from(rep.rows_dropped));
        obj.insert("merged_accessions".into(), Value::from(merged_accs));
    }
    sqlx::query(
        "INSERT INTO core.biosample_merge (surviving_guid, merged_guid, candidate_id, evidence, merged_by) \
         VALUES ($1, $2, $3, $4, $5)",
    )
    .bind(survivor)
    .bind(merged)
    .bind(candidate_id)
    .bind(&evidence)
    .bind(merged_by)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;
    Ok(rep)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jaccard_basic() {
        assert_eq!(jaccard(&[1, 2, 3], &[2, 3, 4]), (2, 0.5));
        assert_eq!(jaccard(&[], &[1]), (0, 0.0));
        let (i, j) = jaccard(&[1, 2, 3], &[1, 2, 3]);
        assert_eq!(i, 3);
        assert!((j - 1.0).abs() < 1e-9);
    }

    #[test]
    fn mt_match_emits_even_without_shared_privates() {
        let cfg = DedupConfig::default();
        let ps = evaluate_pair(&[], &[], Some(7), Some(7), &cfg).expect("mt match emits");
        assert!(ps.mt_match);
        assert_eq!(ps.shared, 0);
        // base score = w_mt (no jaccard contribution)
        assert!((ps.score - cfg.w_mt).abs() < 1e-9);
    }

    #[test]
    fn y_only_pair_needs_strong_private_overlap() {
        let cfg = DedupConfig::default();
        // Different mt, no shared privates → not a candidate.
        assert!(evaluate_pair(&[1, 2, 3], &[4, 5, 6], Some(1), Some(2), &cfg).is_none());
        // Different mt but identical private sets (jaccard 1.0, shared 3) → candidate.
        let ps = evaluate_pair(&[1, 2, 3], &[1, 2, 3], Some(1), Some(2), &cfg).expect("strong overlap emits");
        assert!(!ps.mt_match);
        assert_eq!(ps.shared, 3);
        assert!((ps.score - cfg.w_jaccard).abs() < 1e-9);
    }

    #[test]
    fn mt_match_plus_privates_outranks_plain_mt_match() {
        let cfg = DedupConfig::default();
        let plain = evaluate_pair(&[], &[], Some(7), Some(7), &cfg).unwrap();
        let rich = evaluate_pair(&[1, 2, 3], &[1, 2, 3], Some(7), Some(7), &cfg).unwrap();
        assert!(rich.score > plain.score);
    }

    #[test]
    fn identifier_disposition_and_score_by_placement() {
        let mk = |rel: &str| ClassifiedPair {
            sample_a: Uuid::nil(),
            sample_b: Uuid::nil(),
            namespace: "FTDNA".into(),
            value: "B5163".into(),
            relationship: rel.into(),
            a_terminal: None,
            b_terminal: None,
        };
        // Concordant placement confirms the kit match → top of the queue.
        for rel in ["SAME_TERMINAL", "PARENT_CHILD", "SIBLING"] {
            assert_eq!(mk(rel).disposition(), "CONFIRMED", "{rel}");
            assert!((mk(rel).score() - 0.98).abs() < 1e-9, "{rel}");
        }
        // Divergent placement = kit matches but DNA disagrees → disputed, never auto-merged.
        assert_eq!(mk("DISTANT").disposition(), "DISPUTED");
        // Unresolvable (no Y placement) sits between: can't confirm, can't refute.
        assert_eq!(mk("NO_PLACEMENT").disposition(), "UNPLACED");
        // Queue ranking: confirmed > unplaced > disputed.
        assert!(mk("SAME_TERMINAL").score() > mk("NO_PLACEMENT").score());
        assert!(mk("NO_PLACEMENT").score() > mk("DISTANT").score());
    }
}
