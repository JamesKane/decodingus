//! Sequencer-lab lookup — resolve a sequencing instrument id (from BAM/CRAM `@RG`
//! headers, e.g. `A00123`) to its sequencing laboratory, for the Edge analyzer.
//!
//! Resolves via the **preseeded** direct association
//! (`genomics.sequencer_instrument.lab_id` → `genomics.sequencing_lab`). The
//! consensus/curation path (`instrument_observation` → `instrument_association_
//! proposal` → accept) is not live yet; when it is, accepting a proposal sets
//! `lab_id` and this lookup is unchanged.

use crate::pagination::Page;
use crate::DbError;
use serde_json::json;
use sqlx::PgPool;
use std::collections::{HashMap, HashSet};
use uuid::Uuid;

/// A resolved instrument → lab association.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct LabLookup {
    pub instrument_id: String,
    pub lab_name: String,
    pub is_d2c: bool,
    pub website_url: Option<String>,
    pub manufacturer: Option<String>,
    pub model_name: Option<String>,
}

/// Resolve a single instrument id to its lab. `None` when the instrument is
/// unknown or has no preseeded lab association.
pub async fn lookup_lab(pool: &PgPool, instrument_id: &str) -> Result<Option<LabLookup>, DbError> {
    Ok(sqlx::query_as::<_, LabLookup>(
        "SELECT si.instrument_id, sl.name AS lab_name, sl.is_d2c, sl.website_url, \
                si.manufacturer, si.model_name \
         FROM genomics.sequencer_instrument si \
         JOIN genomics.sequencing_lab sl ON sl.id = si.lab_id \
         WHERE si.instrument_id = $1",
    )
    .bind(instrument_id)
    .fetch_optional(pool)
    .await?)
}

/// Every preseeded instrument → lab association (the Edge's bulk cache seed),
/// ordered by instrument id.
pub async fn lab_instruments(pool: &PgPool) -> Result<Vec<LabLookup>, DbError> {
    Ok(sqlx::query_as::<_, LabLookup>(
        "SELECT si.instrument_id, sl.name AS lab_name, sl.is_d2c, sl.website_url, \
                si.manufacturer, si.model_name \
         FROM genomics.sequencer_instrument si \
         JOIN genomics.sequencing_lab sl ON sl.id = si.lab_id \
         ORDER BY si.instrument_id",
    )
    .fetch_all(pool)
    .await?)
}

// ── consensus engine ─────────────────────────────────────────────────────────
//
// Observations are derived from the federation: each `fed.sequencerun` carrying
// an `@RG` instrument id, joined to its `fed.biosample.center_name` (the claimed
// lab), is one citizen observation. They aggregate per instrument into a single
// active `instrument_association_proposal` (dominant lab + confidence + status);
// a curator accept sets `sequencer_instrument.lab_id`, which is what `lookup_lab`
// resolves. The AppView aggregates + proposes; it does not auto-decide unless
// `auto_accept` is explicitly enabled.

/// Center names that are not real labs — excluded from observations.
const GENERIC_CENTERS: &[&str] =
    &["", "unknown", "self", "home", "n/a", "na", "none", "not provided", "unspecified", "private"];

/// Consensus thresholds + confidence weights (spec defaults).
#[derive(Debug, Clone)]
pub struct ConsensusConfig {
    /// Minimum observations before a proposal is created at all.
    pub min_observations: i64,
    /// Distinct citizens (or observations) for `READY_FOR_REVIEW`.
    pub ready_for_review: i64,
    /// Observations for auto-acceptance (only if `auto_accept`).
    pub auto_accept_threshold: i64,
    /// Distinct citizens required for `READY_FOR_REVIEW` / auto-acceptance.
    pub min_distinct_citizens: i64,
    /// Dominant-lab agreement ratio required (else held as a conflict).
    pub agreement_ratio: f64,
    /// Auto-accept unanimous, well-supported proposals (default off — curator-gated).
    pub auto_accept: bool,
    pub w_observation: f64,
    pub w_citizen: f64,
    pub w_recency: f64,
    pub w_level: f64,
}

