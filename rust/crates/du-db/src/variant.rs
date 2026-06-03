//! Queries for `core.variant`. Demonstrates the du-db mapping pattern:
//! enum columns are fetched as `::text` and parsed via serde; JSONB columns are
//! read through `sqlx::types::Json<T>` into the du-domain payload structs.

use crate::{parse_pg_enum, pg_enum_label, DbError, Page};
use du_domain::enums::{MutationType, NamingStatus};
use du_domain::ids::VariantId;
use du_domain::variant::{Aliases, Annotations, Coordinates, NewVariant, Variant};
use sqlx::types::Json;
use sqlx::PgPool;

#[derive(sqlx::FromRow)]
struct VariantRow {
    id: i64,
    canonical_name: String,
    mutation_type: String,
    naming_status: String,
    aliases: Json<Aliases>,
    coordinates: Json<Coordinates>,
    annotations: Json<Annotations>,
}

impl VariantRow {
    fn into_domain(self) -> Result<Variant, DbError> {
        Ok(Variant {
            id: VariantId(self.id),
            canonical_name: self.canonical_name,
            mutation_type: parse_pg_enum(&self.mutation_type, "mutation_type")?,
            naming_status: parse_pg_enum(&self.naming_status, "naming_status")?,
            aliases: self.aliases.0,
            coordinates: self.coordinates.0,
            annotations: self.annotations.0,
        })
    }
}

const SELECT: &str = "SELECT id, canonical_name, mutation_type::text AS mutation_type, \
    naming_status::text AS naming_status, aliases, coordinates, annotations FROM core.variant";

pub async fn get_by_id(pool: &PgPool, id: VariantId) -> Result<Option<Variant>, DbError> {
    let row: Option<VariantRow> = sqlx::query_as(&format!("{SELECT} WHERE id = $1"))
        .bind(id.0)
        .fetch_optional(pool)
        .await?;
    row.map(VariantRow::into_domain).transpose()
}

/// Create a variant (scalar fields + aliases; coordinates/annotations default
/// empty and are managed elsewhere). Returns the new id.
pub async fn create(
    pool: &PgPool,
    canonical_name: &str,
    mutation_type: MutationType,
    naming_status: NamingStatus,
    aliases: &Aliases,
) -> Result<VariantId, DbError> {
    let aliases_json = serde_json::to_value(aliases).map_err(|e| DbError::Decode(e.to_string()))?;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, aliases) \
         VALUES ($1, $2::core.mutation_type, $3::core.naming_status, $4) RETURNING id",
    )
    .bind(canonical_name)
    .bind(pg_enum_label(&mutation_type)?)
    .bind(pg_enum_label(&naming_status)?)
    .bind(aliases_json)
    .fetch_one(pool)
    .await?;
    Ok(VariantId(id))
}

/// Update a variant's scalar fields + aliases. Coordinates and annotations are
/// left untouched. Returns whether a row was affected.
pub async fn update(
    pool: &PgPool,
    id: VariantId,
    canonical_name: &str,
    mutation_type: MutationType,
    naming_status: NamingStatus,
    aliases: &Aliases,
) -> Result<bool, DbError> {
    let aliases_json = serde_json::to_value(aliases).map_err(|e| DbError::Decode(e.to_string()))?;
    let affected = sqlx::query(
        "UPDATE core.variant SET canonical_name=$2, mutation_type=$3::core.mutation_type, \
         naming_status=$4::core.naming_status, aliases=$5, updated_at=now() WHERE id=$1",
    )
    .bind(id.0)
    .bind(canonical_name)
    .bind(pg_enum_label(&mutation_type)?)
    .bind(pg_enum_label(&naming_status)?)
    .bind(aliases_json)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(affected > 0)
}

/// Upsert a variant by canonical name (the ingestion path, e.g. YBrowse).
/// Updates mutation_type, aliases, and the multi-build coordinates; preserves
/// the existing `naming_status` (curator-owned). Returns the variant id.
pub async fn upsert_by_name(pool: &PgPool, v: &NewVariant) -> Result<VariantId, DbError> {
    let aliases = serde_json::to_value(&v.aliases).map_err(|e| DbError::Decode(e.to_string()))?;
    let coords = serde_json::to_value(&v.coordinates).map_err(|e| DbError::Decode(e.to_string()))?;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type, aliases, coordinates) \
         VALUES ($1, $2::core.mutation_type, $3, $4) \
         ON CONFLICT (canonical_name) WHERE canonical_name IS NOT NULL \
         DO UPDATE SET mutation_type = EXCLUDED.mutation_type, \
           aliases = EXCLUDED.aliases, coordinates = EXCLUDED.coordinates, updated_at = now() \
         RETURNING id",
    )
    .bind(&v.canonical_name)
    .bind(pg_enum_label(&v.mutation_type)?)
    .bind(aliases)
    .bind(coords)
    .fetch_one(pool)
    .await?;
    Ok(VariantId(id))
}

/// Whether the variant is referenced by a current haplogroup association
/// (a guard before deletion).
pub async fn is_referenced(pool: &PgPool, id: VariantId) -> Result<bool, DbError> {
    let n: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_variant WHERE variant_id = $1 AND valid_until IS NULL",
    )
    .bind(id.0)
    .fetch_one(pool)
    .await?;
    Ok(n > 0)
}

pub async fn delete(pool: &PgPool, id: VariantId) -> Result<bool, DbError> {
    let affected = sqlx::query("DELETE FROM core.variant WHERE id=$1")
        .bind(id.0)
        .execute(pool)
        .await?
        .rows_affected();
    Ok(affected > 0)
}

