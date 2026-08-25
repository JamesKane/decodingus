//! DecodingUs Grid — work-unit coordination (AppView side).
//!
//! The AppView publishes public-ENA work units; volunteer Navigator instances lease one, fetch
//! it, (re)align to CHM13, run the analysis stack, and submit a signed digest. This module is the
//! storage and the state transitions. Signature verification lives in the `du-web` handler, which
//! is where async DID resolution belongs — the same split as [`crate::exchange`].
//!
//! Design: `documents/design/distributed-compute-grid.md` in the DUNavigator repo.
//!
//! **Claimability is derived, not stored.** `work_unit.state` holds only the exclusive lifecycle
//! milestones (AVAILABLE / CANONICAL / CONTESTED / RETIRED). Whether a unit can be claimed *now*
//! is a function of how many replicas are in flight against `required_replicas`, which the lease
//! and submission tables already answer. Nothing counts replicas into a column, so no count can
//! drift out of step with the rows it summarises. See `0075_grid.sql` for the full reasoning.
//!
//! **Canonical signed messages** ([`messages`]) are a cross-repo contract: the Navigator edge
//! signs byte-identical strings. Keep them stable.

pub mod digest;

use crate::DbError;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sqlx::PgPool;
use uuid::Uuid;

/// The exact bytes each grid request's Ed25519 signature covers. A cross-repo contract with the
/// Navigator edge — do not reorder or reformat.
///
/// Mutating calls go through `du_web::sig::verify_signed_fresh`, which frames these as
/// `{ts}\n{base}` before verifying, so the timestamp binds to the operation in one signature.
/// Read polls carry their own `ts` and go through `verify_signed`.
pub mod messages {
    /// Announce a node and its capabilities. `caps_sha256_b64` is over the canonical JSON of the
    /// capabilities object, so a node cannot claim RAM it did not advertise at registration.
    pub fn register(did: &str, software_version: &str, caps_sha256_b64: &str) -> String {
        format!("grid-register\n{did}\n{software_version}\n{caps_sha256_b64}")
    }
    /// Replay-guarded poll for the catalogue view (what is claimable, and the caller's standing).
    pub fn poll(did: &str, ts: i64) -> String {
        format!("grid-poll\n{did}\n{ts}")
    }
    /// Reserve up to `count` units of the given kinds for `lease_secs`. `kinds` is the
    /// comma-joined, ascending-sorted list the caller sent, so the server cannot widen it.
    pub fn claim(did: &str, kinds: &str, count: i32, lease_secs: i64) -> String {
        format!("grid-claim\n{did}\n{kinds}\n{count}\n{lease_secs}")
    }
    /// Liveness for one held lease, with the stage the node is on.
    pub fn heartbeat(did: &str, lease_id: i64, stage: &str) -> String {
        format!("grid-heartbeat\n{did}\n{lease_id}\n{stage}")
    }
    /// Give a lease back without a result. `reason` is free text and is signed, so a node cannot
    /// have a release attributed to it that it did not send.
    pub fn release(did: &str, lease_id: i64, reason: &str) -> String {
        format!("grid-release\n{did}\n{lease_id}\n{reason}")
    }
    /// Submit a result. The signature covers the digest **bytes** the node computed, not a
    /// re-serialisation of them: `digest_sha256_b64` is the SHA-256 of the exact canonical JSON
    /// the node signed and sent, so no JSON round-trip on either side can change what was signed.
    pub fn submit(did: &str, work_unit_id: i64, digest_sha256_b64: &str) -> String {
        format!("grid-submit\n{did}\n{work_unit_id}\n{digest_sha256_b64}")
    }
}

/// A unit as a node receives it on claim — everything needed to do the work without asking the
/// AppView (or ENA) anything else.
#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct ClaimedUnit {
    pub lease_id: i64,
    pub work_unit_id: i64,
    pub sample_accession: String,
    pub study_accession: Option<String>,
    pub data_kind: String,
    /// `[{run_accession, url, md5, bytes, format}]`.
    pub manifest: Value,
    pub est_bases: Option<i64>,
    pub total_bytes: Option<i64>,
    pub expires_at: chrono::DateTime<chrono::Utc>,
}

/// One row of the public leaderboard.
///
/// `cobblestones_milli` is thousandths, matching the ledger column — see `0075_grid.sql`. Divide
/// by 1000 at the point of rendering, never before, so the sum stays exact.
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct LeaderboardRow {
    pub handle: Option<String>,
    pub did: String,
    pub cobblestones_milli: i64,
    pub units: i64,
}

/// One cobblestone, in the ledger's units.
pub const COBBLESTONE: i64 = 1_000;