impl Default for ConsensusConfig {
    fn default() -> Self {
        Self {
            min_observations: 2,
            ready_for_review: 5,
            auto_accept_threshold: 10,
            min_distinct_citizens: 3,
            agreement_ratio: 0.9,
            auto_accept: false,
            w_observation: 0.4,
            w_citizen: 0.3,
            w_recency: 0.2,
            w_level: 0.1,
        }
    }
}

/// Outcome of [`recompute_consensus`].
#[derive(Debug, Default, Clone)]
pub struct ConsensusReport {
    pub instruments: i64,
    pub observations_upserted: u64,
    pub observations_pruned: u64,
    pub proposals_active: u64,
    pub proposals_ready: u64,
    pub conflicts: u64,
    pub auto_accepted: u64,
}

/// Confidence score in [0,1] from the spec's weighted blend. `conf_level` is the
/// mean per-claim confidence weight (KNOWN=1.0 / INFERRED=0.7 / GUESSED=0.3) and
/// `recency` the freshest observation's recency factor (1.0 ≤30d, linear decay to
/// 0 over a year, 0.5 when undated) — both computed in SQL across the instrument's
/// observations, from explicit `instrumentObservation` records where present.
fn confidence(cfg: &ConsensusConfig, obs: i64, citizens: i64, conf_level: f64, recency: f64) -> f64 {
    let f_obs = (obs as f64 / cfg.auto_accept_threshold.max(1) as f64).min(1.0);
    let f_cit = (citizens as f64 / cfg.min_distinct_citizens.max(1) as f64).min(1.0);
    (cfg.w_observation * f_obs + cfg.w_citizen * f_cit + cfg.w_recency * recency + cfg.w_level * conf_level).min(1.0)
}

/// Get-or-create a sequencing lab by name; returns its id.
async fn get_or_create_lab(conn: &mut sqlx::PgConnection, name: &str, is_d2c: bool) -> Result<i64, DbError> {
    if let Some(id) = sqlx::query_scalar::<_, i64>(
        "INSERT INTO genomics.sequencing_lab (name, is_d2c) VALUES ($1, $2) \
         ON CONFLICT (name) DO NOTHING RETURNING id",
    )
    .bind(name)
    .bind(is_d2c)
    .fetch_optional(&mut *conn)
    .await?
    {
        return Ok(id);
    }
    Ok(sqlx::query_scalar::<_, i64>("SELECT id FROM genomics.sequencing_lab WHERE name = $1")
        .bind(name)
        .fetch_one(&mut *conn)
        .await?)
}

/// Recompute instrument→lab consensus from the federation: refresh observations,
/// then regenerate the active proposal set (dominant lab, counts, confidence,
/// status). Curator-decided proposals (`ACCEPTED`/`REJECTED`) and already-resolved
/// instruments (`lab_id` set) are preserved/skipped.
/// Advisory-lock key guarding concurrent recomputes (the hourly job vs. a manual
/// `run-once`). A second caller no-ops instead of interleaving the observation
/// refresh and proposal regeneration.
const CONSENSUS_ADVISORY_KEY: i64 = 0x434F_4E53_4E53; // "CONSNS"

pub async fn recompute_consensus(pool: &PgPool, cfg: &ConsensusConfig) -> Result<ConsensusReport, DbError> {
    // Hold a dedicated connection for the duration so only one recompute runs at a
    // time. Unlock on every path — a leaked session lock would ride a pooled
    // connection back into reuse and wedge all future recomputes.
    let mut lock_conn = pool.acquire().await?;
    let locked: bool = sqlx::query_scalar("SELECT pg_try_advisory_lock($1)")
        .bind(CONSENSUS_ADVISORY_KEY)
        .fetch_one(&mut *lock_conn)
        .await?;
    if !locked {
        return Ok(ConsensusReport::default());
    }
    let result = recompute_locked(pool, cfg).await;
    let _ = sqlx::query("SELECT pg_advisory_unlock($1)")
        .bind(CONSENSUS_ADVISORY_KEY)
        .execute(&mut *lock_conn)
        .await;
    result
}

