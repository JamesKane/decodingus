//! Core container records: biosample, sequencerun, project, workspace.
//!
//! These carry potential donor PII (donorIdentifier / sampleAccession /
//! description / file paths), so the mirror keeps **only typed, non-identifying
//! columns** — no raw record JSONB — and the consumer never populates the PII
//! fields. Ordered+idempotent upsert (overwrite only on a `time_us` >= the stored
//! one); deletes go through [`super::delete`].

use super::Common;
use crate::DbError;
use sqlx::PgPool;

/// Biosample — pseudonymous DID, sex, Y/mt haplogroup calls, sequencing center,
/// and join refs/counts. Donor identifiers and free-text are dropped on ingest.
pub struct Biosample {
    pub common: Common,
    pub sex: Option<String>,
    pub y_haplogroup: Option<String>,
    pub mt_haplogroup: Option<String>,
    pub center_name: Option<String>,
    pub population_breakdown_ref: Option<String>,
    pub str_profile_ref: Option<String>,
    pub sequence_run_count: i32,
    pub genotype_count: i32,
}

pub async fn upsert_biosample(pool: &PgPool, b: &Biosample) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.biosample \
           (did, rkey, at_uri, cid, sex, y_haplogroup, mt_haplogroup, center_name, \
            population_breakdown_ref, str_profile_ref, sequence_run_count, genotype_count, \
            record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, sex = EXCLUDED.sex, \
           y_haplogroup = EXCLUDED.y_haplogroup, mt_haplogroup = EXCLUDED.mt_haplogroup, \
           center_name = EXCLUDED.center_name, \
           population_breakdown_ref = EXCLUDED.population_breakdown_ref, \
           str_profile_ref = EXCLUDED.str_profile_ref, \
           sequence_run_count = EXCLUDED.sequence_run_count, genotype_count = EXCLUDED.genotype_count, \
           record_created_at = EXCLUDED.record_created_at, time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.biosample.time_us",
    )
    .bind(&b.common.did)
    .bind(&b.common.rkey)
    .bind(&b.common.at_uri)
    .bind(&b.common.cid)
    .bind(&b.sex)
    .bind(&b.y_haplogroup)
    .bind(&b.mt_haplogroup)
    .bind(&b.center_name)
    .bind(&b.population_breakdown_ref)
    .bind(&b.str_profile_ref)
    .bind(b.sequence_run_count)
    .bind(b.genotype_count)
    .bind(b.common.record_created_at)
    .bind(b.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}

/// Sequence run — platform/instrument/test characterization (no files, no PII).
pub struct SequenceRun {
    pub common: Common,
    pub biosample_ref: Option<String>,
    pub platform_name: Option<String>,
    pub instrument_model: Option<String>,
    pub instrument_id: Option<String>,
    pub test_type: Option<String>,
    pub library_layout: Option<String>,
    pub total_reads: Option<i64>,
    pub read_length: Option<i32>,
    pub mean_insert_size: Option<f64>,
}

pub async fn upsert_sequencerun(pool: &PgPool, s: &SequenceRun) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.sequencerun \
           (did, rkey, at_uri, cid, biosample_ref, platform_name, instrument_model, \
            instrument_id, test_type, library_layout, total_reads, read_length, \
            mean_insert_size, record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, biosample_ref = EXCLUDED.biosample_ref, \
           platform_name = EXCLUDED.platform_name, instrument_model = EXCLUDED.instrument_model, \
           instrument_id = EXCLUDED.instrument_id, test_type = EXCLUDED.test_type, \
           library_layout = EXCLUDED.library_layout, total_reads = EXCLUDED.total_reads, \
           read_length = EXCLUDED.read_length, mean_insert_size = EXCLUDED.mean_insert_size, \
           record_created_at = EXCLUDED.record_created_at, time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.sequencerun.time_us",
    )
    .bind(&s.common.did)
    .bind(&s.common.rkey)
    .bind(&s.common.at_uri)
    .bind(&s.common.cid)
    .bind(&s.biosample_ref)
    .bind(&s.platform_name)
    .bind(&s.instrument_model)
    .bind(&s.instrument_id)
    .bind(&s.test_type)
    .bind(&s.library_layout)
    .bind(s.total_reads)
    .bind(s.read_length)
    .bind(s.mean_insert_size)
    .bind(s.common.record_created_at)
    .bind(s.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}

/// Project — surname/research project grouping (project-level, not donor PII).
pub struct Project {
    pub common: Common,
    pub project_name: Option<String>,
    pub administrator_did: Option<String>,
    pub member_count: i32,
}

pub async fn upsert_project(pool: &PgPool, p: &Project) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.project \
           (did, rkey, at_uri, cid, project_name, administrator_did, member_count, \
            record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, project_name = EXCLUDED.project_name, \
           administrator_did = EXCLUDED.administrator_did, member_count = EXCLUDED.member_count, \
           record_created_at = EXCLUDED.record_created_at, time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.project.time_us",
    )
    .bind(&p.common.did)
    .bind(&p.common.rkey)
    .bind(&p.common.at_uri)
    .bind(&p.common.cid)
    .bind(&p.project_name)
    .bind(&p.administrator_did)
    .bind(p.member_count)
    .bind(p.common.record_created_at)
    .bind(p.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}

/// Workspace — researcher container; counts only.
pub struct Workspace {
    pub common: Common,
    pub sample_count: i32,
    pub project_count: i32,
}

pub async fn upsert_workspace(pool: &PgPool, w: &Workspace) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.workspace \
           (did, rkey, at_uri, cid, sample_count, project_count, record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, sample_count = EXCLUDED.sample_count, \
           project_count = EXCLUDED.project_count, record_created_at = EXCLUDED.record_created_at, \
           time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.workspace.time_us",
    )
    .bind(&w.common.did)
    .bind(&w.common.rkey)
    .bind(&w.common.at_uri)
    .bind(&w.common.cid)
    .bind(w.sample_count)
    .bind(w.project_count)
    .bind(w.common.record_created_at)
    .bind(w.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}
