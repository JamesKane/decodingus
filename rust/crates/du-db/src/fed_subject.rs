//! Federated-subject anchoring — mint a canonical `core.biosample` for each
//! federated subject (`fed.biosample`) so the unified sample report, IBD, dedup,
//! and the per-chromosome benchmarks all see it as a first-class biosample.
//!
//! Background: the AppView mirrors each citizen's published biosample into
//! `fed.biosample` (DID-keyed), and their runs / coverage / ancestry into the
//! sibling `fed.*` tables keyed by the biosample record's at:// URI. But the
//! *only* production writer of `core.biosample.atproto` is the legacy ETL
//! ([`crate`]'s `du-migrate` transform) — so a subject published **live via
//! Jetstream** landed in `fed.biosample` with no `core.biosample` anchor. The
//! sample report's federated sections (Q2–Q5: haplogroups, sequencing runs,
//! coverage, ancestry) all gate on `core.biosample.atproto->>'uri'`, so for those
//! subjects the report stayed dark and they never appeared in the catalog.
//!
//! [`link_federated_subjects`] closes the gap: for every `fed.biosample` without a
//! linked `core.biosample`, it mints a pseudonymous **CITIZEN** biosample (plus a
//! donor carrying the published sex — the report reads sex from the donor)
//! anchored to the federated record's `at_uri` / `cid` / `repo_did`. It is the
//! federated analogue of [`crate::donor::consolidate_denovo_donors`]: identity
//! merging against publication/de-novo rows for the *same* individual is left to
//! the dedup machinery (`dedup-candidates` + autosomal confirmation).
//!
//! Idempotent + re-runnable (a subject already anchored is skipped by the
//! `NOT EXISTS` guard). `apply = false` previews the candidate count.

use crate::dedup::{self, IdentifierPair};
use crate::DbError;
use sqlx::PgPool;
use uuid::Uuid;

/// Outcome of [`link_federated_subjects`].
#[derive(Debug, Clone, Default)]
pub struct FederatedLinkReport {
    /// `fed.biosample` rows with no linked `core.biosample` at the start of the run.
    pub unlinked: i64,
    /// Pseudonymous `core.biosample` anchors minted.
    pub biosamples_created: u64,
    /// `core.specimen_donor` rows minted (one per new DID).
    pub donors_created: u64,
    /// Published external ids registered into `core.biosample_identifier` (Phase 4 anchor dedup).
    pub identifiers_registered: u64,
    /// IDENTIFIER-tier duplicate candidates raised — a published id already on another sample
    /// (the same donor re-published under a new DID/URI).
    pub dup_candidates: u64,
}