async fn recompute_locked(pool: &PgPool, cfg: &ConsensusConfig) -> Result<ConsensusReport, DbError> {
    let mut rep = ConsensusReport::default();
    let generic: Vec<String> = GENERIC_CENTERS.iter().map(|s| s.to_string()).collect();

    // 1) Ensure a sequencer_instrument row for every federated instrument id —
    //    from both implicit sequenceruns and explicit instrumentObservation records.
    sqlx::query(
        "INSERT INTO genomics.sequencer_instrument (instrument_id, model_name) \
         SELECT s.instrument_id, (array_agg(s.instrument_model) FILTER (WHERE s.instrument_model IS NOT NULL))[1] \
         FROM fed.sequencerun s \
         WHERE s.instrument_id IS NOT NULL AND btrim(s.instrument_id) <> '' \
         GROUP BY s.instrument_id \
         ON CONFLICT (instrument_id) DO NOTHING",
    )
    .execute(pool)
    .await?;
    sqlx::query(
        "INSERT INTO genomics.sequencer_instrument (instrument_id, model_name) \
         SELECT o.instrument_id, (array_agg(o.instrument_model) FILTER (WHERE o.instrument_model IS NOT NULL))[1] \
         FROM fed.instrument_observation o \
         WHERE o.instrument_id IS NOT NULL AND btrim(o.instrument_id) <> '' \
         GROUP BY o.instrument_id \
         ON CONFLICT (instrument_id) DO NOTHING",
    )
    .execute(pool)
    .await?;

    // 2) Refresh observations from fed.sequencerun ⋈ fed.biosample (upsert by uri).
    rep.observations_upserted = sqlx::query(
        "INSERT INTO genomics.instrument_observation \
            (instrument_id, lab_name, biosample_ref, platform, instrument_model, confidence, atproto) \
         SELECT si.id, btrim(b.center_name), s.biosample_ref, s.platform_name, s.instrument_model, 'INFERRED', \
                jsonb_build_object('uri', s.at_uri, 'cid', s.cid, 'repo_did', s.did) \
         FROM fed.sequencerun s \
         JOIN fed.biosample b ON b.at_uri = s.biosample_ref \
         JOIN genomics.sequencer_instrument si ON si.instrument_id = s.instrument_id \
         WHERE s.instrument_id IS NOT NULL AND b.center_name IS NOT NULL \
           AND lower(btrim(b.center_name)) <> ALL($1::text[]) \
         ON CONFLICT ((atproto->>'uri')) WHERE atproto IS NOT NULL \
         DO UPDATE SET instrument_id = EXCLUDED.instrument_id, lab_name = EXCLUDED.lab_name, \
            biosample_ref = EXCLUDED.biosample_ref, platform = EXCLUDED.platform, \
            instrument_model = EXCLUDED.instrument_model",
    )
    .bind(&generic)
    .execute(pool)
    .await?
    .rows_affected();

    // 2b) Fold in explicit citizen instrumentObservation records — these carry a
    //     real confidence level and observation timestamp (the implicit ones above
    //     are all INFERRED/undated). Keyed by the record's own uri, so they coexist
    //     with the sequencerun-derived rows rather than replacing them.
    rep.observations_upserted += sqlx::query(
        "INSERT INTO genomics.instrument_observation \
            (instrument_id, lab_name, biosample_ref, platform, instrument_model, flowcell_id, run_date, confidence, observed_at, atproto) \
         SELECT si.id, btrim(o.lab_name), o.biosample_ref, o.platform, o.instrument_model, o.flowcell_id, o.run_date, \
                upper(coalesce(o.confidence, 'INFERRED')), o.observed_at, \
                jsonb_build_object('uri', o.at_uri, 'cid', o.cid, 'repo_did', o.did) \
         FROM fed.instrument_observation o \
         JOIN genomics.sequencer_instrument si ON si.instrument_id = o.instrument_id \
         WHERE o.instrument_id IS NOT NULL AND o.lab_name IS NOT NULL \
           AND lower(btrim(o.lab_name)) <> ALL($1::text[]) \
         ON CONFLICT ((atproto->>'uri')) WHERE atproto IS NOT NULL \
         DO UPDATE SET instrument_id = EXCLUDED.instrument_id, lab_name = EXCLUDED.lab_name, \
            biosample_ref = EXCLUDED.biosample_ref, platform = EXCLUDED.platform, \
            instrument_model = EXCLUDED.instrument_model, flowcell_id = EXCLUDED.flowcell_id, \
            run_date = EXCLUDED.run_date, confidence = EXCLUDED.confidence, observed_at = EXCLUDED.observed_at",
    )
    .bind(&generic)
    .execute(pool)
    .await?
    .rows_affected();

    // Prune observations no longer backed by a current fed record (either source).
    rep.observations_pruned = sqlx::query(
        "DELETE FROM genomics.instrument_observation o \
         WHERE o.atproto->>'uri' IS NOT NULL \
           AND o.atproto->>'uri' NOT IN (SELECT at_uri FROM fed.sequencerun WHERE instrument_id IS NOT NULL) \
           AND o.atproto->>'uri' NOT IN (SELECT at_uri FROM fed.instrument_observation WHERE instrument_id IS NOT NULL)",
    )
    .execute(pool)
    .await?
    .rows_affected();

    // 3) Aggregate. Per-instrument totals (distinct citizens overall) and per-lab claims.
    #[derive(sqlx::FromRow)]
    struct Total {
        si_id: i64,
        obs_total: i64,
        citizens_total: i64,
        conf_level: f64,
        recency: f64,
    }
    let totals: Vec<Total> = sqlx::query_as(
        "SELECT o.instrument_id AS si_id, count(*) AS obs_total, \
                count(DISTINCT o.atproto->>'repo_did') AS citizens_total, \
                avg(CASE upper(o.confidence) WHEN 'KNOWN' THEN 1.0 WHEN 'GUESSED' THEN 0.3 ELSE 0.7 END)::float8 AS conf_level, \
                COALESCE(max(CASE \
                     WHEN o.observed_at IS NULL THEN 0.5 \
                     WHEN o.observed_at > now() - interval '30 days' THEN 1.0 \
                     ELSE GREATEST(0.0, 1.0 - EXTRACT(EPOCH FROM now() - o.observed_at) / EXTRACT(EPOCH FROM interval '365 days')) \
                   END), 0.5)::float8 AS recency \
         FROM genomics.instrument_observation o \
         JOIN genomics.sequencer_instrument si ON si.id = o.instrument_id \
         WHERE si.lab_id IS NULL AND o.lab_name IS NOT NULL \
         GROUP BY o.instrument_id",
    )
    .fetch_all(pool)
    .await?;
    let totals: HashMap<i64, (i64, i64, f64, f64)> = totals
        .into_iter()
        .map(|t| (t.si_id, (t.obs_total, t.citizens_total, t.conf_level, t.recency)))
        .collect();

    #[derive(sqlx::FromRow)]
    struct Claim {
        si_id: i64,
        lab_name: String,
        obs: i64,
        citizens: i64,
    }
    let claims: Vec<Claim> = sqlx::query_as(
        "SELECT o.instrument_id AS si_id, o.lab_name, count(*) AS obs, \
                count(DISTINCT o.atproto->>'repo_did') AS citizens \
         FROM genomics.instrument_observation o \
         JOIN genomics.sequencer_instrument si ON si.id = o.instrument_id \
         WHERE si.lab_id IS NULL AND o.lab_name IS NOT NULL \
         GROUP BY o.instrument_id, o.lab_name",
    )
    .fetch_all(pool)
    .await?;
    let mut by_instr: HashMap<i64, Vec<(String, i64, i64)>> = HashMap::new();
    for c in claims {
        by_instr.entry(c.si_id).or_default().push((c.lab_name, c.obs, c.citizens));
    }

    // Curator decisions to honor: accepted instruments (skip) + rejected (instr,lab) pairs.
    let terminal: Vec<(i64, Option<String>, String)> = sqlx::query_as(
        "SELECT instrument_id, proposed_lab_name, status FROM genomics.instrument_association_proposal \
         WHERE status IN ('ACCEPTED','REJECTED')",
    )
    .fetch_all(pool)
    .await?;
    let mut accepted: HashSet<i64> = HashSet::new();
    let mut rejected: HashSet<(i64, String)> = HashSet::new();
    for (si_id, lab, status) in terminal {
        if status == "ACCEPTED" {
            accepted.insert(si_id);
        } else if let Some(lab) = lab {
            rejected.insert((si_id, lab));
        }
    }

    // Regenerate the active proposal set in one transaction. Each unresolved
    // instrument keeps at most one active (PENDING/READY) proposal, UPSERTed in
    // place so its id stays stable across recomputes — a curator's open proposal
    // survives a background run. Active proposals whose instrument fell out of the
    // set (resolved, dropped below threshold, dominant lab rejected) are pruned at
    // the end.
    let mut tx = pool.begin().await?;
    let mut active_ids: Vec<i64> = Vec::new();

    for (si_id, mut labs) in by_instr {
        rep.instruments += 1;
        if accepted.contains(&si_id) {
            continue;
        }
        let (obs_total, citizens_total, conf_level, recency) = totals.get(&si_id).copied().unwrap_or((0, 0, 0.7, 0.5));
        if obs_total < cfg.min_observations {
            continue;
        }
        // Dominant lab: most distinct citizens, then most observations.
        labs.sort_by(|a, b| b.2.cmp(&a.2).then(b.1.cmp(&a.1)));
        let (lab_name, _obs, citizens) = labs[0].clone();
        if rejected.contains(&(si_id, lab_name.clone())) {
            continue; // curator rejected this exact association
        }
        let agreement = if citizens_total > 0 { citizens as f64 / citizens_total as f64 } else { 0.0 };
        let conflict = agreement < cfg.agreement_ratio;
        if conflict {
            rep.conflicts += 1;
        }
        let score = confidence(cfg, obs_total, citizens_total, conf_level, recency);
        let ready = !conflict && obs_total >= cfg.ready_for_review && citizens_total >= cfg.min_distinct_citizens;

        if cfg.auto_accept
            && !conflict
            && obs_total >= cfg.auto_accept_threshold
            && citizens_total >= cfg.min_distinct_citizens
        {
            let lab_id = get_or_create_lab(&mut tx, &lab_name, false).await?;
            sqlx::query("UPDATE genomics.sequencer_instrument SET lab_id = $2 WHERE id = $1")
                .bind(si_id)
                .bind(lab_id)
                .execute(&mut *tx)
                .await?;
            sqlx::query(
                "INSERT INTO genomics.instrument_association_proposal \
                    (instrument_id, proposed_lab_name, observation_count, distinct_citizen_count, \
                     confidence_score, status, accepted_lab_id, accepted_instrument_id) \
                 VALUES ($1, $2, $3, $4, $5::float8::numeric, 'ACCEPTED', $6, $1)",
            )
            .bind(si_id)
            .bind(&lab_name)
            .bind(obs_total as i32)
            .bind(citizens_total as i32)
            .bind(score)
            .bind(lab_id)
            .execute(&mut *tx)
            .await?;
            rep.auto_accepted += 1;
            continue;
        }

        let status = if ready { "READY_FOR_REVIEW" } else { "PENDING" };
        sqlx::query(
            "INSERT INTO genomics.instrument_association_proposal \
                (instrument_id, proposed_lab_name, proposed_model, observation_count, \
                 distinct_citizen_count, confidence_score, status) \
             VALUES ($1, $2, (SELECT model_name FROM genomics.sequencer_instrument WHERE id = $1), \
                     $3, $4, $5::float8::numeric, $6) \
             ON CONFLICT (instrument_id) WHERE status IN ('PENDING','READY_FOR_REVIEW') \
             DO UPDATE SET proposed_lab_name = EXCLUDED.proposed_lab_name, \
                proposed_model = EXCLUDED.proposed_model, \
                observation_count = EXCLUDED.observation_count, \
                distinct_citizen_count = EXCLUDED.distinct_citizen_count, \
                confidence_score = EXCLUDED.confidence_score, status = EXCLUDED.status",
        )
        .bind(si_id)
        .bind(&lab_name)
        .bind(obs_total as i32)
        .bind(citizens_total as i32)
        .bind(score)
        .bind(status)
        .execute(&mut *tx)
        .await?;
        active_ids.push(si_id);
        rep.proposals_active += 1;
        if ready {
            rep.proposals_ready += 1;
        }
    }
    // Drop active proposals for instruments no longer proposing. `<> ALL('{}')`
    // is true for every row, so an empty active set clears them all.
    sqlx::query(
        "DELETE FROM genomics.instrument_association_proposal \
         WHERE status IN ('PENDING','READY_FOR_REVIEW') AND instrument_id <> ALL($1)",
    )
    .bind(&active_ids)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(rep)
}

