//! Haplogroup-discovery **variant-set consensus engine** (D6).
//!
//! Citizens publish their private variants (mutations beyond their terminal
//! haplogroup) as `com.decodingus.atmosphere.privateVariant` records; the Jetstream
//! consumer mirrors them into `fed.private_variant`. This engine:
//!   1. **materializes** them into `tree.biosample_private_variant` (resolving each
//!      variant to a `core.variant` id, the biosample to its sample_guid, and the
//!      terminal haplogroup name to an id), and
//!   2. **pools** the per-sample variant sets into `tree.proposed_branch` by
//!      variant-set **Jaccard** similarity — clustering samples that share a private
//!      branch, scoring confidence, and flagging partial overlaps for curator split.
//!
//! Structure mirrors [`crate::sequencer`]'s consensus engine: an advisory-lock
//! wrapper, a **declarative** recompute (counts are *recomputed and assigned* each
//! run, never incremented — so repeated runs are idempotent), stable proposal ids
//! via a partial-unique `cluster_key`, and preservation of curator decisions
//! (`ACCEPTED`/`REJECTED`/`PROMOTED` are never auto-touched). Coexists with the
//! event-driven [`crate::proposal::submit`] path (whose proposals have a NULL
//! `cluster_key` and are excluded from the engine's upsert space).

use crate::DbError;
use serde_json::Value;
use sqlx::PgPool;
use std::collections::HashMap;
use uuid::Uuid;

/// Advisory-lock key guarding concurrent recomputes (hourly job vs. manual run).
const DISCOVERY_ADVISORY_KEY: i64 = 0x4453_4356_5253; // "DSCVRS"

/// Per-DNA-arm consensus thresholds (seeded in `tree.discovery_config`, mig 0029).
#[derive(Debug, Clone)]
pub struct DnaThresholds {
    pub consensus_threshold: i64,
    pub auto_promote_threshold: i64,
    pub confidence_threshold: f64,
    pub similarity_match_threshold: f64,
    pub similarity_split_threshold: f64,
}

impl Default for DnaThresholds {
    fn default() -> Self {
        Self {
            consensus_threshold: 3,
            auto_promote_threshold: 10,
            confidence_threshold: 0.95,
            similarity_match_threshold: 0.80,
            similarity_split_threshold: 0.50,
        }
    }
}

/// Discovery engine configuration (thresholds + confidence weights + flags).
#[derive(Debug, Clone)]
pub struct DiscoveryConfig {
    pub y: DnaThresholds,
    pub mt: DnaThresholds,
    /// Auto-promote unanimous, named, well-supported clusters (default off).
    pub auto_promote: bool,
    /// A contributor whose cross-technology consensus confidence is below this floor
    /// (or whose reconciliation is `INCOMPATIBLE`) is excluded from pooling — its
    /// shaky calls can't drive a branch. Un-reconciled samples are kept (un-gated).
    pub min_consensus_confidence: f64,
    pub w_count: f64,
    pub w_submitters: f64,
    pub w_consistency: f64,
    /// Weight on the cluster's mean consensus reliability (un-reconciled = full).
    pub w_reliability: f64,
}

impl Default for DiscoveryConfig {
    fn default() -> Self {
        Self {
            y: DnaThresholds::default(),
            mt: DnaThresholds::default(),
            auto_promote: false,
            min_consensus_confidence: 0.5,
            w_count: 0.35,
            w_submitters: 0.2,
            w_consistency: 0.25,
            w_reliability: 0.2,
        }
    }
}

impl DiscoveryConfig {
    fn thresholds(&self, dna: &str) -> &DnaThresholds {
        if dna == "MT_DNA" {
            &self.mt
        } else {
            &self.y
        }
    }
}

