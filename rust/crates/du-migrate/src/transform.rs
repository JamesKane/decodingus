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
#[derive(sqlx::FromRow)]
struct VarRow {
    variant_id: i64,
    canonical_name: String,
    variant_type: String,
    rs_id: Option<String>,
    common_name: Option<String>,
    contig: Option<String>,
    build: String,
    position: i64,
    reference_allele: String,
    alternate_allele: String,
    aliases: Value,
}

pub async fn variant(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows: Vec<VarRow> = sqlx::query_as(
        "SELECT v.variant_id::bigint AS variant_id, \
                COALESCE(v.common_name, v.rs_id, gc.common_name || ':' || v.position::text) AS canonical_name, \
                v.variant_type, v.rs_id, v.common_name, \
                gc.common_name AS contig, COALESCE(gc.reference_genome, 'GRCh38') AS build, \
                v.position::bigint AS position, v.reference_allele, v.alternate_allele, \
                COALESCE((SELECT jsonb_agg(jsonb_build_object('type', va.alias_type, 'value', va.alias_value, 'source', va.source)) \
                  FROM public.variant_alias va WHERE va.variant_id = v.variant_id), '[]'::jsonb) AS aliases \
         FROM public.variant v JOIN public.genbank_contig gc ON gc.genbank_contig_id = v.genbank_contig_id",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for r in rows {
        // Assemble the consolidated aliases JSONB from rs_id/common_name + variant_alias rows.
        let mut common_names = Vec::new();
        let mut rs_ids = Vec::new();
        let mut sources = Map::new();
        if let Some(cn) = &r.common_name {
            common_names.push(cn.clone());
        }
        if let Some(rs) = &r.rs_id {
            rs_ids.push(rs.clone());
        }
        if let Some(arr) = r.aliases.as_array() {
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
        let aliases = json!({ "common_names": dedup(common_names), "rs_ids": dedup(rs_ids), "sources": sources });
        let coordinates = json!({ r.build.clone(): {
            "contig": r.contig, "position": r.position,
            "reference_allele": r.reference_allele, "alternate_allele": r.alternate_allele
        }});
        let naming_status = if r.common_name.is_some() { "NAMED" } else { "UNNAMED" };
        let mutation_type = if r.variant_type.trim().eq_ignore_ascii_case("INDEL") { "INDEL" } else { "SNP" };

        sqlx::query(
            "INSERT INTO core.variant (id, canonical_name, mutation_type, naming_status, aliases, coordinates) \
             OVERRIDING SYSTEM VALUE \
             VALUES ($1,$2,$3::core.mutation_type,$4::core.naming_status,$5,$6) \
             ON CONFLICT (id) DO UPDATE SET canonical_name=EXCLUDED.canonical_name, mutation_type=EXCLUDED.mutation_type, \
               naming_status=EXCLUDED.naming_status, aliases=EXCLUDED.aliases, coordinates=EXCLUDED.coordinates",
        )
        .bind(r.variant_id)
        .bind(r.canonical_name)
        .bind(mutation_type)
        .bind(naming_status)
        .bind(aliases)
        .bind(coordinates)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "variant", rows = n, "migrated");
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
    let rows = sqlx::query_as::<_, (i64, i64, i64)>(
        "SELECT haplogroup_variant_id::bigint, haplogroup_id::bigint, variant_id::bigint FROM tree.haplogroup_variant",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, hg, var) in rows {
        sqlx::query(
            "INSERT INTO tree.haplogroup_variant (id, haplogroup_id, variant_id, revision, valid_from) \
             OVERRIDING SYSTEM VALUE VALUES ($1,$2,$3,'{}'::jsonb, now()) \
             ON CONFLICT (id) DO UPDATE SET haplogroup_id=EXCLUDED.haplogroup_id, variant_id=EXCLUDED.variant_id",
        )
        .bind(id)
        .bind(hg)
        .bind(var)
        .execute(&mut *tx)
        .await?;
    }
    tx.commit().await?;
    tracing::info!(table = "haplogroup_variant", rows = n, "migrated");
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