/// A proposal for the curator queue.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct ProposalView {
    pub id: i64,
    pub instrument_id: String,
    pub proposed_lab_name: Option<String>,
    pub proposed_model: Option<String>,
    pub observation_count: i32,
    pub distinct_citizen_count: i32,
    pub confidence_score: Option<f64>,
    pub status: String,
}

/// A supporting observation (proposal detail).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct ObservationView {
    pub lab_name: Option<String>,
    pub biosample_ref: Option<String>,
    pub platform: Option<String>,
    pub instrument_model: Option<String>,
    pub repo_did: Option<String>,
    pub confidence: Option<String>,
}

/// Paginated proposal list, optionally filtered by status, ranked by confidence.
pub async fn list_proposals(
    pool: &PgPool,
    status: Option<&str>,
    page: i64,
    page_size: i64,
) -> Result<Page<ProposalView>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let items: Vec<ProposalView> = sqlx::query_as(
        "SELECT p.id, si.instrument_id, p.proposed_lab_name, p.proposed_model, \
                p.observation_count, p.distinct_citizen_count, p.confidence_score::float8 AS confidence_score, p.status \
         FROM genomics.instrument_association_proposal p \
         JOIN genomics.sequencer_instrument si ON si.id = p.instrument_id \
         WHERE ($1::text IS NULL OR p.status = $1) \
         ORDER BY p.confidence_score DESC NULLS LAST, p.id \
         LIMIT $2 OFFSET $3",
    )
    .bind(status)
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    let total: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM genomics.instrument_association_proposal WHERE ($1::text IS NULL OR status = $1)",
    )
    .bind(status)
    .fetch_one(pool)
    .await?;
    Ok(Page { items, total, page: page.max(1), page_size: limit })
}