/// A work unit as the curation job upserts it.
#[derive(Debug, Clone)]
pub struct NewWorkUnit {
    pub sample_accession: String,
    pub study_accession: Option<String>,
    pub data_kind: String,
    pub manifest: Value,
    pub est_bases: Option<i64>,
    pub total_bytes: Option<i64>,
}

/// Publish (or refresh) a work unit. Idempotent at `sample_accession`, so a re-crawl of the same
/// study is safe and cheap.
///
/// A refresh updates the manifest and the size estimates — ENA does re-publish files — but never
/// touches `state`, `required_replicas` or the canonical digest. Curation describes the *input*;
/// validation owns the *lifecycle*, and a re-crawl must not silently un-canonicalise a unit or
/// reset a contested one back to claimable.
pub async fn upsert_work_unit(pool: &PgPool, u: &NewWorkUnit) -> Result<i64, DbError> {
    let id: (i64,) = sqlx::query_as(
        "INSERT INTO grid.work_unit (sample_accession, study_accession, data_kind, manifest, est_bases, total_bytes) \
         VALUES ($1, $2, $3, $4, $5, $6) \
         ON CONFLICT (sample_accession) DO UPDATE SET \
             study_accession = EXCLUDED.study_accession, \
             data_kind       = EXCLUDED.data_kind, \
             manifest        = EXCLUDED.manifest, \
             est_bases       = EXCLUDED.est_bases, \
             total_bytes     = EXCLUDED.total_bytes, \
             updated_at      = now() \
         RETURNING id",
    )
    .bind(&u.sample_accession)
    .bind(&u.study_accession)
    .bind(&u.data_kind)
    .bind(&u.manifest)
    .bind(u.est_bases)
    .bind(u.total_bytes)
    .fetch_one(pool)
    .await?;
    Ok(id.0)
}

