//! IBD candidate-generation engine (D3 first slice, D1-independent).
//!
//! The AppView *coordinates* IBD: it proposes **introduction candidates** (which pairs
//! should attempt an Edge-to-Edge comparison) from anonymized `fed.*` aggregates — it
//! never sees a genotype, and the segment detection is the Edge's job. The
//! load-bearing rule (D3 §3.0): **never materialize N×N, never hand a client
//! "everyone"** — block by ancestry, expand the match graph, emit a bounded top-K list
//! per sample. Three signals feed `ibd.match_suggestion`:
//!   1. **population overlap** — `Σ min(A[pop], B[pop])`, computed only *within ancestry
//!      blocks* (dominant super-population × a z-scored PCA cell);
//!   2. **haplogroup** — a shared terminal Y/mt consensus haplogroup (rarer = higher);
//!   3. **shared-match** — 2-hop expansion over the `ibd_discovery_index` match graph
//!      (the in-common-with / clustering signal; dormant until the graph has edges).
//!
//! Mirrors the sequencer/discovery engines: advisory-locked, declarative recompute that
//! preserves user decisions (`DISMISSED`/`CONVERTED` pairs are never re-suggested).

use crate::DbError;
use serde_json::{json, Value};
use sqlx::PgPool;
use std::collections::{HashMap, HashSet};
use uuid::Uuid;

/// Advisory-lock key guarding concurrent recomputes.
const IBD_ADVISORY_KEY: i64 = 0x4942_445F_4347; // "IBD_CG"

const SIG_POPULATION: &str = "POPULATION_OVERLAP";
const SIG_HAPLOGROUP: &str = "HAPLOGROUP";
const SIG_SHARED_MATCH: &str = "SHARED_MATCH";

/// Thresholds + weights for candidate generation (plain config; no table for v1).
#[derive(Debug, Clone)]
pub struct IbdConfig {
    /// Minimum population-overlap (Σ min over shared populations, 0..1).
    pub min_overlap: f64,
    /// Minimum shared third parties for a SHARED_MATCH candidate.
    pub min_shared: i64,
    /// Max suggestions kept per target sample (the no-N:N cap).
    pub top_k: usize,
    /// PCA grid cell size in standard deviations (z-scored, scale-free).
    pub pca_cell_sigma: f64,
    /// Suggestion lifetime (days) before it ages out.
    pub ttl_days: i32,
    pub w_population: f64,
    pub w_haplogroup: f64,
    pub w_shared_match: f64,
    /// Tree-depth half-saturation for the haplogroup signal: a shared clade at this depth
    /// scores at half its rarity. Deeper (more terminal) shared clades are far more
    /// informative — sharing R-CTS4466 (depth ~21) ≫ sharing R (depth ~2).
    pub depth_half_life: f64,
}

impl Default for IbdConfig {
    fn default() -> Self {
        Self {
            min_overlap: 0.6,
            min_shared: 2,
            top_k: 50,
            pca_cell_sigma: 0.5,
            ttl_days: 30,
            w_population: 0.4,
            w_haplogroup: 0.3,
            w_shared_match: 0.3,
            depth_half_life: 8.0,
        }
    }
}

/// Outcome of [`recompute_suggestions`].
#[derive(Debug, Default, Clone)]
pub struct SuggestionReport {
    pub samples: u64,
    pub blocks: u64,
    pub population_pairs: u64,
    pub haplogroup_pairs: u64,
    pub shared_match_pairs: u64,
    pub suggestions_written: u64,
}

/// A ranked suggestion for a sample (the reader's row).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct SuggestionView {
    pub suggested_sample_guid: Uuid,
    pub suggestion_type: String,
    pub score: Option<f64>,
    pub metadata: Value,
}

/// Serve a sample's ranked active candidates (used by the eventual consent-gated API).
pub async fn suggestions_for(pool: &PgPool, sample_guid: Uuid, limit: i64) -> Result<Vec<SuggestionView>, DbError> {
    Ok(sqlx::query_as(
        "SELECT suggested_sample_guid, suggestion_type, score, metadata \
         FROM ibd.match_suggestion \
         WHERE target_sample_guid = $1 AND status = 'ACTIVE' \
         ORDER BY score DESC NULLS LAST LIMIT $2",
    )
    .bind(sample_guid)
    .bind(limit)
    .fetch_all(pool)
    .await?)
}

// ── federated read API (D3 entry point — pseudonymous, owner-DID scoped) ────────