/// Read `tree.discovery_config` (key → JSONB), falling back to [`Default`].
pub async fn load_config(pool: &PgPool) -> Result<DiscoveryConfig, DbError> {
    let rows: Vec<(String, Value)> =
        sqlx::query_as("SELECT config_key, config_value FROM tree.discovery_config")
            .fetch_all(pool)
            .await?;
    let map: HashMap<String, Value> = rows.into_iter().collect();
    let mut cfg = DiscoveryConfig::default();
    let parse = |v: Option<&Value>, base: DnaThresholds| -> DnaThresholds {
        let Some(v) = v else { return base };
        DnaThresholds {
            consensus_threshold: v.get("consensus_threshold").and_then(Value::as_i64).unwrap_or(base.consensus_threshold),
            auto_promote_threshold: v.get("auto_promote_threshold").and_then(Value::as_i64).unwrap_or(base.auto_promote_threshold),
            confidence_threshold: v.get("confidence_threshold").and_then(Value::as_f64).unwrap_or(base.confidence_threshold),
            similarity_match_threshold: v.get("similarity_match_threshold").and_then(Value::as_f64).unwrap_or(base.similarity_match_threshold),
            similarity_split_threshold: v.get("similarity_split_threshold").and_then(Value::as_f64).unwrap_or(base.similarity_split_threshold),
        }
    };
    cfg.y = parse(map.get("thresholds_Y_DNA"), DnaThresholds::default());
    cfg.mt = parse(map.get("thresholds_MT_DNA"), DnaThresholds::default());
    if let Some(w) = map.get("confidence_weights") {
        cfg.w_count = w.get("w_count").and_then(Value::as_f64).unwrap_or(cfg.w_count);
        cfg.w_submitters = w.get("w_submitters").and_then(Value::as_f64).unwrap_or(cfg.w_submitters);
        cfg.w_consistency = w.get("w_consistency").and_then(Value::as_f64).unwrap_or(cfg.w_consistency);
        cfg.w_reliability = w.get("w_reliability").and_then(Value::as_f64).unwrap_or(cfg.w_reliability);
    }
    if let Some(e) = map.get("engine") {
        cfg.auto_promote = e.get("auto_promote").and_then(Value::as_bool).unwrap_or(cfg.auto_promote);
        cfg.min_consensus_confidence =
            e.get("min_consensus_confidence").and_then(Value::as_f64).unwrap_or(cfg.min_consensus_confidence);
    }
    Ok(cfg)
}

/// Outcome of [`recompute_consensus`].
#[derive(Debug, Default, Clone)]
pub struct DiscoveryReport {
    pub bpv_upserted: u64,
    pub bpv_pruned: u64,
    pub samples_unresolved: u64,
    pub proposals_active: u64,
    pub proposals_ready: u64,
    pub split_flagged: u64,
    pub auto_promoted: u64,
}

/// Recompute the discovery consensus from `fed.private_variant`. Single-flighted by
/// a session advisory lock (a second caller no-ops). Unlocks on every path.
pub async fn recompute_consensus(pool: &PgPool, cfg: &DiscoveryConfig) -> Result<DiscoveryReport, DbError> {
    let mut lock_conn = pool.acquire().await?;
    let locked: bool = sqlx::query_scalar("SELECT pg_try_advisory_lock($1)")
        .bind(DISCOVERY_ADVISORY_KEY)
        .fetch_one(&mut *lock_conn)
        .await?;
    if !locked {
        return Ok(DiscoveryReport::default());
    }
    let result = recompute_locked(pool, cfg).await;
    let _ = sqlx::query("SELECT pg_advisory_unlock($1)")
        .bind(DISCOVERY_ADVISORY_KEY)
        .execute(&mut *lock_conn)
        .await;
    result
}

async fn recompute_locked(pool: &PgPool, cfg: &DiscoveryConfig) -> Result<DiscoveryReport, DbError> {
    let mut rep = DiscoveryReport::default();
    materialize(pool, &mut rep).await?;
    let auto_ids = pool_and_propose(pool, cfg, &mut rep).await?;
    // Auto-promote (off by default; only named clusters succeed — promote requires a
    // name, so unnamed engine proposals stay ACCEPTED for a curator to name+promote).
    for id in auto_ids {
        match crate::proposal::promote(pool, id, "discovery-engine").await {
            Ok(_) => rep.auto_promoted += 1,
            Err(e) => tracing::info!(proposal = id, error = %e, "auto-promote skipped (likely unnamed)"),
        }
    }
    Ok(rep)
}

// ── materialization: fed.private_variant → tree.biosample_private_variant ─────────

/// A fed.private_variant row joined to its resolved sample_guid (NULL when the
/// biosample isn't mirrored): (sample_guid, dna_type, terminal_haplogroup, variants).
type FedRow = (Option<Uuid>, Option<String>, Option<String>, Value);

