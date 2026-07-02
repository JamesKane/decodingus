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
        // needs_name (default): the actionable naming backlog. The DU authority names
        // Y-DNA variants only — mtDNA variants are identified by their [anc]pos[der]
        // coordinate string and never get a DU name, so they're excluded. A canonical
        // name is (name + the branch it defines), so only a variant that DEFINES a
        // (Y) branch is nameable — branch-less catalog rows are reference data, not
        // naming work. "Needs a name" = defines a Y branch AND has no real name yet:
        // no name, a synthetic coordinate placeholder (`chrY:pos…`), or flagged.
        _ => "v.defining_haplogroup_id IS NOT NULL \
              AND EXISTS (SELECT 1 FROM tree.haplogroup h \
                          WHERE h.id = v.defining_haplogroup_id AND h.haplogroup_type = 'Y_DNA'::core.dna_type) \
              AND (v.canonical_name IS NULL OR v.canonical_name LIKE 'chr%:%' \
                   OR v.naming_status = 'PENDING_REVIEW')",
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

/// Whether a `canonical_name` is a **synthetic coordinate placeholder** written by
/// the de-novo loader (`chrY:pos anc->der`) rather than a real, ratified name. The
/// loader stamps every branch-defining variant `NAMED` with such a stand-in, so the
/// authority must treat these as *unnamed* — they're eligible to mint/adopt, and
/// must not display as "already named". Mirrors the SQL `canonical_name LIKE 'chr%:%'`
/// the needs_name queue filters on. Real Y-SNP names never start with `chr`.
pub fn is_placeholder_name(name: &str) -> bool {
    name.starts_with("chr") && name.contains(':')
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
    // A synthetic coordinate placeholder (`chrY:…`) counts as unnamed even though the
    // loader marked it NAMED — only a *real* ratified name blocks re-minting.
    let real_named = old_name.as_deref().is_some_and(|n| !is_placeholder_name(n));
    if status == "NAMED" && real_named {
        return Err(DbError::Conflict("variant is already NAMED".into()));
    }
    let du: String = sqlx::query_scalar("SELECT core.next_du_name()").fetch_one(&mut *tx).await?;

    // Preserve any prior *real* working name as a common-name alias (union, deduped).
    // A placeholder coordinate string is not a name, so it's dropped rather than kept.
    if let Some(prev) = old_name.filter(|n| !n.trim().is_empty() && *n != du && !is_placeholder_name(n)) {
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
    // Set the DU name and, in the same write, purge any synthetic coordinate placeholders
    // (`chr…:…`) the loader left in common_names — they were never real alternate names.
    sqlx::query(
        "UPDATE core.variant SET canonical_name = $2, naming_status = 'NAMED', \
           aliases = jsonb_set(COALESCE(aliases, '{}'::jsonb), '{common_names}', \
             COALESCE((SELECT jsonb_agg(a) \
                       FROM jsonb_array_elements_text(COALESCE(aliases->'common_names', '[]'::jsonb)) a \
                       WHERE a NOT LIKE 'chr%:%'), '[]'::jsonb), true), \
           updated_at = now() \
         WHERE id = $1",
    )
    .bind(id)
    .bind(&du)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(du)
}

/// The first established (non-DU) working name among a variant's `common_names`
/// aliases — an ISOGG/YBrowse-derived name the authority can **reuse** rather than
/// re-mint. Our own `DU…` mints are skipped (they aren't external definitions).
pub fn established_name(aliases: &Value) -> Option<String> {
    aliases
        .get("common_names")?
        .as_array()?
        .iter()
        .filter_map(|x| x.as_str())
        // Skip our own DU mints and synthetic coordinate placeholders (`chrY:…`) — neither
        // is an external "named by definition" reference the authority can adopt.
        .find(|s| !s.trim().is_empty() && !s.starts_with("DU") && !is_placeholder_name(s))
        .map(str::to_string)
}

/// **Adopt an established name**: ratify a variant's existing ISOGG/YBrowse name
/// (a non-DU `common_names` alias) as its canonical name instead of minting a new
/// `DU` identifier. This is the "named by definition" path — when a matching locus
/// + mutation state already has a name in the source set, the authority reuses it.
/// Sets `canonical_name` to that name and marks `NAMED`. Errors if the variant is
/// already named or carries no established name.
pub async fn adopt_established_name(pool: &PgPool, id: i64) -> Result<String, DbError> {
    let mut tx = pool.begin().await?;
    let row: Option<(Option<String>, Value)> = sqlx::query_as(
        "SELECT canonical_name, aliases FROM core.variant WHERE id = $1 FOR UPDATE",
    )
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?;
    let (canon, aliases) =
        row.ok_or_else(|| DbError::Conflict(format!("variant {id} not found")))?;
    // A synthetic coordinate placeholder (`chrY:…`) doesn't count as a real canonical
    // name — the loader stamps it NAMED, but the variant is still adoptable.
    let real_named = canon.as_deref().is_some_and(|n| !is_placeholder_name(n));
    if real_named {
        return Err(DbError::Conflict("variant already has a canonical name".into()));
    }
    let name = established_name(&aliases)
        .ok_or_else(|| DbError::Conflict("variant has no established name to adopt".into()))?;
    // Canonical identity is (name + defining branch) — the recurrence model that
    // replaces ISOGG's L270.1/L270.2 suffixing: the same SNP name is canonical on
    // each branch it defines, scoped by `defining_haplogroup_id`. A clash only
    // exists when another row already holds this name for the *same* branch (which
    // `variant_canonical_name_key` enforces, treating NULL as one bucket). Guard it
    // so the write returns a clear notice instead of a 500.
    let taken: Option<i64> = sqlx::query_scalar(
        "SELECT o.id FROM core.variant o, core.variant me \
         WHERE me.id = $2 AND o.id <> me.id AND o.canonical_name = $1 \
           AND COALESCE(o.defining_haplogroup_id, -1) = COALESCE(me.defining_haplogroup_id, -1) LIMIT 1",
    )
    .bind(&name)
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?;
    if let Some(other) = taken {
        return Err(DbError::Conflict(format!(
            "name {name} is already canonical for this branch on variant #{other} — resolve the duplicate / recurrence in merge review before reusing it"
        )));
    }
    sqlx::query(
        "UPDATE core.variant SET canonical_name = $2, naming_status = 'NAMED', updated_at = now() WHERE id = $1",
    )
    .bind(id)
    .bind(&name)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(name)
}

/// **Dedup check** before naming: other *named* variants at the same locus **and
/// mutation state** — contig + position + ancestral + derived — on this variant's
/// preferred build (`hs1` first, else `GRCh38`). Matching by locus alone is wrong:
/// two distinct SNPs can share a position with different alleles (e.g. Z12236 A>C
/// vs Y17125 A>G), so a same-position-different-allele variant is NOT a duplicate.
pub async fn dedup_by_site(pool: &PgPool, id: i64) -> Result<Vec<(i64, String)>, DbError> {
    Ok(sqlx::query_as(
        "WITH b AS ( \
           SELECT CASE WHEN coordinates ? 'hs1' THEN 'hs1' \
                       WHEN coordinates ? 'GRCh38' THEN 'GRCh38' END AS build \
           FROM core.variant WHERE id = $1), \
         me AS ( \
           SELECT b.build, \
                  v.coordinates->b.build->>'contig'    AS c, \
                  v.coordinates->b.build->>'position'  AS p, \
                  v.coordinates->b.build->>'ancestral' AS a, \
                  v.coordinates->b.build->>'derived'   AS d \
           FROM core.variant v, b WHERE v.id = $1) \
         SELECT v.id, v.canonical_name FROM core.variant v, me \
         WHERE v.id <> $1 AND v.canonical_name IS NOT NULL AND me.build IS NOT NULL \
           AND me.c IS NOT NULL AND me.p IS NOT NULL \
           AND v.coordinates->me.build->>'contig'    = me.c \
           AND v.coordinates->me.build->>'position'  = me.p \
           AND v.coordinates->me.build->>'ancestral' IS NOT DISTINCT FROM me.a \
           AND v.coordinates->me.build->>'derived'   IS NOT DISTINCT FROM me.d \
         ORDER BY v.canonical_name LIMIT 10",
    )
    .bind(id)
    .fetch_all(pool)
    .await?)
}
