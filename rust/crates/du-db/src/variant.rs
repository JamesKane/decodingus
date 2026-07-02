//! Queries for `core.variant`. Demonstrates the du-db mapping pattern:
//! enum columns are fetched as `::text` and parsed via serde; JSONB columns are
//! read through `sqlx::types::Json<T>` into the du-domain payload structs.

use crate::{parse_pg_enum, pg_enum_label, DbError, Page};
use du_domain::enums::{DnaType, MutationType, NamingStatus};
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

/// Region-overlap kinds masked from age counting **and** discovery branch-formation
/// as recurrent / false-positive-prone sequence. Both sit outside the SNP-age callable
/// denominator (`y_xdegen+y_ampliconic+y_palindromic`), so excising their SNPs keeps the
/// numerator on the same footprint as the denominator; both are classic recurrent-miscall
/// sources (satellite/DYZ heterochromatin, palindrome-arm inverted repeats) that otherwise
/// inflate per-sample private counts and can manufacture phantom branches. Ampliconic and
/// palindromic are deliberately **kept** (in the denominator, Hallast 2026-validated).
/// Extend this list (e.g. `par`, `telomere`, `low_complexity`) once those region BEDs are
/// ingested into `core.genome_region` → `region_overlaps`.
pub const RECURRENT_REGION_KINDS: &[&str] = &["heterochromatin", "inverted_repeat"];

