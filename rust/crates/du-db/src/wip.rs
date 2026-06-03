//! Curator **merge-review** staging over the `tree.wip_*` shadow tables.
//!
//! When a merge/graft can't place a source node confidently (the SNP-graft
//! Phase-4 flags, or the merge engine's `Ambiguity` cases), the item is *staged*
//! into a change-set's WIP tables for a human to adjudicate — rather than guessed
//! or dropped. Each staged item is:
//!
//! - a [`tree.wip_haplogroup`] placeholder row (the proposed node: name, source,
//!   and the full review context — category, SNP scatter, parent status — kept in
//!   `provenance.review` so the UI needs no extra table), and
//! - a [`tree.wip_relationship`] row holding its *tentative* production parent
//!   (the best anchor, or NULL when even that is uncertain).
//!
//! A curator's decision is a [`tree.wip_resolution`] row
//! (`REPARENT`/`MERGE_EXISTING`/`EDIT_VARIANTS`/`DEFER`). The change-set apply
//! engine ([`crate::change_set::apply`]) enacts the resolved items — so review
//! decisions flow through the same tested temporal-apply path as everything else.
//!
//! Defining SNPs are kept by *name* in `provenance.review.defining_snps` and only
//! turned into `core.variant` rows at enactment, so deferring/rejecting an item
//! never pollutes the catalog with speculative variants.

use crate::{DbError, Page};
use serde_json::Value;
use sqlx::types::chrono::{DateTime, Utc};
use sqlx::PgPool;

/// One item to stage for review (built by `snp_graft::stage_review`).
#[derive(Debug, Clone)]
pub struct StageItem {
    /// Source node name (e.g. a decoding-us `A0-T`).
    pub name: String,
    /// Tentative production parent (best-anchor id), or `None` when uncertain.
    pub tentative_parent_id: Option<i64>,
    /// Review payload stored under `provenance.review` (category, reason,
    /// candidates, strength, defining_snps, source_parent, is_backbone, …).
    pub review: Value,
}

/// Create the staged WIP rows for `items` inside an existing change-set. Assigns
/// each a negative placeholder id unique within the set. Returns the count.
pub async fn stage(
    pool: &PgPool,
    change_set_id: i64,
    source: &str,
    items: &[StageItem],
) -> Result<usize, DbError> {
    let mut tx = pool.begin().await?;
    // Continue past any placeholders already staged in this set.
    let min_ph: Option<i32> =
        sqlx::query_scalar("SELECT min(placeholder_id) FROM tree.wip_haplogroup WHERE change_set_id = $1")
            .bind(change_set_id)
            .fetch_one(&mut *tx)
            .await?;
    let mut ph = min_ph.unwrap_or(0) - 1;
    for it in items {
        let provenance = serde_json::json!({ "review": it.review });
        sqlx::query(
            "WITH w AS ( \
               INSERT INTO tree.wip_haplogroup (change_set_id, placeholder_id, name, source, provenance) \
               VALUES ($1, $2, $3, $4, $5) RETURNING id) \
             INSERT INTO tree.wip_relationship (change_set_id, child_placeholder_id, parent_production_id) \
             VALUES ($1, $2, $6)",
        )
        .bind(change_set_id)
        .bind(ph)
        .bind(&it.name)
        .bind(source)
        .bind(provenance)
        .bind(it.tentative_parent_id)
        .execute(&mut *tx)
        .await?;
        ph -= 1;
    }
    tx.commit().await?;
    Ok(items.len())
}

/// A row in the review worklist (left list).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct ReviewRow {
    pub wip_id: i64,
    pub change_set_id: i64,
    pub source: String,
    pub name: String,
    pub category: Option<String>,
    pub best_anchor: Option<String>,
    pub tentative_parent: Option<String>,
    /// `None` = open (no decision yet).
    pub resolution_type: Option<String>,
    pub created_at: DateTime<Utc>,
}

/// Which staged items to list.
pub enum ReviewFilter {
    /// No resolution recorded yet.
    Open,
    /// Resolved as DEFER.
    Deferred,
    /// Resolved (non-DEFER).
    Resolved,
    All,
}

impl ReviewFilter {
    fn from_str(s: Option<&str>) -> Self {
        match s {
            Some("deferred") => ReviewFilter::Deferred,
            Some("resolved") => ReviewFilter::Resolved,
            Some("all") => ReviewFilter::All,
            _ => ReviewFilter::Open,
        }
    }
}

