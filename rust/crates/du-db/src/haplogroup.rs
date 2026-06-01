//! Queries for `tree.haplogroup` + current parent/child edges. These back the
//! Y/MT tree views. "Current" edges are those with `valid_until IS NULL`.

use crate::{parse_pg_enum, pg_enum_label, DbError, Page};
use du_domain::enums::DnaType;
use du_domain::haplogroup::Haplogroup;
use du_domain::ids::HaplogroupId;
use sqlx::PgPool;

#[derive(sqlx::FromRow)]
struct HaplogroupRow {
    id: i64,
    name: String,
    haplogroup_type: String,
    lineage: Option<String>,
    source: Option<String>,
    confidence_level: Option<String>,
    formed_ybp: Option<i32>,
    tmrca_ybp: Option<i32>,
    provenance: serde_json::Value,
}

impl HaplogroupRow {
    fn into_domain(self) -> Result<Haplogroup, DbError> {
        Ok(Haplogroup {
            id: HaplogroupId(self.id),
            name: self.name,
            haplogroup_type: parse_pg_enum(&self.haplogroup_type, "haplogroup_type")?,
            lineage: self.lineage,
            source: self.source,
            confidence_level: self.confidence_level,
            formed_ybp: self.formed_ybp,
            tmrca_ybp: self.tmrca_ybp,
            provenance: self.provenance,
        })
    }
}

// Columns qualified with alias `h` so joins (which also carry an `id`) stay
// unambiguous; output column names still match HaplogroupRow's fields.
const COLS: &str = "h.id, h.name, h.haplogroup_type::text AS haplogroup_type, h.lineage, h.source, \
    h.confidence_level, h.formed_ybp, h.tmrca_ybp, h.provenance";

fn collect(rows: Vec<HaplogroupRow>) -> Result<Vec<Haplogroup>, DbError> {
    rows.into_iter().map(HaplogroupRow::into_domain).collect()
}

pub async fn get_by_id(pool: &PgPool, id: HaplogroupId) -> Result<Option<Haplogroup>, DbError> {
    let row: Option<HaplogroupRow> =
        sqlx::query_as(&format!("SELECT {COLS} FROM tree.haplogroup h WHERE h.id = $1"))
            .bind(id.0)
            .fetch_optional(pool)
            .await?;
    row.map(HaplogroupRow::into_domain).transpose()
}

pub async fn get_by_name(
    pool: &PgPool,
    name: &str,
    dna_type: DnaType,
) -> Result<Option<Haplogroup>, DbError> {
    let row: Option<HaplogroupRow> = sqlx::query_as(&format!(
        "SELECT {COLS} FROM tree.haplogroup h WHERE h.name = $1 AND h.haplogroup_type::text = $2"
    ))
    .bind(name)
    .bind(pg_enum_label(&dna_type)?)
    .fetch_optional(pool)
    .await?;
    row.map(HaplogroupRow::into_domain).transpose()
}

/// Direct children of a haplogroup (current edges), ordered by name.
pub async fn children(pool: &PgPool, parent: HaplogroupId) -> Result<Vec<Haplogroup>, DbError> {
    let rows: Vec<HaplogroupRow> = sqlx::query_as(&format!(
        "SELECT {COLS} FROM tree.haplogroup h \
         JOIN tree.haplogroup_relationship r ON r.child_haplogroup_id = h.id \
         WHERE r.parent_haplogroup_id = $1 AND r.valid_until IS NULL \
         ORDER BY h.name"
    ))
    .bind(parent.0)
    .fetch_all(pool)
    .await?;
    collect(rows)
}

/// Flat, paginated, optionally name-filtered / lineage-filtered list for the
/// curator UI (the public tree views use roots/children instead).
pub async fn list_paginated(
    pool: &PgPool,
    query: Option<&str>,
    dna_type: Option<DnaType>,
    page: i64,
    page_size: i64,
) -> Result<Page<Haplogroup>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let like = query
        .map(str::trim)
        .filter(|q| !q.is_empty())
        .map(|q| format!("%{q}%"));
    let dna = dna_type.map(|d| pg_enum_label(&d)).transpose()?;

    // $1 = name filter (NULL = any), $2 = dna label (NULL = any).
    let where_sql = "WHERE ($1::text IS NULL OR h.name ILIKE $1) \
                     AND ($2::text IS NULL OR h.haplogroup_type::text = $2)";

    let total: i64 = sqlx::query_scalar(&format!(
        "SELECT count(*) FROM tree.haplogroup h {where_sql}"
    ))
    .bind(&like)
    .bind(&dna)
    .fetch_one(pool)
    .await?;

    let rows: Vec<HaplogroupRow> = sqlx::query_as(&format!(
        "SELECT {COLS} FROM tree.haplogroup h {where_sql} ORDER BY h.name LIMIT $3 OFFSET $4"
    ))
    .bind(&like)
    .bind(&dna)
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;

    Ok(Page { items: collect(rows)?, total, page: page.max(1), page_size: limit })
}

/// Create a haplogroup; returns the new id.
pub async fn create(
    pool: &PgPool,
    name: &str,
    dna_type: DnaType,
    lineage: Option<&str>,
    source: Option<&str>,
    formed_ybp: Option<i32>,
    tmrca_ybp: Option<i32>,
) -> Result<HaplogroupId, DbError> {
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type, lineage, source, formed_ybp, tmrca_ybp) \
         VALUES ($1, $2::core.dna_type, $3, $4, $5, $6) RETURNING id",
    )
    .bind(name)
    .bind(pg_enum_label(&dna_type)?)
    .bind(lineage)
    .bind(source)
    .bind(formed_ybp)
    .bind(tmrca_ybp)
    .fetch_one(pool)
    .await?;
    Ok(HaplogroupId(id))
}