/// SQL boolean fragment that is true when `core.variant` row `<alias>` does **not** overlap
/// any [`RECURRENT_REGION_KINDS`] region. `alias` must name a `core.variant` row in scope
/// (its `annotations->'region_overlaps'` is a JSONB array of `"<kind>:<feature>"` strings).
/// Single source of truth shared by the SNP-age model and the discovery consensus engine.
pub fn recurrent_region_mask_sql(alias: &str) -> String {
    let likes = RECURRENT_REGION_KINDS
        .iter()
        .map(|k| format!("e LIKE '{k}:%'"))
        .collect::<Vec<_>>()
        .join(" OR ");
    format!(
        "NOT EXISTS (SELECT 1 FROM \
         jsonb_array_elements_text(COALESCE({alias}.annotations->'region_overlaps','[]'::jsonb)) e \
         WHERE {likes})"
    )
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

/// Region types whose sequence is structurally unreliable for Y-SNP placement
/// (multi-copy / repeat-rich), so a variant landing inside one should not be
/// trusted as branch-defining without scrutiny. AZF intervals are deliberately
/// excluded: that's a functional annotation, and AZFa is largely single-copy
/// X-degenerate sequence. Sourced from `du_jobs::yregions` (T2T-CHM13 Y BEDs).
const UNRELIABLE_REGION_TYPES: [&str; 4] =
    ["palindromic", "ampliconic", "inverted_repeat", "heterochromatin"];

/// Recompute `annotations.region_overlaps` for every variant from the current
/// `core.genome_region` set, comparing hs1 coordinates (1-based inclusive on
/// both sides). Each entry is `"<region_type>:<label>"` (e.g. `"palindromic:P8"`);
/// a non-empty array marks a placement the Y-tree should treat as low-confidence.
///
/// Idempotent and churn-free: only variants whose overlap set actually changes
/// are written (so `updated_at` is stable across re-runs), and a variant that no
/// longer overlaps any region has the key removed. Variants without an hs1
/// position (lift gap) are left untouched. Returns the number of rows changed.
pub async fn refresh_region_overlaps(pool: &PgPool) -> Result<u64, DbError> {
    let types: Vec<&str> = UNRELIABLE_REGION_TYPES.to_vec();
    let affected = sqlx::query(
        "WITH desired AS ( \
           SELECT v.id, \
                  COALESCE( \
                    jsonb_agg(DISTINCT (r.region_type || ':' || (r.properties->>'label')) \
                              ORDER BY (r.region_type || ':' || (r.properties->>'label'))) \
                      FILTER (WHERE r.id IS NOT NULL), \
                    '[]'::jsonb) AS labels \
           FROM core.variant v \
           LEFT JOIN core.genome_region r \
             ON r.region_type = ANY($1::text[]) \
            AND r.coordinates->'hs1'->>'contig' = v.coordinates->'hs1'->>'contig' \
            AND (v.coordinates->'hs1'->>'position')::bigint \
                  BETWEEN (r.coordinates->'hs1'->>'start')::bigint \
                      AND (r.coordinates->'hs1'->>'end')::bigint \
           WHERE v.coordinates->'hs1'->>'position' IS NOT NULL \
           GROUP BY v.id) \
         UPDATE core.variant v \
         SET annotations = CASE WHEN d.labels = '[]'::jsonb \
                                THEN v.annotations - 'region_overlaps' \
                                ELSE jsonb_set(v.annotations, '{region_overlaps}', d.labels) END, \
             updated_at = now() \
         FROM desired d \
         WHERE v.id = d.id \
           AND COALESCE(v.annotations->'region_overlaps', '[]'::jsonb) IS DISTINCT FROM d.labels",
    )
    .bind(&types)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(affected)
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
         ON CONFLICT (canonical_name, COALESCE(defining_haplogroup_id, -1)) WHERE canonical_name IS NOT NULL \
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

/// Resolve the id of the *base* variant (`defining_haplogroup_id IS NULL`) with
/// this canonical name, minting it `UNNAMED`/`SNP` if absent. Hot in the tree
/// build (graft + merge + change-set apply), where the catalog is already
/// populated by the YBrowse ingest so nearly every call conflicts.
///
/// Unlike a no-op `ON CONFLICT … DO UPDATE SET canonical_name = EXCLUDED.…`
/// (which rewrites the existing row on every conflict purely to return its id —
/// tens of thousands of dead tuples + index churn across a build, the ~2s bulk
/// statement seen at cutover scale), the conflict path here writes **nothing**:
/// it inserts only when new, otherwise reads the id back. The read-back filters
/// `defining_haplogroup_id IS NULL` to match the arbiter
/// `(canonical_name, COALESCE(defining_haplogroup_id, -1))` for the base row.
pub(crate) async fn ensure_base_variant_id(
    conn: &mut sqlx::PgConnection,
    name: &str,
) -> Result<i64, DbError> {
    if let Some(id) = sqlx::query_scalar::<_, i64>(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status) \
         VALUES ($1, 'SNP'::core.mutation_type, 'UNNAMED'::core.naming_status) \
         ON CONFLICT (canonical_name, COALESCE(defining_haplogroup_id, -1)) WHERE canonical_name IS NOT NULL \
         DO NOTHING RETURNING id",
    )
    .bind(name)
    .fetch_optional(&mut *conn)
    .await?
    {
        return Ok(id);
    }
    // Conflict: the base row already exists — read its id without rewriting it.
    Ok(sqlx::query_scalar::<_, i64>(
        "SELECT id FROM core.variant WHERE canonical_name = $1 AND defining_haplogroup_id IS NULL",
    )
    .bind(name)
    .fetch_one(&mut *conn)
    .await?)
}

/// Get-or-create a coordinate-only (novel, unnamed) variant. With no real name to
/// key on, the partial unique index can't dedupe it, so we mint a **deterministic
/// synthetic `canonical_name`** from the GRCh38 coordinates (`chrY:21648000A>G`) —
/// stable across runs (idempotent), `UNNAMED`, and easy for a curator to later fold
/// onto a real name via [`merge_into`]. Sets coordinates on first mint; writes
/// nothing on conflict (same no-op-write discipline as [`ensure_base_variant_id`]).
pub(crate) async fn ensure_variant_by_coords(
    conn: &mut sqlx::PgConnection,
    contig: &str,
    position: i64,
    ancestral: Option<&str>,
    derived: Option<&str>,
) -> Result<i64, DbError> {
    let synth = format!("{contig}:{position}{}>{}", ancestral.unwrap_or(""), derived.unwrap_or(""));
    let coords = serde_json::json!({ "GRCh38": {
        "contig": contig, "position": position, "ancestral": ancestral, "derived": derived
    }});
    if let Some(id) = sqlx::query_scalar::<_, i64>(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, coordinates) \
         VALUES ($1, 'SNP'::core.mutation_type, 'UNNAMED'::core.naming_status, $2) \
         ON CONFLICT (canonical_name, COALESCE(defining_haplogroup_id, -1)) WHERE canonical_name IS NOT NULL \
         DO NOTHING RETURNING id",
    )
    .bind(&synth)
    .bind(&coords)
    .fetch_optional(&mut *conn)
    .await?
    {
        return Ok(id);
    }
    Ok(sqlx::query_scalar::<_, i64>(
        "SELECT id FROM core.variant WHERE canonical_name = $1 AND defining_haplogroup_id IS NULL",
    )
    .bind(&synth)
    .fetch_one(&mut *conn)
    .await?)
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

/// Every current `(haplogroup_id, defining variant)` pair for a lineage, in one round
/// trip. Backs the tree-with-variants ("full") tree API, which embeds each node's variants
/// so a client (the Navigator desktop app) can build a placement tree without a per-node
/// fetch. Current edges only (`valid_until IS NULL`); unnamed variants excluded.
/// Every named defining variant per haplogroup, each paired with this branch's
/// ancestral/derived alleles from `tree.haplogroup_variant` (the per-branch ASR
/// polarity — authoritative over the variant's global coordinate polarity for
/// descent classification; NULL ⇒ forward/legacy link, fall back to coordinates).
///
/// Fetched as two passes joined in-process, NOT one big join. A recurrent SNP maps
/// to many branches, so the whole-tree join produces ~100k links over ~5k nodes; the
/// planner badly underestimates that fanout and picks a nested loop that random-reads
/// the wide `core.variant` heap (JSONB coordinates/annotations) once *per link* — plus
/// a disk-spilling sort. Instead: (1) pull the narrow link rows, (2) read each distinct
/// referenced variant exactly once by id, (3) stitch + sort in Rust. One sequential
/// variant scan instead of ~200k random fetches.
pub async fn for_dna_type_grouped(
    pool: &PgPool,
    dna_type: DnaType,
) -> Result<Vec<(i64, Variant, Option<String>, Option<String>)>, DbError> {
    use std::collections::HashMap;

    // (1) Narrow link rows: haplogroup ↔ variant with this branch's ASR alleles. No JSONB,
    //     no heap fetch of core.variant — just the current Y links.
    #[derive(sqlx::FromRow)]
    struct LinkRow {
        haplogroup_id: i64,
        variant_id: i64,
        link_ancestral: Option<String>,
        link_derived: Option<String>,
    }
    let links: Vec<LinkRow> = sqlx::query_as(
        "SELECT hv.haplogroup_id, hv.variant_id, \
                hv.ancestral_allele AS link_ancestral, hv.derived_allele AS link_derived \
         FROM tree.haplogroup_variant hv \
         JOIN tree.haplogroup h ON h.id = hv.haplogroup_id \
         WHERE h.haplogroup_type::text = $1 AND h.valid_until IS NULL AND hv.valid_until IS NULL",
    )
    .bind(pg_enum_label(&dna_type)?)
    .fetch_all(pool)
    .await?;

    // (2) Each distinct referenced variant, once. Named-only (matches the prior join's
    //     `v.canonical_name IS NOT NULL`); unnamed ids simply won't resolve below.
    let mut ids: Vec<i64> = links.iter().map(|l| l.variant_id).collect();
    ids.sort_unstable();
    ids.dedup();
    let variant_rows: Vec<VariantRow> = sqlx::query_as(&format!(
        "{SELECT} WHERE id = ANY($1::bigint[]) AND canonical_name IS NOT NULL"
    ))
    .bind(&ids)
    .fetch_all(pool)
    .await?;
    let mut by_id: HashMap<i64, Variant> = HashMap::with_capacity(variant_rows.len());
    for r in variant_rows {
        by_id.insert(r.id, r.into_domain()?);
    }

    // (3) Stitch links to variants (clone — a recurrent variant fans out to many branches),
    //     dropping links whose variant was unnamed/absent. Sort to the prior contract
    //     (haplogroup_id, canonical_name) in Rust — cheap vs Postgres's external-merge sort.
    let mut out: Vec<(i64, Variant, Option<String>, Option<String>)> = links
        .into_iter()
        .filter_map(|l| {
            by_id
                .get(&l.variant_id)
                .map(|v| (l.haplogroup_id, v.clone(), l.link_ancestral, l.link_derived))
        })
        .collect();
    out.sort_by(|a, b| a.0.cmp(&b.0).then_with(|| a.1.canonical_name.cmp(&b.1.canonical_name)));
    Ok(out)
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

/// **Merge** variant `drop` into `keep`: fold `drop`'s canonical name + aliases
/// into `keep`'s `aliases.common_names`, repoint every reference (tree links,
/// WIP, private-variant, proposed-branch) from `drop` to `keep`, then delete
/// `drop`. Current haplogroup links that would collide are dropped (the keep
/// variant already defines that branch). One transaction. The curator-facing
/// resolution for a reconcile-flag (synonyms split across rows).
pub async fn merge_into(pool: &PgPool, keep: i64, drop: i64) -> Result<(), DbError> {
    if keep == drop {
        return Err(DbError::Conflict("cannot merge a variant into itself".into()));
    }
    let mut tx = pool.begin().await?;

    // Fold drop's canonical + aliases into keep (union, excluding keep's canonical).
    sqlx::query(
        "UPDATE core.variant k SET aliases = jsonb_set(COALESCE(k.aliases, '{}'::jsonb), '{common_names}', ( \
           SELECT COALESCE(jsonb_agg(DISTINCT x), '[]'::jsonb) FROM ( \
             SELECT jsonb_array_elements_text(COALESCE(k.aliases->'common_names', '[]'::jsonb)) AS x \
             UNION SELECT jsonb_array_elements_text(COALESCE(d.aliases->'common_names', '[]'::jsonb)) \
                   FROM core.variant d WHERE d.id = $2 \
             UNION SELECT d.canonical_name FROM core.variant d WHERE d.id = $2 AND d.canonical_name IS NOT NULL \
           ) u WHERE x IS NOT NULL AND x <> COALESCE(k.canonical_name, '')), true), updated_at = now() \
         WHERE k.id = $1",
    )
    .bind(keep)
    .bind(drop)
    .execute(&mut *tx)
    .await?;

    // Repoint tree links — drop any current link that would collide with keep's.
    sqlx::query(
        "DELETE FROM tree.haplogroup_variant d \
         WHERE d.variant_id = $2 AND d.valid_until IS NULL \
           AND EXISTS (SELECT 1 FROM tree.haplogroup_variant e \
             WHERE e.haplogroup_id = d.haplogroup_id AND e.variant_id = $1 AND e.valid_until IS NULL)",
    )
    .bind(keep)
    .bind(drop)
    .execute(&mut *tx)
    .await?;
    for table in ["tree.haplogroup_variant", "tree.wip_haplogroup_variant", "tree.biosample_private_variant", "tree.proposed_branch_variant"] {
        sqlx::query(&format!("UPDATE {table} SET variant_id = $1 WHERE variant_id = $2"))
            .bind(keep)
            .bind(drop)
            .execute(&mut *tx)
            .await?;
    }

    sqlx::query("DELETE FROM core.variant WHERE id = $1").bind(drop).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(())
}

/// Count variants whose `evidence.source` equals `source` (e.g. how many came
/// from the YBrowse GFF3 ingest).
pub async fn count_by_evidence_source(pool: &PgPool, source: &str) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar("SELECT count(*) FROM core.variant WHERE evidence->>'source' = $1")
        .bind(source)
        .fetch_one(pool)
        .await?)
}