/// The exact bytes each request's Ed25519 signature is computed over (cross-repo
/// contract with the Navigator Edge — keep byte-stable, mirrors `exchange::messages`).
pub mod messages {
    /// Replay-guarded read poll: caller proves it is `did` at `ts` (unix seconds).
    pub fn poll(did: &str, ts: i64) -> String {
        format!("ibd-poll\n{did}\n{ts}")
    }
    /// Ask the broker to relay a consent request to a pseudonymous candidate. The caller
    /// signs over the sample handle it *can* see; the counterpart DID is resolved server-side.
    pub fn introduce(did: &str, suggested_sample_guid: &str) -> String {
        format!("ibd-introduce\n{did}\n{suggested_sample_guid}")
    }
    /// Dismiss a candidate so it stops being suggested (preserved across recomputes).
    pub fn dismiss(did: &str, suggested_sample_guid: &str) -> String {
        format!("ibd-dismiss\n{did}\n{suggested_sample_guid}")
    }
    /// Attest the *outcome* of a completed Edge-to-Edge comparison: the attester (a party to
    /// the consented exchange `request_uri`) reports that its own sample and the counterpart's
    /// share an IBD segment in `region` totalling `cm` centimorgans. The figure is part of the
    /// signed message so the claim is bound to the signature.
    pub fn attest(did: &str, request_uri: &str, claimed: &str, counterpart: &str, region: &str, cm: &str) -> String {
        format!("ibd-attest\n{did}\n{request_uri}\n{claimed}\n{counterpart}\n{region}\n{cm}")
    }
}

/// A caller's ranked active candidates, scoped to the samples they own (via the
/// `core.biosample.atproto->>'repo_did'` self-publish bridge the engine itself uses).
/// Pseudonymous: rows carry only `suggested_sample_guid` + non-PII signal scores — never
/// a counterpart DID (identity reveal stays Edge-to-Edge over D1 consent).
pub async fn suggestions_for_did(pool: &PgPool, did: &str, limit: i64) -> Result<Vec<SuggestionView>, DbError> {
    Ok(sqlx::query_as(
        "SELECT ms.suggested_sample_guid, ms.suggestion_type, ms.score, ms.metadata \
         FROM ibd.match_suggestion ms \
         JOIN core.biosample b ON b.sample_guid = ms.target_sample_guid \
         WHERE b.atproto->>'repo_did' = $1 AND ms.status = 'ACTIVE' \
         ORDER BY ms.score DESC NULLS LAST LIMIT $2",
    )
    .bind(did)
    .bind(limit)
    .fetch_all(pool)
    .await?)
}

/// Authorization for `introduce`: true only when an ACTIVE suggestion exists whose
/// *target* is one of `did`'s own samples and whose suggested sample matches — so a caller
/// can only ask to meet its own genuine candidates, never probe/forge contact to an
/// arbitrary sample.
pub async fn is_suggested_to_did(pool: &PgPool, did: &str, suggested_sample_guid: Uuid) -> Result<bool, DbError> {
    Ok(sqlx::query_scalar::<_, i64>(
        "SELECT count(*) FROM ibd.match_suggestion ms \
         JOIN core.biosample b ON b.sample_guid = ms.target_sample_guid \
         WHERE b.atproto->>'repo_did' = $1 AND ms.suggested_sample_guid = $2 AND ms.status = 'ACTIVE'",
    )
    .bind(did)
    .bind(suggested_sample_guid)
    .fetch_one(pool)
    .await?
        > 0)
}

/// Resolve the publisher DID that owns a sample (server-side counterpart resolution for
/// `introduce`). `None` ⇒ the sample isn't federated/claimable, so no introduction is
/// possible — and no DID is ever returned to the caller.
pub async fn owner_did_of_sample(pool: &PgPool, sample_guid: Uuid) -> Result<Option<String>, DbError> {
    Ok(sqlx::query_scalar("SELECT atproto->>'repo_did' FROM core.biosample WHERE sample_guid = $1")
        .bind(sample_guid)
        .fetch_optional(pool)
        .await?
        .flatten())
}

