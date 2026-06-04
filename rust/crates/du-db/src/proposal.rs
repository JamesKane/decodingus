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

/// A defining variant extracted from proposal evidence.
struct DefiningVariant {
    name: String,
    position: Option<i64>,
    reference: Option<String>,
    alternate: Option<String>,
}

/// Pull distinct defining variants from the proposal's evidence JSONB. Each
/// evidence object may carry `variant` (name) plus optional `pos`/`ref`/`alt`.
fn defining_variants(evidence: &[Value]) -> Vec<DefiningVariant> {
    let mut seen = std::collections::BTreeMap::new();
    for e in evidence {
        if let Some(name) = e.get("variant").and_then(Value::as_str) {
            seen.entry(name.to_string()).or_insert_with(|| DefiningVariant {
                name: name.to_string(),
                position: e.get("pos").and_then(Value::as_i64),
                reference: e.get("ref").and_then(Value::as_str).map(str::to_string),
                alternate: e.get("alt").and_then(Value::as_str).map(str::to_string),
            });
        }
    }
    seen.into_values().collect()
}

/// Promote an ACCEPTED proposal into the named catalog: create the
/// `tree.haplogroup` branch under its parent, a current relationship edge, and
/// `core.variant` links from the evidence's defining variants. Sets the
/// proposal status to `PROMOTED` and records a `PROMOTE` curator action.
/// Returns the new haplogroup id. All in one transaction.
pub async fn promote(pool: &PgPool, id: i64, action_by: &str) -> Result<i64, DbError> {
    // Load proposal + evidence first (read-only).
    let detail = get(pool, id).await?.ok_or_else(|| DbError::Conflict(format!("proposal {id} not found")))?;
    if detail.summary.status != "ACCEPTED" {
        return Err(DbError::Conflict(format!("proposal must be ACCEPTED to promote (is {})", detail.summary.status)));
    }
    let name = detail.summary.proposed_name.clone().filter(|n| !n.is_empty())
        .ok_or_else(|| DbError::Conflict("proposal has no proposed_name".into()))?;

    let mut tx = pool.begin().await?;

    // Parent (and its lineage) is required to place the branch.
    let parent: Option<(i64, String)> = sqlx::query_as(
        "SELECT h.id, h.haplogroup_type::text FROM tree.proposed_branch pb \
         JOIN tree.haplogroup h ON h.id = pb.parent_haplogroup_id WHERE pb.id = $1",
    )
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?;
    let (parent_id, dna) = parent.ok_or_else(|| DbError::Conflict("proposal has no parent haplogroup to attach under".into()))?;

    // Name must not already exist for this lineage.
    let exists: Option<i64> = sqlx::query_scalar(
        "SELECT id FROM tree.haplogroup WHERE name = $1 AND haplogroup_type::text = $2",
    )
    .bind(&name)
    .bind(&dna)
    .fetch_optional(&mut *tx)
    .await?;
    if exists.is_some() {
        return Err(DbError::Conflict(format!("'{name}' is already in the {dna} catalog")));
    }

    // Create the branch.
    let new_id: i64 = sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type, source, confidence_level) \
         VALUES ($1, $2::core.dna_type, 'discovery', 'proposed') RETURNING id",
    )
    .bind(&name)
    .bind(&dna)
    .fetch_one(&mut *tx)
    .await?;

    // Edge under the parent.
    sqlx::query(
        "INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id, source) \
         VALUES ($1, $2, 'discovery')",
    )
    .bind(new_id)
    .bind(parent_id)
    .execute(&mut *tx)
    .await?;

    // Defining variants: get-or-create by name (promote UNNAMED -> NAMED), link.
    for dv in defining_variants(&detail.evidence) {
        let coords = match dv.position {
            Some(pos) => serde_json::json!({ "GRCh38": {
                "contig": "chrY", "position": pos,
                "ancestral": dv.reference, "derived": dv.alternate
            }}),
            None => serde_json::json!({}),
        };
        let variant_id: i64 = sqlx::query_scalar(
            "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, coordinates) \
             VALUES ($1, 'SNP'::core.mutation_type, 'NAMED'::core.naming_status, $2) \
             ON CONFLICT (canonical_name) WHERE canonical_name IS NOT NULL DO UPDATE SET naming_status = \
               CASE WHEN core.variant.naming_status = 'UNNAMED' THEN 'NAMED'::core.naming_status \
                    ELSE core.variant.naming_status END \
             RETURNING id",
        )
        .bind(&dv.name)
        .bind(coords)
        .fetch_one(&mut *tx)
        .await?;
        sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1, $2)")
            .bind(new_id)
            .bind(variant_id)
            .execute(&mut *tx)
            .await?;
    }

    sqlx::query("UPDATE tree.proposed_branch SET status = 'PROMOTED' WHERE id = $1")
        .bind(id)
        .execute(&mut *tx)
        .await?;
    sqlx::query(
        "INSERT INTO tree.curator_action (proposed_branch_id, action, notes, action_by) \
         VALUES ($1, 'PROMOTE', $2, $3)",
    )
    .bind(id)
    .bind(format!("promoted to haplogroup #{new_id}"))
    .bind(action_by)
    .execute(&mut *tx)
    .await?;

    tx.commit().await?;
    Ok(new_id)
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
