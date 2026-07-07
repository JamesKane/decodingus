//! YBrowse source mirror (`source.ybrowse_snp`) + reconciliation into the
//! curated `core.variant`.
//!
//! YBrowse publishes a FULL `snps_hg38.gff3` snapshot (no deltas) where one
//! physical SNP often appears under several names on separate lines. The ingest
//! refreshes the mirror verbatim (one row per upstream name); [`reconcile`] then
//! *derives* the catalog from the mirror so curator decisions survive re-ingest:
//!
//! - synonyms (same `contig,position,anc,der`) **fold deterministically** into
//!   one `core.variant` (canonical by [`core.ysnp_name_rank`], else a minted DU
//!   name; the rest become `aliases.common_names`) — idempotent, so there's no
//!   manual merge to undo;
//! - a cluster already represented by **one** existing variant is enriched
//!   *additively* (union aliases, fill coordinate gaps, set evidence) — its
//!   `canonical_name` and `naming_status` are never overwritten (curator/NAMED
//!   choices are locked);
//! - a cluster split across **several** existing variants is **flagged** for
//!   curator review (never auto-merged, since rows may be tree-linked).

use crate::{DbError, Page};
use serde_json::Value;
use sqlx::PgPool;

/// One verbatim upstream SNP (one GFF Name), with lifted multi-build coordinates.
#[derive(Debug, Clone)]
pub struct MirrorRow {
    pub name: String,
    pub contig: String,
    pub position: i64,
    pub allele_anc: Option<String>,
    pub allele_der: Option<String>,
    pub coordinates: Value,
    pub evidence: Value,
}

/// Bulk-upsert mirror rows by upstream name (chunked `unnest`; dedup within chunk
/// for exact-duplicate lines). Returns rows affected.
pub async fn upsert_mirror(pool: &PgPool, rows: &[MirrorRow]) -> Result<u64, DbError> {
    let mut affected = 0u64;
    for chunk in rows.chunks(2000) {
        use std::collections::BTreeMap;
        let mut by_name: BTreeMap<&str, &MirrorRow> = BTreeMap::new();
        for r in chunk {
            by_name.insert(r.name.as_str(), r);
        }
        let (mut names, mut contigs): (Vec<&str>, Vec<&str>) = (Vec::new(), Vec::new());
        let mut positions: Vec<i64> = Vec::new();
        let (mut ancs, mut ders): (Vec<Option<&str>>, Vec<Option<&str>>) = (Vec::new(), Vec::new());
        let (mut coords, mut evs): (Vec<String>, Vec<String>) = (Vec::new(), Vec::new());
        for r in by_name.values() {
            names.push(&r.name);
            contigs.push(&r.contig);
            positions.push(r.position);
            ancs.push(r.allele_anc.as_deref());
            ders.push(r.allele_der.as_deref());
            coords.push(r.coordinates.to_string());
            evs.push(r.evidence.to_string());
        }
        affected += sqlx::query(
            "INSERT INTO source.ybrowse_snp (name, contig, position, allele_anc, allele_der, coordinates, evidence) \
             SELECT u.nm, u.ct, u.ps, u.an, u.de, u.co::jsonb, u.ev::jsonb \
             FROM unnest($1::text[], $2::text[], $3::bigint[], $4::text[], $5::text[], $6::text[], $7::text[]) \
                  AS u(nm, ct, ps, an, de, co, ev) \
             ON CONFLICT (name) DO UPDATE SET contig = EXCLUDED.contig, position = EXCLUDED.position, \
               allele_anc = EXCLUDED.allele_anc, allele_der = EXCLUDED.allele_der, \
               coordinates = EXCLUDED.coordinates, evidence = EXCLUDED.evidence, ingested_at = now()",
        )
        .bind(&names)
        .bind(&contigs)
        .bind(&positions)
        .bind(&ancs)
        .bind(&ders)
        .bind(&coords)
        .bind(&evs)
        .execute(pool)
        .await?
        .rows_affected();
    }
    Ok(affected)
}

pub async fn mirror_count(pool: &PgPool) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar("SELECT count(*) FROM source.ybrowse_snp").fetch_one(pool).await?)
}

/// Empty the mirror (e.g. a full clean reload). Reconciliation-derived
/// `core.variant` rows are unaffected.
pub async fn clear_mirror(pool: &PgPool) -> Result<u64, DbError> {
    Ok(sqlx::query("DELETE FROM source.ybrowse_snp").execute(pool).await?.rows_affected())
}

