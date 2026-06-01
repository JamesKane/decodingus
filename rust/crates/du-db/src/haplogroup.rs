//! Queries for `tree.haplogroup` + current parent/child edges. These back the
//! Y/MT tree views. "Current" edges are those with `valid_until IS NULL`.

use crate::{parse_pg_enum, pg_enum_label, DbError};
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
