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

/// Reclaim every lease whose bound has passed. Run by the `grid-reap` job.
///
/// Reclamation is what makes a lease *honest*: a node that crashes, is closed, or simply loses
/// interest costs the catalogue one lease duration and nothing more. Returns how many were
/// reclaimed.
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
/// **`est_bases` is usually `NULL` today, and that is a real gap.** It is `reads × read_length`,
/// but the crawl sets `read_length` to `None` — ENA's `filereport` exposes `base_count`, and
/// `RUN_FIELDS` does not request it. Until that is fixed the per-Gbp term of the credit formula
/// (§6.3) has nothing to weigh a FASTQ unit by. A fabricated estimate would be worse than a null in
/// a ledger, so this returns the null and the job reports how many it saw.
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
                ( SELECT SUM(l2.reads::bigint * l2.read_length::bigint) \
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
