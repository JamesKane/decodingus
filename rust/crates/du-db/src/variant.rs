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
pub async fn for_dna_type_grouped(
    pool: &PgPool,
    dna_type: DnaType,
) -> Result<Vec<(i64, Variant)>, DbError> {
    #[derive(sqlx::FromRow)]
    struct GroupedRow {
        haplogroup_id: i64,
        #[sqlx(flatten)]
        variant: VariantRow,
    }
    let rows: Vec<GroupedRow> = sqlx::query_as(
        "SELECT hv.haplogroup_id, v.id, v.canonical_name, v.mutation_type::text AS mutation_type, \
                v.naming_status::text AS naming_status, v.aliases, v.coordinates, v.annotations \
         FROM core.variant v \
         JOIN tree.haplogroup_variant hv ON hv.variant_id = v.id AND hv.valid_until IS NULL \
         JOIN tree.haplogroup h ON h.id = hv.haplogroup_id \
         WHERE h.haplogroup_type::text = $1 AND h.valid_until IS NULL AND v.canonical_name IS NOT NULL \
         ORDER BY hv.haplogroup_id, v.canonical_name",
    )
    .bind(pg_enum_label(&dna_type)?)
    .fetch_all(pool)
    .await?;
    rows.into_iter()
        .map(|r| Ok((r.haplogroup_id, r.variant.into_domain()?)))
        .collect()
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

/// Bulk-set variant `coordinates` by canonical name, **merging** into any
/// existing builds (existing keys win, the new payload fills gaps). Backs
/// source-tree coordinate enrichment — e.g. decoding-us carries multi-build SNP
/// coordinates that the SNP-graft (name-only) drops. Returns rows updated.
pub async fn set_coordinates_bulk(
    pool: &PgPool,
    items: &[(String, serde_json::Value)],
) -> Result<u64, DbError> {
    let mut updated = 0u64;
    for chunk in items.chunks(1000) {
        let names: Vec<&str> = chunk.iter().map(|(n, _)| n.as_str()).collect();
        let coords: Vec<String> = chunk.iter().map(|(_, c)| c.to_string()).collect();
        updated += sqlx::query(
            "UPDATE core.variant v SET coordinates = u.co::jsonb || v.coordinates, updated_at = now() \
             FROM (SELECT unnest($1::text[]) AS nm, unnest($2::text[]) AS co) u \
             WHERE v.canonical_name = u.nm",
        )
        .bind(&names)
        .bind(&coords)
        .execute(pool)
        .await?
        .rows_affected();
    }
    Ok(updated)
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

/// Report from [`resolve_isogg_recurrence`].
#[derive(Debug, Default, Clone)]
pub struct RecurrenceReport {
    /// Decorated (`.N`/`^^`) coordless tree-linked variants considered.
    pub candidates: i64,
    /// Converted into recurrence rows on their base identity (got coordinates).
    pub recurrence_set: u64,
    /// Folded into the base because the base already defines the same branch (true dup).
    pub redundant_folded: u64,
    /// Deferred: variant defines >1 branch (would need one recurrence row per branch).
    pub multi_link: i64,
    /// Residue: no base primary carrying a GRCh38 coordinate (base absent from ybrowse,
    /// itself coordless, or a compound `.`-joined name) — can't resolve here.
    pub no_base_coords: i64,
    pub samples: Vec<String>,
}

/// Resolve ISOGG name-decorated coordless variants into the universal-variant
/// recurrence model. ISOGG suffixes a SNP name with `.1`/`.2` when the SAME
/// physical site recurs on a second lineage (homoplasy) and `^^` as a "below
/// criteria but phylogenetically stable" marker — neither is a distinct identity.
///
/// For each decorated coordless variant linked to exactly one branch H whose base
/// SNP exists as a coordinate-bearing primary P (`defining_haplogroup_id IS NULL`):
/// rewrite it in place to `canonical_name = base`, `defining_haplogroup_id = H`,
/// inheriting P's coordinate (same physical site) and folding the old decorated
/// name into aliases. Each row then defines exactly one lineage, so
/// [`crate::haplogroup::scrub_recurrent_links`] leaves the genuine recurrence
/// alone. When the base already defines H (a true duplicate) the row is folded
/// into P instead. Dry-run (counts only) unless `apply`. Idempotent.
pub async fn resolve_isogg_recurrence(
    pool: &PgPool,
    dna: DnaType,
    apply: bool,
) -> Result<RecurrenceReport, DbError> {
    let dna_label = pg_enum_label(&dna)?;
    let mut rep = RecurrenceReport::default();
    let mut tx = pool.begin().await?;

    // Materialize the working set ONCE (the candidate scan is a single regex pass
    // over the coordless variants; recomputing the CTE per query seq-scanned 3M
    // rows repeatedly). Everything below reads these temp tables.
    //
    // _cand: decorated coordless PRIMARY variants with ≥1 current link in this tree.
    sqlx::query(
        "CREATE TEMP TABLE _cand ON COMMIT DROP AS \
           SELECT v.id AS vid, v.canonical_name AS oldname, \
                  regexp_replace(regexp_replace(v.canonical_name, '\\^+', '', 'g'), '\\.[0-9]+$', '') AS base \
           FROM core.variant v \
           WHERE v.defining_haplogroup_id IS NULL \
             AND (v.coordinates = '{}'::jsonb OR NOT v.coordinates ? 'GRCh38') \
             AND v.canonical_name ~ '(\\.[0-9]+$|\\^)' \
             AND EXISTS (SELECT 1 FROM tree.haplogroup_variant hv \
                           JOIN tree.haplogroup h ON h.id = hv.haplogroup_id AND h.valid_until IS NULL \
                                                 AND h.haplogroup_type::text = $1 \
                         WHERE hv.variant_id = v.id AND hv.valid_until IS NULL)",
    )
    .bind(&dna_label)
    .execute(&mut *tx)
    .await?;
    sqlx::query("CREATE INDEX ON _cand (vid)").execute(&mut *tx).await?;
    sqlx::query("CREATE INDEX ON _cand (base)").execute(&mut *tx).await?;

    // _single: candidates linked to exactly ONE branch (its defining haplogroup).
    sqlx::query(
        "CREATE TEMP TABLE _single ON COMMIT DROP AS \
           SELECT c.vid, c.oldname, c.base, (array_agg(hv.haplogroup_id))[1] AS hg \
           FROM _cand c \
             JOIN tree.haplogroup_variant hv ON hv.variant_id = c.vid AND hv.valid_until IS NULL \
             JOIN tree.haplogroup h ON h.id = hv.haplogroup_id AND h.valid_until IS NULL \
                                   AND h.haplogroup_type::text = $1 \
           GROUP BY c.vid, c.oldname, c.base HAVING count(*) = 1",
    )
    .bind(&dna_label)
    .execute(&mut *tx)
    .await?;

    // _res: singles whose base SNP is a coordinate-bearing primary; flag the true
    // duplicates (base already defines this branch). INDELs are excluded — YBrowse
    // encodes them as bare `ins`/`del` markers (not bases), so the coordinate can't
    // be folded or directioned; they need curator moderation, not auto-resolution.
    sqlx::query(
        "CREATE TEMP TABLE _res ON COMMIT DROP AS \
           SELECT s.vid, s.oldname, s.base, s.hg, p.id AS pid, p.coordinates AS pcoords, p.mutation_type AS pmt, \
                  EXISTS (SELECT 1 FROM tree.haplogroup_variant pv \
                          WHERE pv.variant_id = p.id AND pv.valid_until IS NULL AND pv.haplogroup_id = s.hg) AS redundant \
           FROM _single s \
             JOIN core.variant p ON p.canonical_name = s.base AND p.defining_haplogroup_id IS NULL \
                                AND p.coordinates ? 'GRCh38' AND p.mutation_type <> 'INDEL'",
    )
    .execute(&mut *tx)
    .await?;

    rep.candidates = sqlx::query_scalar("SELECT count(*) FROM _cand").fetch_one(&mut *tx).await?;
    rep.multi_link = sqlx::query_scalar(
        "SELECT count(*) FROM _cand c WHERE NOT EXISTS (SELECT 1 FROM _single s WHERE s.vid = c.vid)",
    )
    .fetch_one(&mut *tx)
    .await?;
    rep.no_base_coords = sqlx::query_scalar(
        "SELECT count(*) FROM _single s WHERE NOT EXISTS (SELECT 1 FROM _res r WHERE r.vid = s.vid)",
    )
    .fetch_one(&mut *tx)
    .await?;
    rep.samples = sqlx::query_scalar(
        "SELECT oldname || ' -> ' || base FROM _res WHERE NOT redundant ORDER BY oldname LIMIT 12",
    )
    .fetch_all(&mut *tx)
    .await?;

    if !apply {
        return Ok(rep); // tx rolls back on drop — temp tables discarded.
    }

    // Step A — recurrence-ize: rewrite each non-redundant resolvable row onto its
    // base identity, scoped to its branch, inheriting the base coordinate.
    rep.recurrence_set = sqlx::query(
        "UPDATE core.variant v SET \
           canonical_name = r.base, \
           defining_haplogroup_id = r.hg, \
           coordinates = r.pcoords, \
           mutation_type = r.pmt, \
           aliases = jsonb_set(COALESCE(v.aliases, '{}'::jsonb), '{common_names}', ( \
             SELECT COALESCE(jsonb_agg(DISTINCT x), '[]'::jsonb) FROM ( \
               SELECT jsonb_array_elements_text(COALESCE(v.aliases->'common_names', '[]'::jsonb)) AS x \
               UNION SELECT r.oldname) u WHERE x <> r.base), true), \
           evidence = COALESCE(v.evidence, '{}'::jsonb) || jsonb_build_object('isogg_recurrence', true), \
           updated_at = now() \
         FROM _res r WHERE v.id = r.vid AND NOT r.redundant",
    )
    .execute(&mut *tx)
    .await?
    .rows_affected();

    // Collect the true-duplicate folds before committing (merge_into needs its own
    // transaction and can't see the temp tables).
    let redundant: Vec<(i64, i64)> =
        sqlx::query_as("SELECT pid, vid FROM _res WHERE redundant").fetch_all(&mut *tx).await?;
    tx.commit().await?;

    // Step B — fold the true duplicates (base already defines the same branch).
    for (keep, drop) in redundant {
        merge_into(pool, keep, drop).await?;
        rep.redundant_folded += 1;
    }

    Ok(rep)
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
