//! Legacy -> new schema transformers. Validated against the real production
//! schema (`/Users/jkane/db.schema`): legacy `public.variant` is positional
//! (`variant` + `genbank_contig` + `variant_alias`), original-haplogroup calls
//! live in separate tables, `tree.haplogroup` has no provenance column, etc.
//!
//! Strategy unchanged: preserve primary keys (`OVERRIDING SYSTEM VALUE`) and
//! `sample_guid` UUIDs; idempotent upserts; one transaction per transformer.

use serde_json::{json, Map, Value};
use sqlx::types::chrono::{DateTime, NaiveDate, Utc};
use sqlx::PgPool;
use uuid::Uuid;

type Ts = Option<DateTime<Utc>>;

fn dna(s: &str) -> &'static str {
    match s.trim() {
        "MT" | "MT_DNA" | "mtDNA" => "MT_DNA",
        _ => "Y_DNA",
    }
}

/// Legacy `biosample_type` -> new `biosample_source`.
fn source_from_donor(s: &str) -> &'static str {
    match s.trim() {
        "Ancient" => "ANCIENT",
        "PGP" => "PGP",
        "Citizen" => "CITIZEN",
        "External" | "EXTERNAL" => "EXTERNAL",
        _ => "STANDARD",
    }
}

fn upper_opt(s: Option<String>) -> Option<String> {
    s.map(|v| v.trim().to_uppercase())
}

fn dedup(mut v: Vec<String>) -> Vec<String> {
    v.sort();
    v.dedup();
    v
}

// ── specimen donors ──────────────────────────────────────────────────────────
pub async fn specimen_donor(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (i64, Option<String>, Option<String>, Option<String>, Option<String>, Option<String>)>(
        "SELECT id::bigint, donor_identifier, origin_biobank, sex::text, donor_type::text, ST_AsEWKT(geocoord) \
         FROM public.specimen_donor",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, ident, biobank, sex, donor_type, ewkt) in rows {
        sqlx::query(
            "INSERT INTO core.specimen_donor (id, donor_identifier, origin_biobank, sex, donor_type, geocoord) \
             OVERRIDING SYSTEM VALUE \
             VALUES ($1,$2,$3,$4::core.biological_sex,$5::core.biosample_source, ST_GeomFromEWKT($6)) \
             ON CONFLICT (id) DO UPDATE SET donor_identifier=EXCLUDED.donor_identifier, \
               origin_biobank=EXCLUDED.origin_biobank, sex=EXCLUDED.sex, donor_type=EXCLUDED.donor_type, \
               geocoord=EXCLUDED.geocoord",
        )
        .bind(id)
        .bind(ident)
        .bind(biobank)
        .bind(upper_opt(sex))
        .bind(source_from_donor(donor_type.as_deref().unwrap_or("Standard")))
        .bind(ewkt)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "specimen_donor", rows = n, "migrated");
    Ok(())
}

// ── unified biosample (3 legacy tables -> 1) ─────────────────────────────────
const BIOSAMPLE_UPSERT: &str = "INSERT INTO core.biosample \
    (sample_guid, donor_id, source, accession, alias, description, center_name, locked, deleted, \
     source_attrs, original_haplogroups, atproto) \
    VALUES ($1,$2,$3::core.biosample_source,$4,$5,$6,$7,$8,$9,$10,$11,$12) \
    ON CONFLICT (sample_guid) DO UPDATE SET donor_id=EXCLUDED.donor_id, source=EXCLUDED.source, \
      accession=EXCLUDED.accession, alias=EXCLUDED.alias, description=EXCLUDED.description, \
      center_name=EXCLUDED.center_name, locked=EXCLUDED.locked, deleted=EXCLUDED.deleted, \
      source_attrs=EXCLUDED.source_attrs, original_haplogroups=EXCLUDED.original_haplogroups, \
      atproto=EXCLUDED.atproto";

#[derive(sqlx::FromRow)]
struct StdRow {
    sample_guid: Uuid,
    donor_id: Option<i64>,
    accession: Option<String>,
    alias: Option<String>,
    description: Option<String>,
    center_name: Option<String>,
    locked: bool,
    donor_type: Option<String>,
    original_haplogroups: Value,
    source_platform: Option<String>,
}

#[derive(sqlx::FromRow)]
struct CitRow {
    sample_guid: Uuid,
    donor_id: Option<i64>,
    accession: Option<String>,
    alias: Option<String>,
    description: Option<String>,
    deleted: bool,
    at_uri: Option<String>,
    at_cid: Option<String>,
    source_platform: Option<String>,
    y_haplogroup: Option<Value>,
    mt_haplogroup: Option<Value>,
    original_haplogroups: Value,
}

pub async fn biosample(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let mut tx = target.begin().await?;
    let mut total = 0usize;

    // Standard/Ancient samples — source from the donor type; original haplogroup
    // calls aggregated from biosample_original_haplogroup.
    let std_rows: Vec<StdRow> = sqlx::query_as(
        "SELECT b.sample_guid, b.specimen_donor_id::bigint AS donor_id, b.sample_accession AS accession, \
                b.alias, b.description, b.center_name, COALESCE(b.locked,false) AS locked, \
                d.donor_type::text AS donor_type, b.source_platform, \
                COALESCE((SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object( \
                    'publication_id', boh.publication_id, 'y', boh.original_y_haplogroup, \
                    'mt', boh.original_mt_haplogroup, 'y_result', boh.y_haplogroup_result, \
                    'mt_result', boh.mt_haplogroup_result))) \
                  FROM public.biosample_original_haplogroup boh WHERE boh.biosample_id = b.id), '[]'::jsonb) \
                  AS original_haplogroups \
         FROM public.biosample b LEFT JOIN public.specimen_donor d ON d.id = b.specimen_donor_id",
    )
    .fetch_all(legacy)
    .await?;
    for r in std_rows {
        sqlx::query(BIOSAMPLE_UPSERT)
            .bind(r.sample_guid)
            .bind(r.donor_id)
            .bind(source_from_donor(r.donor_type.as_deref().unwrap_or("Standard")))
            .bind(r.accession)
            .bind(r.alias)
            .bind(r.description)
            .bind(r.center_name)
            .bind(r.locked)
            .bind(false)
            .bind(json!({ "source_platform": r.source_platform }))
            .bind(r.original_haplogroups)
            .bind(None::<Value>)
            .execute(&mut *tx)
            .await?;
        total += 1;
    }

    // Citizen samples — at_uri/at_cid -> atproto; y/mt haplogroup -> source_attrs.
    let cit_rows: Vec<CitRow> = sqlx::query_as(
        "SELECT c.sample_guid, c.specimen_donor_id::bigint AS donor_id, c.accession, c.alias, c.description, \
                COALESCE(c.deleted,false) AS deleted, c.at_uri, c.at_cid, c.source_platform, \
                c.y_haplogroup, c.mt_haplogroup, \
                COALESCE((SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object( \
                    'publication_id', coh.publication_id, 'y_result', coh.y_haplogroup_result, \
                    'mt_result', coh.mt_haplogroup_result))) \
                  FROM public.citizen_biosample_original_haplogroup coh WHERE coh.citizen_biosample_id = c.id), '[]'::jsonb) \
                  AS original_haplogroups \
         FROM public.citizen_biosample c",
    )
    .fetch_all(legacy)
    .await?;
    for r in cit_rows {
        let atproto = r.at_uri.as_ref().map(|uri| json!({ "uri": uri, "cid": r.at_cid }));
        sqlx::query(BIOSAMPLE_UPSERT)
            .bind(r.sample_guid)
            .bind(r.donor_id)
            .bind("CITIZEN")
            .bind(r.accession)
            .bind(r.alias)
            .bind(r.description)
            .bind(None::<String>)
            .bind(false)
            .bind(r.deleted)
            .bind(json!({ "source_platform": r.source_platform, "y_haplogroup": r.y_haplogroup, "mt_haplogroup": r.mt_haplogroup }))
            .bind(r.original_haplogroups)
            .bind(atproto)
            .execute(&mut *tx)
            .await?;
        total += 1;
    }

    // PGP samples.
    let pgp_rows = sqlx::query_as::<_, (Uuid, Option<String>, Option<String>)>(
        "SELECT sample_guid, ena_biosample_accession, pgp_participant_id FROM public.pgp_biosample",
    )
    .fetch_all(legacy)
    .await?;
    for (guid, ena, pgp_id) in pgp_rows {
        sqlx::query(BIOSAMPLE_UPSERT)
            .bind(guid)
            .bind(None::<i64>)
            .bind("PGP")
            .bind(ena.clone())
            .bind(None::<String>)
            .bind(None::<String>)
            .bind(None::<String>)
            .bind(false)
            .bind(false)
            .bind(json!({ "pgp_participant_id": pgp_id, "ena_biosample_accession": ena }))
            .bind(json!([]))
            .bind(None::<Value>)
            .execute(&mut *tx)
            .await?;
        total += 1;
    }

    tx.commit().await?;
    tracing::info!(table = "biosample", rows = total, "migrated (standard+citizen+pgp -> core.biosample)");
    Ok(())
}