/// Delete **unreferenced** variants whose `evidence.source` equals `source` — an
/// orphan clean-up hook (e.g. before a full re-ingest). Variants still linked to
/// a haplogroup are left in place (they're part of the tree); a re-ingest
/// updates those via upsert. Returns rows removed.
pub async fn delete_by_evidence_source(pool: &PgPool, source: &str) -> Result<u64, DbError> {
    Ok(sqlx::query(
        "DELETE FROM core.variant WHERE evidence->>'source' = $1 \
           AND id NOT IN (SELECT variant_id FROM tree.haplogroup_variant)",
    )
    .bind(source)
    .execute(pool)
    .await?
    .rows_affected())
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

#[cfg(test)]
mod tests {
    use sqlx::PgPool;

    /// Insert a variant with an hs1 coordinate; returns its id.
    async fn insert_variant(pool: &PgPool, name: &str, hs1_pos: Option<i64>) -> i64 {
        let coords = match hs1_pos {
            Some(p) => serde_json::json!({ "hs1": { "contig": "chrY", "position": p } }),
            None => serde_json::json!({ "GRCh38": { "contig": "chrY", "position": 1 } }),
        };
        sqlx::query_scalar(
            "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, coordinates) \
             VALUES ($1, 'SNP'::core.mutation_type, 'NAMED'::core.naming_status, $2) RETURNING id",
        )
        .bind(name)
        .bind(coords)
        .fetch_one(pool)
        .await
        .unwrap()
    }

    async fn insert_region(pool: &PgPool, rtype: &str, label: &str, start: i64, end: i64) {
        sqlx::query(
            "INSERT INTO core.genome_region (region_type, name, coordinates, properties) \
             VALUES ($1, $2, $3, $4)",
        )
        .bind(rtype)
        .bind(format!("{label} (chrY:{start}-{end})"))
        .bind(serde_json::json!({ "hs1": { "contig": "chrY", "start": start, "end": end } }))
        .bind(serde_json::json!({ "label": label }))
        .execute(pool)
        .await
        .unwrap();
    }

    async fn overlaps(pool: &PgPool, id: i64) -> Option<serde_json::Value> {
        sqlx::query_scalar("SELECT annotations->'region_overlaps' FROM core.variant WHERE id = $1")
            .bind(id)
            .fetch_one(pool)
            .await
            .unwrap()
    }

    /// A variant inside an unreliable region gets flagged; one outside (and one
    /// without hs1 coords) does not; an excluded region type (azf) never flags;
    /// and the pass is idempotent (a second run changes nothing).
    #[tokio::test]
    async fn region_overlaps_flagging() {
        let Ok(url) = std::env::var("DATABASE_URL") else {
            eprintln!("DATABASE_URL unset — skipping region-overlap flagging test");
            return;
        };
        if url.is_empty() {
            return;
        }
        let db = crate::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();

        insert_region(&pool, "palindromic", "P8", 100, 200).await;
        insert_region(&pool, "azf", "AZFa", 100, 200).await; // excluded type — must not flag
        let inside = insert_variant(&pool, "TESTRO-IN", Some(150)).await;
        let outside = insert_variant(&pool, "TESTRO-OUT", Some(500)).await;
        let no_hs1 = insert_variant(&pool, "TESTRO-NOHS1", None).await;

        let changed = super::refresh_region_overlaps(&pool).await.unwrap();
        assert_eq!(changed, 1, "only the inside variant changes");
        assert_eq!(overlaps(&pool, inside).await, Some(serde_json::json!(["palindromic:P8"])));
        assert_eq!(overlaps(&pool, outside).await, None, "outside variant unflagged");
        assert_eq!(overlaps(&pool, no_hs1).await, None, "no-hs1 variant untouched");

        // Idempotent: re-running over the same regions changes nothing.
        assert_eq!(super::refresh_region_overlaps(&pool).await.unwrap(), 0);

        // Drop the region → the stale flag is cleared on the next pass.
        sqlx::query("DELETE FROM core.genome_region WHERE region_type = 'palindromic'")
            .execute(&pool)
            .await
            .unwrap();
        assert_eq!(super::refresh_region_overlaps(&pool).await.unwrap(), 1, "inside variant cleared");
        assert_eq!(overlaps(&pool, inside).await, None, "flag removed");
    }
}
