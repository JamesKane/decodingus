//! Signed Edge API for the DecodingUs Grid (`/api/v1/grid/*`) — design §4.4.
//!
//! Volunteer Navigator instances register, lease public-ENA work units, report progress, and submit
//! signed result digests. Authentication is the same Ed25519 device-key path the D1 exchange and the
//! recruitment Edge already use: `verify_signed_fresh` for anything that mutates, `verify_signed`
//! plus `ensure_fresh_ts` for reads. The canonical signed strings live in `du_db::grid::messages`
//! and are a cross-repo contract with Navigator.
//!
//! PII-free throughout: DIDs, signatures, ENA accessions and computed calls. Nothing here touches a
//! donor.
//!
//! # Two deviations from §4.4, both to avoid a second way to do one thing
//!
//! **No `/grid/node/heartbeat`.** The table §4.4 gives it lists `POST /grid/node/register` as an
//! upsert of `fed.pds_node` and `/grid/node/heartbeat` as node liveness — but `register_node`
//! already sets `last_heartbeat` and is idempotent, so re-registering *is* the node heartbeat. Two
//! endpoints writing the same row is a drift waiting to happen.
//!
//! **`/grid/heartbeat` does not extend the lease.** §4.4 offers an "optional TTL extension"; a node
//! that can heartbeat but never finish would then hold a unit forever, which is the exact failure a
//! bounded lease exists to prevent. See design §4.3 and §12.5.

use crate::error::AppError;
use crate::sig::{ensure_fresh_ts, verify_signed, verify_signed_fresh};
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use du_db::grid::{self, digest, messages};
use serde::Deserialize;
use serde_json::{json, Value};

/// Upper bound on units handed out in one call, whatever the node asks for. A node that wants more
/// comes back; a node that asks for ten thousand does not get to empty the catalogue.
const MAX_CLAIM: i64 = 32;
/// Lease bounds in seconds — 1 hour to 14 days (§4.3, "AppView clamps").
const MIN_LEASE_SECS: i64 = 3_600;
const MAX_LEASE_SECS: i64 = 14 * 24 * 3_600;

const DEFAULT_LEADERBOARD_LIMIT: i64 = 100;
const MAX_LEADERBOARD_LIMIT: i64 = 500;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/v1/grid/node/register", post(node_register))
        .route("/api/v1/grid/claim", post(claim))
        .route("/api/v1/grid/heartbeat", post(heartbeat))
        .route("/api/v1/grid/release", post(release))
        .route("/api/v1/grid/submit", post(submit))
        .route("/api/v1/grid/mine", get(mine))
        .route("/api/v1/grid/leaderboard", get(leaderboard))
        .route("/api/v1/grid/work/:accession", get(work_unit))
        .route("/api/v1/grid/stats", get(stats))
}

// ── signed: node lifecycle ────────────────────────────────────────────────────

#[derive(Deserialize)]
struct RegisterBody {
    did: String,
    software_version: String,
    /// Free-form: data kinds, threads, disk budget, OS/arch.
    capabilities: Value,
    os_info: Option<String>,
    ts: i64,
    signature: String,
}

/// Register a node, or refresh what it advertises. Doubles as the node-level heartbeat.
///
/// The capabilities hash is inside the signed message, so a node cannot have capabilities
/// attributed to it that it did not send — which matters because `claim` filters on them, and a
/// forged claim of FASTQ capability would hand a node work it cannot do.
async fn node_register(
    State(st): State<AppState>,
    Json(b): Json<RegisterBody>,
) -> Result<Json<Value>, AppError> {
    let caps_hash = digest::canonical_sha256_b64(&b.capabilities);
    verify_signed_fresh(
        &st.pool,
        &b.did,
        b.ts,
        &messages::register(&b.did, &b.software_version, &caps_hash),
        &b.signature,
    )
    .await?;
    let id = grid::register_node(
        &st.pool,
        &b.did,
        &b.software_version,
        &b.capabilities,
        b.os_info.as_deref(),
    )
    .await?;
    Ok(Json(json!({ "node_id": id })))
}

// ── signed: the work loop ─────────────────────────────────────────────────────

#[derive(Deserialize)]
struct ClaimBody {
    did: String,
    /// Data kinds this node can actually process, e.g. `["CRAM","FASTQ"]`.
    data_kinds: Vec<String>,
    count: i64,
    lease_secs: i64,
    ts: i64,
    signature: String,
}