// ── variants (positional legacy model -> core.variant) ───────────────────────
//
// Legacy `public.variant` holds ONE row per (SNP, reference build, mutation
// DIRECTION). The same physical SNP appears once per build (GRCh38/GRCh37/hs1),
// and recurrent/back-mutation sites appear TWICE per build with opposite allele
// orientation (anc->der forward and der->anc reverse) — Ancestral State
// Reconstruction output. The redesigned `core.variant` is ONE row per physical
// SNP **site** with a multi-build `coordinates` JSONB; per-branch direction lives
// on `tree.haplogroup_variant` (ancestral_allele/derived_allele).
//
// `site_idx` = dense_rank by POSITION within (name, build): the two directional
// rows share a position -> same site -> fold to one variant; genuine within-build
// name reuse at a DIFFERENT position (~100 homoplasy/legacy-noise names) gets a
// new site_idx (>=2 -> UNNAMED, keeps the name as an alias, flagged for curation).
// FK references (haplogroup_variant) are repointed to the fold anchor
// (`min(variant_id)` per site) and carry their row's anc->der transition.

/// Shared base CTE: every legacy variant row annotated with its fold-site key
/// (`cname`, `site_idx`) and that row's ancestral->derived transition (`anc`/`der`,
/// the legacy reference/alternate columns, which encode mutation direction — the
/// genome reference is not the phylogenetic root). Callers append a projection.
const FOLD_BASE: &str = "WITH base AS ( \
    SELECT v.variant_id::bigint AS variant_id, \
           COALESCE(v.common_name, v.rs_id, gc.common_name || ':' || v.position::text) AS cname, \
           v.variant_type, v.common_name, v.rs_id, \
           gc.common_name AS contig, COALESCE(gc.reference_genome, 'GRCh38') AS build, \
           v.position::bigint AS position, v.reference_allele AS anc, v.alternate_allele AS der, \
           dense_rank() OVER ( \
             PARTITION BY COALESCE(v.common_name, v.rs_id, gc.common_name || ':' || v.position::text), \
                          COALESCE(gc.reference_genome, 'GRCh38') \
             ORDER BY v.position) AS site_idx \
    FROM public.variant v JOIN public.genbank_contig gc ON gc.genbank_contig_id = v.genbank_contig_id \
  )";

#[derive(sqlx::FromRow)]
struct FoldRow {
    id: i64,
    cname: String,
    site_idx: i64,
    is_indel: bool,
    named: bool,
    common_names: Vec<String>,
    rs_ids: Vec<String>,
    coordinates: Value,
    extra_aliases: Value,
}

pub async fn variant(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows: Vec<FoldRow> = sqlx::query_as(&format!(
        // `rep` = one representative directional row per (site, build) for the
        // variant's coordinate anc/der (provisional polarity; per-link is the truth).
        "{FOLD_BASE}, \
         rep AS ( \
           SELECT DISTINCT ON (cname, site_idx, build) \
                  cname, site_idx, build, contig, position, anc, der \
           FROM base ORDER BY cname, site_idx, build, variant_id \
         ), \
         agg AS ( \
           SELECT cname, site_idx, min(variant_id) AS id, \
                  bool_or(variant_type ILIKE 'indel') AS is_indel, \
                  bool_or(common_name IS NOT NULL) AS named, \
                  array_remove(array_agg(DISTINCT common_name), NULL) AS common_names, \
                  array_remove(array_agg(DISTINCT rs_id), NULL) AS rs_ids \
           FROM base GROUP BY cname, site_idx \
         ), \
         coords AS ( \
           SELECT cname, site_idx, \
                  jsonb_object_agg(build, jsonb_build_object( \
                    'contig', contig, 'position', position, \
                    'ancestral', anc, 'derived', der)) AS coordinates \
           FROM rep GROUP BY cname, site_idx \
         ), \
         alias_agg AS ( \
           SELECT b.cname, b.site_idx, \
                  jsonb_agg(jsonb_build_object('value', va.alias_value, 'type', va.alias_type, 'source', va.source)) AS aliases \
           FROM base b JOIN public.variant_alias va ON va.variant_id = b.variant_id \
           GROUP BY b.cname, b.site_idx \
         ) \
         SELECT a.id, a.cname, a.site_idx, a.is_indel, a.named, a.common_names, a.rs_ids, \
                c.coordinates, COALESCE(al.aliases, '[]'::jsonb) AS extra_aliases \
         FROM agg a JOIN coords c USING (cname, site_idx) \
                    LEFT JOIN alias_agg al USING (cname, site_idx)"
    ))
    .fetch_all(legacy)
    .await?;
    let n = rows.len();

    let mut tx = target.begin().await?;
    for chunk in rows.chunks(5000) {
        let mut ids = Vec::with_capacity(chunk.len());
        let mut names: Vec<Option<String>> = Vec::with_capacity(chunk.len());
        let mut mtypes: Vec<&str> = Vec::with_capacity(chunk.len());
        let mut statuses: Vec<&str> = Vec::with_capacity(chunk.len());
        let mut aliases: Vec<String> = Vec::with_capacity(chunk.len());
        let mut coords: Vec<String> = Vec::with_capacity(chunk.len());

        for r in chunk {
            let owns_name = r.site_idx == 1;
            let mut common_names = r.common_names.clone();
            let mut rs_ids = r.rs_ids.clone();
            let mut sources = Map::new();
            // A different-locus homoplasy site (site_idx > 1) keeps the name as an alias.
            if !owns_name {
                common_names.push(r.cname.clone());
            }
            if let Some(arr) = r.extra_aliases.as_array() {
                for a in arr {
                    let val = a.get("value").and_then(Value::as_str).unwrap_or("");
                    if val.is_empty() {
                        continue;
                    }
                    let typ = a.get("type").and_then(Value::as_str).unwrap_or("").to_lowercase();
                    if typ.contains("rs") {
                        rs_ids.push(val.to_string());
                    } else {
                        common_names.push(val.to_string());
                    }
                    if let Some(src) = a.get("source").and_then(Value::as_str) {
                        sources.insert(val.to_string(), json!(src));
                    }
                }
            }
            ids.push(r.id);
            names.push(owns_name.then(|| r.cname.clone()));
            mtypes.push(if r.is_indel { "INDEL" } else { "SNP" });
            statuses.push(if owns_name && r.named { "NAMED" } else { "UNNAMED" });
            aliases.push(json!({ "common_names": dedup(common_names), "rs_ids": dedup(rs_ids), "sources": sources }).to_string());
            coords.push(r.coordinates.to_string());
        }

        sqlx::query(
            "INSERT INTO core.variant (id, canonical_name, mutation_type, naming_status, aliases, coordinates) \
             OVERRIDING SYSTEM VALUE \
             SELECT u.id, u.cn, u.mt::core.mutation_type, u.ns::core.naming_status, u.al::jsonb, u.co::jsonb \
             FROM unnest($1::bigint[], $2::text[], $3::text[], $4::text[], $5::text[], $6::text[]) \
                  AS u(id, cn, mt, ns, al, co) \
             ON CONFLICT (id) DO UPDATE SET canonical_name=EXCLUDED.canonical_name, mutation_type=EXCLUDED.mutation_type, \
               naming_status=EXCLUDED.naming_status, aliases=EXCLUDED.aliases, coordinates=EXCLUDED.coordinates",
        )
        .bind(&ids)
        .bind(&names)
        .bind(&mtypes)
        .bind(&statuses)
        .bind(&aliases)
        .bind(&coords)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "variant", rows = n, "migrated (folded legacy per-build rows by SNP)");
    Ok(())
}

