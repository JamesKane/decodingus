//! Legacy -> new schema transformers. Each reads from the legacy DB and upserts
//! into the new DB, preserving primary keys (`OVERRIDING SYSTEM VALUE`) and
//! `sample_guid` UUIDs so foreign keys carry over unchanged. All upserts are
//! idempotent (`ON CONFLICT ... DO UPDATE`/`DO NOTHING`) so a re-run is safe.
//!
//! NOTE: the SELECT statements encode the *legacy* column layout (reconstructed
//! from the Play app's evolutions). Validate them against the live EC2 schema
//! before the production run; adjust column names here if they differ.

use serde_json::{json, Value};
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

/// Legacy `biosample_type` / donor_type -> new `biosample_source`.
fn source_from_donor(s: &str) -> &'static str {
    match s.trim() {
        "External" | "EXTERNAL" => "EXTERNAL",
        "Ancient" | "ANCIENT" => "ANCIENT",
        "PGP" => "PGP",
        "Citizen" | "CITIZEN" => "CITIZEN",
        _ => "STANDARD",
    }
}

fn upper_opt(s: Option<String>) -> Option<String> {
    s.map(|v| v.trim().to_uppercase())
}

// ── specimen donors ──────────────────────────────────────────────────────────
pub async fn specimen_donor(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (i64, Option<String>, Option<String>, Option<String>, Option<String>, Option<String>)>(
        "SELECT id::bigint, donor_identifier, origin_biobank, sex::text, donor_type::text, ST_AsEWKT(geocoord) \
         FROM specimen_donor",
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
pub async fn biosample(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    const UPSERT: &str = "INSERT INTO core.biosample \
        (sample_guid, donor_id, source, accession, alias, description, center_name, locked, deleted, \
         source_attrs, original_haplogroups, atproto) \
        VALUES ($1,$2,$3::core.biosample_source,$4,$5,$6,$7,$8,$9,$10,$11,$12) \
        ON CONFLICT (sample_guid) DO UPDATE SET donor_id=EXCLUDED.donor_id, source=EXCLUDED.source, \
          accession=EXCLUDED.accession, alias=EXCLUDED.alias, description=EXCLUDED.description, \
          center_name=EXCLUDED.center_name, locked=EXCLUDED.locked, deleted=EXCLUDED.deleted, \
          source_attrs=EXCLUDED.source_attrs, original_haplogroups=EXCLUDED.original_haplogroups, \
          atproto=EXCLUDED.atproto";

    let mut tx = target.begin().await?;
    let mut total = 0usize;

    // Standard/external/ancient samples — source derived from the donor type.
    let std_rows = sqlx::query_as::<_, (Uuid, Option<i64>, Option<String>, Option<String>, Option<String>, Option<String>, bool, Option<String>, Value)>(
        "SELECT b.sample_guid, b.specimen_donor_id::bigint, b.sample_accession, b.alias, b.description, \
                b.center_name, COALESCE(b.locked,false), d.donor_type::text, \
                COALESCE(b.original_haplogroups,'[]'::jsonb) \
         FROM biosample b LEFT JOIN specimen_donor d ON d.id = b.specimen_donor_id",
    )
    .fetch_all(legacy)
    .await?;
    for (guid, donor, acc, alias, desc, center, locked, donor_type, orig) in std_rows {
        sqlx::query(UPSERT)
            .bind(guid)
            .bind(donor)
            .bind(source_from_donor(donor_type.as_deref().unwrap_or("Standard")))
            .bind(acc)
            .bind(alias)
            .bind(desc)
            .bind(center)
            .bind(locked)
            .bind(false)
            .bind(json!({}))
            .bind(orig)
            .bind(None::<Value>)
            .execute(&mut *tx)
            .await?;
        total += 1;
    }

    // Citizen samples — federated; at_uri/at_cid fold into the `atproto` doc.
    let cit_rows = sqlx::query_as::<_, (Uuid, Option<String>, bool, Value, Option<String>, Option<String>)>(
        "SELECT sample_guid, accession, COALESCE(deleted,false), \
                COALESCE(original_haplogroups,'[]'::jsonb), at_uri, at_cid \
         FROM citizen_biosample",
    )
    .fetch_all(legacy)
    .await?;
    for (guid, acc, deleted, orig, at_uri, at_cid) in cit_rows {
        let atproto = at_uri.as_ref().map(|uri| json!({ "uri": uri, "cid": at_cid }));
        sqlx::query(UPSERT)
            .bind(guid)
            .bind(None::<i64>)
            .bind("CITIZEN")
            .bind(acc)
            .bind(None::<String>)
            .bind(None::<String>)
            .bind(None::<String>)
            .bind(false)
            .bind(deleted)
            .bind(json!({}))
            .bind(orig)
            .bind(atproto)
            .execute(&mut *tx)
            .await?;
        total += 1;
    }

    // PGP samples — PGP-specific fields fold into source_attrs.
    let pgp_rows = sqlx::query_as::<_, (Uuid, Option<String>, Option<String>)>(
        "SELECT sample_guid, ena_biosample_accession, pgp_participant_id FROM pgp_biosample",
    )
    .fetch_all(legacy)
    .await?;
    for (guid, ena, pgp_id) in pgp_rows {
        let attrs = json!({ "pgp_participant_id": pgp_id, "ena_biosample_accession": ena });
        sqlx::query(UPSERT)
            .bind(guid)
            .bind(None::<i64>)
            .bind("PGP")
            .bind(ena)
            .bind(None::<String>)
            .bind(None::<String>)
            .bind(None::<String>)
            .bind(false)
            .bind(false)
            .bind(attrs)
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

// ── variants ─────────────────────────────────────────────────────────────────
pub async fn variant(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows = sqlx::query_as::<_, (i64, String, String, String, Value, Value, Value)>(
        "SELECT variant_id::bigint, canonical_name, mutation_type::text, naming_status::text, \
                COALESCE(aliases,'{}'::jsonb), COALESCE(coordinates,'{}'::jsonb), COALESCE(annotations,'{}'::jsonb) \
         FROM variant_v2",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, name, mtype, nstatus, aliases, coords, annots) in rows {
        sqlx::query(
            "INSERT INTO core.variant (id, canonical_name, mutation_type, naming_status, aliases, coordinates, annotations) \
             OVERRIDING SYSTEM VALUE \
             VALUES ($1,$2,$3::core.mutation_type,$4::core.naming_status,$5,$6,$7) \
             ON CONFLICT (id) DO UPDATE SET canonical_name=EXCLUDED.canonical_name, mutation_type=EXCLUDED.mutation_type, \
               naming_status=EXCLUDED.naming_status, aliases=EXCLUDED.aliases, coordinates=EXCLUDED.coordinates, \
               annotations=EXCLUDED.annotations",
        )
        .bind(id)
        .bind(name)
        .bind(mtype.trim().to_uppercase())
        .bind(nstatus.trim().to_uppercase())
        .bind(aliases)
        .bind(coords)
        .bind(annots)
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
                formed_ybp, tmrca_ybp, COALESCE(provenance,'{}'::jsonb), valid_from, valid_until \
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
        "SELECT id::bigint, child_haplogroup_id::bigint, parent_haplogroup_id::bigint, \
                revision_id, source, valid_from, valid_until FROM tree.haplogroup_relationship",
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
    let rows = sqlx::query_as::<_, (i64, i64, i64, Ts, Ts)>(
        "SELECT haplogroup_variant_id::bigint, haplogroup_id::bigint, variant_id::bigint, valid_from, valid_until \
         FROM tree.haplogroup_variant",
    )
    .fetch_all(legacy)
    .await?;
    let n = rows.len();
    let mut tx = target.begin().await?;
    for (id, hg, var, vfrom, vuntil) in rows {
        sqlx::query(
            "INSERT INTO tree.haplogroup_variant (id, haplogroup_id, variant_id, revision, valid_from, valid_until) \
             OVERRIDING SYSTEM VALUE VALUES ($1,$2,$3,'{}'::jsonb,COALESCE($4, now()),$5) \
             ON CONFLICT (id) DO UPDATE SET haplogroup_id=EXCLUDED.haplogroup_id, variant_id=EXCLUDED.variant_id, \
               valid_from=EXCLUDED.valid_from, valid_until=EXCLUDED.valid_until",
        )
        .bind(id)
        .bind(hg)
        .bind(var)
        .bind(vfrom)
        .bind(vuntil)
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
    version: Option<i32>,
    submission_date: Option<NaiveDate>,
    details: Value,
}

pub async fn genomic_study(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let rows: Vec<StudyRow> = sqlx::query_as(
        "SELECT id::bigint, accession, title, center_name, study_name, source::text AS source, \
                bio_project_id, molecule, topology, taxonomy_id, version, submission_date, \
                COALESCE(details,'{}'::jsonb) AS details FROM genomic_studies",
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
                abstract_summary, cited_by_count, open_access_status FROM publication",
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

/// Both legacy link tables (standard + citizen) -> the unified link, resolving
/// the legacy integer biosample id to the preserved `sample_guid`.
pub async fn publication_biosample(legacy: &PgPool, target: &PgPool) -> anyhow::Result<()> {
    let std = sqlx::query_as::<_, (i64, Uuid)>(
        "SELECT pb.publication_id::bigint, b.sample_guid \
         FROM publication_biosample pb JOIN biosample b ON b.id = pb.biosample_id",
    )
    .fetch_all(legacy)
    .await?;
    let cit = sqlx::query_as::<_, (i64, Uuid)>(
        "SELECT pcb.publication_id::bigint, c.sample_guid \
         FROM publication_citizen_biosample pcb JOIN citizen_biosample c ON c.id = pcb.citizen_biosample_id",
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
        // Set the identity sequence so the next nextval is max(id)+1.
        let sql = format!(
            "SELECT setval(pg_get_serial_sequence('{qualified}','id'), \
             COALESCE((SELECT max(id) FROM {qualified}), 0) + 1, false)"
        );
        sqlx::query(&sql).execute(target).await?;
    }
    tracing::info!("identity sequences advanced");
    Ok(())
}