/// A proposal with its supporting observations.
pub async fn proposal_detail(pool: &PgPool, id: i64) -> Result<Option<(ProposalView, Vec<ObservationView>)>, DbError> {
    let Some(p): Option<ProposalView> = sqlx::query_as(
        "SELECT p.id, si.instrument_id, p.proposed_lab_name, p.proposed_model, \
                p.observation_count, p.distinct_citizen_count, p.confidence_score::float8 AS confidence_score, p.status \
         FROM genomics.instrument_association_proposal p \
         JOIN genomics.sequencer_instrument si ON si.id = p.instrument_id \
         WHERE p.id = $1",
    )
    .bind(id)
    .fetch_optional(pool)
    .await?
    else {
        return Ok(None);
    };
    let obs: Vec<ObservationView> = sqlx::query_as(
        "SELECT o.lab_name, o.biosample_ref, o.platform, o.instrument_model, \
                o.atproto->>'repo_did' AS repo_did, o.confidence \
         FROM genomics.instrument_observation o \
         WHERE o.instrument_id = (SELECT instrument_id FROM genomics.instrument_association_proposal WHERE id = $1) \
         ORDER BY o.lab_name, o.id",
    )
    .bind(id)
    .fetch_all(pool)
    .await?;
    Ok(Some((p, obs)))
}