// ── haplogroups + edges + variant associations ──────────────────────────────
pub async fn haplogroup(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (i64, String, String, Option<String>, Option<String>, Option<String>, Option<i32>, Option<i32>, Value, Ts, Ts)>(
        "SELECT haplogroup_id::bigint, name, haplogroup_type::text, lineage, source, confidence_level, \
                formed_ybp, tmrca_ybp, \
                jsonb_strip_nulls(jsonb_build_object('age_estimate_source', age_estimate_source, \
                  'formed_ybp_lower', formed_ybp_lower, 'formed_ybp_upper', formed_ybp_upper, \
                  'tmrca_ybp_lower', tmrca_ybp_lower, 'tmrca_ybp_upper', tmrca_ybp_upper, \
                  'description', description)) AS provenance, \
                valid_from::timestamptz, valid_until::timestamptz \
         FROM tree.haplogroup",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, name, htype, lineage, source, conf, formed, tmrca, prov, vfrom, vuntil) in rows {
        sqlx::query(
            "INSERT INTO tree.haplogroup (id, name, haplogroup_type, lineage, source, confidence_level, \
                formed_ybp, tmrca_ybp, provenance, valid_from, valid_until) OVERRIDING SYSTEM VALUE \
             VALUES ($1,$2,$3::core.dna_type,$4,$5,$6,$7,$8,$9,COALESCE($10, now()),$11) \
             ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, haplogroup_type=EXCLUDED.haplogroup_type, \
               lineage=EXCLUDED.lineage, source=EXCLUDED.source, confidence_level=EXCLUDED.confidence_level, \
               formed_ybp=EXCLUDED.formed_ybp, tmrca_ybp=EXCLUDED.tmrca_ybp, provenance=EXCLUDED.provenance, \
               valid_from=EXCLUDED.valid_from, valid_until=EXCLUDED.valid_until",
        )
        .bind(id)
        .bind(name)
        .bind(dna(&htype))
        .bind(lineage)
        .bind(source)
        .bind(conf)
        .bind(formed)
        .bind(tmrca)
        .bind(prov)
        .bind(vfrom)
        .bind(vuntil)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "haplogroup", rows = n, "migrated");
    Ok(())
}

pub async fn haplogroup_relationship(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (i64, i64, Option<i64>, Option<i32>, Option<String>, Ts, Ts)>(
        "SELECT haplogroup_relationship_id::bigint, child_haplogroup_id::bigint, parent_haplogroup_id::bigint, \
                revision_id, source, valid_from::timestamptz, valid_until::timestamptz FROM tree.haplogroup_relationship",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, child, parent, rev, source, vfrom, vuntil) in rows {
        sqlx::query(
            "INSERT INTO tree.haplogroup_relationship (id, child_haplogroup_id, parent_haplogroup_id, \
                revision_id, source, revision, valid_from, valid_until) OVERRIDING SYSTEM VALUE \
             VALUES ($1,$2,$3,COALESCE($4,1),$5,'{}'::jsonb,COALESCE($6, now()),$7) \
             ON CONFLICT (id) DO UPDATE SET child_haplogroup_id=EXCLUDED.child_haplogroup_id, \
               parent_haplogroup_id=EXCLUDED.parent_haplogroup_id, revision_id=EXCLUDED.revision_id, \
               source=EXCLUDED.source, valid_from=EXCLUDED.valid_from, valid_until=EXCLUDED.valid_until",
        )
        .bind(id)
        .bind(child)
        .bind(parent)
        .bind(rev)
        .bind(source)
        .bind(vfrom)
        .bind(vuntil)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "haplogroup_relationship", rows = n, "migrated");
    Ok(())
}

pub async fn haplogroup_variant(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    // Repoint each legacy link's variant_id to its fold-site anchor (the same
    // `min(variant_id) per (cname, site_idx)` the variant transform used), and
    // carry the legacy row's anc->der as the link's per-branch transition. Collapse
    // the per-build duplicates a haplogroup accrued (it linked the same SNP once per
    // build, same direction) to one link per (haplogroup, folded variant). A branch
    // that links both directions of a site (contradiction) keeps the lowest link id.
    let rows = sqlx::query_as::<_, (i64, i64, i64, Option<String>, Option<String>)>(&format!(
        "{FOLD_BASE}, \
         remap AS (SELECT variant_id AS legacy_id, \
                          min(variant_id) OVER (PARTITION BY cname, site_idx) AS fold_id, \
                          anc, der FROM base) \
         SELECT DISTINCT ON (hv.haplogroup_id, r.fold_id) \
                hv.haplogroup_variant_id::bigint, hv.haplogroup_id::bigint, r.fold_id::bigint, \
                r.anc, r.der \
         FROM tree.haplogroup_variant hv JOIN remap r ON r.legacy_id = hv.variant_id \
         ORDER BY hv.haplogroup_id, r.fold_id, hv.haplogroup_variant_id"
    ))
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for chunk in rows.chunks(5000) {
        let ids: Vec<i64> = chunk.iter().map(|r| r.0).collect();
        let hgs: Vec<i64> = chunk.iter().map(|r| r.1).collect();
        let vars: Vec<i64> = chunk.iter().map(|r| r.2).collect();
        let ancs: Vec<Option<String>> = chunk.iter().map(|r| r.3.clone()).collect();
        let ders: Vec<Option<String>> = chunk.iter().map(|r| r.4.clone()).collect();
        sqlx::query(
            "INSERT INTO tree.haplogroup_variant (id, haplogroup_id, variant_id, ancestral_allele, derived_allele, revision, valid_from) \
             OVERRIDING SYSTEM VALUE \
             SELECT u.id, u.hg, u.var, u.anc, u.der, '{}'::jsonb, now() \
             FROM unnest($1::bigint[], $2::bigint[], $3::bigint[], $4::text[], $5::text[]) AS u(id, hg, var, anc, der) \
             ON CONFLICT (id) DO UPDATE SET haplogroup_id=EXCLUDED.haplogroup_id, variant_id=EXCLUDED.variant_id, \
               ancestral_allele=EXCLUDED.ancestral_allele, derived_allele=EXCLUDED.derived_allele",
        )
        .bind(&ids)
        .bind(&hgs)
        .bind(&vars)
        .bind(&ancs)
        .bind(&ders)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "haplogroup_variant", rows = n, "migrated (folded sites + per-branch anc/der)");
    Ok(())
}

// ── publications + studies + links ───────────────────────────────────────────
#[derive(sqlx::FromRow)]
struct StudyRow {
    id: i64,
    accession: String,
    title: Option<String>,
    center_name: Option<String>,
    study_name: Option<String>,
    source: Option<String>,
    bio_project_id: Option<String>,
    molecule: Option<String>,
    topology: Option<String>,
    taxonomy_id: Option<i32>,
    version: Option<String>,
    submission_date: Option<NaiveDate>,
    details: Value,
}