/// Paginated review worklist, newest change-set first. `status` ∈ {open (default),
/// deferred, resolved, all}; `category` filters on `provenance.review.category`;
/// `source` filters the change-set source.
pub async fn list(
    pool: &PgPool,
    status: Option<&str>,
    category: Option<&str>,
    source: Option<&str>,
    page: i64,
    page_size: i64,
) -> Result<Page<ReviewRow>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let category = category.filter(|s| !s.is_empty());
    let source = source.filter(|s| !s.is_empty());

    // Resolution gate expressed in SQL against the LEFT-JOINed resolution row.
    let gate = match ReviewFilter::from_str(status) {
        ReviewFilter::Open => "r.id IS NULL",
        ReviewFilter::Deferred => "r.resolution_type = 'DEFER'",
        ReviewFilter::Resolved => "r.id IS NOT NULL AND r.resolution_type <> 'DEFER'",
        ReviewFilter::All => "TRUE",
    };
    let where_sql = format!(
        "WHERE {gate} \
           AND ($1::text IS NULL OR w.provenance #>> '{{review,category}}' = $1) \
           AND ($2::text IS NULL OR cs.source = $2)"
    );

    let total: i64 = sqlx::query_scalar(&format!(
        "SELECT count(*) FROM tree.wip_haplogroup w \
         JOIN tree.change_set cs ON cs.id = w.change_set_id \
         LEFT JOIN tree.wip_resolution r ON r.wip_haplogroup_id = w.id {where_sql}"
    ))
    .bind(category)
    .bind(source)
    .fetch_one(pool)
    .await?;

    let items: Vec<ReviewRow> = sqlx::query_as(&format!(
        "SELECT w.id AS wip_id, w.change_set_id, cs.source, w.name, \
                w.provenance #>> '{{review,category}}' AS category, \
                w.provenance #>> '{{review,best_anchor}}' AS best_anchor, \
                p.name AS tentative_parent, \
                r.resolution_type, cs.created_at \
         FROM tree.wip_haplogroup w \
         JOIN tree.change_set cs ON cs.id = w.change_set_id \
         LEFT JOIN tree.wip_relationship rel \
                ON rel.change_set_id = w.change_set_id AND rel.child_placeholder_id = w.placeholder_id \
         LEFT JOIN tree.haplogroup p ON p.id = rel.parent_production_id \
         LEFT JOIN tree.wip_resolution r ON r.wip_haplogroup_id = w.id \
         {where_sql} \
         ORDER BY w.change_set_id DESC, w.id LIMIT $3 OFFSET $4"
    ))
    .bind(category)
    .bind(source)
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;

    Ok(Page { items, total, page: page.max(1), page_size: limit })
}

/// Full detail for one staged item (the review panel).
#[derive(Debug, Clone)]
pub struct ReviewDetail {
    pub wip_id: i64,
    pub change_set_id: i64,
    pub cs_status: String,
    pub cs_dna: Option<String>,
    pub source: String,
    pub name: String,
    /// The `provenance.review` payload (the UI extracts its fields).
    pub review: Value,
    pub tentative_parent_id: Option<i64>,
    pub tentative_parent_name: Option<String>,
    pub resolution: Option<Resolution>,
}

#[derive(Debug, Clone)]
pub struct Resolution {
    pub resolution_type: String,
    pub new_parent_id: Option<i64>,
    pub new_parent_name: Option<String>,
    pub merge_target_id: Option<i64>,
    pub merge_target_name: Option<String>,
    pub notes: Option<String>,
}