/// Update editable haplogroup fields. Returns whether a row was affected.
pub async fn update(
    pool: &PgPool,
    id: HaplogroupId,
    name: &str,
    lineage: Option<&str>,
    source: Option<&str>,
    formed_ybp: Option<i32>,
    tmrca_ybp: Option<i32>,
) -> Result<bool, DbError> {
    let affected = sqlx::query(
        "UPDATE tree.haplogroup SET name=$2, lineage=$3, source=$4, formed_ybp=$5, tmrca_ybp=$6 WHERE id=$1",
    )
    .bind(id.0)
    .bind(name)
    .bind(lineage)
    .bind(source)
    .bind(formed_ybp)
    .bind(tmrca_ybp)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(affected > 0)
}

/// Whether the haplogroup participates in any current relationship (a guard the
/// curator UI uses before allowing deletion).
pub async fn has_current_edges(pool: &PgPool, id: HaplogroupId) -> Result<bool, DbError> {
    let n: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_relationship \
         WHERE (child_haplogroup_id = $1 OR parent_haplogroup_id = $1) AND valid_until IS NULL",
    )
    .bind(id.0)
    .fetch_one(pool)
    .await?;
    Ok(n > 0)
}

/// Delete a haplogroup. Returns whether a row was removed.
pub async fn delete(pool: &PgPool, id: HaplogroupId) -> Result<bool, DbError> {
    let affected = sqlx::query("DELETE FROM tree.haplogroup WHERE id=$1")
        .bind(id.0)
        .execute(pool)
        .await?
        .rows_affected();
    Ok(affected > 0)
}

/// Root haplogroups of a lineage: no current edge to a parent.
pub async fn roots(pool: &PgPool, dna_type: DnaType) -> Result<Vec<Haplogroup>, DbError> {
    let rows: Vec<HaplogroupRow> = sqlx::query_as(&format!(
        "SELECT {COLS} FROM tree.haplogroup h \
         WHERE h.haplogroup_type::text = $1 \
           AND NOT EXISTS ( \
             SELECT 1 FROM tree.haplogroup_relationship r \
             WHERE r.child_haplogroup_id = h.id AND r.parent_haplogroup_id IS NOT NULL \
               AND r.valid_until IS NULL) \
         ORDER BY h.name"
    ))
    .bind(pg_enum_label(&dna_type)?)
    .fetch_all(pool)
    .await?;
    collect(rows)
}

/// A node in a subtree fetch: the haplogroup plus its current parent (`None` at
/// the subtree root). The JSON tree API assembles the nesting in-process.
#[derive(Debug, Clone)]
pub struct SubtreeNode {
    pub id: i64,
    pub name: String,
    pub parent_id: Option<i64>,
    pub haplogroup_type: String,
    pub formed_ybp: Option<i32>,
    pub tmrca_ybp: Option<i32>,
}

/// All haplogroups in the subtree under `root_name` (or every root of the
/// lineage when `None`), following current edges (`valid_until IS NULL`), as a
/// flat list with parent linkage.
pub async fn subtree(
    pool: &PgPool,
    dna_type: DnaType,
    root_name: Option<&str>,
) -> Result<Vec<SubtreeNode>, DbError> {
    #[derive(sqlx::FromRow)]
    struct SubRow {
        id: i64,
        name: String,
        parent_id: Option<i64>,
        haplogroup_type: String,
        formed_ybp: Option<i32>,
        tmrca_ybp: Option<i32>,
    }
    let rows: Vec<SubRow> = sqlx::query_as(
        "WITH RECURSIVE sub AS ( \
            SELECT h.id, h.name, NULL::bigint AS parent_id, h.haplogroup_type::text AS haplogroup_type, \
                   h.formed_ybp, h.tmrca_ybp \
            FROM tree.haplogroup h \
            WHERE h.haplogroup_type::text = $1 AND ( \
              ($2::text IS NULL AND NOT EXISTS ( \
                 SELECT 1 FROM tree.haplogroup_relationship r \
                 WHERE r.child_haplogroup_id = h.id AND r.parent_haplogroup_id IS NOT NULL \
                   AND r.valid_until IS NULL)) \
              OR ($2::text IS NOT NULL AND h.name = $2)) \
          UNION ALL \
            SELECT c.id, c.name, r.parent_haplogroup_id, c.haplogroup_type::text, c.formed_ybp, c.tmrca_ybp \
            FROM tree.haplogroup c \
            JOIN tree.haplogroup_relationship r \
              ON r.child_haplogroup_id = c.id AND r.valid_until IS NULL \
            JOIN sub ON sub.id = r.parent_haplogroup_id) \
         SELECT id, name, parent_id, haplogroup_type, formed_ybp, tmrca_ybp FROM sub",
    )
    .bind(pg_enum_label(&dna_type)?)
    .bind(root_name)
    .fetch_all(pool)
    .await?;
    Ok(rows
        .into_iter()
        .map(|r| SubtreeNode {
            id: r.id,
            name: r.name,
            parent_id: r.parent_id,
            haplogroup_type: r.haplogroup_type,
            formed_ybp: r.formed_ybp,
            tmrca_ybp: r.tmrca_ybp,
        })
        .collect())
}