/// Accept a proposal: get-or-create the lab, set the instrument's `lab_id` (which
/// is what `lookup_lab` resolves), and mark the proposal `ACCEPTED`. The lab name
/// / model may be curator-overridden. Returns the resolved association.
pub async fn accept_proposal(
    pool: &PgPool,
    proposal_id: i64,
    user_id: Uuid,
    lab_name: &str,
    manufacturer: Option<&str>,
    model: Option<&str>,
    is_d2c: Option<bool>,
) -> Result<LabLookup, DbError> {
    let mut tx = pool.begin().await?;
    let si_id: i64 = sqlx::query_scalar(
        "SELECT instrument_id FROM genomics.instrument_association_proposal WHERE id = $1 FOR UPDATE",
    )
    .bind(proposal_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| DbError::Conflict(format!("proposal {proposal_id} not found")))?;

    let lab_id = get_or_create_lab(&mut tx, lab_name, is_d2c.unwrap_or(false)).await?;
    // Only touch an existing lab's d2c flag when the curator explicitly set it — an
    // omitted flag must not silently clear a preseeded lab's is_d2c (which would
    // mislabel every other instrument tied to that lab).
    if let Some(d2c) = is_d2c {
        sqlx::query("UPDATE genomics.sequencing_lab SET is_d2c = $2 WHERE id = $1")
            .bind(lab_id)
            .bind(d2c)
            .execute(&mut *tx)
            .await?;
    }
    sqlx::query(
        "UPDATE genomics.sequencer_instrument \
         SET lab_id = $2, manufacturer = COALESCE($3, manufacturer), model_name = COALESCE($4, model_name) \
         WHERE id = $1",
    )
    .bind(si_id)
    .bind(lab_id)
    .bind(manufacturer)
    .bind(model)
    .execute(&mut *tx)
    .await?;
    sqlx::query(
        "UPDATE genomics.instrument_association_proposal \
         SET status = 'ACCEPTED', proposed_lab_name = $2, accepted_lab_id = $3, accepted_instrument_id = $1 \
         WHERE id = $4",
    )
    .bind(si_id)
    .bind(lab_name)
    .bind(lab_id)
    .bind(proposal_id)
    .execute(&mut *tx)
    .await?;
    let hit: LabLookup = sqlx::query_as(
        "SELECT si.instrument_id, sl.name AS lab_name, sl.is_d2c, sl.website_url, si.manufacturer, si.model_name \
         FROM genomics.sequencer_instrument si JOIN genomics.sequencing_lab sl ON sl.id = si.lab_id \
         WHERE si.id = $1",
    )
    .bind(si_id)
    .fetch_one(&mut *tx)
    .await?;
    // Audit in the same transaction — the decision and its trail commit together.
    let new = json!({ "instrument_id": hit.instrument_id, "lab_name": hit.lab_name, "is_d2c": hit.is_d2c });
    crate::audit::log(&mut *tx, user_id, "instrument_proposal", proposal_id, "ACCEPT", None, Some(&new), None).await?;
    tx.commit().await?;
    Ok(hit)
}