/// Lease up to `count` units the node can handle.
///
/// `data_kinds` is normalised — uppercased, deduplicated, sorted — *before* the signature is
/// checked, and the signed message covers the normalised form. Otherwise `["CRAM","cram"]` and
/// `["cram","CRAM"]` would be different signed strings for the same request, and a node whose
/// ordering differed from ours would get an unexplained 403.
async fn claim(
    State(st): State<AppState>,
    Json(b): Json<ClaimBody>,
) -> Result<Json<Value>, AppError> {
    let mut kinds: Vec<String> = b
        .data_kinds
        .iter()
        .map(|k| k.trim().to_ascii_uppercase())
        .collect();
    kinds.sort();
    kinds.dedup();
    if kinds.is_empty() {
        return Err(AppError::BadRequest("no data kinds advertised".into()));
    }
    let count = b.count.clamp(1, MAX_CLAIM);
    let lease = b.lease_secs.clamp(MIN_LEASE_SECS, MAX_LEASE_SECS);

    // Sign what the node asked for, not what we clamped it to: the node cannot know our bounds, and
    // making it guess them to produce a valid signature would be an unusable API.
    verify_signed_fresh(
        &st.pool,
        &b.did,
        b.ts,
        &messages::claim(&b.did, &kinds.join(","), b.count as i32, b.lease_secs),
        &b.signature,
    )
    .await?;

    let node_id = grid::node_id_for_did(&st.pool, &b.did).await?;
    let units = grid::claim(&st.pool, &b.did, node_id, &kinds, count, lease).await?;
    Ok(Json(json!({ "units": units })))
}

#[derive(Deserialize)]
struct HeartbeatBody {
    did: String,
    lease_id: i64,
    stage: String,
    progress: Option<Value>,
    ts: i64,
    signature: String,
}

/// Report progress on a held lease. Does **not** extend it (see the module note).
///
/// `held: false` tells a node it no longer owns the lease — expired, released, or never its own —
/// so it can stop work rather than spend hours finishing a unit it will not be credited for.
async fn heartbeat(
    State(st): State<AppState>,
    Json(b): Json<HeartbeatBody>,
) -> Result<Json<Value>, AppError> {
    verify_signed_fresh(
        &st.pool,
        &b.did,
        b.ts,
        &messages::heartbeat(&b.did, b.lease_id, &b.stage),
        &b.signature,
    )
    .await?;
    let held = grid::heartbeat(&st.pool, &b.did, b.lease_id, b.progress.as_ref()).await?;
    Ok(Json(json!({ "held": held })))
}

#[derive(Deserialize)]
struct ReleaseBody {
    did: String,
    lease_id: i64,
    reason: String,
    ts: i64,
    signature: String,
}

/// Give a lease back without a result, so the unit recycles immediately instead of waiting out its
/// bound. Idempotent: releasing an already-closed lease reports `released: false` and is not an
/// error, because a node retrying after a dropped response has done nothing wrong.
async fn release(
    State(st): State<AppState>,
    Json(b): Json<ReleaseBody>,
) -> Result<Json<Value>, AppError> {
    verify_signed_fresh(
        &st.pool,
        &b.did,
        b.ts,
        &messages::release(&b.did, b.lease_id, &b.reason),
        &b.signature,
    )
    .await?;
    let released = grid::release(&st.pool, &b.did, b.lease_id, "RELEASED").await?;
    Ok(Json(json!({ "released": released })))
}

#[derive(Deserialize)]
struct SubmitBody {
    did: String,
    work_unit_id: i64,
    lease_id: Option<i64>,
    /// The result digest (§5.2), carrying **raw** values — the AppView buckets at comparison time.
    digest: Value,
    /// The node's own Ed25519 signature over its digest, stored for later audit.
    digest_sig: String,
    stack_version: String,
    reference_build: String,
    aligner: Option<String>,
    /// `at://` URIs of the fed records the node published.
    record_refs: Value,
    ts: i64,
    signature: String,
}

/// Record a signed result and close the lease that produced it.
///
/// The request signature covers the **hash of the digest**, and that hash is recomputed here from
/// what actually arrived. Without that check a node could sign the hash of a good result and post a
/// different one, and the stored `digest_sig` would still look valid to a later auditor.
async fn submit(
    State(st): State<AppState>,
    Json(b): Json<SubmitBody>,
) -> Result<Json<Value>, AppError> {
    let hash = digest::canonical_sha256_b64(&b.digest);
    verify_signed_fresh(
        &st.pool,
        &b.did,
        b.ts,
        &messages::submit(&b.did, b.work_unit_id, &hash),
        &b.signature,
    )
    .await?;

    let id = grid::submit(
        &st.pool,
        &b.did,
        b.work_unit_id,
        b.lease_id,
        &b.digest,
        &b.digest_sig,
        &b.stack_version,
        &b.reference_build,
        b.aligner.as_deref(),
        &b.record_refs,
    )
    .await?;
    Ok(Json(json!({ "submission_id": id })))
}

