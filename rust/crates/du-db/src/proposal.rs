//! Curation proposals: Navigator submits variant/branch proposals; the AppView
//! pools them by (name, parent) across submitters, and curators review/name/
//! promote. Backed by `tree.proposed_branch` (+ evidence) and `tree.curator_action`.
//!
//! AppView-only (not in the shared `du-domain`): proposals are a server concern.

use crate::{DbError, Page};
use serde_json::Value;
use sqlx::PgPool;
use uuid::Uuid;

/// A proposal as submitted by Navigator. Defining-variant detail rides in
/// `evidence` (JSONB) — intake does NOT mutate the catalog; that happens at
/// promotion when a curator names/creates the variants.
pub struct SubmitProposal {
    pub proposed_name: String,
    /// Parent haplogroup name (resolved to an id if it exists).
    pub parent_haplogroup: Option<String>,
    pub dna_type: du_domain::enums::DnaType,
    /// The submitting sample's GUID (for distinct-submitter consensus), if any.
    pub sample_guid: Option<Uuid>,
    pub proposed_by: Option<String>,
    /// Free-form evidence (candidate variants, positions, scores, …).
    pub evidence: Value,
}

#[derive(sqlx::FromRow)]
pub struct ProposalSummary {
    pub id: i64,
    pub proposed_name: Option<String>,
    pub parent_name: Option<String>,
    pub evidence_count: i32,
    pub submitter_count: i32,
    pub confidence: Option<f64>,
    pub status: String,
}

pub struct ProposalDetail {
    pub summary: ProposalSummary,
    pub evidence: Vec<Value>,
}

/// Submit a proposal, pooling into an existing open proposal with the same
/// (proposed_name, parent). Returns (proposal_id, created_new).
pub async fn submit(pool: &PgPool, p: &SubmitProposal) -> Result<(i64, bool), DbError> {
    let mut tx = pool.begin().await?;

    // Resolve parent haplogroup id (by name + lineage), if a parent was given.
    let parent_id: Option<i64> = match &p.parent_haplogroup {
        Some(name) => {
            sqlx::query_scalar(
                "SELECT id FROM tree.haplogroup WHERE name = $1 AND haplogroup_type::text = $2",
            )
            .bind(name)
            .bind(crate::pg_enum_label(&p.dna_type)?)
            .fetch_optional(&mut *tx)
            .await?
        }
        None => None,
    };

    // Find an open proposal to pool into.
    let existing: Option<i64> = sqlx::query_scalar(
        "SELECT id FROM tree.proposed_branch \
         WHERE proposed_name = $1 AND parent_haplogroup_id IS NOT DISTINCT FROM $2 \
           AND status IN ('PROPOSED','UNDER_REVIEW') LIMIT 1",
    )
    .bind(&p.proposed_name)
    .bind(parent_id)
    .fetch_optional(&mut *tx)
    .await?;

    let (id, created) = match existing {
        Some(id) => {
            sqlx::query(
                "UPDATE tree.proposed_branch SET evidence_count = evidence_count + 1, \
                   confidence = LEAST(0.99, (evidence_count + 1) * 0.1), \
                   discovery_sample_guids = CASE \
                     WHEN $2::uuid IS NULL OR $2 = ANY(discovery_sample_guids) THEN discovery_sample_guids \
                     ELSE array_append(discovery_sample_guids, $2) END \
                 WHERE id = $1",
            )
            .bind(id)
            .bind(p.sample_guid)
            .execute(&mut *tx)
            .await?;
            (id, false)
        }
        None => {
            let guids: Vec<Uuid> = p.sample_guid.into_iter().collect();
            let id: i64 = sqlx::query_scalar(
                "INSERT INTO tree.proposed_branch \
                   (proposed_name, parent_haplogroup_id, discovery_sample_guids, evidence_count, confidence, proposed_by, status) \
                 VALUES ($1, $2, $3, 1, 0.1, $4, 'PROPOSED') RETURNING id",
            )
            .bind(&p.proposed_name)
            .bind(parent_id)
            .bind(&guids)
            .bind(&p.proposed_by)
            .fetch_one(&mut *tx)
            .await?;
            (id, true)
        }
    };

    sqlx::query(
        "INSERT INTO tree.proposed_branch_evidence (proposed_branch_id, evidence_type, evidence_detail) \
         VALUES ($1, 'PRIVATE_VARIANT', $2)",
    )
    .bind(id)
    .bind(&p.evidence)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;
    Ok((id, created))
}