/// Reject a proposal (the dominant lab won't be re-proposed for this instrument).
/// Returns the (instrument_id, proposed_lab_name) for the audit comment, or `None`
/// if the proposal isn't in a reviewable state.
pub async fn reject_proposal(
    pool: &PgPool,
    proposal_id: i64,
    user_id: Uuid,
    reason: Option<&str>,
) -> Result<Option<(String, Option<String>)>, DbError> {
    let mut tx = pool.begin().await?;
    let row: Option<(String, Option<String>)> = sqlx::query_as(
        "UPDATE genomics.instrument_association_proposal p \
         SET status = 'REJECTED' \
         FROM genomics.sequencer_instrument si \
         WHERE p.id = $1 AND si.id = p.instrument_id AND p.status IN ('PENDING','READY_FOR_REVIEW') \
         RETURNING si.instrument_id, p.proposed_lab_name",
    )
    .bind(proposal_id)
    .fetch_optional(&mut *tx)
    .await?;
    if let Some((ref instrument, ref lab)) = row {
        // Audit in the same transaction as the status change.
        let new = json!({ "instrument_id": instrument, "rejected_lab": lab });
        crate::audit::log(&mut *tx, user_id, "instrument_proposal", proposal_id, "REJECT", None, Some(&new), reason).await?;
    }
    tx.commit().await?;
    Ok(row)
}