/// Reserve up to `count` units for `did`, for `lease_secs`.
///
/// This is the one piece of genuinely new concurrency in the Grid, and it is one statement:
///
/// * `FOR UPDATE SKIP LOCKED` — two nodes claiming at the same instant take *different* units
///   instead of one of them blocking or both taking the same one.
/// * The replica arithmetic — active leases plus submissions that have not been ruled divergent —
///   is what stops a unit being handed out beyond `required_replicas`.
/// * The two `NOT EXISTS` clauses stop a node replicating *itself*: a contributor that already
///   holds a lease on a unit, or already submitted for it, is not offered it again. Quorum means
///   independent results, so self-replication is not a rate limit but a correctness rule.
/// * `ON CONFLICT … DO NOTHING` on the partial unique index makes a retried claim idempotent
///   rather than an error, which matters because the node retries on any transport failure.
///
/// Returns the units actually reserved, which may be fewer than `count` (or none) when the
/// catalogue is exhausted for those kinds. `count` is an `i64` because Postgres `LIMIT` takes a
/// bigint; binding a narrower integer there is a type error, not a widening.
pub async fn claim(
    pool: &PgPool,
    did: &str,
    node_id: Option<i64>,
    data_kinds: &[String],
    count: i64,
    lease_secs: i64,
) -> Result<Vec<ClaimedUnit>, DbError> {
    let rows = sqlx::query_as::<_, ClaimedUnit>(
        "WITH candidate AS ( \
             SELECT w.id FROM grid.work_unit w \
              WHERE w.state IN ('AVAILABLE', 'CONTESTED') \
                AND w.data_kind = ANY($3) \
                AND ( (SELECT count(*) FROM grid.lease l \
                        WHERE l.work_unit_id = w.id AND l.released_at IS NULL AND l.expires_at > now()) \
                    + (SELECT count(*) FROM grid.submission s \
                        WHERE s.work_unit_id = w.id AND s.status <> 'DIVERGENT') \
                    ) < w.required_replicas \
                AND NOT EXISTS (SELECT 1 FROM grid.lease l2 \
                                 WHERE l2.work_unit_id = w.id AND l2.did = $1 AND l2.released_at IS NULL) \
                AND NOT EXISTS (SELECT 1 FROM grid.submission s2 \
                                 WHERE s2.work_unit_id = w.id AND s2.did = $1) \
              ORDER BY w.id \
              LIMIT $4 \
              FOR UPDATE SKIP LOCKED \
         ), ins AS ( \
             INSERT INTO grid.lease (work_unit_id, did, node_id, expires_at) \
             SELECT c.id, $1, $2, now() + make_interval(secs => $5) FROM candidate c \
             ON CONFLICT (work_unit_id, did) WHERE released_at IS NULL DO NOTHING \
             RETURNING id, work_unit_id, expires_at \
         ) \
         SELECT i.id AS lease_id, i.work_unit_id, w.sample_accession, w.study_accession, \
                w.data_kind, w.manifest, w.est_bases, w.total_bytes, i.expires_at \
           FROM ins i JOIN grid.work_unit w ON w.id = i.work_unit_id \
          ORDER BY i.work_unit_id",
    )
    .bind(did)
    .bind(node_id)
    .bind(data_kinds)
    .bind(count)
    // `make_interval(secs => …)` takes an int4. Clamping rather than erroring is right: a lease
    // longer than 68 years is a caller bug, and the server's own bound is what matters anyway.
    .bind(i32::try_from(lease_secs).unwrap_or(i32::MAX))
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

/// Record liveness on a held lease. Returns false when the lease is not the caller's, or is
/// already released — the node then knows to stop working rather than finish a unit it lost.
///
/// A heartbeat deliberately does **not** extend `expires_at`. A node that can heartbeat but never
/// finish would otherwise hold a unit forever; the lease is a bounded promise, and a node that
/// needs longer re-claims.
pub async fn heartbeat(
    pool: &PgPool,
    did: &str,
    lease_id: i64,
    progress: Option<&Value>,
) -> Result<bool, DbError> {
    let r = sqlx::query(
        "UPDATE grid.lease SET heartbeat_at = now(), progress = COALESCE($3, progress) \
          WHERE id = $1 AND did = $2 AND released_at IS NULL",
    )
    .bind(lease_id)
    .bind(did)
    .bind(progress)
    .execute(pool)
    .await?;
    Ok(r.rows_affected() > 0)
}

/// Give a lease back without a result. Idempotent: releasing an already-released lease is a
/// no-op that reports false.
pub async fn release(
    pool: &PgPool,
    did: &str,
    lease_id: i64,
    outcome: &str,
) -> Result<bool, DbError> {
    let r = sqlx::query(
        "UPDATE grid.lease SET released_at = now(), outcome = $3 \
          WHERE id = $1 AND did = $2 AND released_at IS NULL",
    )
    .bind(lease_id)
    .bind(did)
    .bind(outcome)
    .execute(pool)
    .await?;
    Ok(r.rows_affected() > 0)
}

/// Close every lease whose bound has passed. Run by the `grid-reap` job.
///
/// **This is not what frees the replica slot** — [`claim`] already ignores any lease past
/// `expires_at`, so a unit held by a node that crashed becomes claimable by *another* contributor
/// the moment the lease lapses, with no job run in between. That is what makes a lease honest: a
/// vanished node costs the catalogue one lease duration and nothing more, even if the reaper is
/// down.
///
/// What the reaper actually does is the other two things, both of which need a row write:
///
/// 1. **Records the outcome** (`EXPIRED`), so a node's history distinguishes "timed out" from
///    "gave it back" — which trust tiering (§6.1) needs and a derived query cannot recover.
/// 2. **Lets the *same* node claim the unit again.** The self-replication guard in [`claim`] keys
///    on `released_at IS NULL` with no expiry test, deliberately: relaxing it would let a node
///    re-claim a unit it already holds a row for, and the partial unique index would then make
///    `ON CONFLICT DO NOTHING` swallow the insert and hand back an empty result with no
///    explanation. So a node that overran its lease waits for the reaper before it can retry —
///    which is the honest ordering, since its first attempt is genuinely over.
///
/// Returns how many leases were closed.
pub async fn reap_expired(pool: &PgPool) -> Result<u64, DbError> {
    let r = sqlx::query(
        "UPDATE grid.lease SET released_at = now(), outcome = 'EXPIRED' \
          WHERE released_at IS NULL AND expires_at <= now()",
    )
    .execute(pool)
    .await?;
    Ok(r.rows_affected())
}

/// Record a signed result and close the lease that produced it, in one transaction — the node
/// must never be able to lose its lease without its submission landing, or vice versa.
///
/// Re-submitting for the same unit **updates** the row rather than adding a second vote (the
/// `(work_unit_id, did)` unique index), so a retry after a dropped response is safe and a
/// contributor still cannot pad its own quorum.
#[allow(clippy::too_many_arguments)]
pub async fn submit(
    pool: &PgPool,
    did: &str,
    work_unit_id: i64,
    lease_id: Option<i64>,
    digest: &Value,
    digest_sig: &str,
    stack_version: &str,
    reference_build: &str,
    aligner: Option<&str>,
    record_refs: &Value,
) -> Result<i64, DbError> {
    let mut tx = pool.begin().await?;
    let id: (i64,) = sqlx::query_as(
        "INSERT INTO grid.submission \
             (work_unit_id, did, lease_id, digest, digest_sig, stack_version, reference_build, aligner, record_refs) \
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) \
         ON CONFLICT (work_unit_id, did) DO UPDATE SET \
             lease_id        = EXCLUDED.lease_id, \
             digest          = EXCLUDED.digest, \
             digest_sig      = EXCLUDED.digest_sig, \
             stack_version   = EXCLUDED.stack_version, \
             reference_build = EXCLUDED.reference_build, \
             aligner         = EXCLUDED.aligner, \
             record_refs     = EXCLUDED.record_refs, \
             status          = 'PENDING', \
             submitted_at    = now(), \
             validated_at    = NULL \
         RETURNING id",
    )
    .bind(work_unit_id)
    .bind(did)
    .bind(lease_id)
    .bind(digest)
    .bind(digest_sig)
    .bind(stack_version)
    .bind(reference_build)
    .bind(aligner)
    .bind(record_refs)
    .fetch_one(&mut *tx)
    .await?;

    if let Some(lease) = lease_id {
        sqlx::query(
            "UPDATE grid.lease SET released_at = now(), outcome = 'SUBMITTED' \
              WHERE id = $1 AND did = $2 AND released_at IS NULL",
        )
        .bind(lease)
        .bind(did)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    Ok(id.0)
}

/// Award credit for an agreed submission. Unique per `(work_unit_id, did)`, so a re-validation
/// cannot pay the same contributor twice for the same unit; the second award is dropped.
///
/// `user_id` is resolved here rather than at claim time because a contributor may have linked an
/// account between claiming and being validated.
pub async fn award_credit(
    pool: &PgPool,
    did: &str,
    work_unit_id: i64,
    submission_id: i64,
    cobblestones_milli: i64,
    kind: &str,
) -> Result<bool, DbError> {
    let r = sqlx::query(
        "INSERT INTO grid.credit (did, user_id, work_unit_id, submission_id, cobblestones_milli, kind) \
         SELECT $1, (SELECT id FROM ident.users WHERE did = $1), $2, $3, $4, $5 \
         ON CONFLICT (work_unit_id, did) DO NOTHING",
    )
    .bind(did)
    .bind(work_unit_id)
    .bind(submission_id)
    .bind(cobblestones_milli)
    .bind(kind)
    .execute(pool)
    .await?;
    Ok(r.rows_affected() > 0)
}

/// The public leaderboard: total cobblestones per contributor, best first.
///
/// `since_days = None` is all-time; `Some(30)` is the rolling window. Contributors with no linked
/// account still appear, by DID and without a handle — the work was done and the board should say
/// so, even though the account link is what `ident.users` would give it a name from.
pub async fn leaderboard(
    pool: &PgPool,
    since_days: Option<i32>,
    limit: i64,
) -> Result<Vec<LeaderboardRow>, DbError> {
    let rows = sqlx::query_as::<_, LeaderboardRow>(
        "SELECT u.handle, c.did, SUM(c.cobblestones_milli)::bigint AS cobblestones_milli, count(*) AS units \
           FROM grid.credit c \
           LEFT JOIN ident.users u ON u.id = c.user_id \
          WHERE $1::int IS NULL OR c.awarded_at >= now() - ($1::int * interval '1 day') \
          GROUP BY u.handle, c.did \
          ORDER BY cobblestones_milli DESC \
          LIMIT $2",
    )
    .bind(since_days)
    .bind(limit)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

/// A sample the crawl has already resolved, shaped as a work unit.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct CurationCandidate {
    pub sample_accession: String,
    pub study_accession: Option<String>,
    pub data_kind: String,
    pub manifest: Value,
    pub est_bases: Option<i64>,
    pub total_bytes: Option<i64>,
}

/// Samples that could become work units, projected out of what `crawl_project` already stored.
///
/// The Grid does **not** talk to ENA to build its catalogue. `EnaClient::run_files` and
/// `du_jobs::crawl_project` already resolve every run of a study, group the runs by sample, and
/// materialise the files into `genomics.sequence_file` with their URLs, md5s and sizes. Curation is
/// therefore a projection of tables we already have, not a second ENA integration — which also
/// keeps the fleet off the ENA portal (design §6.2, fair use).
///
/// **`data_kind` is decided per sample, and the manifest is filtered to match it.** A sample with
/// any CRAM/BAM is a passthrough unit and its manifest carries only the aligned files; otherwise it
/// is a FASTQ unit carrying only reads. `crawl_project::build_libraries` already prefers aligned
/// over FASTQ per sample, so in practice the two agree — but deciding it here as well means the
/// manifest can never carry a file the data kind says the node will not use.
///
/// With `only_new`, samples that already have a work unit are skipped. That is the nightly path.
/// Passing `false` re-projects everything, which refreshes manifests after a re-crawl.
///
/// **`est_bases` prefers the measured `base_count`** that ENA publishes on `read_run`, and falls
/// back to `reads × read_length` where a row predates that column. The fallback is only ever a
/// mean-length approximation and is wrong outright for variable-length long reads, so it is a
/// fallback and not the primary.
///
/// It can still be `NULL`, for a crawled row that has neither — `du-jobs run-once ena-base-count`
/// backfills those. A NULL is deliberate: this figure sets what a contributor is *paid* (§6.3), and
/// a fabricated estimate in a ledger is worse than an honest absence. `grid-curate` warns with a
/// count of the units it published without one.
pub async fn curation_candidates(
    pool: &PgPool,
    only_new: bool,
    limit: i64,
) -> Result<Vec<CurationCandidate>, DbError> {
    let rows = sqlx::query_as::<_, CurationCandidate>(
        "WITH sample AS ( \
             SELECT b.sample_guid, b.accession, \
                    CASE WHEN bool_or(sf.file_format IN ('CRAM', 'BAM')) THEN 'CRAM' ELSE 'FASTQ' END AS data_kind \
               FROM core.biosample b \
               JOIN genomics.sequence_library sl ON sl.sample_guid = b.sample_guid \
               JOIN genomics.sequence_file sf ON sf.library_id = sl.id \
              WHERE b.deleted = false \
                AND b.accession IS NOT NULL \
                AND sl.atproto->>'source' = 'ENA' \
                AND sf.file_format IN ('CRAM', 'BAM', 'FASTQ') \
                AND sf.http_locations->0->>'file_url' IS NOT NULL \
              GROUP BY b.sample_guid, b.accession \
         ) \
         SELECT s.accession AS sample_accession, \
                ( SELECT gs.accession FROM pubs.publication_biosample pb \
                    JOIN pubs.publication_study ps ON ps.publication_id = pb.publication_id \
                    JOIN pubs.genomic_study gs ON gs.id = ps.study_id \
                   WHERE pb.sample_guid = s.sample_guid \
                   ORDER BY gs.accession LIMIT 1 ) AS study_accession, \
                s.data_kind, \
                jsonb_agg(jsonb_strip_nulls(jsonb_build_object( \
                    'run_accession', sl.atproto->>'run_accession', \
                    'url',           sf.http_locations->0->>'file_url', \
                    'index_url',     sf.http_locations->0->>'file_index_url', \
                    'md5',           sf.checksums->0->>'checksum', \
                    'bytes',         sf.file_size_bytes, \
                    'format',        sf.file_format \
                )) ORDER BY sl.id, sf.id) AS manifest, \
                ( SELECT SUM(COALESCE(l2.base_count, l2.reads::bigint * l2.read_length::bigint))::bigint \
                    FROM genomics.sequence_library l2 WHERE l2.sample_guid = s.sample_guid ) AS est_bases, \
                SUM(sf.file_size_bytes)::bigint AS total_bytes \
           FROM sample s \
           JOIN genomics.sequence_library sl ON sl.sample_guid = s.sample_guid \
           JOIN genomics.sequence_file sf ON sf.library_id = sl.id \
          WHERE ( (s.data_kind = 'CRAM'  AND sf.file_format IN ('CRAM', 'BAM')) \
               OR (s.data_kind = 'FASTQ' AND sf.file_format = 'FASTQ') ) \
            AND ( NOT $1 OR NOT EXISTS (SELECT 1 FROM grid.work_unit w WHERE w.sample_accession = s.accession) ) \
          GROUP BY s.sample_guid, s.accession, s.data_kind \
          ORDER BY s.accession \
          LIMIT $2",
    )
    .bind(only_new)
    .bind(limit)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

/// One submission awaiting validation, with everything the agreement test needs.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct PendingSubmission {
    pub id: i64,
    pub did: String,
    pub digest: Value,
    pub stack_version: String,
    pub reference_build: String,
    pub submitted_at: chrono::DateTime<chrono::Utc>,
}

/// A unit with unvalidated submissions.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct PendingUnit {
    pub id: i64,
    pub sample_accession: String,
    pub required_replicas: i16,
    pub est_bases: Option<i64>,
    pub data_kind: String,
}

/// A contributor's grid history, which is what trust tiering is derived from (§6.1).
///
/// Deliberately *not* the social reputation score: grid trust must be earned by grid work, or a
/// well-regarded community member could canonicalize bad results on reputation alone.
#[derive(Debug, Clone, Copy, sqlx::FromRow)]
pub struct GridHistory {
    pub agreed: i64,
    pub divergent: i64,
}

/// Units carrying at least one `PENDING` submission, oldest first.
pub async fn units_awaiting_validation(
    pool: &PgPool,
    limit: i64,
) -> Result<Vec<PendingUnit>, DbError> {
    let rows = sqlx::query_as::<_, PendingUnit>(
        "SELECT w.id, w.sample_accession, w.required_replicas, w.est_bases, w.data_kind \
           FROM grid.work_unit w \
          WHERE w.state IN ('AVAILABLE', 'CONTESTED') \
            AND EXISTS (SELECT 1 FROM grid.submission s \
                         WHERE s.work_unit_id = w.id AND s.status = 'PENDING') \
          ORDER BY w.id \
          LIMIT $1",
    )
    .bind(limit)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

/// Every submission on a unit that has not been ruled divergent, oldest first.
///
/// `AGREED` rows are included as well as `PENDING` ones: a unit re-opened by a shadow check is
/// judged over its whole history, not just the newest arrival.
pub async fn submissions_for_validation(
    pool: &PgPool,
    work_unit_id: i64,
) -> Result<Vec<PendingSubmission>, DbError> {
    let rows = sqlx::query_as::<_, PendingSubmission>(
        "SELECT id, did, digest, stack_version, reference_build, submitted_at \
           FROM grid.submission \
          WHERE work_unit_id = $1 AND status <> 'DIVERGENT' \
          ORDER BY submitted_at, id",
    )
    .bind(work_unit_id)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

/// A contributor's agreed and divergent counts.
pub async fn grid_history(pool: &PgPool, did: &str) -> Result<GridHistory, DbError> {
    let row: GridHistory = sqlx::query_as(
        "SELECT COUNT(*) FILTER (WHERE status = 'AGREED')    AS agreed, \
                COUNT(*) FILTER (WHERE status = 'DIVERGENT') AS divergent \
           FROM grid.submission WHERE did = $1",
    )
    .bind(did)
    .fetch_one(pool)
    .await?;
    Ok(row)
}

/// Promote a unit to `CANONICAL`: store the agreed digest, mark the winning submissions `AGREED`
/// and the rest `DIVERGENT`, all in one transaction.
///
/// A partial application here would be the worst possible state — a canonical unit whose
/// submissions still read `PENDING` would be re-judged on the next pass and could be credited
/// twice, which the `grid.credit` unique index would then silently swallow. So it is all or none.
pub async fn canonicalize(
    pool: &PgPool,
    work_unit_id: i64,
    canonical: &Value,
    agreed_ids: &[i64],
    divergent_ids: &[i64],
) -> Result<(), DbError> {
    let mut tx = pool.begin().await?;
    sqlx::query(
        "UPDATE grid.work_unit \
            SET state = 'CANONICAL', canonical_digest = $2, canonical_at = now(), updated_at = now() \
          WHERE id = $1",
    )
    .bind(work_unit_id)
    .bind(canonical)
    .execute(&mut *tx)
    .await?;
    sqlx::query(
        "UPDATE grid.submission SET status = 'AGREED', validated_at = now() WHERE id = ANY($1)",
    )
    .bind(agreed_ids)
    .execute(&mut *tx)
    .await?;
    sqlx::query(
        "UPDATE grid.submission SET status = 'DIVERGENT', validated_at = now() WHERE id = ANY($1)",
    )
    .bind(divergent_ids)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(())
}

/// Mark a unit contested and raise the bar: the submissions on it disagree and none of them has
/// earned the right to be believed, so the unit needs another independent result.
///
/// Nothing is marked `DIVERGENT` here. With two conflicting clusters and no quorum there is no
/// evidence about *which* is wrong, and penalising a contributor on a coin-flip would punish
/// honest work. The next replica breaks the tie, and that pass assigns blame.
pub async fn contest(pool: &PgPool, work_unit_id: i64, note: &str) -> Result<(), DbError> {
    sqlx::query(
        "UPDATE grid.work_unit \
            SET state = 'CONTESTED', required_replicas = required_replicas + 1, \
                note = $2, updated_at = now() \
          WHERE id = $1",
    )
    .bind(work_unit_id)
    .bind(note)
    .execute(pool)
    .await?;
    Ok(())
}

/// With probability `rate`, ask for one more independent result without contesting anything —
/// the shadow spot-check. Returns whether the shadow was requested.
///
/// Used when a trusted node's lone submission would otherwise canonicalize, so that trust is
/// re-earned rather than assumed indefinitely. The unit simply stays claimable with a higher
/// replica bar: the shadow then arrives through the ordinary claim path and the next validation
/// pass either confirms or contests it. No `SHADOW` state, no schema column, no second code path.
///
/// **The draw happens in the database, deliberately.** §6.2 requires spot-checks to be
/// AppView-chosen rather than self-selected, so the rule must be one a contributor cannot compute
/// in advance — which rules out anything derived from the unit id or the digest. Doing it in SQL
/// also avoids pulling a random-number crate into the workspace for a single coin flip.
pub async fn maybe_request_shadow(
    pool: &PgPool,
    work_unit_id: i64,
    rate: f64,
) -> Result<bool, DbError> {
    let r = sqlx::query(
        "UPDATE grid.work_unit \
            SET required_replicas = GREATEST(required_replicas, 2), \
                note = 'shadow spot-check', updated_at = now() \
          WHERE id = $1 AND state = 'AVAILABLE' AND random() < $2",
    )
    .bind(work_unit_id)
    .bind(rate)
    .execute(pool)
    .await?;
    Ok(r.rows_affected() > 0)
}

/// Register (or refresh) a contributing node in the shared fleet registry.
///
/// Reuses `fed.pds_node` rather than adding a `grid.node`: the columns the Grid needs — DID,
/// capabilities, heartbeat, software version — are the columns that table was built with and
/// never wired to anything.
pub async fn register_node(
    pool: &PgPool,
    did: &str,
    software_version: &str,
    capabilities: &Value,
    os_info: Option<&str>,
) -> Result<i64, DbError> {
    let id: (i64,) = sqlx::query_as(
        "INSERT INTO fed.pds_node (did, software_version, capabilities, os_info, status, last_heartbeat) \
         VALUES ($1, $2, $3, $4, 'ONLINE', now()) \
         ON CONFLICT (did) DO UPDATE SET \
             software_version = EXCLUDED.software_version, \
             capabilities     = EXCLUDED.capabilities, \
             os_info          = EXCLUDED.os_info, \
             status           = 'ONLINE', \
             last_heartbeat   = now(), \
             updated_at       = now() \
         RETURNING id",
    )
    .bind(did)
    .bind(software_version)
    .bind(capabilities)
    .bind(os_info)
    .fetch_one(pool)
    .await?;
    Ok(id.0)
}

/// The registry id of a node, if it has registered. `claim` records it on the lease so the fleet
/// view can attribute work to a machine; a node that never registered still gets to work, it is
/// simply anonymous in that view.
pub async fn node_id_for_did(pool: &PgPool, did: &str) -> Result<Option<i64>, DbError> {
    let row: Option<(i64,)> = sqlx::query_as("SELECT id FROM fed.pds_node WHERE did = $1")
        .bind(did)
        .fetch_optional(pool)
        .await?;
    Ok(row.map(|r| r.0))
}

/// A work unit as the public `/grid/work/{accession}` endpoint shows it.
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct PublicWorkUnit {
    pub sample_accession: String,
    pub study_accession: Option<String>,
    pub data_kind: String,
    pub state: String,
    pub canonical_digest: Option<Value>,
    pub canonical_at: Option<chrono::DateTime<chrono::Utc>>,
    /// How many independent contributors agreed. The number is the reason to believe the digest,
    /// so publishing the result without it would be publishing a claim with its evidence removed.
    pub replicas_agreed: i64,
}

/// One unit by ENA accession, for the public result page.
pub async fn work_unit_public(
    pool: &PgPool,
    sample_accession: &str,
) -> Result<Option<PublicWorkUnit>, DbError> {
    let row = sqlx::query_as::<_, PublicWorkUnit>(
        "SELECT w.sample_accession, w.study_accession, w.data_kind, w.state, \
                w.canonical_digest, w.canonical_at, \
                (SELECT count(*) FROM grid.submission s \
                  WHERE s.work_unit_id = w.id AND s.status = 'AGREED') AS replicas_agreed \
           FROM grid.work_unit w WHERE w.sample_accession = $1",
    )
    .bind(sample_accession)
    .fetch_optional(pool)
    .await?;
    Ok(row)
}

/// Grid throughput, for the public stats endpoint.
///
/// One round trip rather than five: these are counts over small indexed sets, and a stats endpoint
/// that costs five queries is one that gets called on every page load and then blamed for load.
pub async fn stats(pool: &PgPool) -> Result<Value, DbError> {
    let row: (i64, i64, i64, i64, i64, i64) = sqlx::query_as(
        "SELECT (SELECT count(*) FROM grid.work_unit), \
                (SELECT count(*) FROM grid.work_unit WHERE state = 'CANONICAL'), \
                (SELECT count(*) FROM grid.work_unit WHERE state = 'CONTESTED'), \
                (SELECT count(*) FROM grid.lease WHERE released_at IS NULL AND expires_at > now()), \
                (SELECT count(DISTINCT did) FROM grid.submission), \
                (SELECT COALESCE(SUM(cobblestones_milli), 0)::bigint FROM grid.credit)",
    )
    .fetch_one(pool)
    .await?;
    Ok(serde_json::json!({
        "units_total": row.0,
        "units_canonical": row.1,
        "units_contested": row.2,
        "leases_active": row.3,
        "contributors": row.4,
        "cobblestones_awarded": row.5 as f64 / COBBLESTONE as f64,
    }))
}

/// What one contributor's own grid participation looks like: the leases it holds right now, its
/// agreed/divergent history, its credit, and where it sits on the board.
///
/// Authenticated rather than public, because it is the caller's own work — the leaderboard shows
/// totals, and this shows the rows behind one contributor's total.
pub async fn standing(pool: &PgPool, did: &str) -> Result<Value, DbError> {
    let held = sqlx::query_as::<_, ClaimedUnit>(
        "SELECT l.id AS lease_id, l.work_unit_id, w.sample_accession, w.study_accession, \
                w.data_kind, w.manifest, w.est_bases, w.total_bytes, l.expires_at \
           FROM grid.lease l JOIN grid.work_unit w ON w.id = l.work_unit_id \
          WHERE l.did = $1 AND l.released_at IS NULL AND l.expires_at > now() \
          ORDER BY l.expires_at",
    )
    .bind(did)
    .fetch_all(pool)
    .await?;

    let h = grid_history(pool, did).await?;
    let (milli, units, rank): (i64, i64, i64) = sqlx::query_as(
        "SELECT COALESCE(SUM(c.cobblestones_milli), 0)::bigint, \
                COUNT(c.id)::bigint, \
                COALESCE(( SELECT count(*) + 1 FROM ( \
                     SELECT did, SUM(cobblestones_milli) AS t FROM grid.credit GROUP BY did \
                   ) b WHERE b.t > COALESCE((SELECT SUM(cobblestones_milli) FROM grid.credit WHERE did = $1), 0) \
                ), 1)::bigint \
           FROM grid.credit c WHERE c.did = $1",
    )
    .bind(did)
    .fetch_one(pool)
    .await?;

    // A contributor with no credit has no row on the leaderboard, so it has no rank either.
    // Reporting the position it *would* hold reads as "you are last" to someone who has simply not
    // finished their first unit yet — a discouraging answer to a question they did not ask. `null`
    // says "unranked", which is what is true.
    let rank = (units > 0).then_some(rank);

    Ok(serde_json::json!({
        "leases": held,
        "agreed": h.agreed,
        "divergent": h.divergent,
        "cobblestones": milli as f64 / COBBLESTONE as f64,
        "units_credited": units,
        "rank": rank,
    }))
}

/// The user account a contributing DID resolves to, if any. Sybil resistance leans on this: an
/// untrusted submission cannot canonicalise alone, so a lone account-less attacker cannot inject
/// a canonical result.
pub async fn user_for_did(pool: &PgPool, did: &str) -> Result<Option<Uuid>, DbError> {
    let row: Option<(Uuid,)> = sqlx::query_as("SELECT id FROM ident.users WHERE did = $1")
        .bind(did)
        .fetch_optional(pool)
        .await?;
    Ok(row.map(|r| r.0))
}

#[cfg(test)]
mod tests {
    use super::messages;

    /// The signed strings are a cross-repo contract with the Navigator edge. This test exists to
    /// make an accidental reformat fail here, in the repo that defines them, rather than as a 403
    /// against a released desktop build that signs the old bytes.
    #[test]
    fn canonical_messages_are_stable() {
        assert_eq!(
            messages::poll("did:plc:abc", 1_724_500_000),
            "grid-poll\ndid:plc:abc\n1724500000"
        );
        assert_eq!(
            messages::claim("did:plc:abc", "CRAM,FASTQ", 4, 259_200),
            "grid-claim\ndid:plc:abc\nCRAM,FASTQ\n4\n259200"
        );
        assert_eq!(
            messages::heartbeat("did:plc:abc", 7, "align"),
            "grid-heartbeat\ndid:plc:abc\n7\nalign"
        );
        assert_eq!(
            messages::release("did:plc:abc", 7, "cancelled"),
            "grid-release\ndid:plc:abc\n7\ncancelled"
        );
        assert_eq!(
            messages::submit("did:plc:abc", 12, "3q2+7w=="),
            "grid-submit\ndid:plc:abc\n12\n3q2+7w=="
        );
        assert_eq!(
            messages::register("did:plc:abc", "0.1.0-alpha.18", "3q2+7w=="),
            "grid-register\ndid:plc:abc\n0.1.0-alpha.18\n3q2+7w=="
        );
    }

    /// Every message starts with its own operation tag, so a signature harvested from one
    /// endpoint cannot be replayed against another.
    #[test]
    fn each_message_is_domain_separated() {
        let all = [
            messages::poll("d", 1),
            messages::claim("d", "CRAM", 1, 1),
            messages::heartbeat("d", 1, "s"),
            messages::release("d", 1, "r"),
            messages::submit("d", 1, "h"),
            messages::register("d", "v", "h"),
        ];
        let tags: Vec<&str> = all.iter().map(|m| m.split('\n').next().unwrap()).collect();
        let mut sorted = tags.clone();
        sorted.sort_unstable();
        sorted.dedup();
        assert_eq!(
            sorted.len(),
            tags.len(),
            "two grid messages share an operation tag: {tags:?}"
        );
    }
}