/// The D1 exchange `purpose` for introducing the caller to one of its candidates, derived
/// from the suggestion's dominant signal: a shared-haplogroup match → `IBD_Y`/`IBD_MT` (from
/// the recorded arm), an autosomal signal (population / shared-match) → `IBD_AUTOSOMAL`.
/// `None` ⇒ the candidate is not a live (`ACTIVE`/`CONVERTED`) suggestion for `did` — the
/// caller may not introduce to it (authorization, replacing the bare existence check).
pub async fn introduction_purpose(pool: &PgPool, did: &str, suggested_sample_guid: Uuid) -> Result<Option<String>, DbError> {
    let row: Option<(String, Value)> = sqlx::query_as(
        "SELECT ms.suggestion_type, ms.metadata FROM ibd.match_suggestion ms \
         JOIN core.biosample b ON b.sample_guid = ms.target_sample_guid \
         WHERE b.atproto->>'repo_did' = $1 AND ms.suggested_sample_guid = $2 \
           AND ms.status IN ('ACTIVE','CONVERTED') \
         ORDER BY ms.score DESC NULLS LAST LIMIT 1",
    )
    .bind(did)
    .bind(suggested_sample_guid)
    .fetch_optional(pool)
    .await?;
    Ok(row.map(|(suggestion_type, meta)| match suggestion_type.as_str() {
        SIG_HAPLOGROUP => match meta.get("hgDnaType").and_then(Value::as_str) {
            Some("MT_DNA") => "IBD_MT".to_string(),
            Some("Y_DNA") => "IBD_Y".to_string(),
            _ => "IBD_AUTOSOMAL".to_string(),
        },
        _ => "IBD_AUTOSOMAL".to_string(),
    }))
}

/// Mark the caller's suggestion for a candidate `CONVERTED` (it became an exchange request) so
/// it drops out of the active candidate list. Idempotent; only affects the caller's own ACTIVE rows.
pub async fn mark_converted(pool: &PgPool, did: &str, suggested_sample_guid: Uuid) -> Result<(), DbError> {
    sqlx::query(
        "UPDATE ibd.match_suggestion ms SET status = 'CONVERTED' \
         FROM core.biosample b \
         WHERE b.sample_guid = ms.target_sample_guid \
           AND b.atproto->>'repo_did' = $1 AND ms.suggested_sample_guid = $2 AND ms.status = 'ACTIVE'",
    )
    .bind(did)
    .bind(suggested_sample_guid)
    .execute(pool)
    .await?;
    Ok(())
}

/// Dismiss the caller's candidate so the engine stops suggesting it (recompute preserves
/// `DISMISSED`). Only the caller's own ACTIVE rows are affected; returns the number dismissed.
pub async fn dismiss_suggestion(pool: &PgPool, did: &str, suggested_sample_guid: Uuid) -> Result<u64, DbError> {
    Ok(sqlx::query(
        "UPDATE ibd.match_suggestion ms SET status = 'DISMISSED' \
         FROM core.biosample b \
         WHERE b.sample_guid = ms.target_sample_guid \
           AND b.atproto->>'repo_did' = $1 AND ms.suggested_sample_guid = $2 AND ms.status = 'ACTIVE'",
    )
    .bind(did)
    .bind(suggested_sample_guid)
    .execute(pool)
    .await?
    .rows_affected())
}

// ── attestation ingest (close the loop: a completed exchange → match state) ─────

/// One Edge's signed report of a completed IBD comparison. PII-free: only pseudonymous
/// sample handles, a region, and coarse totals — never segment coordinates or genotypes.
#[derive(Debug, Clone)]
pub struct Attestation<'a> {
    /// The DID that signed this report (verified by the route before it reaches here).
    pub attester_did: &'a str,
    /// The consented exchange this comparison came out of (the privacy rail).
    pub request_uri: &'a str,
    /// The attester's own sample (must be owned by `attester_did`).
    pub claimed_sample: Uuid,
    /// The counterpart's sample (must be owned by the exchange's *other* party).
    pub counterpart_sample: Uuid,
    /// Match region: `AUTOSOMAL` / `X` / `Y` / `MT`.
    pub region_type: &'a str,
    pub total_shared_cm: Option<f64>,
    pub num_segments: Option<i32>,
    /// `INITIAL_REPORT` / `CONFIRMATION` / `DISPUTE` / `REVOCATION`.
    pub attestation_type: &'a str,
    pub signature: &'a str,
    pub notes: Option<&'a str>,
}