#[derive(Deserialize)]
struct PollQuery {
    did: String,
    ts: i64,
    sig: String,
}

/// The caller's own grid standing: leases held right now, agreed/divergent history, cobblestones
/// and board rank. This is what §7.1's Grid panel renders.
///
/// The one **signed read** in this API — everything else either mutates or is public. It is
/// authenticated because it is the caller's own work: the leaderboard publishes totals, and this
/// publishes the rows behind one contributor's total, which is theirs to see and nobody else's.
async fn mine(
    State(st): State<AppState>,
    Query(q): Query<PollQuery>,
) -> Result<Json<Value>, AppError> {
    ensure_fresh_ts(q.ts)?;
    verify_signed(&st.pool, &q.did, &messages::poll(&q.did, q.ts), &q.sig).await?;
    Ok(Json(grid::standing(&st.pool, &q.did).await?))
}

// ── public ────────────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct LeaderboardQuery {
    /// `30` for the rolling window; omitted for all-time.
    days: Option<i32>,
    limit: Option<i64>,
}

/// Ranked contributors (§6.4). Public and unauthenticated — a leaderboard nobody can read is not a
/// leaderboard.
///
/// Cobblestones are rendered from the ledger's integer thousandths here, at the last possible
/// moment, so the sum stays exact all the way up (§12.2).
async fn leaderboard(
    State(st): State<AppState>,
    Query(q): Query<LeaderboardQuery>,
) -> Result<Json<Value>, AppError> {
    let limit = q
        .limit
        .unwrap_or(DEFAULT_LEADERBOARD_LIMIT)
        .clamp(1, MAX_LEADERBOARD_LIMIT);
    let rows = grid::leaderboard(&st.pool, q.days, limit).await?;
    let items: Vec<Value> = rows
        .into_iter()
        .map(|r| {
            json!({
                "handle": r.handle,
                "did": r.did,
                "cobblestones": r.cobblestones_milli as f64 / grid::COBBLESTONE as f64,
                "units": r.units,
            })
        })
        .collect();
    Ok(Json(json!({ "items": items })))
}

/// The canonical community result for one ENA sample, once a quorum has agreed on it.
///
/// A unit that exists but has not reached quorum returns its state and no digest, rather than a
/// 404: "we are working on it" and "we have never heard of it" are different answers, and a
/// consumer needs to tell them apart.
async fn work_unit(
    State(st): State<AppState>,
    Path(accession): Path<String>,
) -> Result<Json<Value>, AppError> {
    let row = grid::work_unit_public(&st.pool, accession.trim())
        .await?
        .ok_or_else(|| AppError::NotFound(format!("no grid work unit for {accession}")))?;
    Ok(Json(json!({
        "sample_accession": row.sample_accession,
        "study_accession": row.study_accession,
        "data_kind": row.data_kind,
        "state": row.state,
        "canonical_digest": row.canonical_digest,
        "canonical_at": row.canonical_at,
        "replicas_agreed": row.replicas_agreed,
    })))
}

/// Grid throughput: units by state, active leases, contributing nodes.
async fn stats(State(st): State<AppState>) -> Result<Json<Value>, AppError> {
    Ok(Json(grid::stats(&st.pool).await?))
}

#[cfg(test)]
mod tests {
    /// The claim endpoint normalises `data_kinds` before signing, so that the same request written
    /// two ways produces the same signed bytes. This mirrors the handler's normalisation; if one
    /// changes without the other, honest nodes start getting 403s.
    #[test]
    fn data_kinds_normalise_to_one_signed_form() {
        let norm = |v: &[&str]| {
            let mut k: Vec<String> = v.iter().map(|s| s.trim().to_ascii_uppercase()).collect();
            k.sort();
            k.dedup();
            k.join(",")
        };
        assert_eq!(norm(&["FASTQ", "CRAM"]), "CRAM,FASTQ");
        assert_eq!(norm(&["cram", " CRAM ", "FASTQ"]), "CRAM,FASTQ");
        assert_eq!(norm(&["CRAM"]), "CRAM");
    }
}