#[derive(Debug, Default, Clone)]
pub struct ReconcileReport {
    /// Physical-SNP clusters considered (excludes the pos=1 sentinel + allele-less rows).
    pub clusters: i64,
    /// New folded variants created.
    pub created: u64,
    /// Existing single-match variants enriched additively.
    pub enriched: u64,
    /// Clusters split across multiple existing variants, flagged for review.
    pub flagged: u64,
    /// Variants whose `annotations.region_overlaps` placement flag changed
    /// (recomputed against `core.genome_region` after the catalog is updated).
    pub region_flagged: u64,
}

/// Derive `core.variant` from the current mirror (see module docs). Idempotent:
/// re-running over the same mirror produces the same catalog. Runs in one
/// transaction over set-based temp tables.
pub async fn reconcile(pool: &PgPool) -> Result<ReconcileReport, DbError> {
    let mut tx = pool.begin().await?;

    // name -> existing variant id (by canonical OR common-name alias). The alias
    // arm EXPLODES each variant's common_names into (name, vid) rows up front and
    // hash-joins on text equality — a per-source-row `aliases ? s.name` probe over
    // 3M+ mirror rows can't use the jsonb_path_ops GIN index (it serves @>, not ?)
    // and degrades to a seq nested-loop that never finishes at full scale.
    // Match only PRIMARY identities (defining_haplogroup_id IS NULL). Recurrence
    // siblings share the canonical name + coordinate but are ISOGG-tree-internal
    // (homoplasy occurrences); ybrowse must not collide with them or it would see
    // every recurrent SNP as a multi-variant split and mass-flag it.
    sqlx::query(
        "CREATE TEMP TABLE _nv ON COMMIT DROP AS \
           SELECT s.name, v.id AS vid FROM source.ybrowse_snp s \
             JOIN core.variant v ON v.canonical_name = s.name AND v.defining_haplogroup_id IS NULL \
           UNION \
           SELECT s.name, cn.vid FROM source.ybrowse_snp s \
             JOIN ( \
               SELECT v.id AS vid, jsonb_array_elements_text(v.aliases->'common_names') AS nm \
               FROM core.variant v WHERE v.aliases ? 'common_names' AND v.defining_haplogroup_id IS NULL \
             ) cn ON cn.nm = s.name",
    )
    .execute(&mut *tx)
    .await?;

    // GRCh38 coordinate (strand-canonical) -> existing variant id. Lets a cluster
    // match a catalog variant we already hold even when NO name overlaps (#2).
    sqlx::query(
        "CREATE TEMP TABLE _cv ON COMMIT DROP AS \
           SELECT v.id AS vid, \
                  v.coordinates->'GRCh38'->>'contig' AS contig, \
                  (v.coordinates->'GRCh38'->>'position')::bigint AS position, \
                  core.ysnp_canon(v.coordinates->'GRCh38'->>'ancestral', \
                                  v.coordinates->'GRCh38'->>'derived') AS akey \
           FROM core.variant v \
           WHERE v.defining_haplogroup_id IS NULL \
             AND v.coordinates->'GRCh38'->>'contig' IS NOT NULL \
             AND v.coordinates->'GRCh38'->>'ancestral' IS NOT NULL \
             AND v.coordinates->'GRCh38'->>'derived' IS NOT NULL",
    )
    .execute(&mut *tx)
    .await?;
    sqlx::query("CREATE INDEX ON _cv (contig, position, akey)").execute(&mut *tx).await?;

    // One row per physical-SNP cluster, keyed by position + STRAND-CANONICAL
    // alleles (#1: A>G and reverse-complement T>C fold together). Carries ranked
    // names, a representative coordinate (preferring a canonical-strand row) +
    // evidence, and the name-matched existing variant ids.
    sqlx::query(
        "CREATE TEMP TABLE _cl ON COMMIT DROP AS \
           SELECT s.contig, s.position, core.ysnp_canon(s.allele_anc, s.allele_der) AS akey, \
                  array_agg(s.name ORDER BY core.ysnp_name_rank(s.name), s.name) AS names, \
                  min(core.ysnp_name_rank(s.name)) AS min_rank, \
                  (array_agg(s.coordinates ORDER BY \
                     (s.allele_anc || '>' || s.allele_der = core.ysnp_canon(s.allele_anc, s.allele_der)) DESC, \
                     core.ysnp_name_rank(s.name), s.name))[1] AS coords, \
                  (array_agg(s.evidence ORDER BY core.ysnp_name_rank(s.name), s.name))[1] AS evidence, \
                  array_remove(array_agg(DISTINCT nv.vid), NULL) AS name_vids \
           FROM source.ybrowse_snp s LEFT JOIN _nv nv ON nv.name = s.name \
           WHERE s.position > 1 AND s.allele_anc IS NOT NULL AND s.allele_der IS NOT NULL \
           GROUP BY s.contig, s.position, core.ysnp_canon(s.allele_anc, s.allele_der)",
    )
    .execute(&mut *tx)
    .await?;

    // Combine name matches with coordinate matches → the cluster's full vid set.
    sqlx::query(
        "CREATE TEMP TABLE _clv ON COMMIT DROP AS \
           SELECT c.*, ( \
             SELECT array_remove(array_agg(DISTINCT vid), NULL) FROM ( \
               SELECT unnest(c.name_vids) AS vid \
               UNION \
               SELECT cv.vid FROM _cv cv \
                 WHERE cv.contig = c.contig AND cv.position = c.position AND cv.akey = c.akey \
             ) z) AS vids \
           FROM _cl c",
    )
    .execute(&mut *tx)
    .await?;

    let clusters: i64 = sqlx::query_scalar("SELECT count(*) FROM _clv").fetch_one(&mut *tx).await?;

    // New clusters (no existing match) → create one folded variant. Provisional-
    // only clusters (best rank >= 90) get a minted DU name (NAMED); otherwise the
    // best-ranked existing name is canonical (UNNAMED — has a working name).
    let created = sqlx::query(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, aliases, coordinates, evidence) \
         SELECT \
           CASE WHEN c.min_rank >= 90 THEN core.next_du_name() ELSE c.names[1] END, \
           core.ysnp_mutation_type(split_part(c.akey,'>',1), split_part(c.akey,'>',2))::core.mutation_type, \
           CASE WHEN c.min_rank >= 90 THEN 'NAMED' ELSE 'UNNAMED' END::core.naming_status, \
           jsonb_build_object('common_names', to_jsonb( \
             CASE WHEN c.min_rank >= 90 THEN c.names ELSE c.names[2:] END)), \
           c.coords, c.evidence \
         FROM _clv c WHERE array_length(c.vids, 1) IS NULL \
         ON CONFLICT (canonical_name, COALESCE(defining_haplogroup_id, -1)) \
           WHERE canonical_name IS NOT NULL DO NOTHING",
    )
    .execute(&mut *tx)
    .await?
    .rows_affected();

    // Single-match clusters → enrich the existing variant additively. Union the
    // cluster's names into aliases (minus the canonical), fill missing coordinate
    // builds (existing wins), and set the YBrowse evidence. canonical_name and
    // naming_status are deliberately untouched (NAMED/curator choices locked).
    let enriched = sqlx::query(
        "UPDATE core.variant v SET \
           aliases = jsonb_set(COALESCE(v.aliases, '{}'::jsonb), '{common_names}', ( \
             SELECT COALESCE(jsonb_agg(DISTINCT x), '[]'::jsonb) FROM ( \
               SELECT jsonb_array_elements_text(COALESCE(v.aliases->'common_names', '[]'::jsonb)) AS x \
               UNION SELECT unnest(c.names) AS x) u WHERE x <> v.canonical_name), true), \
           coordinates = c.coords || v.coordinates, \
           evidence = v.evidence || c.evidence, \
           mutation_type = CASE \
             WHEN v.mutation_type = 'SNP' \
              AND core.ysnp_mutation_type(split_part(c.akey,'>',1), split_part(c.akey,'>',2)) <> 'SNP' \
             THEN core.ysnp_mutation_type(split_part(c.akey,'>',1), split_part(c.akey,'>',2))::core.mutation_type \
             ELSE v.mutation_type END, \
           updated_at = now() \
         FROM _clv c WHERE array_length(c.vids, 1) = 1 AND v.id = c.vids[1]",
    )
    .execute(&mut *tx)
    .await?
    .rows_affected();

    // Multi-match clusters → flag for curator review (rebuild the flag set).
    sqlx::query("TRUNCATE source.ybrowse_reconcile_flag").execute(&mut *tx).await?;
    let flagged = sqlx::query(
        "INSERT INTO source.ybrowse_reconcile_flag (contig, position, allele_anc, allele_der, names, variant_ids) \
         SELECT c.contig, c.position, split_part(c.akey,'>',1), split_part(c.akey,'>',2), c.names, c.vids \
         FROM _clv c WHERE array_length(c.vids, 1) > 1",
    )
    .execute(&mut *tx)
    .await?
    .rows_affected();

    tx.commit().await?;

    // Now that the catalog reflects this snapshot, recompute the Y-structural
    // placement flags (variants in palindrome/ampliconic/repeat/heterochromatin
    // sequence). Idempotent and outside the tx — needs the committed rows.
    let region_flagged = crate::variant::refresh_region_overlaps(pool).await?;

    // Refresh planner statistics on the freshly re-derived catalog — including the
    // `variant_alias_search_text` functional-index expression (mig 0061). A bulk reload
    // leaves core.variant with stale stats until autovacuum ANALYZEs it minutes later;
    // in that window the Variant Browser's `ORDER BY canonical_name LIMIT` search
    // misestimates the trigram predicates' selectivity and walks the name btree filtering
    // millions of rows (the alias function per row) instead of using the trigram bitmap —
    // pathologically slow. ANALYZE now so the catalog is search-ready the moment reconcile
    // returns.
    sqlx::query("ANALYZE core.variant").execute(pool).await?;

    // The reconcile re-derived core.variant from the snapshot (coords, naming,
    // enrichment) — bump the tree revision so Edge caches revalidate.
    crate::tree_revision::bump(pool).await?;

    Ok(ReconcileReport { clusters, created, enriched, flagged, region_flagged })
}