async fn materialize(pool: &PgPool, rep: &mut DiscoveryReport) -> Result<(), DbError> {
    let rows: Vec<FedRow> = sqlx::query_as(
        "SELECT b.sample_guid, pv.dna_type, pv.terminal_haplogroup, pv.variants \
         FROM fed.private_variant pv \
         LEFT JOIN core.biosample b ON b.atproto->>'uri' = pv.biosample_ref",
    )
    .fetch_all(pool)
    .await?;

    let mut tx = pool.begin().await?;
    // (sample_guid, variant_id) pairs materialized this run — the prune keep-set.
    let mut keep_samples: Vec<Uuid> = Vec::new();
    let mut keep_variants: Vec<i64> = Vec::new();

    for (sample_guid, dna_type, terminal, variants) in &rows {
        let Some(sample_guid) = sample_guid else {
            rep.samples_unresolved += 1;
            continue;
        };
        let dna = dna_type.as_deref().unwrap_or("Y_DNA");
        let Some(items) = variants.as_array() else { continue };
        for v in items {
            // Resolve to a core.variant id: named -> by name; else by coordinates.
            let variant_id = if let Some(name) = v.get("name").and_then(Value::as_str).filter(|s| !s.is_empty()) {
                crate::variant::ensure_base_variant_id(&mut tx, name).await?
            } else if let (Some(contig), Some(pos)) =
                (v.get("contig").and_then(Value::as_str), v.get("position").and_then(Value::as_i64))
            {
                let anc = v.get("ancestral").and_then(Value::as_str);
                let der = v.get("derived").and_then(Value::as_str);
                crate::variant::ensure_variant_by_coords(&mut tx, contig, pos, anc, der).await?
            } else {
                continue; // malformed element
            };

            sqlx::query(
                "INSERT INTO tree.biosample_private_variant \
                    (sample_guid, variant_id, haplogroup_type, terminal_haplogroup_id, status) \
                 VALUES ($1, $2, $3::core.dna_type, \
                    (SELECT id FROM tree.haplogroup WHERE name = $4 AND haplogroup_type = $3::core.dna_type \
                       AND valid_until IS NULL LIMIT 1), 'ACTIVE') \
                 ON CONFLICT (sample_guid, variant_id, haplogroup_type) DO UPDATE SET \
                    terminal_haplogroup_id = EXCLUDED.terminal_haplogroup_id, \
                    status = CASE WHEN tree.biosample_private_variant.status IN ('INVALIDATED','PROMOTED') \
                                  THEN tree.biosample_private_variant.status ELSE 'ACTIVE' END",
            )
            .bind(sample_guid)
            .bind(variant_id)
            .bind(dna)
            .bind(terminal)
            .execute(&mut *tx)
            .await?;
            rep.bpv_upserted += 1;
            keep_samples.push(*sample_guid);
            keep_variants.push(variant_id);
        }
    }

    // Prune ACTIVE rows no longer backed by a current fed record (paired arrays as
    // the keep-set; an empty set prunes all ACTIVE, which is correct when fed empties).
    rep.bpv_pruned = sqlx::query(
        // origin='FED' only: de-novo-seeded ('DENOVO') privates have no fed record
        // backing them and must survive the prune.
        "DELETE FROM tree.biosample_private_variant bpv \
         WHERE bpv.status = 'ACTIVE' AND bpv.origin = 'FED' AND NOT EXISTS ( \
            SELECT 1 FROM unnest($1::uuid[], $2::bigint[]) AS k(sg, vid) \
            WHERE k.sg = bpv.sample_guid AND k.vid = bpv.variant_id)",
    )
    .bind(&keep_samples)
    .bind(&keep_variants)
    .execute(&mut *tx)
    .await?
    .rows_affected();

    tx.commit().await?;
    Ok(())
}

/// After a proposal is promoted to a new terminal haplogroup, freeze its
/// contributing samples: mark their `tree.biosample_private_variant` rows for the
/// promoted (defining) variants `PROMOTED` and bump their `terminal_haplogroup_id`
/// to the new branch. This is the only writable per-sample assignment record (there
/// is no `biosample_haplogroup` table), and marking them `PROMOTED` also freezes
/// them out of the recompute loop (materialize/prune/pool all act on `ACTIVE` only),
/// so a promoted contribution never re-pools into a fresh proposal. Runs inside the
/// promote transaction. Returns the number of rows reassigned.
pub async fn reassign_after_promote(
    conn: &mut sqlx::PgConnection,
    proposed_branch_id: i64,
    new_haplogroup_id: i64,
) -> Result<u64, DbError> {
    let affected = sqlx::query(
        "UPDATE tree.biosample_private_variant bpv \
         SET status = 'PROMOTED', terminal_haplogroup_id = $2 \
         WHERE bpv.status = 'ACTIVE' \
           AND bpv.sample_guid IN ( \
               SELECT unnest(discovery_sample_guids) FROM tree.proposed_branch WHERE id = $1) \
           AND bpv.variant_id IN ( \
               SELECT variant_id FROM tree.proposed_branch_variant WHERE proposed_branch_id = $1)",
    )
    .bind(proposed_branch_id)
    .bind(new_haplogroup_id)
    .execute(conn)
    .await?
    .rows_affected();
    Ok(affected)
}