pub async fn get(pool: &PgPool, wip_id: i64) -> Result<Option<ReviewDetail>, DbError> {
    #[derive(sqlx::FromRow)]
    struct Row {
        wip_id: i64,
        change_set_id: i64,
        cs_status: String,
        cs_dna: Option<String>,
        source: String,
        name: String,
        provenance: Value,
        tentative_parent_id: Option<i64>,
        tentative_parent_name: Option<String>,
    }
    let row: Option<Row> = sqlx::query_as(
        "SELECT w.id AS wip_id, w.change_set_id, cs.status::text AS cs_status, \
                cs.haplogroup_type::text AS cs_dna, cs.source, w.name, \
                w.provenance, rel.parent_production_id AS tentative_parent_id, p.name AS tentative_parent_name \
         FROM tree.wip_haplogroup w \
         JOIN tree.change_set cs ON cs.id = w.change_set_id \
         LEFT JOIN tree.wip_relationship rel \
                ON rel.change_set_id = w.change_set_id AND rel.child_placeholder_id = w.placeholder_id \
         LEFT JOIN tree.haplogroup p ON p.id = rel.parent_production_id \
         WHERE w.id = $1",
    )
    .bind(wip_id)
    .fetch_optional(pool)
    .await?;
    let Some(row) = row else { return Ok(None) };

    #[derive(sqlx::FromRow)]
    struct ResRow {
        resolution_type: String,
        new_parent_id: Option<i64>,
        new_parent_name: Option<String>,
        merge_target_id: Option<i64>,
        merge_target_name: Option<String>,
        notes: Option<String>,
    }
    let res: Option<ResRow> = sqlx::query_as(
        "SELECT r.resolution_type, r.new_parent_id, np.name AS new_parent_name, \
                r.merge_target_id, mt.name AS merge_target_name, r.details->>'notes' AS notes \
         FROM tree.wip_resolution r \
         LEFT JOIN tree.haplogroup np ON np.id = r.new_parent_id \
         LEFT JOIN tree.haplogroup mt ON mt.id = r.merge_target_id \
         WHERE r.wip_haplogroup_id = $1 ORDER BY r.id DESC LIMIT 1",
    )
    .bind(wip_id)
    .fetch_optional(pool)
    .await?;

    let review = row.provenance.get("review").cloned().unwrap_or(Value::Null);
    Ok(Some(ReviewDetail {
        wip_id: row.wip_id,
        change_set_id: row.change_set_id,
        cs_status: row.cs_status,
        cs_dna: row.cs_dna,
        source: row.source,
        name: row.name,
        review,
        tentative_parent_id: row.tentative_parent_id,
        tentative_parent_name: row.tentative_parent_name,
        resolution: res.map(|r| Resolution {
            resolution_type: r.resolution_type,
            new_parent_id: r.new_parent_id,
            new_parent_name: r.new_parent_name,
            merge_target_id: r.merge_target_id,
            merge_target_name: r.merge_target_name,
            notes: r.notes,
        }),
    }))
}

/// Record (or replace) a curator's resolution for one staged item. `kind` is
/// `REPARENT` (→ create the node under `new_parent_id`), `MERGE_EXISTING` (→ fold
/// its SNPs into `merge_target_id`), `EDIT_VARIANTS`, or `DEFER`. Returns the
/// change-set id (so the caller can route to apply).
pub async fn resolve(
    pool: &PgPool,
    wip_id: i64,
    kind: &str,
    new_parent_id: Option<i64>,
    merge_target_id: Option<i64>,
    notes: Option<&str>,
    by: &str,
) -> Result<i64, DbError> {
    let mut tx = pool.begin().await?;
    let change_set_id: i64 =
        sqlx::query_scalar("SELECT change_set_id FROM tree.wip_haplogroup WHERE id = $1 FOR UPDATE")
            .bind(wip_id)
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(|| DbError::Conflict(format!("wip item {wip_id} not found")))?;

    // One current decision per item — replace any prior one.
    sqlx::query("DELETE FROM tree.wip_resolution WHERE wip_haplogroup_id = $1")
        .bind(wip_id)
        .execute(&mut *tx)
        .await?;
    let details = serde_json::json!({ "notes": notes, "by": by });
    sqlx::query(
        "INSERT INTO tree.wip_resolution \
           (change_set_id, wip_haplogroup_id, resolution_type, new_parent_id, merge_target_id, details) \
         VALUES ($1, $2, $3, $4, $5, $6)",
    )
    .bind(change_set_id)
    .bind(wip_id)
    .bind(kind)
    .bind(new_parent_id)
    .bind(merge_target_id)
    .bind(details)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(change_set_id)
}

/// Count of staged items in a change-set, split into (open, resolved-non-defer,
/// deferred) — for the panel's "ready to apply?" summary.
pub async fn counts(pool: &PgPool, change_set_id: i64) -> Result<(i64, i64, i64), DbError> {
    let row: (i64, i64, i64) = sqlx::query_as(
        "SELECT \
           count(*) FILTER (WHERE r.id IS NULL), \
           count(*) FILTER (WHERE r.resolution_type IS NOT NULL AND r.resolution_type <> 'DEFER'), \
           count(*) FILTER (WHERE r.resolution_type = 'DEFER') \
         FROM tree.wip_haplogroup w \
         LEFT JOIN tree.wip_resolution r ON r.wip_haplogroup_id = w.id \
         WHERE w.change_set_id = $1",
    )
    .bind(change_set_id)
    .fetch_one(pool)
    .await?;
    Ok(row)
}