pub async fn genomic_study(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows: Vec<StudyRow> = sqlx::query_as(
        "SELECT id::bigint, accession, title, center_name, study_name, source::text AS source, \
                bio_project_id, molecule, topology, taxonomy_id, version, submission_date, \
                jsonb_strip_nulls(jsonb_build_object('text', details, 'last_update', last_update::text)) AS details \
         FROM public.genomic_studies",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for r in rows {
        sqlx::query(
            "INSERT INTO pubs.genomic_study (id, accession, title, center_name, study_name, source, \
                bio_project_id, molecule, topology, taxonomy_id, version, submission_date, details) \
             OVERRIDING SYSTEM VALUE \
             VALUES ($1,$2,$3,$4,$5,COALESCE($6,'ENA')::pubs.study_source,$7,$8,$9,$10,$11,$12,$13) \
             ON CONFLICT (id) DO UPDATE SET accession=EXCLUDED.accession, title=EXCLUDED.title, \
               center_name=EXCLUDED.center_name, study_name=EXCLUDED.study_name, source=EXCLUDED.source, \
               bio_project_id=EXCLUDED.bio_project_id, molecule=EXCLUDED.molecule, topology=EXCLUDED.topology, \
               taxonomy_id=EXCLUDED.taxonomy_id, version=EXCLUDED.version, submission_date=EXCLUDED.submission_date, \
               details=EXCLUDED.details",
        )
        .bind(r.id)
        .bind(r.accession)
        .bind(r.title)
        .bind(r.center_name)
        .bind(r.study_name)
        .bind(r.source.map(|s| s.to_uppercase()))
        .bind(r.bio_project_id)
        .bind(r.molecule)
        .bind(r.topology)
        .bind(r.taxonomy_id)
        .bind(r.version)
        .bind(r.submission_date)
        .bind(r.details)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "genomic_study", rows = n, "migrated");
    Ok(())
}

#[derive(sqlx::FromRow)]
struct PubRow {
    id: i64,
    pubmed_id: Option<String>,
    doi: Option<String>,
    open_alex_id: Option<String>,
    title: String,
    journal: Option<String>,
    publication_date: Option<NaiveDate>,
    url: Option<String>,
    authors: Option<String>,
    abstract_summary: Option<String>,
    cited_by_count: Option<i32>,
    open_access_status: Option<String>,
}

pub async fn publication(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows: Vec<PubRow> = sqlx::query_as(
        "SELECT id::bigint, pubmed_id, doi, open_alex_id, title, journal, publication_date, url, authors, \
                abstract_summary, cited_by_count, open_access_status FROM public.publication",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for r in rows {
        sqlx::query(
            "INSERT INTO pubs.publication (id, pubmed_id, doi, open_alex_id, title, journal, publication_date, \
                url, authors, abstract_summary, cited_by_count, open_access_status) OVERRIDING SYSTEM VALUE \
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12) \
             ON CONFLICT (id) DO UPDATE SET pubmed_id=EXCLUDED.pubmed_id, doi=EXCLUDED.doi, \
               open_alex_id=EXCLUDED.open_alex_id, title=EXCLUDED.title, journal=EXCLUDED.journal, \
               publication_date=EXCLUDED.publication_date, url=EXCLUDED.url, authors=EXCLUDED.authors, \
               abstract_summary=EXCLUDED.abstract_summary, cited_by_count=EXCLUDED.cited_by_count, \
               open_access_status=EXCLUDED.open_access_status",
        )
        .bind(r.id)
        .bind(r.pubmed_id)
        .bind(r.doi)
        .bind(r.open_alex_id)
        .bind(r.title)
        .bind(r.journal)
        .bind(r.publication_date)
        .bind(r.url)
        .bind(r.authors)
        .bind(r.abstract_summary)
        .bind(r.cited_by_count)
        .bind(r.open_access_status)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "publication", rows = n, "migrated");
    Ok(())
}

/// Both legacy biosample link tables -> the unified link, resolving legacy
/// integer ids to the preserved `sample_guid`.
pub async fn publication_biosample(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let std = sqlx::query_as::<_, (i64, Uuid)>(
        "SELECT pb.publication_id::bigint, b.sample_guid \
         FROM public.publication_biosample pb JOIN public.biosample b ON b.id = pb.biosample_id",
    )
    .fetch_all(legacy)
    .await?;
    let cit = sqlx::query_as::<_, (i64, Uuid)>(
        "SELECT pcb.publication_id::bigint, c.sample_guid \
         FROM public.publication_citizen_biosample pcb JOIN public.citizen_biosample c ON c.id = pcb.citizen_biosample_id",
    )
    .fetch_all(legacy)
    .await?;
    let n = std.len() + cit.len();
    let mut tx = target.begin().await?;
    for (pub_id, guid) in std.into_iter().chain(cit) {
        sqlx::query(
            "INSERT INTO pubs.publication_biosample (publication_id, sample_guid) VALUES ($1,$2) \
             ON CONFLICT DO NOTHING",
        )
        .bind(pub_id)
        .bind(guid)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "publication_biosample", rows = n, "migrated");
    Ok(())
}

/// Legacy `publication_ena_study` -> `pubs.publication_study`.
pub async fn publication_study(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (i64, i64)>(
        "SELECT publication_id::bigint, genomic_study_id::bigint FROM public.publication_ena_study",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (pub_id, study_id) in rows {
        sqlx::query(
            "INSERT INTO pubs.publication_study (publication_id, study_id) VALUES ($1,$2) ON CONFLICT DO NOTHING",
        )
        .bind(pub_id)
        .bind(study_id)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "publication_study", rows = n, "migrated");
    Ok(())
}

// ── ident: users, RBAC, AT Protocol identity/OAuth, consent, audit ───────────
// All these legacy tables use UUID PKs, so ids carry over 1:1 (no OVERRIDING
// SYSTEM VALUE). Legacy timestamps are `timestamp without time zone`, cast to
// timestamptz so they decode into DateTime<Utc>. Auth is AT Protocol OAuth-only
// in production (no password table), so password_hash stays NULL.

#[derive(sqlx::FromRow)]
struct UserRow {
    id: Uuid,
    email: Option<String>,
    did: Option<String>,
    handle: Option<String>,
    display_name: Option<String>,
    is_active: bool,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

pub async fn users(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows: Vec<UserRow> = sqlx::query_as(
        "SELECT id, email::text AS email, did, handle, display_name, is_active, \
                created_at::timestamptz, updated_at::timestamptz FROM public.users",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for r in rows {
        sqlx::query(
            "INSERT INTO ident.users (id, email, did, handle, display_name, is_active, created_at, updated_at) \
             VALUES ($1,$2::citext,$3,$4,$5,$6,$7,$8) \
             ON CONFLICT (id) DO UPDATE SET email=EXCLUDED.email, did=EXCLUDED.did, handle=EXCLUDED.handle, \
               display_name=EXCLUDED.display_name, is_active=EXCLUDED.is_active, updated_at=EXCLUDED.updated_at",
        )
        .bind(r.id)
        .bind(r.email)
        .bind(r.did)
        .bind(r.handle)
        .bind(r.display_name)
        .bind(r.is_active)
        .bind(r.created_at)
        .bind(r.updated_at)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "users", rows = n, "migrated");
    Ok(())
}

pub async fn roles(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (Uuid, String, Option<String>, DateTime<Utc>, DateTime<Utc>)>(
        "SELECT id, name, description, created_at::timestamptz, updated_at::timestamptz FROM auth.roles",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, name, desc, created, updated) in rows {
        // The schema pre-seeds base roles (Admin/Curator/TreeCurator) with random
        // UUIDs; relocate them to the legacy UUID so user_roles FKs resolve.
        sqlx::query(
            "INSERT INTO ident.roles (id, name, description, created_at, updated_at) VALUES ($1,$2,$3,$4,$5) \
             ON CONFLICT (name) DO UPDATE SET id=EXCLUDED.id, description=EXCLUDED.description, \
               updated_at=EXCLUDED.updated_at",
        )
        .bind(id)
        .bind(name)
        .bind(desc)
        .bind(created)
        .bind(updated)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "roles", rows = n, "migrated");
    Ok(())
}

pub async fn permissions(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (Uuid, String, Option<String>, DateTime<Utc>, DateTime<Utc>)>(
        "SELECT id, name, description, created_at::timestamptz, updated_at::timestamptz FROM auth.permissions",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, name, desc, created, updated) in rows {
        sqlx::query(
            "INSERT INTO ident.permissions (id, name, description, created_at, updated_at) VALUES ($1,$2,$3,$4,$5) \
             ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, \
               updated_at=EXCLUDED.updated_at",
        )
        .bind(id)
        .bind(name)
        .bind(desc)
        .bind(created)
        .bind(updated)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "permissions", rows = n, "migrated");
    Ok(())
}

pub async fn role_permissions(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (Uuid, Uuid)>(
        "SELECT role_id, permission_id FROM auth.role_permissions",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (role_id, perm_id) in rows {
        sqlx::query(
            "INSERT INTO ident.role_permissions (role_id, permission_id) VALUES ($1,$2) ON CONFLICT DO NOTHING",
        )
        .bind(role_id)
        .bind(perm_id)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "role_permissions", rows = n, "migrated");
    Ok(())
}

pub async fn user_roles(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (Uuid, Uuid)>("SELECT user_id, role_id FROM auth.user_roles")
        .fetch_all(legacy)
        .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (user_id, role_id) in rows {
        sqlx::query(
            "INSERT INTO ident.user_roles (user_id, role_id) VALUES ($1,$2) ON CONFLICT DO NOTHING",
        )
        .bind(user_id)
        .bind(role_id)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "user_roles", rows = n, "migrated");
    Ok(())
}

pub async fn user_login_info(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (Uuid, Uuid, String, String, DateTime<Utc>, DateTime<Utc>)>(
        "SELECT id, user_id, provider_id, provider_key, created_at::timestamptz, updated_at::timestamptz \
         FROM auth.user_login_info",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, user_id, pid, pkey, created, updated) in rows {
        // password_hash NULL: production auth is AT Protocol OAuth, not passwords.
        sqlx::query(
            "INSERT INTO ident.user_login_info (id, user_id, provider_id, provider_key, password_hash, created_at, updated_at) \
             VALUES ($1,$2,$3,$4,NULL,$5,$6) \
             ON CONFLICT (id) DO UPDATE SET user_id=EXCLUDED.user_id, provider_id=EXCLUDED.provider_id, \
               provider_key=EXCLUDED.provider_key, updated_at=EXCLUDED.updated_at",
        )
        .bind(id)
        .bind(user_id)
        .bind(pid)
        .bind(pkey)
        .bind(created)
        .bind(updated)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "user_login_info", rows = n, "migrated");
    Ok(())
}

pub async fn user_oauth2_info(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (Uuid, Uuid, String, Option<String>, Option<i64>, Option<String>, Option<String>, DateTime<Utc>, DateTime<Utc>)>(
        "SELECT id, login_info_id, access_token, token_type, expires_in, refresh_token, scope, \
                created_at::timestamptz, updated_at::timestamptz FROM auth.user_oauth2_info",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, lid, access, ttype, expires, refresh, scope, created, updated) in rows {
        sqlx::query(
            "INSERT INTO ident.user_oauth2_info (id, login_info_id, access_token, token_type, expires_in, \
                refresh_token, scope, created_at, updated_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) \
             ON CONFLICT (id) DO UPDATE SET login_info_id=EXCLUDED.login_info_id, access_token=EXCLUDED.access_token, \
               token_type=EXCLUDED.token_type, expires_in=EXCLUDED.expires_in, refresh_token=EXCLUDED.refresh_token, \
               scope=EXCLUDED.scope, updated_at=EXCLUDED.updated_at",
        )
        .bind(id)
        .bind(lid)
        .bind(access)
        .bind(ttype)
        .bind(expires)
        .bind(refresh)
        .bind(scope)
        .bind(created)
        .bind(updated)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "user_oauth2_info", rows = n, "migrated");
    Ok(())
}

pub async fn user_pds_info(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (Uuid, Uuid, String, String, Option<String>, DateTime<Utc>, DateTime<Utc>)>(
        "SELECT id, user_id, pds_url, did, handle, created_at::timestamptz, updated_at::timestamptz \
         FROM auth.user_pds_info",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, user_id, pds_url, did, handle, created, updated) in rows {
        sqlx::query(
            "INSERT INTO ident.user_pds_info (id, user_id, pds_url, did, handle, created_at, updated_at) \
             VALUES ($1,$2,$3,$4,$5,$6,$7) \
             ON CONFLICT (id) DO UPDATE SET user_id=EXCLUDED.user_id, pds_url=EXCLUDED.pds_url, \
               did=EXCLUDED.did, handle=EXCLUDED.handle, updated_at=EXCLUDED.updated_at",
        )
        .bind(id)
        .bind(user_id)
        .bind(pds_url)
        .bind(did)
        .bind(handle)
        .bind(created)
        .bind(updated)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "user_pds_info", rows = n, "migrated");
    Ok(())
}

pub async fn cookie_consents(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (Uuid, Option<Uuid>, Option<String>, Option<String>, bool, DateTime<Utc>, Option<String>, Option<String>, DateTime<Utc>)>(
        "SELECT id, user_id, session_id, ip_address_hash, consent_given, consent_timestamp::timestamptz, \
                policy_version, user_agent, created_at::timestamptz FROM auth.cookie_consents",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, user_id, session_id, iph, given, ts, ver, ua, created) in rows {
        sqlx::query(
            "INSERT INTO ident.cookie_consents (id, user_id, session_id, ip_address_hash, consent_given, \
                consent_timestamp, policy_version, user_agent, created_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) \
             ON CONFLICT (id) DO NOTHING",
        )
        .bind(id)
        .bind(user_id)
        .bind(session_id)
        .bind(iph)
        .bind(given)
        .bind(ts)
        .bind(ver)
        .bind(ua)
        .bind(created)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "cookie_consents", rows = n, "migrated");
    Ok(())
}

pub async fn atproto_metadata(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    // Authorization servers (legacy `client_id_metadata_document_supported` dropped).
    let servers = sqlx::query_as::<_, (Uuid, String, Option<String>, Option<String>, Option<String>, Option<String>, Option<String>, DateTime<Utc>, DateTime<Utc>, DateTime<Utc>)>(
        "SELECT id, issuer_url, authorization_endpoint, token_endpoint, \
                pushed_authorization_request_endpoint, dpop_signing_alg_values_supported, scopes_supported, \
                metadata_fetched_at::timestamptz, created_at::timestamptz, updated_at::timestamptz \
         FROM auth.atprotocol_authorization_servers",
    )
    .fetch_all(legacy)
    .await?;
    let s = servers.len();
    let mut tx = target.begin().await?;
    for (id, issuer, az, tok, par, dpop, scopes, fetched, created, updated) in servers {
        sqlx::query(
            "INSERT INTO ident.atprotocol_authorization_servers (id, issuer_url, authorization_endpoint, \
                token_endpoint, pushed_authorization_request_endpoint, dpop_signing_alg_values_supported, \
                scopes_supported, metadata_fetched_at, created_at, updated_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) \
             ON CONFLICT (id) DO UPDATE SET issuer_url=EXCLUDED.issuer_url, \
               authorization_endpoint=EXCLUDED.authorization_endpoint, token_endpoint=EXCLUDED.token_endpoint, \
               pushed_authorization_request_endpoint=EXCLUDED.pushed_authorization_request_endpoint, \
               dpop_signing_alg_values_supported=EXCLUDED.dpop_signing_alg_values_supported, \
               scopes_supported=EXCLUDED.scopes_supported, metadata_fetched_at=EXCLUDED.metadata_fetched_at, \
               updated_at=EXCLUDED.updated_at",
        )
        .bind(id).bind(issuer).bind(az).bind(tok).bind(par).bind(dpop).bind(scopes).bind(fetched).bind(created).bind(updated)
        .execute(&mut *tx)
        .await?;
    }
    // Client metadata (legacy `client_uri` dropped).
    let clients = sqlx::query_as::<_, (Uuid, String, Option<String>, Option<String>, Option<String>, Option<String>, Option<String>, DateTime<Utc>, DateTime<Utc>)>(
        "SELECT id, client_id_url, client_name, logo_uri, tos_uri, policy_uri, redirect_uris, \
                created_at::timestamptz, updated_at::timestamptz FROM auth.atprotocol_client_metadata",
    )
    .fetch_all(legacy)
    .await?;
    let c = clients.len();
    for (id, url, name, logo, tos, policy, redirects, created, updated) in clients {
        sqlx::query(
            "INSERT INTO ident.atprotocol_client_metadata (id, client_id_url, client_name, logo_uri, tos_uri, \
                policy_uri, redirect_uris, created_at, updated_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) \
             ON CONFLICT (id) DO UPDATE SET client_id_url=EXCLUDED.client_id_url, client_name=EXCLUDED.client_name, \
               logo_uri=EXCLUDED.logo_uri, tos_uri=EXCLUDED.tos_uri, policy_uri=EXCLUDED.policy_uri, \
               redirect_uris=EXCLUDED.redirect_uris, updated_at=EXCLUDED.updated_at",
        )
        .bind(id).bind(url).bind(name).bind(logo).bind(tos).bind(policy).bind(redirects).bind(created).bind(updated)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(servers = s, clients = c, "migrated AT Protocol metadata caches");
    Ok(())
}

/// Legacy `curator.audit_log` -> `ident.audit_log` (entity_id widened to bigint).
pub async fn audit_log(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (Uuid, Uuid, String, i32, String, Option<Value>, Option<Value>, Option<String>, DateTime<Utc>)>(
        "SELECT id, user_id, entity_type, entity_id, action, old_value, new_value, comment, \
                created_at::timestamptz FROM curator.audit_log",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, user_id, etype, eid, action, old, new, comment, created) in rows {
        sqlx::query(
            "INSERT INTO ident.audit_log (id, user_id, entity_type, entity_id, action, old_value, new_value, \
                comment, created_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) ON CONFLICT (id) DO NOTHING",
        )
        .bind(id)
        .bind(user_id)
        .bind(etype)
        .bind(eid as i64)
        .bind(action)
        .bind(old)
        .bind(new)
        .bind(comment)
        .bind(created)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "audit_log", rows = n, "migrated");
    Ok(())
}

// ── genomics: contigs, labs, instruments, test types, pangenome, sequencing ──
// Validated against db.schema. The legacy `alignment_coverage` /
// `pangenome_alignment_coverage` child tables and assorted non-columnar fields
// fold into the redesigned `coverage`/`metadata` JSONB. The coverage page reads
// `coverage->>'meanDepth'` and `->>'percent_coverage_at_10x'`, so those keys are
// always populated when a source value exists. Tables with no production source
// are skipped (instrument_observation, instrument_association_proposal,
// coverage_expectation_profile, biosample_callable_loci — Navigator populates
// them going forward); `pangenome_node` is dropped (folded into node-id arrays).

pub async fn genbank_contig(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (i64, String, Option<String>, Option<String>, Option<i64>)>(
        "SELECT genbank_contig_id::bigint, accession, common_name, reference_genome, seq_length::bigint \
         FROM public.genbank_contig",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, acc, common, refg, len) in rows {
        sqlx::query(
            "INSERT INTO genomics.genbank_contig (id, accession, common_name, reference_genome, seq_length) \
             OVERRIDING SYSTEM VALUE VALUES ($1,$2,$3,COALESCE($4,'GRCh38'),$5) \
             ON CONFLICT (id) DO UPDATE SET accession=EXCLUDED.accession, common_name=EXCLUDED.common_name, \
               reference_genome=EXCLUDED.reference_genome, seq_length=EXCLUDED.seq_length",
        )
        .bind(id).bind(acc).bind(common).bind(refg).bind(len)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "genbank_contig", rows = n, "migrated");
    Ok(())
}

pub async fn sequencing_lab(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (i64, String, bool, Option<String>, Option<String>)>(
        "SELECT id::bigint, name, is_d2c, website_url, description_markdown FROM public.sequencing_lab",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, name, d2c, web, desc) in rows {
        sqlx::query(
            "INSERT INTO genomics.sequencing_lab (id, name, is_d2c, website_url, description_markdown) \
             OVERRIDING SYSTEM VALUE VALUES ($1,$2,$3,$4,$5) \
             ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, is_d2c=EXCLUDED.is_d2c, \
               website_url=EXCLUDED.website_url, description_markdown=EXCLUDED.description_markdown",
        )
        .bind(id).bind(name).bind(d2c).bind(web).bind(desc)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "sequencing_lab", rows = n, "migrated");
    Ok(())
}

pub async fn sequencer_instrument(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    // instrument_id is now UNIQUE; dedup to the first row per instrument_id
    // (target<legacy here is benign de-duplication). The legacy per-lab tie is
    // carried over as the preseeded direct `lab_id` (mig 0025) — the lookup API
    // resolves through it (the proposal/consensus path is not live yet).
    let rows = sqlx::query_as::<_, (i64, String, Option<String>, Option<String>, Option<i64>)>(
        "SELECT DISTINCT ON (instrument_id) id::bigint, instrument_id, model, manufacturer, lab_id::bigint \
         FROM public.sequencer_instrument ORDER BY instrument_id, id",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, iid, model, manuf, lab_id) in rows {
        sqlx::query(
            "INSERT INTO genomics.sequencer_instrument (id, instrument_id, model_name, manufacturer, lab_id) \
             OVERRIDING SYSTEM VALUE VALUES ($1,$2,$3,$4,$5) ON CONFLICT (instrument_id) DO NOTHING",
        )
        .bind(id).bind(iid).bind(model).bind(manuf).bind(lab_id)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "sequencer_instrument", rows = n, "migrated");
    Ok(())
}

#[derive(sqlx::FromRow)]
struct TestTypeRow {
    id: i64,
    code: String,
    display_name: String,
    category: String,
    vendor: Option<String>,
    target_type: Option<String>,
    expected_min_depth: Option<f64>,
    supports_haplogroup_y: bool,
    supports_haplogroup_mt: bool,
    supports_autosomal_ibd: bool,
    supports_ancestry: bool,
    typical_file_formats: Vec<String>,
    description: Option<String>,
}

pub async fn test_type_definition(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows: Vec<TestTypeRow> = sqlx::query_as(
        "SELECT id::bigint, code, display_name, category::text AS category, vendor, \
                target_type::text AS target_type, expected_min_depth, supports_haplogroup_y, \
                supports_haplogroup_mt, supports_autosomal_ibd, supports_ancestry, \
                COALESCE(typical_file_formats, '{}') AS typical_file_formats, description \
         FROM public.test_type_definition",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for r in rows {
        sqlx::query(
            "INSERT INTO genomics.test_type_definition (id, code, display_name, category, vendor, target_type, \
                expected_min_depth, supports_haplogroup_y, supports_haplogroup_mt, supports_autosomal_ibd, \
                supports_ancestry, typical_file_formats, description) OVERRIDING SYSTEM VALUE \
             VALUES ($1,$2,$3,$4::core.data_generation_method,$5,$6::core.target_type,$7,$8,$9,$10,$11,$12,$13) \
             ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, display_name=EXCLUDED.display_name, \
               category=EXCLUDED.category, vendor=EXCLUDED.vendor, target_type=EXCLUDED.target_type, \
               expected_min_depth=EXCLUDED.expected_min_depth, supports_haplogroup_y=EXCLUDED.supports_haplogroup_y, \
               supports_haplogroup_mt=EXCLUDED.supports_haplogroup_mt, supports_autosomal_ibd=EXCLUDED.supports_autosomal_ibd, \
               supports_ancestry=EXCLUDED.supports_ancestry, typical_file_formats=EXCLUDED.typical_file_formats, \
               description=EXCLUDED.description",
        )
        .bind(r.id).bind(r.code).bind(r.display_name).bind(r.category).bind(r.vendor)
        .bind(r.target_type).bind(r.expected_min_depth).bind(r.supports_haplogroup_y)
        .bind(r.supports_haplogroup_mt).bind(r.supports_autosomal_ibd).bind(r.supports_ancestry)
        .bind(r.typical_file_formats).bind(r.description)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "test_type_definition", rows = n, "migrated");
    Ok(())
}

pub async fn pangenome_graph(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (i64, String, Option<String>, Option<String>, DateTime<Utc>)>(
        "SELECT id::bigint, graph_name, source_gfa_file, description, creation_date::timestamptz \
         FROM public.pangenome_graph",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, name, gfa, desc, created) in rows {
        sqlx::query(
            "INSERT INTO genomics.pangenome_graph (id, graph_name, source_gfa_file, description, creation_date) \
             OVERRIDING SYSTEM VALUE VALUES ($1,$2,$3,$4,$5) \
             ON CONFLICT (id) DO UPDATE SET graph_name=EXCLUDED.graph_name, source_gfa_file=EXCLUDED.source_gfa_file, \
               description=EXCLUDED.description, creation_date=EXCLUDED.creation_date",
        )
        .bind(id).bind(name).bind(gfa).bind(desc).bind(created)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "pangenome_graph", rows = n, "migrated");
    Ok(())
}

pub async fn pangenome_path(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (i64, i64, String, Option<bool>, Option<i64>)>(
        "SELECT id::bigint, graph_id::bigint, path_name, is_reference, length_bp FROM public.pangenome_path",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, graph, name, isref, len) in rows {
        sqlx::query(
            "INSERT INTO genomics.pangenome_path (id, graph_id, path_name, is_reference, length_bp) \
             OVERRIDING SYSTEM VALUE VALUES ($1,$2,$3,COALESCE($4,false),$5) \
             ON CONFLICT (id) DO UPDATE SET graph_id=EXCLUDED.graph_id, path_name=EXCLUDED.path_name, \
               is_reference=EXCLUDED.is_reference, length_bp=EXCLUDED.length_bp",
        )
        .bind(id).bind(graph).bind(name).bind(isref).bind(len)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "pangenome_path", rows = n, "migrated");
    Ok(())
}

#[derive(sqlx::FromRow)]
struct CanonRow {
    id: i64,
    pangenome_graph_id: i64,
    variant_type: Option<String>,
    variant_nodes: Vec<i32>,
    variant_edges: Vec<i32>,
    reference_path_id: Option<i64>,
    reference_allele_sequence: Option<String>,
    canonical_hash: String,
}

pub async fn canonical_pangenome_variant(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows: Vec<CanonRow> = sqlx::query_as(
        "SELECT id::bigint, pangenome_graph_id::bigint, variant_type, variant_nodes, variant_edges, \
                reference_path_id::bigint, reference_allele_sequence, canonical_hash \
         FROM public.canonical_pangenome_variant",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for r in rows {
        sqlx::query(
            "INSERT INTO genomics.canonical_pangenome_variant (id, pangenome_graph_id, variant_type, variant_nodes, \
                variant_edges, reference_path_id, reference_allele_sequence, canonical_hash) OVERRIDING SYSTEM VALUE \
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8) \
             ON CONFLICT (id) DO UPDATE SET pangenome_graph_id=EXCLUDED.pangenome_graph_id, \
               variant_type=EXCLUDED.variant_type, variant_nodes=EXCLUDED.variant_nodes, \
               variant_edges=EXCLUDED.variant_edges, reference_path_id=EXCLUDED.reference_path_id, \
               reference_allele_sequence=EXCLUDED.reference_allele_sequence, canonical_hash=EXCLUDED.canonical_hash",
        )
        .bind(r.id).bind(r.pangenome_graph_id).bind(r.variant_type).bind(r.variant_nodes)
        .bind(r.variant_edges).bind(r.reference_path_id).bind(r.reference_allele_sequence).bind(r.canonical_hash)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "canonical_pangenome_variant", rows = n, "migrated");
    Ok(())
}

#[derive(sqlx::FromRow)]
struct SeqLibRow {
    id: i64,
    sample_guid: Uuid,
    test_type_id: Option<i64>,
    lab_id: Option<i64>,
    run_date: Option<NaiveDate>,
    instrument: Option<String>,
    reads: Option<i64>,
    read_length: Option<i32>,
    paired_end: Option<bool>,
    insert_size: Option<i32>,
    atproto: Option<Value>,
    created_at: DateTime<Utc>,
    updated_at: Option<DateTime<Utc>>,
}

pub async fn sequence_library(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    // Legacy stores the lab as a name string; resolve it to the migrated lab id.
    let rows: Vec<SeqLibRow> = sqlx::query_as(
        "SELECT sl.id::bigint, sl.sample_guid, sl.test_type_id::bigint, lab_t.id::bigint AS lab_id, \
                sl.run_date::date AS run_date, sl.instrument, sl.reads, sl.read_length, sl.paired_end, sl.insert_size, \
                CASE WHEN sl.at_uri IS NOT NULL \
                     THEN jsonb_strip_nulls(jsonb_build_object('uri', sl.at_uri, 'cid', sl.at_cid)) END AS atproto, \
                sl.created_at::timestamptz, sl.updated_at::timestamptz \
         FROM public.sequence_library sl LEFT JOIN public.sequencing_lab lab_t ON lab_t.name = sl.lab",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for r in rows {
        sqlx::query(
            "INSERT INTO genomics.sequence_library (id, sample_guid, test_type_id, lab_id, run_date, instrument, \
                reads, read_length, paired_end, insert_size, atproto, created_at, updated_at) OVERRIDING SYSTEM VALUE \
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,COALESCE($13, now())) \
             ON CONFLICT (id) DO UPDATE SET sample_guid=EXCLUDED.sample_guid, test_type_id=EXCLUDED.test_type_id, \
               lab_id=EXCLUDED.lab_id, run_date=EXCLUDED.run_date, instrument=EXCLUDED.instrument, reads=EXCLUDED.reads, \
               read_length=EXCLUDED.read_length, paired_end=EXCLUDED.paired_end, insert_size=EXCLUDED.insert_size, \
               atproto=EXCLUDED.atproto, updated_at=EXCLUDED.updated_at",
        )
        .bind(r.id).bind(r.sample_guid).bind(r.test_type_id).bind(r.lab_id).bind(r.run_date)
        .bind(r.instrument).bind(r.reads).bind(r.read_length).bind(r.paired_end).bind(r.insert_size)
        .bind(r.atproto).bind(r.created_at).bind(r.updated_at)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "sequence_library", rows = n, "migrated");
    Ok(())
}

#[derive(sqlx::FromRow)]
struct SeqFileRow {
    id: i64,
    library_id: i64,
    file_name: String,
    file_size_bytes: Option<i64>,
    file_format: Option<String>,
    aligner: Option<String>,
    target_reference: Option<String>,
    pangenome_graph_id: Option<i64>,
    checksums: Value,
    http_locations: Value,
    atp_location: Option<Value>,
    created_at: DateTime<Utc>,
}

pub async fn sequence_file(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows: Vec<SeqFileRow> = sqlx::query_as(
        "SELECT id::bigint, library_id::bigint, file_name, file_size_bytes, file_format, aligner, target_reference, \
                pangenome_graph_id::bigint, COALESCE(checksums, '[]'::jsonb) AS checksums, \
                COALESCE(http_locations, '[]'::jsonb) AS http_locations, atp_location, created_at::timestamptz \
         FROM public.sequence_file",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for r in rows {
        sqlx::query(
            "INSERT INTO genomics.sequence_file (id, library_id, file_name, file_size_bytes, file_format, aligner, \
                target_reference, pangenome_graph_id, checksums, http_locations, atp_location, created_at) \
             OVERRIDING SYSTEM VALUE VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12) \
             ON CONFLICT (id) DO UPDATE SET library_id=EXCLUDED.library_id, file_name=EXCLUDED.file_name, \
               file_size_bytes=EXCLUDED.file_size_bytes, file_format=EXCLUDED.file_format, aligner=EXCLUDED.aligner, \
               target_reference=EXCLUDED.target_reference, pangenome_graph_id=EXCLUDED.pangenome_graph_id, \
               checksums=EXCLUDED.checksums, http_locations=EXCLUDED.http_locations, atp_location=EXCLUDED.atp_location",
        )
        .bind(r.id).bind(r.library_id).bind(r.file_name).bind(r.file_size_bytes).bind(r.file_format)
        .bind(r.aligner).bind(r.target_reference).bind(r.pangenome_graph_id).bind(r.checksums)
        .bind(r.http_locations).bind(r.atp_location).bind(r.created_at)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "sequence_file", rows = n, "migrated");
    Ok(())
}

#[derive(sqlx::FromRow)]
struct AlignRow {
    id: i64,
    sequence_file_id: i64,
    genbank_contig_id: Option<i64>,
    metric_level: String,
    region_name: Option<String>,
    region_start_pos: Option<i64>,
    region_end_pos: Option<i64>,
    reference_build: Option<String>,
    variant_caller: Option<String>,
    coverage: Value,
}

pub async fn alignment_metadata(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    // Fold the 1:1 alignment_coverage child + inline Picard metrics + analysis
    // provenance into one coverage JSONB; meanDepth/percent_coverage_at_10x are
    // the keys the coverage page aggregates on.
    let rows: Vec<AlignRow> = sqlx::query_as(
        "SELECT am.id::bigint, am.sequence_file_id::bigint, am.genbank_contig_id::bigint, am.metric_level, \
                am.region_name, am.region_start_pos, am.region_end_pos, am.reference_build, am.variant_caller, \
                (COALESCE(am.metadata, '{}'::jsonb) || jsonb_strip_nulls(jsonb_build_object( \
                    'meanDepth', COALESCE(ac.mean_depth, am.mean_coverage), \
                    'medianDepth', COALESCE(ac.median_depth, am.median_coverage), \
                    'percent_coverage_at_1x', ac.percent_coverage_at_1x, \
                    'percent_coverage_at_5x', ac.percent_coverage_at_5x, \
                    'percent_coverage_at_10x', COALESCE(ac.percent_coverage_at_10x, am.pct_10x), \
                    'percent_coverage_at_20x', COALESCE(ac.percent_coverage_at_20x, am.pct_20x), \
                    'percent_coverage_at_30x', COALESCE(ac.percent_coverage_at_30x, am.pct_30x), \
                    'basesNoCoverage', ac.bases_no_coverage, 'basesLowQualityMapping', ac.bases_low_quality_mapping, \
                    'basesCallable', ac.bases_callable, 'meanMappingQuality', ac.mean_mapping_quality, \
                    'genomeTerritory', am.genome_territory, 'sdCoverage', am.sd_coverage, \
                    'pctExcDupe', am.pct_exc_dupe, 'pctExcMapq', am.pct_exc_mapq, \
                    'hetSnpSensitivity', am.het_snp_sensitivity, \
                    'analysis', NULLIF(jsonb_strip_nulls(jsonb_build_object('tool', am.analysis_tool, \
                        'tool_version', am.analysis_tool_version, 'notes', am.notes, \
                        'metrics_date', am.metrics_date::text, 'region_length_bp', am.region_length_bp)), '{}'::jsonb) \
                ))) AS coverage \
         FROM public.alignment_metadata am \
         LEFT JOIN public.alignment_coverage ac ON ac.alignment_metadata_id = am.id",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for r in rows {
        sqlx::query(
            "INSERT INTO genomics.alignment_metadata (id, sequence_file_id, genbank_contig_id, metric_level, \
                region_name, region_start_pos, region_end_pos, reference_build, variant_caller, coverage) \
             OVERRIDING SYSTEM VALUE VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) \
             ON CONFLICT (id) DO UPDATE SET sequence_file_id=EXCLUDED.sequence_file_id, \
               genbank_contig_id=EXCLUDED.genbank_contig_id, metric_level=EXCLUDED.metric_level, \
               region_name=EXCLUDED.region_name, region_start_pos=EXCLUDED.region_start_pos, \
               region_end_pos=EXCLUDED.region_end_pos, reference_build=EXCLUDED.reference_build, \
               variant_caller=EXCLUDED.variant_caller, coverage=EXCLUDED.coverage",
        )
        .bind(r.id).bind(r.sequence_file_id).bind(r.genbank_contig_id).bind(r.metric_level)
        .bind(r.region_name).bind(r.region_start_pos).bind(r.region_end_pos).bind(r.reference_build)
        .bind(r.variant_caller).bind(r.coverage)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "alignment_metadata", rows = n, "migrated");
    Ok(())
}

pub async fn pangenome_alignment_metadata(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (i64, i64, i64, String, Value)>(
        "SELECT pam.id::bigint, pam.sequence_file_id::bigint, pam.pangenome_graph_id::bigint, pam.metric_level, \
                (COALESCE(pam.metadata, '{}'::jsonb) || jsonb_strip_nulls(jsonb_build_object( \
                    'meanDepth', pac.mean_depth, 'medianDepth', pac.median_depth, \
                    'percent_coverage_at_1x', pac.percent_coverage_at_1x, \
                    'percent_coverage_at_5x', pac.percent_coverage_at_5x, \
                    'percent_coverage_at_10x', pac.percent_coverage_at_10x, \
                    'percent_coverage_at_20x', pac.percent_coverage_at_20x, \
                    'percent_coverage_at_30x', pac.percent_coverage_at_30x, \
                    'basesNoCoverage', pac.bases_no_coverage, 'basesLowQualityMapping', pac.bases_low_quality_mapping, \
                    'basesCallable', pac.bases_callable, 'meanMappingQuality', pac.mean_mapping_quality, \
                    'pangenome_path_id', pam.pangenome_path_id, 'pangenome_node_id', pam.pangenome_node_id, \
                    'region_start_node_id', pam.region_start_node_id, 'region_end_node_id', pam.region_end_node_id, \
                    'region_name', pam.region_name, 'region_length_bp', pam.region_length_bp, \
                    'analysis', NULLIF(jsonb_strip_nulls(jsonb_build_object('tool', pam.analysis_tool, \
                        'tool_version', pam.analysis_tool_version, 'notes', pam.notes, \
                        'metrics_date', pam.metrics_date::text)), '{}'::jsonb) \
                ))) AS metadata \
         FROM public.pangenome_alignment_metadata pam \
         LEFT JOIN public.pangenome_alignment_coverage pac ON pac.alignment_metadata_id = pam.id",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, sfid, gid, level, metadata) in rows {
        sqlx::query(
            "INSERT INTO genomics.pangenome_alignment_metadata (id, sequence_file_id, pangenome_graph_id, \
                metric_level, metadata) OVERRIDING SYSTEM VALUE VALUES ($1,$2,$3,$4,$5) \
             ON CONFLICT (id) DO UPDATE SET sequence_file_id=EXCLUDED.sequence_file_id, \
               pangenome_graph_id=EXCLUDED.pangenome_graph_id, metric_level=EXCLUDED.metric_level, \
               metadata=EXCLUDED.metadata",
        )
        .bind(id).bind(sfid).bind(gid).bind(level).bind(metadata)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "pangenome_alignment_metadata", rows = n, "migrated");
    Ok(())
}

#[derive(sqlx::FromRow)]
struct RvpRow {
    id: i64,
    sample_guid: Uuid,
    graph_id: i64,
    variant_type: Option<String>,
    variant_nodes: Vec<i32>,
    variant_edges: Vec<i32>,
    allele_fraction: Option<f64>,
    depth: Option<i32>,
    zygosity: Option<String>,
    haplotype_information: Value,
}

pub async fn reported_variant_pangenome(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows: Vec<RvpRow> = sqlx::query_as(
        "SELECT id::bigint, sample_guid, graph_id::bigint, variant_type, variant_nodes, variant_edges, \
                allele_fraction, depth, zygosity, \
                (COALESCE(haplotype_information, '{}'::jsonb) || jsonb_strip_nulls(jsonb_build_object( \
                    'provenance', provenance, 'confidence_score', confidence_score, 'status', status, 'notes', notes, \
                    'reference_path_id', reference_path_id, 'reference_start_position', reference_start_position, \
                    'reference_end_position', reference_end_position, 'alternate_allele_sequence', alternate_allele_sequence, \
                    'reference_allele_sequence', reference_allele_sequence, 'reference_repeat_count', reference_repeat_count, \
                    'alternate_repeat_count', alternate_repeat_count, 'reported_date', reported_date::text \
                ))) AS haplotype_information \
         FROM public.reported_variant_pangenome",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for r in rows {
        sqlx::query(
            "INSERT INTO genomics.reported_variant_pangenome (id, sample_guid, graph_id, variant_type, variant_nodes, \
                variant_edges, allele_fraction, depth, zygosity, haplotype_information) OVERRIDING SYSTEM VALUE \
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) \
             ON CONFLICT (id) DO UPDATE SET sample_guid=EXCLUDED.sample_guid, graph_id=EXCLUDED.graph_id, \
               variant_type=EXCLUDED.variant_type, variant_nodes=EXCLUDED.variant_nodes, \
               variant_edges=EXCLUDED.variant_edges, allele_fraction=EXCLUDED.allele_fraction, depth=EXCLUDED.depth, \
               zygosity=EXCLUDED.zygosity, haplotype_information=EXCLUDED.haplotype_information",
        )
        .bind(r.id).bind(r.sample_guid).bind(r.graph_id).bind(r.variant_type).bind(r.variant_nodes)
        .bind(r.variant_edges).bind(r.allele_fraction).bind(r.depth).bind(r.zygosity).bind(r.haplotype_information)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "reported_variant_pangenome", rows = n, "migrated");
    Ok(())
}

pub async fn genotype_data(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    // Skip soft-deleted rows; fold chip/build/hash/atproto into the metrics JSONB.
    let rows = sqlx::query_as::<_, (i64, Uuid, Option<i64>, Option<String>, Value, DateTime<Utc>)>(
        "SELECT id::bigint, sample_guid, test_type_id::bigint, provider, \
                (COALESCE(metrics, '{}'::jsonb) || jsonb_strip_nulls(jsonb_build_object( \
                    'chip_version', chip_version, 'build_version', build_version, 'source_file_hash', source_file_hash, \
                    'atproto', CASE WHEN at_uri IS NOT NULL \
                        THEN jsonb_strip_nulls(jsonb_build_object('uri', at_uri, 'cid', at_cid)) END \
                ))) AS metrics, \
                COALESCE(created_at, now())::timestamptz AS created_at \
         FROM public.genotype_data WHERE deleted IS NOT TRUE",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, guid, ttid, provider, metrics, created) in rows {
        sqlx::query(
            "INSERT INTO genomics.genotype_data (id, sample_guid, test_type_id, provider, metrics, created_at) \
             OVERRIDING SYSTEM VALUE VALUES ($1,$2,$3,$4,$5,$6) \
             ON CONFLICT (id) DO UPDATE SET sample_guid=EXCLUDED.sample_guid, test_type_id=EXCLUDED.test_type_id, \
               provider=EXCLUDED.provider, metrics=EXCLUDED.metrics",
        )
        .bind(id).bind(guid).bind(ttid).bind(provider).bind(metrics).bind(created)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "genotype_data", rows = n, "migrated");
    Ok(())
}

// ── post-load: advance identity sequences past the copied max id ─────────────
pub async fn fix_sequences(target: &PgPool) -> anyhow::Result<()> {
    for (schema, table) in [
        ("core", "specimen_donor"),
        ("core", "variant"),
        ("tree", "haplogroup"),
        ("tree", "haplogroup_relationship"),
        ("tree", "haplogroup_variant"),
        ("pubs", "genomic_study"),
        ("pubs", "publication"),
        ("genomics", "genbank_contig"),
        ("genomics", "sequencing_lab"),
        ("genomics", "sequencer_instrument"),
        ("genomics", "test_type_definition"),
        ("genomics", "pangenome_graph"),
        ("genomics", "pangenome_path"),
        ("genomics", "canonical_pangenome_variant"),
        ("genomics", "sequence_library"),
        ("genomics", "sequence_file"),
        ("genomics", "alignment_metadata"),
        ("genomics", "pangenome_alignment_metadata"),
        ("genomics", "reported_variant_pangenome"),
        ("genomics", "genotype_data"),
    ] {
        let qualified = format!("{schema}.{table}");
        let sql = format!(
            "SELECT setval(pg_get_serial_sequence('{qualified}','id'), \
             COALESCE((SELECT max(id) FROM {qualified}), 0) + 1, false)"
        );
        sqlx::query(&sql).execute(target).await?;
    }
    tracing::info!("identity sequences advanced");
    Ok(())
}