// ── pooling: per-sample variant sets → proposed branches (Jaccard) ───────────────

/// One sample's private-variant set under a terminal.
struct SampleSet {
    sample_guid: Uuid,
    vset: Vec<i64>, // sorted ascending
}

/// A cluster of samples that share a private branch.
struct Cluster {
    members: Vec<Uuid>,
    /// Union of member variant ids (sorted) — the proposed branch's defining set.
    union: Vec<i64>,
    /// supporting_sample_count per union variant (parallel to `union`).
    counts: Vec<i32>,
    /// Mean member completeness vs. the union (∈ (0,1]); the consistency signal.
    consistency: f64,
    /// A diverging peer was seen (Jaccard in [split, match)) — flag for curator split.
    split: bool,
}

fn jaccard(a: &[i64], b: &[i64]) -> f64 {
    // Both sorted ascending; merge to count intersection.
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
    if union == 0 {
        0.0
    } else {
        inter as f64 / union as f64
    }
}

/// Deterministic seed-based clustering of one bucket's sample-sets. Sets are sorted
/// (size desc, then min variant id, then guid) so the result is independent of DB
/// row order. Each unassigned set seeds a cluster; later sets join if their Jaccard
/// with the seed ≥ match; sets in [split, match) flag the seed for split review.
fn cluster_bucket(mut sets: Vec<SampleSet>, th: &DnaThresholds) -> Vec<Cluster> {
    sets.sort_by(|a, b| {
        b.vset.len().cmp(&a.vset.len())
            .then_with(|| a.vset.first().cmp(&b.vset.first()))
            .then_with(|| a.sample_guid.cmp(&b.sample_guid))
    });
    let n = sets.len();
    let mut assigned = vec![false; n];
    let mut clusters = Vec::new();

    for i in 0..n {
        if assigned[i] {
            continue;
        }
        assigned[i] = true;
        let seed = sets[i].vset.clone();
        let mut members = vec![sets[i].sample_guid];
        let mut member_sets = vec![sets[i].vset.clone()];
        let mut split = false;
        for (j, item) in sets.iter().enumerate().skip(i + 1) {
            if assigned[j] {
                continue;
            }
            let sim = jaccard(&seed, &item.vset);
            if sim >= th.similarity_match_threshold {
                assigned[j] = true;
                members.push(item.sample_guid);
                member_sets.push(item.vset.clone());
            } else if sim >= th.similarity_split_threshold {
                split = true; // diverging peer — leave it to seed its own cluster
            }
        }
        // Union + per-variant support across cluster members.
        let mut support: std::collections::BTreeMap<i64, i32> = std::collections::BTreeMap::new();
        for ms in &member_sets {
            for &vid in ms {
                *support.entry(vid).or_insert(0) += 1;
            }
        }
        let union: Vec<i64> = support.keys().copied().collect();
        let counts: Vec<i32> = union.iter().map(|v| support[v]).collect();
        let consistency = if union.is_empty() {
            0.0
        } else {
            member_sets.iter().map(|ms| ms.len() as f64 / union.len() as f64).sum::<f64>() / member_sets.len() as f64
        };
        clusters.push(Cluster { members, union, counts, consistency, split });
    }
    clusters
}

