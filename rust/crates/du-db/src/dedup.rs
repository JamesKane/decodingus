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
    let result = recompute_locked(pool, cfg).await;
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

    rep.candidates_pruned = sqlx::query(
        "DELETE FROM dedup.duplicate_candidate WHERE status = 'CANDIDATE' AND id <> ALL($1)",
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
}