/// Paginated search by canonical name OR any alias in the `common_names`/`rs_ids`
/// JSONB arrays (the public variant browser). `query = None`/empty lists all.
pub async fn search(
    pool: &PgPool,
    query: Option<&str>,
    page: i64,
    page_size: i64,
) -> Result<Page<Variant>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let term = query.map(str::trim).filter(|q| !q.is_empty());

    // Matches canonical_name or any element of the alias arrays, case-insensitive.
    // Unnamed variants (canonical_name IS NULL) are pre-publication — excluded
    // from the public browser; they live in the naming queue (`du_db::naming`).
    const FILTER: &str = "WHERE canonical_name IS NOT NULL AND (canonical_name ILIKE $1 \
        OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(aliases->'common_names') a WHERE a ILIKE $1) \
        OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(aliases->'rs_ids') r WHERE r ILIKE $1))";

    let (total, rows): (i64, Vec<VariantRow>) = if let Some(t) = term {
        let like = format!("%{t}%");
        let total: i64 = sqlx::query_scalar(&format!("SELECT count(*) FROM core.variant {FILTER}"))
            .bind(&like)
            .fetch_one(pool)
            .await?;
        let rows = sqlx::query_as(&format!(
            "{SELECT} {FILTER} ORDER BY canonical_name LIMIT $2 OFFSET $3"
        ))
        .bind(&like)
        .bind(limit)
        .bind(offset)
        .fetch_all(pool)
        .await?;
        (total, rows)
    } else {
        let total: i64 = sqlx::query_scalar("SELECT count(*) FROM core.variant WHERE canonical_name IS NOT NULL")
            .fetch_one(pool)
            .await?;
        let rows = sqlx::query_as(&format!(
            "{SELECT} WHERE canonical_name IS NOT NULL ORDER BY canonical_name LIMIT $1 OFFSET $2"
        ))
        .bind(limit)
        .bind(offset)
        .fetch_all(pool)
        .await?;
        (total, rows)
    };

    let items = rows
        .into_iter()
        .map(VariantRow::into_domain)
        .collect::<Result<Vec<_>, _>>()?;
    Ok(Page { items, total, page: page.max(1), page_size: limit })
}

/// Variants currently associated with a haplogroup (by name), via the current
/// `tree.haplogroup_variant` edges (`valid_until IS NULL`).
pub async fn for_haplogroup_name(pool: &PgPool, name: &str) -> Result<Vec<Variant>, DbError> {
    let rows: Vec<VariantRow> = sqlx::query_as(
        "SELECT v.id, v.canonical_name, v.mutation_type::text AS mutation_type, \
                v.naming_status::text AS naming_status, v.aliases, v.coordinates, v.annotations \
         FROM core.variant v \
         JOIN tree.haplogroup_variant hv ON hv.variant_id = v.id AND hv.valid_until IS NULL \
         JOIN tree.haplogroup h ON h.id = hv.haplogroup_id \
         WHERE h.name = $1 AND v.canonical_name IS NOT NULL ORDER BY v.canonical_name",
    )
    .bind(name)
    .fetch_all(pool)
    .await?;
    rows.into_iter().map(VariantRow::into_domain).collect()
}

/// Total NAMED variant count (for the export metadata endpoint).
pub async fn count(pool: &PgPool) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar("SELECT count(*) FROM core.variant WHERE canonical_name IS NOT NULL")
        .fetch_one(pool)
        .await?)
}

/// Every NAMED variant, ordered by canonical name (backs the live CSV export).
/// Loads the full catalog into memory; fine at current scale.
pub async fn export_all(pool: &PgPool) -> Result<Vec<Variant>, DbError> {
    let rows: Vec<VariantRow> =
        sqlx::query_as(&format!("{SELECT} WHERE canonical_name IS NOT NULL ORDER BY canonical_name"))
            .fetch_all(pool)
            .await?;
    rows.into_iter().map(VariantRow::into_domain).collect()
}

/// Every DU-minted variant (canonical_name like `DU%`) carrying a GRCh38
/// coordinate, for the Naming-Authority propagation export (GFF3/VCF → YBrowse).
pub async fn export_du_named(pool: &PgPool) -> Result<Vec<Variant>, DbError> {
    let rows: Vec<VariantRow> = sqlx::query_as(&format!(
        "{SELECT} WHERE canonical_name LIKE 'DU%' AND coordinates ? 'GRCh38' ORDER BY canonical_name"
    ))
    .fetch_all(pool)
    .await?;
    rows.into_iter().map(VariantRow::into_domain).collect()
}

/// Bulk-populate `core.variant.aliases.common_names` for the given canonical
/// names (one physical SNP per row, the universal-variant model). Canonicals
/// not present are skipped. Chunked `unnest` upserts; returns rows updated.
pub async fn set_aliases_bulk(pool: &PgPool, items: &[(String, Vec<String>)]) -> Result<u64, DbError> {
    let mut updated = 0u64;
    for chunk in items.chunks(1000) {
        let names: Vec<&str> = chunk.iter().map(|(n, _)| n.as_str()).collect();
        let jsons: Vec<String> =
            chunk.iter().map(|(_, a)| serde_json::json!({ "common_names": a }).to_string()).collect();
        updated += sqlx::query(
            "UPDATE core.variant v SET aliases = u.al::jsonb \
             FROM (SELECT unnest($1::text[]) AS nm, unnest($2::text[]) AS al) u \
             WHERE v.canonical_name = u.nm",
        )
        .bind(&names)
        .bind(&jsons)
        .execute(pool)
        .await?
        .rows_affected();
    }
    Ok(updated)
}