const SUMMARY_SELECT: &str = "SELECT pb.id, pb.proposed_name, h.name AS parent_name, \
    pb.evidence_count, cardinality(pb.discovery_sample_guids) AS submitter_count, \
    pb.confidence::float8 AS confidence, pb.status \
    FROM tree.proposed_branch pb LEFT JOIN tree.haplogroup h ON h.id = pb.parent_haplogroup_id";

/// List proposals, optionally filtered by status, newest first.
pub async fn list(
    pool: &PgPool,
    status: Option<&str>,
    page: i64,
    page_size: i64,
) -> Result<Page<ProposalSummary>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let filter = "WHERE ($1::text IS NULL OR pb.status = $1)";

    let total: i64 =
        sqlx::query_scalar(&format!("SELECT count(*) FROM tree.proposed_branch pb {filter}"))
            .bind(status)
            .fetch_one(pool)
            .await?;
    let items: Vec<ProposalSummary> =
        sqlx::query_as(&format!("{SUMMARY_SELECT} {filter} ORDER BY pb.id DESC LIMIT $2 OFFSET $3"))
            .bind(status)
            .bind(limit)
            .bind(offset)
            .fetch_all(pool)
            .await?;
    Ok(Page { items, total, page: page.max(1), page_size: limit })
}

pub async fn get(pool: &PgPool, id: i64) -> Result<Option<ProposalDetail>, DbError> {
    let summary: Option<ProposalSummary> =
        sqlx::query_as(&format!("{SUMMARY_SELECT} WHERE pb.id = $1"))
            .bind(id)
            .fetch_optional(pool)
            .await?;
    let Some(summary) = summary else { return Ok(None) };
    let evidence: Vec<Value> = sqlx::query_scalar(
        "SELECT evidence_detail FROM tree.proposed_branch_evidence WHERE proposed_branch_id = $1 ORDER BY id",
    )
    .bind(id)
    .fetch_all(pool)
    .await?;
    Ok(Some(ProposalDetail { summary, evidence }))
}

/// Curator decision. `action` is APPROVE / REJECT / DEFER; sets the proposal
/// status (ACCEPTED / REJECTED / UNDER_REVIEW) and records a curator_action.
/// (Catalog promotion — creating the named branch/variants — is a separate step.)
pub async fn review(
    pool: &PgPool,
    id: i64,
    action: &str,
    action_by: &str,
    notes: Option<&str>,
) -> Result<bool, DbError> {
    let status = match action {
        "APPROVE" => "ACCEPTED",
        "REJECT" => "REJECTED",
        "DEFER" => "UNDER_REVIEW",
        other => return Err(DbError::Decode(format!("unknown review action: {other}"))),
    };
    let mut tx = pool.begin().await?;
    let affected = sqlx::query("UPDATE tree.proposed_branch SET status = $2 WHERE id = $1")
        .bind(id)
        .bind(status)
        .execute(&mut *tx)
        .await?
        .rows_affected();
    if affected == 0 {
        tx.rollback().await?;
        return Ok(false);
    }
    sqlx::query(
        "INSERT INTO tree.curator_action (proposed_branch_id, action, notes, action_by) \
         VALUES ($1, $2, $3, $4)",
    )
    .bind(id)
    .bind(action)
    .bind(notes)
    .bind(action_by)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(true)
}
