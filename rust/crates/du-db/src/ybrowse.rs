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

use crate::DbError;
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
}

/// Derive `core.variant` from the current mirror (see module docs). Idempotent:
/// re-running over the same mirror produces the same catalog. Runs in one
/// transaction over set-based temp tables.
pub async fn reconcile(pool: &PgPool) -> Result<ReconcileReport, DbError> {
    let mut tx = pool.begin().await?;

    // name -> existing variant id (by canonical OR common-name alias).
    sqlx::query(
        "CREATE TEMP TABLE _nv ON COMMIT DROP AS \
           SELECT s.name, v.id AS vid FROM source.ybrowse_snp s \
             JOIN core.variant v ON v.canonical_name = s.name \
           UNION \
           SELECT s.name, v.id FROM source.ybrowse_snp s \
             JOIN core.variant v ON v.aliases->'common_names' ? s.name",
    )
    .execute(&mut *tx)
    .await?;

    // One row per physical-SNP cluster: ranked names, a representative
    // coordinate/evidence, the set of existing variants its names map to, and the
    // best (lowest) name rank.
    sqlx::query(
        "CREATE TEMP TABLE _cl ON COMMIT DROP AS \
           SELECT s.contig, s.position, s.allele_anc, s.allele_der, \
                  array_agg(s.name ORDER BY core.ysnp_name_rank(s.name), s.name) AS names, \
                  min(core.ysnp_name_rank(s.name)) AS min_rank, \
                  (array_agg(s.coordinates ORDER BY core.ysnp_name_rank(s.name), s.name))[1] AS coords, \
                  (array_agg(s.evidence ORDER BY core.ysnp_name_rank(s.name), s.name))[1] AS evidence, \
                  array_remove(array_agg(DISTINCT nv.vid), NULL) AS vids \
           FROM source.ybrowse_snp s LEFT JOIN _nv nv ON nv.name = s.name \
           WHERE s.position > 1 AND s.allele_anc IS NOT NULL AND s.allele_der IS NOT NULL \
           GROUP BY s.contig, s.position, s.allele_anc, s.allele_der",
    )
    .execute(&mut *tx)
    .await?;

    let clusters: i64 = sqlx::query_scalar("SELECT count(*) FROM _cl").fetch_one(&mut *tx).await?;

    // New clusters (no existing match) → create one folded variant. Provisional-
    // only clusters (best rank >= 90) get a minted DU name (NAMED); otherwise the
    // best-ranked existing name is canonical (UNNAMED — has a working name).
    let created = sqlx::query(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, aliases, coordinates, evidence) \
         SELECT \
           CASE WHEN c.min_rank >= 90 THEN core.next_du_name() ELSE c.names[1] END, \
           'SNP'::core.mutation_type, \
           CASE WHEN c.min_rank >= 90 THEN 'NAMED' ELSE 'UNNAMED' END::core.naming_status, \
           jsonb_build_object('common_names', to_jsonb( \
             CASE WHEN c.min_rank >= 90 THEN c.names ELSE c.names[2:] END)), \
           c.coords, c.evidence \
         FROM _cl c WHERE array_length(c.vids, 1) IS NULL \
         ON CONFLICT (canonical_name) WHERE canonical_name IS NOT NULL DO NOTHING",
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
           updated_at = now() \
         FROM _cl c WHERE array_length(c.vids, 1) = 1 AND v.id = c.vids[1]",
    )
    .execute(&mut *tx)
    .await?
    .rows_affected();

    // Multi-match clusters → flag for curator review (rebuild the flag set).
    sqlx::query("TRUNCATE source.ybrowse_reconcile_flag").execute(&mut *tx).await?;
    let flagged = sqlx::query(
        "INSERT INTO source.ybrowse_reconcile_flag (contig, position, allele_anc, allele_der, names, variant_ids) \
         SELECT c.contig, c.position, c.allele_anc, c.allele_der, c.names, c.vids \
         FROM _cl c WHERE array_length(c.vids, 1) > 1",
    )
    .execute(&mut *tx)
    .await?
    .rows_affected();

    tx.commit().await?;
    Ok(ReconcileReport { clusters, created, enriched, flagged })
}