/// Result of recording an attestation. `Rejected` carries a static reason the route maps
/// to a 4xx — the attestation never touched the match graph.
#[derive(Debug, Clone, PartialEq)]
pub enum AttestationOutcome {
    Recorded {
        discovery_index_id: i64,
        consensus_status: String,
        publicly_discoverable: bool,
    },
    Rejected(&'static str),
}

/// True iff `sample` is a federated biosample published by `did` (the repo_did self-publish
/// bridge — the same ownership proof the suggestion engine and `/introduce` rely on).
async fn owns_sample(pool: &PgPool, did: &str, sample: Uuid) -> Result<bool, DbError> {
    Ok(sqlx::query_scalar::<_, i64>(
        "SELECT count(*) FROM core.biosample WHERE sample_guid = $1 AND atproto->>'repo_did' = $2",
    )
    .bind(sample)
    .bind(did)
    .fetch_one(pool)
    .await?
        > 0)
}

/// Two cM totals are compatible when within `max(10 cM, 20%)` — segment detectors disagree
/// on exact boundaries, so consensus tolerates spread rather than demanding equality.
fn cm_agree(a: f64, b: f64) -> bool {
    (a - b).abs() <= (0.20 * a.max(b)).max(10.0)
}

/// Record a signed IBD attestation and recompute the pair's consensus.
///
/// Gated, in order: the `request_uri` must be a **CONSENTED** exchange with an **IBD**
/// purpose; the attester must be a **party** to it; the attester must **own** its claimed
/// sample and the *other* party must own the counterpart sample. This ties every match-graph
/// edge to a real dual-consented comparison — there is no way to forge an edge for a pair
/// that never agreed to compare. On the second party's compatible report the pair flips
/// `CONFIRMED` + `is_publicly_discoverable`, which is what the SHARED_MATCH signal reads.
pub async fn record_attestation(pool: &PgPool, a: &Attestation<'_>) -> Result<AttestationOutcome, DbError> {
    // 1. The exchange must exist, be consented, and be an IBD exchange the attester is in.
    let req: Option<(String, String, String, String)> = sqlx::query_as(
        "SELECT initiator_did, partner_did, status, purpose FROM exchange.exchange_request WHERE request_uri = $1",
    )
    .bind(a.request_uri)
    .fetch_optional(pool)
    .await?;
    let Some((initiator, partner, status, purpose)) = req else {
        return Ok(AttestationOutcome::Rejected("unknown exchange request"));
    };
    if status != "CONSENTED" {
        return Ok(AttestationOutcome::Rejected("exchange is not consented"));
    }
    if !purpose.starts_with("IBD") {
        return Ok(AttestationOutcome::Rejected("not an IBD exchange"));
    }
    let counterpart_did = if a.attester_did == initiator {
        partner
    } else if a.attester_did == partner {
        initiator
    } else {
        return Ok(AttestationOutcome::Rejected("attester is not a party to this exchange"));
    };

    // 2. The reported pair must be the two parties' own samples.
    if !owns_sample(pool, a.attester_did, a.claimed_sample).await? {
        return Ok(AttestationOutcome::Rejected("claimed sample is not owned by the attester"));
    }
    if !owns_sample(pool, &counterpart_did, a.counterpart_sample).await? {
        return Ok(AttestationOutcome::Rejected("counterpart sample is not owned by the other party"));
    }

    // 3. Get-or-create the match-graph edge (order-independent pair × region), record the
    //    attestation, recompute consensus — all in one transaction.
    let (s1, s2) = ordered(a.claimed_sample, a.counterpart_sample);
    let mut tx = pool.begin().await?;
    sqlx::query(
        "INSERT INTO ibd.ibd_discovery_index \
            (sample_guid_1, sample_guid_2, match_region_type, consensus_status, is_publicly_discoverable) \
         VALUES ($1, $2, $3, 'PENDING', false) \
         ON CONFLICT (LEAST(sample_guid_1, sample_guid_2), GREATEST(sample_guid_1, sample_guid_2), match_region_type) \
         DO NOTHING",
    )
    .bind(s1)
    .bind(s2)
    .bind(a.region_type)
    .execute(&mut *tx)
    .await?;
    let idx_id: i64 = sqlx::query_scalar(
        "SELECT id FROM ibd.ibd_discovery_index \
         WHERE LEAST(sample_guid_1, sample_guid_2) = LEAST($1, $2) \
           AND GREATEST(sample_guid_1, sample_guid_2) = GREATEST($1, $2) \
           AND match_region_type = $3",
    )
    .bind(s1)
    .bind(s2)
    .bind(a.region_type)
    .fetch_one(&mut *tx)
    .await?;

    sqlx::query(
        "INSERT INTO ibd.ibd_pds_attestation \
            (ibd_discovery_index_id, attesting_did, exchange_request_uri, attestation_signature, \
             attestation_type, reported_total_cm, reported_segments, attestation_notes) \
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8) \
         ON CONFLICT (ibd_discovery_index_id, attesting_did, attestation_type) WHERE attesting_did IS NOT NULL \
         DO UPDATE SET attestation_signature = EXCLUDED.attestation_signature, \
            exchange_request_uri = EXCLUDED.exchange_request_uri, \
            reported_total_cm = EXCLUDED.reported_total_cm, reported_segments = EXCLUDED.reported_segments, \
            attestation_notes = EXCLUDED.attestation_notes, attestation_timestamp = now()",
    )
    .bind(idx_id)
    .bind(a.attester_did)
    .bind(a.request_uri)
    .bind(a.signature)
    .bind(a.attestation_type)
    .bind(a.total_shared_cm)
    .bind(a.num_segments)
    .bind(a.notes)
    .execute(&mut *tx)
    .await?;

    // Recompute consensus from all of this edge's attestations.
    let atts: Vec<(String, String, Option<f64>, Option<i32>)> = sqlx::query_as(
        "SELECT attesting_did, attestation_type, reported_total_cm, reported_segments \
         FROM ibd.ibd_pds_attestation WHERE ibd_discovery_index_id = $1 AND attesting_did IS NOT NULL",
    )
    .bind(idx_id)
    .fetch_all(&mut *tx)
    .await?;

    let disputed = atts.iter().any(|(_, t, _, _)| t == "DISPUTE" || t == "REVOCATION");
    // The distinct parties that reported a (non-dispute) match, with their figures.
    let mut reports: HashMap<String, (f64, Option<i32>)> = HashMap::new();
    for (did, t, cm, segs) in &atts {
        if t == "DISPUTE" || t == "REVOCATION" {
            continue;
        }
        if let Some(cm) = cm {
            reports.entry(did.clone()).or_insert((*cm, *segs));
        }
    }
    let (consensus_status, discoverable, agreed_cm, agreed_segs) = if disputed {
        ("DISPUTED", false, None, None)
    } else if reports.len() >= 2 {
        let vals: Vec<f64> = reports.values().map(|(cm, _)| *cm).collect();
        let lo = vals.iter().cloned().fold(f64::INFINITY, f64::min);
        let hi = vals.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
        if cm_agree(lo, hi) {
            let avg = vals.iter().sum::<f64>() / vals.len() as f64;
            let seg_avg = {
                let segs: Vec<i32> = reports.values().filter_map(|(_, s)| *s).collect();
                if segs.is_empty() { None } else { Some(segs.iter().sum::<i32>() / segs.len() as i32) }
            };
            ("CONFIRMED", true, Some(avg), seg_avg)
        } else {
            ("DISPUTED", false, None, None)
        }
    } else {
        ("PENDING", false, None, None)
    };

    sqlx::query(
        "UPDATE ibd.ibd_discovery_index \
         SET consensus_status = $2, is_publicly_discoverable = $3, \
             total_shared_cm_approx = COALESCE($4, total_shared_cm_approx), \
             num_shared_segments_approx = COALESCE($5, num_shared_segments_approx) \
         WHERE id = $1",
    )
    .bind(idx_id)
    .bind(consensus_status)
    .bind(discoverable)
    .bind(agreed_cm)
    .bind(agreed_segs)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;
    Ok(AttestationOutcome::Recorded {
        discovery_index_id: idx_id,
        consensus_status: consensus_status.to_string(),
        publicly_discoverable: discoverable,
    })
}

// ── internal model ───────────────────────────────────────────────────────────

struct Profile {
    guid: Uuid,
    breakdown: HashMap<String, f64>, // population -> fraction (0..1)
    super_pop: Option<String>,
    pca: Option<(f64, f64)>,
}

/// One signal's contribution to a candidate pair.
struct Hit {
    a: Uuid, // canonical: a < b
    b: Uuid,
    signal: &'static str,
    score: f64,
    /// For a HAPLOGROUP hit, the shared haplogroup's DNA arm (`Y_DNA`/`MT_DNA`); else None.
    dna: Option<&'static str>,
}

fn ordered(x: Uuid, y: Uuid) -> (Uuid, Uuid) {
    if x <= y {
        (x, y)
    } else {
        (y, x)
    }
}

/// `Σ min(A[pop], B[pop])` over shared populations.
fn overlap(a: &HashMap<String, f64>, b: &HashMap<String, f64>) -> f64 {
    a.iter().filter_map(|(p, fa)| b.get(p).map(|fb| fa.min(*fb))).sum()
}

pub async fn recompute_suggestions(pool: &PgPool, cfg: &IbdConfig) -> Result<SuggestionReport, DbError> {
    let mut lock = pool.acquire().await?;
    let locked: bool = sqlx::query_scalar("SELECT pg_try_advisory_lock($1)")
        .bind(IBD_ADVISORY_KEY)
        .fetch_one(&mut *lock)
        .await?;
    if !locked {
        return Ok(SuggestionReport::default());
    }
    let result = recompute_locked(pool, cfg).await;
    let _ = sqlx::query("SELECT pg_advisory_unlock($1)").bind(IBD_ADVISORY_KEY).execute(&mut *lock).await;
    result
}

async fn recompute_locked(pool: &PgPool, cfg: &IbdConfig) -> Result<SuggestionReport, DbError> {
    let mut rep = SuggestionReport::default();

    // ── Load federated ancestry profiles (latest breakdown per sample) ──
    let rows: Vec<(Uuid, Value, Value, Option<Value>)> = sqlx::query_as(
        "SELECT DISTINCT ON (b.sample_guid) b.sample_guid, pb.components, pb.super_population_summary, pb.pca_coordinates \
         FROM core.biosample b \
         JOIN fed.population_breakdown pb ON pb.biosample_ref = b.atproto->>'uri' \
         WHERE b.atproto IS NOT NULL AND b.deleted = false \
         ORDER BY b.sample_guid, pb.time_us DESC",
    )
    .fetch_all(pool)
    .await?;

    let profiles: Vec<Profile> = rows
        .into_iter()
        .map(|(guid, components, super_pop, pca)| {
            let breakdown = components
                .as_array()
                .map(|a| {
                    a.iter()
                        .filter_map(|c| {
                            let pop = c.get("population").and_then(Value::as_str)?;
                            let pct = c.get("percentage").and_then(Value::as_f64)?;
                            Some((pop.to_string(), pct / 100.0))
                        })
                        .collect()
                })
                .unwrap_or_default();
            let super_pop = super_pop.as_array().and_then(|a| {
                a.iter()
                    .filter_map(|s| {
                        let name = s.get("superPopulation").and_then(Value::as_str)?;
                        let pct = s.get("percentage").and_then(Value::as_f64)?;
                        Some((name.to_string(), pct))
                    })
                    .max_by(|x, y| x.1.total_cmp(&y.1))
                    .map(|(name, _)| name)
            });
            let pca = pca.as_ref().and_then(|v| {
                let arr = v.as_array()?;
                Some((arr.first()?.as_f64()?, arr.get(1)?.as_f64()?))
            });
            Profile { guid, breakdown, super_pop, pca }
        })
        .collect();
    rep.samples = profiles.len() as u64;

    // ── z-score PCA across the cohort so the grid is scale-free ──
    let (mut m1, mut m2, mut n) = (0.0f64, 0.0f64, 0.0f64);
    for p in &profiles {
        if let Some((a, b)) = p.pca {
            m1 += a;
            m2 += b;
            n += 1.0;
        }
    }
    let (mean1, mean2) = if n > 0.0 { (m1 / n, m2 / n) } else { (0.0, 0.0) };
    let (mut v1, mut v2) = (0.0f64, 0.0f64);
    for p in &profiles {
        if let Some((a, b)) = p.pca {
            v1 += (a - mean1).powi(2);
            v2 += (b - mean2).powi(2);
        }
    }
    let sd1 = if n > 1.0 { (v1 / n).sqrt() } else { 1.0 }.max(1e-9);
    let sd2 = if n > 1.0 { (v2 / n).sqrt() } else { 1.0 }.max(1e-9);
    let cell = cfg.pca_cell_sigma.max(1e-6);
    let block_key = |p: &Profile| -> String {
        let sp = p.super_pop.clone().unwrap_or_else(|| "?".into());
        match p.pca {
            Some((a, b)) => {
                let c1 = ((a - mean1) / sd1 / cell).round() as i64;
                let c2 = ((b - mean2) / sd2 / cell).round() as i64;
                format!("{sp}:{c1}:{c2}")
            }
            None => format!("{sp}:nopca"),
        }
    };

    // ── Signal 1: population overlap, only within ancestry blocks ──
    let mut hits: Vec<Hit> = Vec::new();
    let mut overlap_pairs: Vec<(Uuid, Uuid, f64)> = Vec::new();
    let mut blocks: HashMap<String, Vec<usize>> = HashMap::new();
    for (i, p) in profiles.iter().enumerate() {
        if !p.breakdown.is_empty() {
            blocks.entry(block_key(p)).or_default().push(i);
        }
    }
    rep.blocks = blocks.len() as u64;
    for members in blocks.values() {
        for (xi, &i) in members.iter().enumerate() {
            for &j in &members[xi + 1..] {
                let s = overlap(&profiles[i].breakdown, &profiles[j].breakdown);
                if s >= cfg.min_overlap {
                    let (a, b) = ordered(profiles[i].guid, profiles[j].guid);
                    hits.push(Hit { a, b, signal: SIG_POPULATION, score: s, dna: None });
                    overlap_pairs.push((a, b, s));
                    rep.population_pairs += 1;
                }
            }
        }
    }

    // ── Signal 2: shared terminal Y/mt consensus haplogroup (rarer = higher) ──
    let hg_rows: Vec<(Uuid, String, String)> = sqlx::query_as(
        "SELECT DISTINCT ON (b.sample_guid, r.dna_type) b.sample_guid, r.dna_type, r.consensus_haplogroup \
         FROM core.biosample b \
         JOIN fed.haplogroup_reconciliation r ON r.did = b.atproto->>'repo_did' \
         WHERE b.atproto IS NOT NULL AND b.deleted = false \
           AND r.consensus_haplogroup IS NOT NULL AND r.dna_type IS NOT NULL \
         ORDER BY b.sample_guid, r.dna_type, r.run_count DESC NULLS LAST, r.time_us DESC",
    )
    .fetch_all(pool)
    .await?;
    let total = profiles.len().max(1) as f64;

    // Tree depth of every current clade, keyed (haplogroup_type, name), via one downward
    // walk from the roots. Lets the haplogroup signal weight a shared *deep* clade above a
    // shared shallow macro-clade (the `depth_score` refinement, enabled by the de-novo tree).
    let depth_rows: Vec<(String, String, i32)> = sqlx::query_as(
        "WITH RECURSIVE walk AS ( \
            SELECT h.id, h.name, h.haplogroup_type::text AS dna, 0 AS depth \
            FROM tree.haplogroup h \
            WHERE h.valid_until IS NULL \
              AND NOT EXISTS (SELECT 1 FROM tree.haplogroup_relationship r \
                              WHERE r.child_haplogroup_id = h.id AND r.valid_until IS NULL) \
            UNION ALL \
            SELECT c.id, c.name, c.haplogroup_type::text, walk.depth + 1 \
            FROM walk \
            JOIN tree.haplogroup_relationship r ON r.parent_haplogroup_id = walk.id AND r.valid_until IS NULL \
            JOIN tree.haplogroup c ON c.id = r.child_haplogroup_id AND c.valid_until IS NULL \
            WHERE walk.depth < 200) \
         SELECT dna, name, depth FROM walk",
    )
    .fetch_all(pool)
    .await?;
    let mut depth_of: HashMap<(String, String), i32> = HashMap::new();
    for (dna, name, depth) in depth_rows {
        // Keep the shallowest occurrence if a name recurs (defensive; tree is normally acyclic).
        depth_of.entry((dna, name)).and_modify(|d| *d = (*d).min(depth)).or_insert(depth);
    }
    let half = cfg.depth_half_life.max(1.0);

    let mut by_hg: HashMap<(String, String), Vec<Uuid>> = HashMap::new();
    for (guid, dna, hg) in hg_rows {
        by_hg.entry((dna, hg)).or_default().push(guid);
    }
    for ((dna, hg), members) in &by_hg {
        if members.len() < 2 {
            continue;
        }
        // The DNA arm this shared terminal belongs to (drives the IBD_Y/IBD_MT exchange purpose).
        let dna_arm: &'static str = if dna == "MT_DNA" { "MT_DNA" } else { "Y_DNA" };
        // Rarer shared terminal ⇒ more informative, scaled by tree depth: a deeper shared
        // clade is a much tighter relationship signal. Unknown clade ⇒ treated as shallow (3).
        let rarity = (1.0 - members.len() as f64 / total).max(0.01);
        let d = depth_of.get(&(dna.clone(), hg.clone())).copied().unwrap_or(3) as f64;
        let depth_factor = d / (d + half);
        let score = (rarity * depth_factor).max(0.01);
        for (xi, &a) in members.iter().enumerate() {
            for &b in &members[xi + 1..] {
                let (a, b) = ordered(a, b);
                hits.push(Hit { a, b, signal: SIG_HAPLOGROUP, score, dna: Some(dna_arm) });
                rep.haplogroup_pairs += 1;
            }
        }
    }

    // ── Signal 3: shared-match — 2-hop over the *confirmed* match graph. Only
    //    consensus-confirmed, publicly-discoverable edges count (attestation ingest gates
    //    this — see `record_attestation`); a one-sided or disputed report never propagates. ──
    let sm_rows: Vec<(Uuid, Uuid, i64)> = sqlx::query_as(
        "WITH edges AS ( \
            SELECT sample_guid_1 AS a, sample_guid_2 AS b FROM ibd.ibd_discovery_index WHERE is_publicly_discoverable \
            UNION ALL SELECT sample_guid_2, sample_guid_1 FROM ibd.ibd_discovery_index WHERE is_publicly_discoverable) \
         SELECT e1.a, e2.a, count(*) AS shared \
         FROM edges e1 JOIN edges e2 ON e1.b = e2.b AND e1.a < e2.a \
         GROUP BY e1.a, e2.a HAVING count(*) >= $1",
    )
    .bind(cfg.min_shared)
    .fetch_all(pool)
    .await?;
    for (a, b, shared) in sm_rows {
        let (a, b) = ordered(a, b);
        hits.push(Hit { a, b, signal: SIG_SHARED_MATCH, score: shared as f64, dna: None });
        rep.shared_match_pairs += 1;
    }

    // ── Combine per pair, rank per target, cap top-K ──
    struct Combined {
        score: f64,
        primary: &'static str,
        signals: Vec<&'static str>,
        /// The shared haplogroup's DNA arm, if a HAPLOGROUP signal contributed.
        hg_dna: Option<&'static str>,
    }
    let weight = |sig: &str| match sig {
        SIG_POPULATION => cfg.w_population,
        SIG_HAPLOGROUP => cfg.w_haplogroup,
        _ => cfg.w_shared_match,
    };
    let mut combined: HashMap<(Uuid, Uuid), Combined> = HashMap::new();
    for h in hits {
        let contrib = weight(h.signal) * h.score;
        let e = combined.entry((h.a, h.b)).or_insert(Combined { score: 0.0, primary: h.signal, signals: vec![], hg_dna: None });
        if !e.signals.contains(&h.signal) {
            e.signals.push(h.signal);
        }
        if h.dna.is_some() {
            e.hg_dna = h.dna;
        }
        // Primary = the signal with the largest single weighted contribution.
        if contrib >= weight(e.primary) {
            e.primary = h.signal;
        }
        e.score += contrib;
    }

    // Directional candidate rows, grouped by target.
    let mut per_target: HashMap<Uuid, Vec<(Uuid, f64, &'static str, Value)>> = HashMap::new();
    for ((a, b), c) in combined {
        let mut meta = json!({ "signals": c.signals });
        if let Some(dna) = c.hg_dna {
            meta.as_object_mut().unwrap().insert("hgDnaType".into(), json!(dna));
        }
        per_target.entry(a).or_default().push((b, c.score, c.primary, meta.clone()));
        per_target.entry(b).or_default().push((a, c.score, c.primary, meta));
    }

    // ── Declarative write: preserve curator/user decisions, refresh ACTIVE ──
    let dismissed: HashSet<(Uuid, Uuid)> = sqlx::query_as::<_, (Uuid, Uuid)>(
        "SELECT target_sample_guid, suggested_sample_guid FROM ibd.match_suggestion \
         WHERE status IN ('DISMISSED','CONVERTED')",
    )
    .fetch_all(pool)
    .await?
    .into_iter()
    .collect();

    let mut tx = pool.begin().await?;
    sqlx::query("DELETE FROM ibd.match_suggestion WHERE status IN ('ACTIVE','EXPIRED')")
        .execute(&mut *tx)
        .await?;

    for (target, mut cands) in per_target {
        cands.sort_by(|x, y| y.1.total_cmp(&x.1));
        for (suggested, score, primary, meta) in cands.into_iter().take(cfg.top_k) {
            if dismissed.contains(&(target, suggested)) {
                continue;
            }
            sqlx::query(
                "INSERT INTO ibd.match_suggestion \
                    (target_sample_guid, suggested_sample_guid, suggestion_type, score, metadata, status, expires_at) \
                 VALUES ($1, $2, $3, $4, $5, 'ACTIVE', now() + make_interval(days => $6))",
            )
            .bind(target)
            .bind(suggested)
            .bind(primary)
            .bind(score)
            .bind(&meta)
            .bind(cfg.ttl_days)
            .execute(&mut *tx)
            .await?;
            rep.suggestions_written += 1;
        }
    }

    // Refresh the within-block overlap cache (order-independent pairs).
    if !overlap_pairs.is_empty() {
        let s1: Vec<Uuid> = overlap_pairs.iter().map(|(a, _, _)| *a).collect();
        let s2: Vec<Uuid> = overlap_pairs.iter().map(|(_, b, _)| *b).collect();
        let sc: Vec<f64> = overlap_pairs.iter().map(|(_, _, s)| *s).collect();
        sqlx::query(
            "INSERT INTO ibd.population_overlap_score (sample_guid_1, sample_guid_2, score) \
             SELECT a, b, s FROM unnest($1::uuid[], $2::uuid[], $3::float8[]) AS t(a, b, s) \
             ON CONFLICT (LEAST(sample_guid_1, sample_guid_2), GREATEST(sample_guid_1, sample_guid_2)) \
             DO UPDATE SET score = EXCLUDED.score, computed_at = now()",
        )
        .bind(&s1)
        .bind(&s2)
        .bind(&sc)
        .execute(&mut *tx)
        .await?;
    }

    tx.commit().await?;
    Ok(rep)
}
