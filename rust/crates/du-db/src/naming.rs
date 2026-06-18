//! Variant **Naming Authority** (planning/variant-naming-authority.md). DecodingUs
//! owns the `DU` Y-variant name prefix. Variants may exist before they have an
//! official name (discovered by coordinates → `naming_status = UNNAMED`,
//! `canonical_name = NULL`). A curator works the naming queue: reuse an
//! established name where one exists, else **mint** a `DUxxxxx` identifier from
//! `core.du_variant_name_seq` and publish (`NAMED`).
//!
//! Lifecycle: `UNNAMED` → (`PENDING_REVIEW`) → `NAMED`. Minting is the only path
//! that sets a `DU` canonical name; the old working name (if any) is preserved as
//! an alias.

use crate::{DbError, Page};
use serde_json::Value;
use sqlx::PgPool;

/// A variant in the naming queue (named or awaiting a name).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct NamingItem {
    pub id: i64,
    /// `None` for a truly-unnamed (coordinate-only) variant.
    pub canonical_name: Option<String>,
    pub naming_status: String,
    pub mutation_type: String,
    pub coordinates: Value,
    pub aliases: Value,
    /// A haplogroup this variant currently defines (context), if any.
    pub defining: Option<String>,
}

const ITEM_COLS: &str = "v.id, v.canonical_name, v.naming_status::text AS naming_status, \
    v.mutation_type::text AS mutation_type, v.coordinates, v.aliases, \
    (SELECT h.name FROM tree.haplogroup_variant hv \
       JOIN tree.haplogroup h ON h.id = hv.haplogroup_id AND h.valid_until IS NULL \
       WHERE hv.variant_id = v.id AND hv.valid_until IS NULL ORDER BY h.name LIMIT 1) AS defining";

/// The SQL predicate for a queue `mode`. The default **needs_name** queue is the
/// actionable set: variants with no name yet (discovery output) or explicitly
/// flagged for review — NOT the whole `UNNAMED` backlog (most imported variants
/// default to UNNAMED but already carry an established name, which the authority
/// reuses rather than re-minting). Other modes browse by raw status / all.
fn mode_predicate(mode: &str) -> &'static str {
    match mode {
        "NAMED" => "v.naming_status = 'NAMED'",
        "PENDING_REVIEW" => "v.naming_status = 'PENDING_REVIEW'",
        // The imported backlog: has a name but DU hasn't ratified it.
        "UNNAMED" => "v.naming_status = 'UNNAMED' AND v.canonical_name IS NOT NULL",
        "all" => "TRUE",
        // needs_name (default)
        _ => "(v.canonical_name IS NULL OR v.naming_status = 'PENDING_REVIEW')",
    }
}

/// Paginated naming queue. `mode` ∈ {needs_name (default), PENDING_REVIEW, NAMED,
/// UNNAMED (named-but-unratified backlog), all}. Unnamed first, then by name.
pub async fn queue(
    pool: &PgPool,
    mode: &str,
    page: i64,
    page_size: i64,
) -> Result<Page<NamingItem>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let pred = mode_predicate(mode);

    let total: i64 = sqlx::query_scalar(&format!("SELECT count(*) FROM core.variant v WHERE {pred}"))
        .fetch_one(pool)
        .await?;
    let items: Vec<NamingItem> = sqlx::query_as(&format!(
        "SELECT {ITEM_COLS} FROM core.variant v WHERE {pred} \
         ORDER BY v.canonical_name NULLS FIRST, v.id LIMIT $1 OFFSET $2"
    ))
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    Ok(Page { items, total, page: page.max(1), page_size: limit })
}

pub async fn get(pool: &PgPool, id: i64) -> Result<Option<NamingItem>, DbError> {
    Ok(sqlx::query_as(&format!("SELECT {ITEM_COLS} FROM core.variant v WHERE v.id = $1"))
        .bind(id)
        .fetch_optional(pool)
        .await?)
}

/// Set a variant's naming status (e.g. flag for review or send back to unnamed).
/// Does not touch the name. Returns whether a row changed.
pub async fn set_status(pool: &PgPool, id: i64, status: &str) -> Result<bool, DbError> {
    let n = sqlx::query(
        "UPDATE core.variant SET naming_status = $2::core.naming_status, updated_at = now() WHERE id = $1",
    )
    .bind(id)
    .bind(status)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n > 0)
}

/// **Mint a DU name** for a variant: take the next `DUxxxxx` from the authority
/// sequence, set it as `canonical_name`, mark `NAMED`. Any prior working name is
/// preserved in `aliases.common_names`. Refuses a variant already `NAMED`.
/// Returns the minted name.
pub async fn assign_du_name(pool: &PgPool, id: i64) -> Result<String, DbError> {
    let mut tx = pool.begin().await?;
    let row: Option<(Option<String>, String)> =
        sqlx::query_as("SELECT canonical_name, naming_status::text FROM core.variant WHERE id = $1 FOR UPDATE")
            .bind(id)
            .fetch_optional(&mut *tx)
            .await?;
    let (old_name, status) = row.ok_or_else(|| DbError::Conflict(format!("variant {id} not found")))?;
    if status == "NAMED" {
        return Err(DbError::Conflict("variant is already NAMED".into()));
    }
    let du: String = sqlx::query_scalar("SELECT core.next_du_name()").fetch_one(&mut *tx).await?;

    // Preserve any prior working name as a common-name alias (union, deduped).
    if let Some(prev) = old_name.filter(|n| !n.trim().is_empty() && *n != du) {
        sqlx::query(
            "UPDATE core.variant SET aliases = jsonb_set( \
                COALESCE(aliases, '{}'::jsonb), '{common_names}', \
                (SELECT COALESCE(jsonb_agg(DISTINCT a), '[]'::jsonb) FROM ( \
                   SELECT jsonb_array_elements_text(COALESCE(aliases->'common_names', '[]'::jsonb)) AS a \
                   UNION SELECT $2) u), true) \
             WHERE id = $1",
        )
        .bind(id)
        .bind(&prev)
        .execute(&mut *tx)
        .await?;
    }
    sqlx::query(
        "UPDATE core.variant SET canonical_name = $2, naming_status = 'NAMED', updated_at = now() WHERE id = $1",
    )
    .bind(id)
    .bind(&du)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(du)
}

/// **Dedup check** before minting: other *named* variants sharing this variant's
/// GRCh38 coordinate (contig + position). A non-empty result means an
/// established name likely already exists — reuse it instead of minting.
pub async fn dedup_by_coordinates(pool: &PgPool, id: i64) -> Result<Vec<(i64, String)>, DbError> {
    Ok(sqlx::query_as(
        "WITH me AS (SELECT coordinates->'GRCh38'->>'contig' AS c, coordinates->'GRCh38'->>'position' AS p \
                     FROM core.variant WHERE id = $1) \
         SELECT v.id, v.canonical_name FROM core.variant v, me \
         WHERE v.id <> $1 AND v.canonical_name IS NOT NULL AND me.c IS NOT NULL AND me.p IS NOT NULL \
           AND v.coordinates->'GRCh38'->>'contig' = me.c \
           AND v.coordinates->'GRCh38'->>'position' = me.p \
         ORDER BY v.canonical_name LIMIT 10",
    )
    .bind(id)
    .fetch_all(pool)
    .await?)
}