/// Pool ACTIVE private variants into proposed branches; returns proposal ids eligible
/// for auto-promotion (when `cfg.auto_promote`).
async fn pool_and_propose(pool: &PgPool, cfg: &DiscoveryConfig, rep: &mut DiscoveryReport) -> Result<Vec<i64>, DbError> {
    // Per-sample variant sets under a resolved terminal.
    let raw: Vec<(Uuid, i64, String, Vec<i64>)> = sqlx::query_as(
        "SELECT sample_guid, terminal_haplogroup_id, haplogroup_type::text AS dna, \
                array_agg(variant_id ORDER BY variant_id) AS vset \
         FROM tree.biosample_private_variant \
         WHERE status = 'ACTIVE' AND terminal_haplogroup_id IS NOT NULL \
         GROUP BY sample_guid, terminal_haplogroup_id, haplogroup_type",
    )
    .fetch_all(pool)
    .await?;

    // sample_guid → repo_did (distinct-submitter signal).
    let submitter: HashMap<Uuid, Option<String>> = sqlx::query_as::<_, (Uuid, Option<String>)>(
        "SELECT sample_guid, atproto->>'repo_did' FROM core.biosample WHERE atproto IS NOT NULL",
    )
    .fetch_all(pool)
    .await?
    .into_iter()
    .collect();

    // (sample_guid, dna) → cross-technology consensus reliability (confidence,
    // compatibility), via the citizen's repo DID = the reconciliation publisher's DID.
    // Drives both the exclusion gate and the confidence down-weight below.
    let reliability: HashMap<(Uuid, String), (Option<f64>, Option<String>)> =
        sqlx::query_as::<_, (Uuid, String, Option<f64>, Option<String>)>(
            "SELECT DISTINCT ON (b.sample_guid, r.dna_type) b.sample_guid, r.dna_type, r.confidence, r.compatibility_level \
             FROM core.biosample b \
             JOIN fed.haplogroup_reconciliation r ON r.did = b.atproto->>'repo_did' \
             WHERE b.atproto IS NOT NULL AND r.dna_type IS NOT NULL \
             ORDER BY b.sample_guid, r.dna_type, r.run_count DESC NULLS LAST, r.time_us DESC",
        )
        .fetch_all(pool)
        .await?
        .into_iter()
        .map(|(g, dna, conf, compat)| ((g, dna), (conf, compat)))
        .collect();

    // Bucket by (parent terminal, dna).
    let mut buckets: HashMap<(i64, String), Vec<SampleSet>> = HashMap::new();
    for (sample_guid, terminal, dna, vset) in raw {
        buckets.entry((terminal, dna)).or_default().push(SampleSet { sample_guid, vset });
    }

    let mut tx = pool.begin().await?;
    let mut kept_ids: Vec<i64> = Vec::new();
    let mut auto_ids: Vec<i64> = Vec::new();

    // Deterministic bucket order.
    let mut keys: Vec<(i64, String)> = buckets.keys().cloned().collect();
    keys.sort();

    for key in keys {
        let (parent_id, dna) = key.clone();
        let th = cfg.thresholds(&dna);
        let sets = buckets.remove(&key).unwrap();
        // Reliability gate: drop members whose consensus is INCOMPATIBLE or below the
        // confidence floor. Un-reconciled samples (no entry) are kept (un-gated).
        let sets: Vec<SampleSet> = sets
            .into_iter()
            .filter(|s| match reliability.get(&(s.sample_guid, dna.clone())) {
                Some((conf, compat)) => {
                    compat.as_deref() != Some("INCOMPATIBLE")
                        && conf.map_or(true, |c| c >= cfg.min_consensus_confidence)
                }
                None => true,
            })
            .collect();
        if sets.is_empty() {
            continue;
        }
        for c in cluster_bucket(sets, th) {
            let count = c.members.len() as i64;
            // No consensus below the threshold: a single (or sparse) sample set is not
            // a branch candidate, so it does not get a proposal — it stays as ACTIVE
            // privates in the substrate until ≥ consensus_threshold samples corroborate.
            // (Without this, a bulk de-novo seed spawns one placeholder per sample.)
            if count < th.consensus_threshold {
                continue;
            }
            let submitters = c
                .members
                .iter()
                .filter_map(|g| submitter.get(g).and_then(|d| d.clone()))
                .collect::<std::collections::HashSet<_>>()
                .len()
                .max(c.members.len()) as i64; // fall back to sample count if dids missing
            let f_count = (count as f64 / th.consensus_threshold.max(1) as f64).min(1.0);
            let f_sub = (submitters as f64 / th.consensus_threshold.max(1) as f64).min(1.0);
            // Mean cross-technology consensus reliability of the cluster's members
            // (un-reconciled = full credit, so the unknown isn't penalized).
            let mean_rel = c
                .members
                .iter()
                .map(|g| reliability.get(&(*g, dna.clone())).and_then(|(c, _)| *c).unwrap_or(1.0))
                .sum::<f64>()
                / count as f64;
            let confidence = (cfg.w_count * f_count
                + cfg.w_submitters * f_sub
                + cfg.w_consistency * c.consistency
                + cfg.w_reliability * mean_rel)
                .min(1.0);
            let ready = count >= th.consensus_threshold && confidence >= th.confidence_threshold;
            let auto = cfg.auto_promote
                && count >= th.auto_promote_threshold
                && confidence >= th.confidence_threshold
                && c.consistency >= 0.999;
            let status = if auto {
                "ACCEPTED"
            } else if c.split {
                "SPLIT_CANDIDATE"
            } else if ready {
                "READY_FOR_REVIEW"
            } else {
                "PROPOSED"
            };
            // Identity = the sorted union variant-id set, but stored md5-digested:
            // a large shared cluster's raw key (dozens of bigint ids) overflows the
            // btree row limit (2704 B). md5 is a fixed 32-char stable digest, and the
            // key is only an upsert arbiter — the real set lives in proposed_branch_variant.
            let cluster_key: String = c.union.iter().map(|v| v.to_string()).collect::<Vec<_>>().join(",");
            let guids: Vec<Uuid> = c.members.clone();

            let id: i64 = sqlx::query_scalar(
                "INSERT INTO tree.proposed_branch \
                    (parent_haplogroup_id, haplogroup_type, cluster_key, discovery_sample_guids, \
                     evidence_count, confidence, proposed_by, status) \
                 VALUES ($1, $2::core.dna_type, md5($3), $4, $5, $6::float8::numeric, 'discovery-engine', $7) \
                 ON CONFLICT (parent_haplogroup_id, haplogroup_type, cluster_key) \
                   WHERE status IN ('PROPOSED','UNDER_REVIEW','READY_FOR_REVIEW','SPLIT_CANDIDATE') AND cluster_key IS NOT NULL \
                 DO UPDATE SET discovery_sample_guids = EXCLUDED.discovery_sample_guids, \
                    evidence_count = EXCLUDED.evidence_count, confidence = EXCLUDED.confidence, \
                    status = EXCLUDED.status \
                 RETURNING id",
            )
            .bind(parent_id)
            .bind(&dna)
            .bind(&cluster_key)
            .bind(&guids)
            .bind(count as i32)
            .bind(confidence)
            .bind(status)
            .fetch_one(&mut *tx)
            .await?;

            // Rebuild the defining-variant set (declarative — DELETE then insert).
            sqlx::query("DELETE FROM tree.proposed_branch_variant WHERE proposed_branch_id = $1")
                .bind(id)
                .execute(&mut *tx)
                .await?;
            sqlx::query(
                "INSERT INTO tree.proposed_branch_variant (proposed_branch_id, variant_id, supporting_sample_count) \
                 SELECT $1, vid, cnt FROM unnest($2::bigint[], $3::int[]) AS t(vid, cnt)",
            )
            .bind(id)
            .bind(&c.union)
            .bind(&c.counts)
            .execute(&mut *tx)
            .await?;

            // Refresh the engine's own split-candidate evidence row (idempotent).
            sqlx::query(
                "DELETE FROM tree.proposed_branch_evidence \
                 WHERE proposed_branch_id = $1 AND evidence_type = 'SPLIT_CANDIDATE'",
            )
            .bind(id)
            .execute(&mut *tx)
            .await?;
            if c.split {
                rep.split_flagged += 1;
                sqlx::query(
                    "INSERT INTO tree.proposed_branch_evidence (proposed_branch_id, evidence_type, evidence_detail) \
                     VALUES ($1, 'SPLIT_CANDIDATE', $2)",
                )
                .bind(id)
                .bind(serde_json::json!({ "note": "a sample partially overlaps this branch (Jaccard in [split, match))" }))
                .execute(&mut *tx)
                .await?;
            }

            kept_ids.push(id);
            rep.proposals_active += 1;
            if ready {
                rep.proposals_ready += 1;
            }
            if auto {
                auto_ids.push(id);
            }
        }
    }

    // Prune engine-owned open proposals not regenerated this run. Preserves
    // curator-decided states and submit()-created (NULL cluster_key) proposals.
    sqlx::query(
        "DELETE FROM tree.proposed_branch \
         WHERE cluster_key IS NOT NULL \
           AND status IN ('PROPOSED','UNDER_REVIEW','READY_FOR_REVIEW','SPLIT_CANDIDATE') \
           AND id <> ALL($1)",
    )
    .bind(&kept_ids)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;
    Ok(auto_ids)
}
