//! Queries for `core.genome_region` (curator-managed multi-build regions).

use crate::{DbError, Page};
use du_domain::genome_region::GenomeRegion;
use sqlx::PgPool;

#[derive(sqlx::FromRow)]
struct RegionRow {
    id: i64,
    region_type: String,
    name: String,
    coordinates: serde_json::Value,
    properties: serde_json::Value,
}

impl From<RegionRow> for GenomeRegion {
    fn from(r: RegionRow) -> Self {
        GenomeRegion {
            id: r.id,
            region_type: r.region_type,
            name: r.name,
            coordinates: r.coordinates,
            properties: r.properties,
        }
    }
}

const SELECT: &str = "SELECT id, region_type, name, coordinates, properties FROM core.genome_region";

/// Distinct reference builds present across all regions' `coordinates` keys
/// (e.g. ["GRCh37","GRCh38","hs1"]).
pub async fn distinct_builds(pool: &PgPool) -> Result<Vec<String>, DbError> {
    Ok(sqlx::query_scalar(
        "SELECT DISTINCT jsonb_object_keys(coordinates) AS build FROM core.genome_region \
         WHERE coordinates <> '{}'::jsonb ORDER BY build",
    )
    .fetch_all(pool)
    .await?)
}

/// Regions that carry coordinates for the given build.
pub async fn for_build(pool: &PgPool, build: &str) -> Result<Vec<GenomeRegion>, DbError> {
    let rows: Vec<RegionRow> = sqlx::query_as(&format!(
        "{SELECT} WHERE jsonb_exists(coordinates, $1) ORDER BY region_type, name"
    ))
    .bind(build)
    .fetch_all(pool)
    .await?;
    Ok(rows.into_iter().map(Into::into).collect())
}

pub async fn get_by_id(pool: &PgPool, id: i64) -> Result<Option<GenomeRegion>, DbError> {
    let row: Option<RegionRow> = sqlx::query_as(&format!("{SELECT} WHERE id = $1"))
        .bind(id)
        .fetch_optional(pool)
        .await?;
    Ok(row.map(Into::into))
}

/// Paginated list, optionally filtered by name/type substring and region type.
pub async fn list_paginated(
    pool: &PgPool,
    query: Option<&str>,
    region_type: Option<&str>,
    page: i64,
    page_size: i64,
) -> Result<Page<GenomeRegion>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let like = query.map(str::trim).filter(|q| !q.is_empty()).map(|q| format!("%{q}%"));
    let rtype = region_type.map(str::trim).filter(|q| !q.is_empty()).map(str::to_string);

    let where_sql = "WHERE ($1::text IS NULL OR name ILIKE $1 OR region_type ILIKE $1) \
                     AND ($2::text IS NULL OR region_type = $2)";

    let total: i64 = sqlx::query_scalar(&format!("SELECT count(*) FROM core.genome_region {where_sql}"))
        .bind(&like)
        .bind(&rtype)
        .fetch_one(pool)
        .await?;
    let rows: Vec<RegionRow> =
        sqlx::query_as(&format!("{SELECT} {where_sql} ORDER BY region_type, name LIMIT $3 OFFSET $4"))
            .bind(&like)
            .bind(&rtype)
            .bind(limit)
            .bind(offset)
            .fetch_all(pool)
            .await?;

    Ok(Page {
        items: rows.into_iter().map(Into::into).collect(),
        total,
        page: page.max(1),
        page_size: limit,
    })
}

pub async fn create(
    pool: &PgPool,
    region_type: &str,
    name: &str,
    coordinates: &serde_json::Value,
    properties: &serde_json::Value,
) -> Result<i64, DbError> {
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO core.genome_region (region_type, name, coordinates, properties) \
         VALUES ($1, $2, $3, $4) RETURNING id",
    )
    .bind(region_type)
    .bind(name)
    .bind(coordinates)
    .bind(properties)
    .fetch_one(pool)
    .await?;
    Ok(id)
}

/// Insert or update a region keyed on its unique `(region_type, name)`,
/// returning `true` when a new row was inserted and `false` when an existing
/// one was updated. Used by the Y-region reference ingest so re-runs are
/// idempotent (see `du_jobs::yregions`).
pub async fn upsert_by_key(
    pool: &PgPool,
    region_type: &str,
    name: &str,
    coordinates: &serde_json::Value,
    properties: &serde_json::Value,
) -> Result<bool, DbError> {
    let inserted: bool = sqlx::query_scalar(
        "INSERT INTO core.genome_region (region_type, name, coordinates, properties) \
         VALUES ($1, $2, $3, $4) \
         ON CONFLICT (region_type, name) DO UPDATE \
           SET coordinates = EXCLUDED.coordinates, properties = EXCLUDED.properties, updated_at = now() \
         RETURNING (xmax = 0)",
    )
    .bind(region_type)
    .bind(name)
    .bind(coordinates)
    .bind(properties)
    .fetch_one(pool)
    .await?;
    Ok(inserted)
}

/// Delete reference rows for `source` whose `name` is not in `keep`. Prunes
/// orphans left when a source's regions change — e.g. a versioned BED bump that
/// shifts coordinates mints new locus-qualified names, leaving the old rows
/// behind. Returns the number removed. Paired with [`upsert_by_key`] this gives
/// the Y-region load full-snapshot (sync) semantics.
pub async fn prune_source_orphans(pool: &PgPool, source: &str, keep: &[String]) -> Result<u64, DbError> {
    let removed = sqlx::query(
        "DELETE FROM core.genome_region \
         WHERE properties->>'source' = $1 AND name <> ALL($2::text[])",
    )
    .bind(source)
    .bind(keep)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(removed)
}

pub async fn update(
    pool: &PgPool,
    id: i64,
    region_type: &str,
    name: &str,
    coordinates: &serde_json::Value,
    properties: &serde_json::Value,
) -> Result<bool, DbError> {
    let affected = sqlx::query(
        "UPDATE core.genome_region SET region_type=$2, name=$3, coordinates=$4, properties=$5, updated_at=now() WHERE id=$1",
    )
    .bind(id)
    .bind(region_type)
    .bind(name)
    .bind(coordinates)
    .bind(properties)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(affected > 0)
}

pub async fn delete(pool: &PgPool, id: i64) -> Result<bool, DbError> {
    let affected = sqlx::query("DELETE FROM core.genome_region WHERE id=$1")
        .bind(id)
        .execute(pool)
        .await?
        .rows_affected();
    Ok(affected > 0)
}