// ── reconcile-flag review queue ─────────────────────────────────────────────

/// A flagged cluster (synonyms split across multiple existing variants).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct FlagSummary {
    pub id: i64,
    /// Human locus, e.g. "chrY:10000088 G>A".
    pub locus: String,
    pub names: Vec<String>,
    pub variant_count: i32,
}

const FLAG_LOCUS: &str =
    "contig || ':' || position || ' ' || COALESCE(allele_anc,'?') || '>' || COALESCE(allele_der,'?')";

pub async fn flag_count(pool: &PgPool) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar("SELECT count(*) FROM source.ybrowse_reconcile_flag").fetch_one(pool).await?)
}

pub async fn list_flags(pool: &PgPool, page: i64, page_size: i64) -> Result<Page<FlagSummary>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let total = flag_count(pool).await?;
    let items: Vec<FlagSummary> = sqlx::query_as(&format!(
        "SELECT id, {FLAG_LOCUS} AS locus, names, \
                COALESCE(array_length(variant_ids,1),0) AS variant_count \
         FROM source.ybrowse_reconcile_flag ORDER BY id LIMIT $1 OFFSET $2"
    ))
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    Ok(Page { items, total, page: page.max(1), page_size: limit })
}

/// One conflicting variant in a flag: its canonical name + the branches it defines.
#[derive(Debug, Clone)]
pub struct FlagVariant {
    pub id: i64,
    pub canonical_name: Option<String>,
    pub defines: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct FlagDetail {
    pub id: i64,
    pub locus: String,
    pub names: Vec<String>,
    pub variants: Vec<FlagVariant>,
}

pub async fn flag(pool: &PgPool, id: i64) -> Result<Option<FlagDetail>, DbError> {
    let row: Option<(String, Vec<String>, Vec<i64>)> = sqlx::query_as(&format!(
        "SELECT {FLAG_LOCUS} AS locus, names, variant_ids FROM source.ybrowse_reconcile_flag WHERE id = $1"
    ))
    .bind(id)
    .fetch_optional(pool)
    .await?;
    let Some((locus, names, variant_ids)) = row else { return Ok(None) };

    let variants: Vec<FlagVariant> = sqlx::query_as(
        "SELECT v.id, v.canonical_name, \
                COALESCE(array_remove(array_agg(h.name ORDER BY h.name), NULL), '{}') AS defines \
         FROM core.variant v \
         LEFT JOIN tree.haplogroup_variant hv ON hv.variant_id = v.id AND hv.valid_until IS NULL \
         LEFT JOIN tree.haplogroup h ON h.id = hv.haplogroup_id AND h.valid_until IS NULL \
         WHERE v.id = ANY($1) GROUP BY v.id, v.canonical_name ORDER BY v.canonical_name",
    )
    .bind(&variant_ids)
    .fetch_all(pool)
    .await
    .map(|rows: Vec<(i64, Option<String>, Vec<String>)>| {
        rows.into_iter().map(|(id, canonical_name, defines)| FlagVariant { id, canonical_name, defines }).collect()
    })?;

    Ok(Some(FlagDetail { id, locus, names, variants }))
}

/// Remove a flag (after the curator merges its variants). Reconcile would also
/// drop it on the next run (the cluster now maps to one variant), but deleting
/// gives immediate feedback. Returns whether a row was removed.
pub async fn delete_flag(pool: &PgPool, id: i64) -> Result<bool, DbError> {
    Ok(sqlx::query("DELETE FROM source.ybrowse_reconcile_flag WHERE id = $1")
        .bind(id)
        .execute(pool)
        .await?
        .rows_affected()
        > 0)
}