/// Ensure every federated subject has a linked `core.biosample` anchor. See module
/// docs. `apply = false` counts the candidates without mutating.
pub async fn link_federated_subjects(pool: &PgPool, apply: bool) -> Result<FederatedLinkReport, DbError> {
    #[derive(sqlx::FromRow)]
    struct Cand {
        did: String,
        at_uri: String,
        cid: Option<String>,
        sex: Option<String>,
        center_name: Option<String>,
    }
    // A federated subject needs an anchor when no live `core.biosample` already
    // carries its record URI in `atproto.uri`.
    let candidates: Vec<Cand> = sqlx::query_as(
        "SELECT fb.did, fb.at_uri, fb.cid, fb.sex, fb.center_name \
         FROM fed.biosample fb \
         WHERE NOT EXISTS ( \
             SELECT 1 FROM core.biosample b \
             WHERE b.atproto->>'uri' = fb.at_uri AND b.deleted = false) \
         ORDER BY fb.did, fb.at_uri",
    )
    .fetch_all(pool)
    .await?;

    let mut rep = FederatedLinkReport { unlinked: candidates.len() as i64, ..Default::default() };
    if !apply || candidates.is_empty() {
        return Ok(rep);
    }

    let mut tx = pool.begin().await?;
    // Identifier collisions found while anchoring — adjudicated after commit (below).
    let mut collisions: Vec<IdentifierPair> = Vec::new();
    for c in &candidates {
        // One person (DID) = one donor across however many biosamples the DID
        // publishes. Reuse an existing *federated* donor for the DID (matched via a
        // sibling biosample's `atproto.repo_did`); only mint a donor when the DID is
        // new — so a second biosample from the same citizen joins the first's donor.
        let donor_id: i64 = match sqlx::query_scalar::<_, i64>(
            "SELECT sd.id FROM core.specimen_donor sd \
             JOIN core.biosample b ON b.donor_id = sd.id AND b.deleted = false \
             WHERE b.atproto->>'repo_did' = $1 \
             ORDER BY sd.id LIMIT 1",
        )
        .bind(&c.did)
        .fetch_optional(&mut *tx)
        .await?
        {
            Some(id) => id,
            None => {
                rep.donors_created += 1;
                sqlx::query_scalar(
                    "INSERT INTO core.specimen_donor (donor_identifier, donor_type, sex) \
                     VALUES ($1, 'CITIZEN'::core.biosample_source, \
                             CASE WHEN $2 IN ('MALE','FEMALE','INTERSEX') \
                                  THEN $2::core.biological_sex END) \
                     RETURNING id",
                )
                .bind(&c.did)
                .bind(c.sex.as_deref())
                .fetch_one(&mut *tx)
                .await?
            }
        };
        // The anchor. Federated records only reach the AppView because they were
        // published to the public firehose, so the subject is public by construction
        // (`is_public = true`). `source_attrs.federated` flags the origin.
        let anchor: Uuid = sqlx::query_scalar(
            "INSERT INTO core.biosample \
               (source, center_name, is_public, donor_id, source_attrs, atproto) \
             VALUES ('CITIZEN'::core.biosample_source, $1, true, $2, \
                     jsonb_build_object('federated', true), \
                     jsonb_strip_nulls(jsonb_build_object( \
                         'uri', $3::text, 'cid', $4::text, 'repo_did', $5::text))) \
             RETURNING sample_guid",
        )
        .bind(c.center_name.as_deref())
        .bind(donor_id)
        .bind(&c.at_uri)
        .bind(c.cid.as_deref())
        .bind(&c.did)
        .fetch_one(&mut *tx)
        .await?;
        rep.biosamples_created += 1;

        // Phase 4 — anchor dedup. Fold this record's published external ids into the dedup
        // index (mirrored in fed.biosample_identifier). An id already owned by a DIFFERENT
        // sample means the same donor was re-published under a new DID/URI → collect a
        // duplicate pair (adjudicated by Y placement after commit); an id no one holds yet is
        // registered to this anchor so the next publisher matches it.
        let fed_ids: Vec<(String, String, bool)> = sqlx::query_as(
            "SELECT namespace, value, is_public FROM fed.biosample_identifier WHERE at_uri = $1",
        )
        .bind(&c.at_uri)
        .fetch_all(&mut *tx)
        .await?;
        for (namespace, value, is_public) in fed_ids {
            let owner: Option<Uuid> = sqlx::query_scalar(
                "SELECT sample_guid FROM core.biosample_identifier WHERE namespace = $1 AND value = $2",
            )
            .bind(&namespace)
            .bind(&value)
            .fetch_optional(&mut *tx)
            .await?;
            match owner {
                Some(other) if other != anchor => {
                    let (sample_a, sample_b) = if anchor < other { (anchor, other) } else { (other, anchor) };
                    collisions.push(IdentifierPair { sample_a, sample_b, namespace, value });
                }
                Some(_) => {}
                None => {
                    sqlx::query(
                        "INSERT INTO core.biosample_identifier (sample_guid, namespace, value, is_public, source) \
                         VALUES ($1, $2, $3, $4, 'federation') ON CONFLICT (namespace, value) DO NOTHING",
                    )
                    .bind(anchor)
                    .bind(&namespace)
                    .bind(&value)
                    .bind(is_public)
                    .execute(&mut *tx)
                    .await?;
                    rep.identifiers_registered += 1;
                }
            }
        }
    }
    tx.commit().await?;

    // Adjudicate the collisions by terminal-Y concordance and upsert IDENTIFIER candidates
    // (post-commit — both samples are persisted). A freshly-anchored sample not yet placed in
    // the tree classifies UNPLACED and is confirmed by a later dedup pass once it resolves.
    if !collisions.is_empty() {
        let classified = dedup::classify_identifier_pairs(pool, &collisions).await?;
        rep.dup_candidates = dedup::write_identifier_candidates(pool, &classified, "federation").await?;
    }
    Ok(rep)
}
