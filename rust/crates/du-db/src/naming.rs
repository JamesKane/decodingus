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

/// The SQL predicate for a `mutation_type` filter. Whitelisted against the
/// `core.mutation_type` enum — the value is interpolated straight into the SQL
/// (like [`mode_predicate`]), so an unknown value must fall through to the
/// no-op `TRUE` rather than ever reach the database. `all` (default) is every
/// type; the actionable filter is `SNP` (the high-value markers curators name
/// first) vs `INDEL` (the fiddly length-changing ones to defer).
fn type_predicate(vtype: &str) -> &'static str {
    match vtype {
        "SNP" => "v.mutation_type = 'SNP'::core.mutation_type",
        "INDEL" => "v.mutation_type = 'INDEL'::core.mutation_type",
        "STR" => "v.mutation_type = 'STR'::core.mutation_type",
        "DEL" => "v.mutation_type = 'DEL'::core.mutation_type",
        "INS" => "v.mutation_type = 'INS'::core.mutation_type",
        "MNP" => "v.mutation_type = 'MNP'::core.mutation_type",
        _ => "TRUE",
    }
}

/// Paginated naming queue. `mode` ∈ {needs_name (default), PENDING_REVIEW, NAMED,
/// UNNAMED (named-but-unratified backlog), all}. `vtype` filters by mutation type
/// (`all` = no filter, else an enum value like `SNP`/`INDEL`). Unnamed first, then
/// by name.
pub async fn queue(
    pool: &PgPool,
    mode: &str,
    vtype: &str,
    page: i64,
    page_size: i64,
) -> Result<Page<NamingItem>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let pred = mode_predicate(mode);
    let tpred = type_predicate(vtype);

    let total: i64 =
        sqlx::query_scalar(&format!("SELECT count(*) FROM core.variant v WHERE ({pred}) AND ({tpred})"))
            .fetch_one(pool)
            .await?;
    let items: Vec<NamingItem> = sqlx::query_as(&format!(
        "SELECT {ITEM_COLS} FROM core.variant v WHERE ({pred}) AND ({tpred}) \
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
/// loader stamps such a stand-in (naming_status UNNAMED) on any branch-defining variant
/// that has no real name yet, so the authority treats these as *unnamed* — eligible to
/// mint/adopt, and must not display as "already named". The needs_name queue keys on this
/// name pattern (`canonical_name LIKE 'chr%:%'`), not on naming_status. Real Y-SNP names
/// never start with `chr`.
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

/// **Adopt an established name**: ratify the name this variant already has — an
/// ISOGG/YBrowse `common_names` alias, or the name of a variant at the same locus +
/// mutation state — as its canonical name, instead of minting a new `DU` identifier.
/// This is the "named by definition" path: a marker the source set already named must
/// not get a second, DecodingUs-invented identity. Sets `canonical_name` and marks
/// `NAMED`. Errors if the variant is already named or no name is available to adopt.
///
/// Both name sources are resolved here, in [`adoptable_name`]'s order — the site twin
/// is not a fallback nicety but the *only* source for a de-novo coordinate row, which
/// carries no aliases of its own.
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
    let name = match established_name(&aliases) {
        Some(n) => n,
        None => {
            // Same site match as `dedup_by_site`, but inside this tx — build-literal so it
            // uses `variant_hs1_site_named_idx` rather than a full scan (see `site_twin_sql`).
            let build: Option<String> = sqlx::query_scalar(PREFERRED_BUILD_SQL)
                .bind(id)
                .fetch_optional(&mut *tx)
                .await?
                .flatten();
            let head = match build_literal(build.as_deref()) {
                Some(b) => sqlx::query_as::<_, (i64, String)>(&site_twin_sql(b))
                    .bind(id)
                    .fetch_all(&mut *tx)
                    .await?
                    .into_iter()
                    .next()
                    .map(|(_, n)| n),
                None => None,
            };
            head.ok_or_else(|| DbError::Conflict("variant has no established name to adopt".into()))?
        }
    };
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

/// This variant's **preferred build** — `hs1` if it carries hs1 coordinates, else `GRCh38`,
/// else NULL. The site match works in whichever build the variant is framed in.
const PREFERRED_BUILD_SQL: &str = "SELECT CASE WHEN coordinates ? 'hs1' THEN 'hs1' \
     WHEN coordinates ? 'GRCh38' THEN 'GRCh38' END FROM core.variant WHERE id = $1";

/// Whitelist a build string to a **static literal** before it is interpolated into
/// [`site_twin_sql`]. Only `hs1`/`GRCh38` reach the SQL; anything else is `None`.
fn build_literal(build: Option<&str>) -> Option<&'static str> {
    match build {
        Some("hs1") => Some("hs1"),
        Some("GRCh38") => Some("GRCh38"),
        _ => None,
    }
}

/// The site-twin lookup, with `build` as a **whitelisted literal** (`hs1`/`GRCh38`) baked
/// into the coordinate path expressions rather than read from a runtime `me.build` column.
///
/// This is not cosmetic: the partial expression index `variant_hs1_site_named_idx`
/// (migration 0068) is on `coordinates -> 'hs1' ->> …` *literals*, and Postgres only matches
/// an expression index when the query's expression is character-identical. The old
/// `v.coordinates -> me.build ->> …` form — `me.build` being a CTE column — never matched it,
/// so every panel load and every adopt did a full ~3M-row sequential scan (~1.7 s). With the
/// literal build the same lookup is an index scan (~0.3 ms). `build` MUST come from
/// [`build_literal`] so only vetted literals are interpolated.
///
/// Ordering **is the adoption preference** — the head is what [`adopt_established_name`]
/// ratifies: catalog representative first, external name over our own `DU` mint, then
/// alphabetical for determinism.
fn site_twin_sql(build: &str) -> String {
    format!(
        "SELECT v.id, v.canonical_name FROM core.variant v, \
           (SELECT coordinates->'{build}'->>'contig'    AS c, \
                   coordinates->'{build}'->>'position'  AS p, \
                   coordinates->'{build}'->>'ancestral' AS a, \
                   coordinates->'{build}'->>'derived'   AS d \
            FROM core.variant WHERE id = $1 AND coordinates ? '{build}') me \
         WHERE v.id <> $1 AND v.canonical_name IS NOT NULL \
           AND v.canonical_name NOT LIKE 'chr%:%' \
           AND v.coordinates ? '{build}' \
           AND me.c IS NOT NULL AND me.p IS NOT NULL \
           AND v.coordinates->'{build}'->>'contig'    = me.c \
           AND v.coordinates->'{build}'->>'position'  = me.p \
           AND v.coordinates->'{build}'->>'ancestral' IS NOT DISTINCT FROM me.a \
           AND v.coordinates->'{build}'->>'derived'   IS NOT DISTINCT FROM me.d \
         ORDER BY v.catalog_representative DESC, (v.canonical_name LIKE 'DU%'), v.canonical_name \
         LIMIT 10"
    )
}

/// Named site-twins of `id` — other **named** variants at the same locus **and mutation
/// state** (contig + position + ancestral + derived) on this variant's preferred build.
/// Matching by locus alone is wrong: two distinct SNPs can share a position with different
/// alleles (e.g. Z12236 A>C vs Y17125 A>G), so a same-position-different-allele variant is
/// NOT a duplicate. Coordinate placeholders (`chrY:…`) are excluded — a placeholder is not a
/// name (see [`is_placeholder_name`]). See [`site_twin_sql`] for the index note.
pub async fn dedup_by_site(pool: &PgPool, id: i64) -> Result<Vec<(i64, String)>, DbError> {
    let build: Option<String> =
        sqlx::query_scalar(PREFERRED_BUILD_SQL).bind(id).fetch_optional(pool).await?.flatten();
    let Some(build) = build_literal(build.as_deref()) else { return Ok(vec![]) };
    Ok(sqlx::query_as(&site_twin_sql(build)).bind(id).fetch_all(pool).await?)
}

/// A really-named, **tree-linked** variant that already carries `name` on a branch this
/// placeholder also defines — the row that makes an unnamed placeholder a redundant
/// duplicate. `id` is a placeholder (`chrY:…`/no name); `name` is the name it would adopt
/// (its site-twin's or alias's name — see [`adoptable_name`]).
///
/// This is the collision the naming queue can neither mint nor adopt its way out of, and it
/// is why the earlier "one row, same site AND same branch" test failed to fire: the covering
/// name is routinely **split across two rows** — a same-site catalog row with no branch
/// (offered as Reuse), and a branch-defining row a few bp away (the drift residue of an
/// un-lifted catalog variant). Neither row alone is both same-site and same-branch, so the
/// curator is stuck: Reuse hits the "already canonical for this branch" guard in
/// [`adopt_established_name`], and Mint would fork a second identity.
///
/// Keying on the **name already being canonical on a shared *tree* branch** (not on the
/// `defining_haplogroup_id` column, which the loader leaves unset — the branch lives in
/// `tree.haplogroup_variant`) matches both what the panel displays and the real stuck state.
/// The returned row is the safety property twofold: it proves the SNP is already named on
/// this branch, and — being itself tree-linked to that branch — it guarantees deleting the
/// placeholder ([`delete_erroneous_duplicate`]) cannot leave the branch undefined.
///
/// `Some((twin_id, twin_name))` ⇒ `id` is a redundant placeholder safe to delete; `None` ⇒
/// it is not, and must be named/adopted instead.
const BRANCH_COVERING_SQL: &str = "SELECT y.id, y.canonical_name \
     FROM core.variant y \
     JOIN tree.haplogroup_variant yhv ON yhv.variant_id = y.id AND yhv.valid_until IS NULL \
     WHERE y.id <> $1 AND y.canonical_name = $2 AND y.canonical_name NOT LIKE 'chr%:%' \
       AND EXISTS (SELECT 1 FROM tree.haplogroup_variant phv \
                   WHERE phv.variant_id = $1 AND phv.valid_until IS NULL \
                     AND phv.haplogroup_id = yhv.haplogroup_id) \
     ORDER BY y.catalog_representative DESC, y.id LIMIT 1";

/// See [`BRANCH_COVERING_SQL`]. `name` is the placeholder's adoptable name.
pub async fn branch_covering_twin(
    pool: &PgPool,
    id: i64,
    name: &str,
) -> Result<Option<(i64, String)>, DbError> {
    Ok(sqlx::query_as(BRANCH_COVERING_SQL).bind(id).bind(name).fetch_optional(pool).await?)
}

/// Does deleting `id` leave any branch it defines with **no other** current defining variant?
/// A placeholder normally defines exactly one branch (covered by [`BRANCH_COVERING_SQL`]), but
/// this is the general safety net: if `id` is the *last* `tree.haplogroup_variant` edge for any
/// branch, deletion would orphan that tree node, and must be refused. Returns a row iff such a
/// branch exists.
const WOULD_ORPHAN_BRANCH_SQL: &str = "SELECT 1 FROM tree.haplogroup_variant phv \
     WHERE phv.variant_id = $1 AND phv.valid_until IS NULL \
       AND NOT EXISTS (SELECT 1 FROM tree.haplogroup_variant ohv \
                       WHERE ohv.haplogroup_id = phv.haplogroup_id \
                         AND ohv.valid_until IS NULL AND ohv.variant_id <> $1) \
     LIMIT 1";

/// Every table with a (non-cascading) FK to `core.variant(id)`. A hard delete must clear
/// each of these before the row will drop. Kept in one place so a new referencing table
/// added to the schema is a compile-visible thing to revisit here.
const VARIANT_FK_TABLES: [&str; 4] = [
    "tree.haplogroup_variant",
    "tree.wip_haplogroup_variant",
    "tree.biosample_private_variant",
    "tree.proposed_branch_variant",
];

/// **Hard-delete an erroneous duplicate placeholder.** Only for the collision
/// [`branch_covering_twin`] identifies: an unnamed placeholder whose adoptable name is
/// already canonical on a branch it defines, via a really-named, tree-linked twin. Purges the
/// variant's FK references (none cascade) and deletes the row, all in one transaction.
///
/// The guard is **re-checked inside the transaction** (the UI button is only advisory), and in
/// three parts, all of which must hold: `id` is still a placeholder (not named in the
/// meantime); its adoptable name is still canonical on a shared branch via a covering twin; and
/// deleting it would not orphan any branch it defines ([`WOULD_ORPHAN_BRANCH_SQL`]). Otherwise
/// nothing is deleted and a `Conflict` is returned. This is the one path in the module that
/// discards a variant outright. Returns the covering twin's name (for the confirmation notice).
pub async fn delete_erroneous_duplicate(pool: &PgPool, id: i64) -> Result<String, DbError> {
    let mut tx = pool.begin().await?;
    // Lock the row so the guard can't be invalidated between check and delete.
    let row: Option<(Option<String>, Value)> =
        sqlx::query_as("SELECT canonical_name, aliases FROM core.variant WHERE id = $1 FOR UPDATE")
            .bind(id)
            .fetch_optional(&mut *tx)
            .await?;
    let (canon, aliases) =
        row.ok_or_else(|| DbError::Conflict(format!("variant {id} not found")))?;
    // Only a placeholder / unnamed row may be deleted — a real name is data to name or merge,
    // never to discard.
    if canon.as_deref().is_some_and(|n| !is_placeholder_name(n)) {
        return Err(DbError::Conflict(
            "variant has a real canonical name — name or merge it, do not delete".into(),
        ));
    }
    // The name this placeholder would adopt: its own established alias, else its site-twin's.
    let adopt = match established_name(&aliases) {
        Some(n) => Some(n),
        None => {
            let build: Option<String> = sqlx::query_scalar(PREFERRED_BUILD_SQL)
                .bind(id)
                .fetch_optional(&mut *tx)
                .await?
                .flatten();
            match build_literal(build.as_deref()) {
                Some(b) => sqlx::query_as::<_, (i64, String)>(&site_twin_sql(b))
                    .bind(id)
                    .fetch_all(&mut *tx)
                    .await?
                    .into_iter()
                    .next()
                    .map(|(_, n)| n),
                None => None,
            }
        }
    };
    let adopt = adopt.ok_or_else(|| {
        DbError::Conflict(
            "not an erroneous duplicate: no established name to adopt — mint or flag it instead"
                .into(),
        )
    })?;
    // That name must already be canonical on a shared branch via a really-named, tree-linked
    // twin: proof the row is redundant, and that the branch stays defined after deletion.
    let covering: Option<(i64, String)> =
        sqlx::query_as(BRANCH_COVERING_SQL).bind(id).bind(&adopt).fetch_optional(&mut *tx).await?;
    let (_twin_id, name) = covering.ok_or_else(|| {
        DbError::Conflict(
            "not an erroneous duplicate: its name is not yet canonical on a branch this row \
             defines — adopt or mint it instead of deleting"
                .into(),
        )
    })?;
    // Final safety net: never drop the last defining variant of a branch.
    let would_orphan: Option<i32> =
        sqlx::query_scalar(WOULD_ORPHAN_BRANCH_SQL).bind(id).fetch_optional(&mut *tx).await?;
    if would_orphan.is_some() {
        return Err(DbError::Conflict(
            "deleting this row would leave a branch with no defining variant — resolve in \
             merge review instead"
                .into(),
        ));
    }
    for t in VARIANT_FK_TABLES {
        // Table names are compile-time literals from VARIANT_FK_TABLES, not user input.
        sqlx::query(&format!("DELETE FROM {t} WHERE variant_id = $1"))
            .bind(id)
            .execute(&mut *tx)
            .await?;
    }
    sqlx::query("DELETE FROM core.variant WHERE id = $1").bind(id).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(name)
}

/// The name this variant would **adopt**, from the two places a real name can live:
/// its own established (ISOGG/YBrowse) alias, else a named variant at the same locus +
/// mutation state ([`dedup_by_site`]).
///
/// The second source is the one that matters in practice. The de-novo loader mints its
/// coordinate-named rows with **no aliases at all**, so the alias source never fires for
/// them — a de-novo branch row's real name lives only on the separate catalog row at the
/// same site. Consulting aliases alone made the UI offer no way to act on its own "a named
/// variant already exists here" warning.
///
/// Resolution order must stay in step with [`adopt_established_name`], which decides what
/// the button actually writes.
pub async fn adoptable_name(
    pool: &PgPool,
    id: i64,
    aliases: &Value,
) -> Result<Option<String>, DbError> {
    if let Some(n) = established_name(aliases) {
        return Ok(Some(n));
    }
    Ok(dedup_by_site(pool, id).await?.into_iter().next().map(|(_, n)| n))
}

/// Outcome of [`reconcile_placeholder_names`].
#[derive(Debug, Default)]
pub struct NameReconcile {
    /// Coordinate-placeholder rows examined.
    pub scanned: i64,
    /// Of those, ones that have a named variant at the same hs1 site + mutation state.
    pub matched: i64,
    /// Rows folded onto that existing name (`NAMED`).
    pub adopted: i64,
    /// Matched but **not** written: the name is already canonical for that same branch.
    /// That is a real duplicate or an unmodelled recurrence — a merge-review question,
    /// not something to resolve by overwriting. Left in the queue deliberately.
    pub conflicted: i64,
}

/// One batch of the reconcile below: take the next `$1` placeholder rows after id `$2`,
/// find each one's named twin at the same hs1 site + mutation state, and adopt that name
/// where doing so doesn't collide with the `(name + defining branch)` unique key.
///
/// Scoped to **branch-defining** rows — the same population as the `needs_name` queue, so
/// what this clears is exactly what a curator would otherwise have to clear by hand. The
/// loader also leaves behind coordinate-named rows that define *no* branch and are linked
/// to nothing (94.6k of them at last count, ~67k of which duplicate an already-named
/// catalog row). Those are catalog dead weight, not naming work: they never surface in the
/// queue, and resolving them means merging rows ([`crate::merge`]), not renaming one — a
/// rename would just collide with the catalog row on `(name, NULL)`. Left alone here
/// deliberately; folding them is a separate cleanup.
///
/// The `ok` CTE deduplicates *within* the batch as well as against the table — two
/// placeholder rows on one branch can resolve to the same name when the catalog itself
/// carries that name at more than one site, and the second write would violate
/// `variant_canonical_name_key`.
const RECONCILE_BATCH_SQL: &str = "WITH win AS ( \
       SELECT v.id, v.defining_haplogroup_id AS branch, v.coordinates->'hs1' AS hs1 \
       FROM core.variant v \
       WHERE v.canonical_name LIKE 'chr%:%' AND v.coordinates ? 'hs1' \
         AND v.defining_haplogroup_id IS NOT NULL AND v.id > $2 \
       ORDER BY v.id LIMIT $1), \
     cand AS ( \
       SELECT DISTINCT ON (w.id) w.id, w.branch, n.canonical_name AS name \
       FROM win w \
       JOIN core.variant n \
         ON n.id <> w.id \
        AND n.canonical_name IS NOT NULL \
        AND n.canonical_name NOT LIKE 'chr%:%' \
        AND n.coordinates ? 'hs1' \
        AND n.coordinates->'hs1'->>'contig'    = w.hs1->>'contig' \
        AND n.coordinates->'hs1'->>'position'  = w.hs1->>'position' \
        AND n.coordinates->'hs1'->>'ancestral' = w.hs1->>'ancestral' \
        AND n.coordinates->'hs1'->>'derived'   = w.hs1->>'derived' \
       ORDER BY w.id, n.catalog_representative DESC, (n.canonical_name LIKE 'DU%'), n.canonical_name), \
     ok AS ( \
       SELECT DISTINCT ON (c.name, COALESCE(c.branch, -1)) c.id, c.name \
       FROM cand c \
       WHERE NOT EXISTS ( \
         SELECT 1 FROM core.variant o \
         WHERE o.canonical_name = c.name \
           AND COALESCE(o.defining_haplogroup_id, -1) = COALESCE(c.branch, -1)) \
       ORDER BY c.name, COALESCE(c.branch, -1), c.id), \
     upd AS ( \
       UPDATE core.variant v \
       SET canonical_name = ok.name, naming_status = 'NAMED', updated_at = now() \
       FROM ok WHERE v.id = ok.id \
       RETURNING v.id) \
     SELECT (SELECT max(id)  FROM win)  AS cursor, \
            (SELECT count(*) FROM win)  AS scanned, \
            (SELECT count(*) FROM cand) AS matched, \
            (SELECT count(*) FROM upd)  AS adopted";

/// **Fold coordinate-placeholder variants onto the name they already have.**
///
/// The de-novo loader mints a `chrY:10249542C>G` row whenever its hs1 catalog match
/// misses, and that row — not the named catalog row — is what gets linked to the branch.
/// Every such row then surfaces in the curator naming queue as if it were a novel
/// discovery, where the only offered action (Mint DU name) would give a marker YBrowse
/// already named a second, conflicting identity.
///
/// This job resolves them the way a curator would: adopt the existing name. It is the bulk
/// form of [`adopt_established_name`] and uses the same site match and same preference
/// order, so a row cleared here and a row cleared by the button get the same name.
///
/// Idempotent and safe to re-run: an adopted row no longer matches `chr%:%`, so it drops
/// out of the scan. Worth running **recurrently**, not just once — a YBrowse ingest that
/// names a marker after a tree load creates exactly this state again, and the fix is the
/// same fold. Rows whose name is already taken on their branch are counted in
/// `conflicted` and left alone for merge review.
///
/// Batched by id cursor so it never long-locks the catalog.
pub async fn reconcile_placeholder_names(
    pool: &PgPool,
    batch: i64,
) -> Result<NameReconcile, DbError> {
    let batch = batch.clamp(1, 50_000);
    let mut rep = NameReconcile::default();
    let mut cursor: i64 = 0;
    loop {
        let (next, scanned, matched, adopted): (Option<i64>, i64, i64, i64) =
            sqlx::query_as(RECONCILE_BATCH_SQL)
                .bind(batch)
                .bind(cursor)
                .fetch_one(pool)
                .await?;
        rep.scanned += scanned;
        rep.matched += matched;
        rep.adopted += adopted;
        rep.conflicted += matched - adopted;
        // `win` empty ⇒ no placeholder rows past the cursor ⇒ done. Advancing the cursor
        // past every *scanned* row (not just adopted ones) is what makes this terminate:
        // conflicted rows are never written, so a cursor keyed on progress-made would
        // re-select them forever.
        match next {
            Some(id) => cursor = id,
            None => break,
        }
    }
    Ok(rep)
}

// ── batch minting ─────────────────────────────────────────────────────────────

/// The rows the **batch mint** may name: branch-defining **Y** coordinate placeholders
/// (`chrY:…`) with **no name to adopt** — neither an established alias nor a named site-twin
/// — i.e. genuine de-novo discoveries. Excluding the adoptable ones is the safety property:
/// a marker a source already named must Reuse (the reconcile job / adopt button), never get a
/// second DU identity minted. Aliased `v`; embedded in the preview and the mint statement.
///
/// (The `defining_haplogroup_id IS NOT NULL` + `chr%:%` head is served by the partial index
/// `variant_placeholder_branch_idx`, and the site-twin `NOT EXISTS` by `variant_hs1_site_named_idx`.)
const NOVEL_MINTABLE_PRED: &str = "\
    v.defining_haplogroup_id IS NOT NULL \
    AND EXISTS (SELECT 1 FROM tree.haplogroup h \
                WHERE h.id = v.defining_haplogroup_id AND h.haplogroup_type = 'Y_DNA'::core.dna_type) \
    AND v.canonical_name LIKE 'chr%:%' AND v.coordinates ? 'hs1' \
    AND NOT EXISTS (SELECT 1 \
                    FROM jsonb_array_elements_text(COALESCE(v.aliases->'common_names', '[]'::jsonb)) cn \
                    WHERE cn NOT LIKE 'DU%' AND cn NOT LIKE 'chr%:%' AND btrim(cn) <> '') \
    AND NOT EXISTS (SELECT 1 FROM core.variant n \
                    WHERE n.id <> v.id AND n.canonical_name IS NOT NULL \
                      AND n.canonical_name NOT LIKE 'chr%:%' AND n.coordinates ? 'hs1' \
                      AND n.coordinates->'hs1'->>'contig'    = v.coordinates->'hs1'->>'contig' \
                      AND n.coordinates->'hs1'->>'position'  = v.coordinates->'hs1'->>'position' \
                      AND n.coordinates->'hs1'->>'ancestral' = v.coordinates->'hs1'->>'ancestral' \
                      AND n.coordinates->'hs1'->>'derived'   = v.coordinates->'hs1'->>'derived')";

/// [`NOVEL_MINTABLE_PRED`] AND the [`type_predicate`] mutation-type filter, so a batch mints
/// exactly the type the curator is looking at (`SNP` first; the fiddly `DEL`/`INS` deferred).
fn novel_mintable_pred(vtype: &str) -> String {
    format!("({NOVEL_MINTABLE_PRED}) AND ({})", type_predicate(vtype))
}

/// How much work a batch mint of a given `vtype` would do: distinct **sites** (each gets one
/// DU name) and total **rows** (≥ sites — a recurrent SNP shares its name across branches).
#[derive(Debug, Default, Clone)]
pub struct MintPreview {
    pub sites: i64,
    pub rows: i64,
}

/// Preview [`mint_batch`] without writing — the count behind the UI's "N ready to mint".
pub async fn mint_batch_preview(pool: &PgPool, vtype: &str) -> Result<MintPreview, DbError> {
    let pred = novel_mintable_pred(vtype);
    let sql = format!(
        "SELECT count(DISTINCT (v.coordinates->'hs1'->>'contig', v.coordinates->'hs1'->>'position', \
                                v.coordinates->'hs1'->>'ancestral', v.coordinates->'hs1'->>'derived'))::bigint AS sites, \
                count(*)::bigint AS rows \
         FROM core.variant v WHERE {pred}"
    );
    let (sites, rows): (i64, i64) = sqlx::query_as(&sql).fetch_one(pool).await?;
    Ok(MintPreview { sites, rows })
}

/// Result of one [`mint_batch`] call.
#[derive(Debug, Default, Clone)]
pub struct MintReport {
    /// Distinct DU names assigned (one per recurrence site).
    pub sites_minted: i64,
    /// Variant rows named (≥ `sites_minted`; recurrences share a name).
    pub rows_minted: i64,
}

/// Mint up to `max_sites` recurrence sites of `vtype`, in **one atomic statement**.
///
/// The unit is a **site** (contig + position + ancestral + derived), not a row: canonical
/// identity is (name + branch), so a variant that recurs on several branches is several rows
/// that must share ONE name. `next_du_name()` is therefore drawn once **per site** — the
/// `named` CTE is `MATERIALIZED` so the sequence value is stable across the join, and every
/// row at that site is set to the same name (each keeps its own branch). Sites with two rows
/// on the *same* branch are skipped by the `count(*) = count(DISTINCT b)` guard (that is a
/// duplicate for merge review, and minting both would violate `variant_canonical_name_key`).
///
/// Idempotent: a minted row is no longer `chr%:%`, so it leaves the set — re-running names the
/// next sites. See [`mint_all`] for the drain-to-empty loop the job uses.
pub async fn mint_batch(pool: &PgPool, vtype: &str, max_sites: i64) -> Result<MintReport, DbError> {
    let max_sites = max_sites.clamp(1, 50_000);
    let pred = novel_mintable_pred(vtype);
    let sql = format!(
        "WITH sites AS MATERIALIZED ( \
           SELECT c, p, a, d FROM ( \
             SELECT v.coordinates->'hs1'->>'contig' AS c, v.coordinates->'hs1'->>'position' AS p, \
                    v.coordinates->'hs1'->>'ancestral' AS a, v.coordinates->'hs1'->>'derived' AS d, \
                    v.defining_haplogroup_id AS b \
             FROM core.variant v WHERE {pred}) g \
           GROUP BY c, p, a, d HAVING count(*) = count(DISTINCT b) \
           ORDER BY c, p, a, d LIMIT $1), \
         named AS MATERIALIZED (SELECT c, p, a, d, core.next_du_name() AS du FROM sites), \
         upd AS ( \
           UPDATE core.variant v \
           SET canonical_name = named.du, naming_status = 'NAMED', \
               aliases = jsonb_set(COALESCE(v.aliases, '{{}}'::jsonb), '{{common_names}}', \
                 COALESCE((SELECT jsonb_agg(x) \
                           FROM jsonb_array_elements_text(COALESCE(v.aliases->'common_names', '[]'::jsonb)) x \
                           WHERE x NOT LIKE 'chr%:%'), '[]'::jsonb), true), \
               updated_at = now() \
           FROM named \
           WHERE {pred} \
             AND v.coordinates->'hs1'->>'contig'    = named.c \
             AND v.coordinates->'hs1'->>'position'  = named.p \
             AND v.coordinates->'hs1'->>'ancestral' = named.a \
             AND v.coordinates->'hs1'->>'derived'   = named.d \
           RETURNING 1) \
         SELECT (SELECT count(*) FROM named)::bigint, (SELECT count(*) FROM upd)::bigint"
    );
    let (sites_minted, rows_minted): (i64, i64) =
        sqlx::query_as(&sql).bind(max_sites).fetch_one(pool).await?;
    Ok(MintReport { sites_minted, rows_minted })
}

/// Drain the whole `vtype` novel-mint queue in `batch`-sized statements (each atomic, so the
/// catalog is never long-locked). Loops [`mint_batch`] until a batch names nothing. This is
/// the job form; the curator UI calls [`mint_batch`] once with a per-click bound.
pub async fn mint_all(pool: &PgPool, vtype: &str, batch: i64) -> Result<MintReport, DbError> {
    let mut total = MintReport::default();
    loop {
        let r = mint_batch(pool, vtype, batch).await?;
        if r.sites_minted == 0 {
            break;
        }
        total.sites_minted += r.sites_minted;
        total.rows_minted += r.rows_minted;
    }
    Ok(total)
}
