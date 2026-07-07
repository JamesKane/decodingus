//! Ingest of academic sequencing runs + data files (`genomics.sequence_library`
//! + `genomics.sequence_file`) for a biosample.
//!
//! This is the write side of the "link the BAM/CRAM back to ENA" cleanup: academic
//! biosamples that carry an ENA sample accession (SAM*) but were loaded without any
//! `sequence_library`/`sequence_file` rows. An ops driver enumerates them, pulls the
//! file list from ENA's `filereport` API, and posts it here to materialize the links
//! that the per-sample report ([`crate::biosample::report`]) renders.
//!
//! Idempotency is at **sample granularity**: [`ingest_libraries`] no-ops when the
//! sample already has any `sequence_library`, which mirrors the driver's worklist
//! predicate (`NOT EXISTS sequence_library`). Re-running is therefore safe.

use crate::DbError;
use du_domain::ids::SampleGuid;
use serde_json::json;
use sqlx::PgPool;

/// One data file for a run (CRAM/BAM/FASTQ) with its public location + md5.
#[derive(Debug, Clone)]
pub struct NewSeqFile {
    pub file_name: String,
    pub file_format: Option<String>, // BAM / CRAM / FASTQ
    pub file_size_bytes: Option<i64>,
    /// Public HTTP/FTP location (scheme-less ENA paths are fine; the view prefixes https).
    pub file_url: String,
    /// Index sidecar (.crai/.bai), when present.
    pub file_index_url: Option<String>,
    pub md5: Option<String>,
    pub aligner: Option<String>,
    pub target_reference: Option<String>,
}

/// One sequencing run (a `sequence_library` + its files), keyed to an external run.
#[derive(Debug, Clone)]
pub struct NewSeqLibrary {
    pub instrument: Option<String>,
    pub reads: Option<i64>,
    pub read_length: Option<i32>,
    pub paired_end: Option<bool>,
    pub run_date: Option<chrono::NaiveDate>,
    /// Source run/analysis accession (e.g. ENA `ERR...`), stored as provenance.
    pub external_run_ref: String,
    pub files: Vec<NewSeqFile>,
}

/// Outcome of an ingest call.
#[derive(Debug, Clone)]
pub struct IngestReport {
    pub library_ids: Vec<i64>,
    pub files_created: usize,
    /// True when the sample already had libraries and nothing was written (idempotent no-op).
    pub skipped_existing: bool,
}

/// Create the given runs' `sequence_library` + `sequence_file` rows for one biosample,
/// in a single transaction. No-ops (returns `skipped_existing`) when the sample already
/// has any library, so the call is idempotent at sample granularity.
///
/// Returns [`DbError::Decode`] (→ 4xx at the web layer) for an unknown/deleted sample or
/// an empty payload — never a silent partial write.
pub async fn ingest_libraries(
    pool: &PgPool,
    guid: SampleGuid,
    libs: &[NewSeqLibrary],
) -> Result<IngestReport, DbError> {
    if libs.is_empty() {
        return Err(DbError::Decode("no sequencing runs supplied".into()));
    }

    let mut tx = pool.begin().await?;

    // Sample must exist and be live.
    let deleted: Option<bool> =
        sqlx::query_scalar("SELECT deleted FROM core.biosample WHERE sample_guid = $1")
            .bind(guid.0)
            .fetch_optional(&mut *tx)
            .await?;
    match deleted {
        None => return Err(DbError::Decode(format!("unknown biosample {}", guid.0))),
        Some(true) => return Err(DbError::Decode(format!("biosample {} is deleted", guid.0))),
        Some(false) => {}
    }

    // Idempotency guard: skip entirely if the sample already carries any library.
    let existing: i64 =
        sqlx::query_scalar("SELECT count(*) FROM genomics.sequence_library WHERE sample_guid = $1")
            .bind(guid.0)
            .fetch_one(&mut *tx)
            .await?;
    if existing > 0 {
        return Ok(IngestReport { library_ids: Vec::new(), files_created: 0, skipped_existing: true });
    }

    let mut library_ids = Vec::with_capacity(libs.len());
    let mut files_created = 0usize;
    for lib in libs {
        let lib_id: i64 = sqlx::query_scalar(
            "INSERT INTO genomics.sequence_library \
                (sample_guid, run_date, instrument, reads, read_length, paired_end, atproto) \
             VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING id",
        )
        .bind(guid.0)
        .bind(lib.run_date)
        .bind(&lib.instrument)
        .bind(lib.reads)
        .bind(lib.read_length)
        .bind(lib.paired_end)
        // No ATP record for academic runs; reuse the JSONB slot for source provenance.
        .bind(json!({ "source": "ENA", "run_accession": lib.external_run_ref }))
        .fetch_one(&mut *tx)
        .await?;
        library_ids.push(lib_id);

        for f in &lib.files {
            // Match the JSONB shape the report read path + legacy rows use.
            let checksums = match &f.md5 {
                Some(m) if !m.is_empty() => json!([{ "id": 1, "algorithm": "MD5", "checksum": m }]),
                _ => json!([]),
            };
            let mut loc = json!({ "id": 1, "file_url": f.file_url });
            if let Some(idx) = f.file_index_url.as_deref().filter(|s| !s.is_empty()) {
                loc["file_index_url"] = json!(idx);
            }
            sqlx::query(
                "INSERT INTO genomics.sequence_file \
                    (library_id, file_name, file_size_bytes, file_format, aligner, \
                     target_reference, checksums, http_locations) \
                 VALUES ($1, $2, $3, $4, $5, $6, $7, $8)",
            )
            .bind(lib_id)
            .bind(&f.file_name)
            .bind(f.file_size_bytes.filter(|&n| n > 0))
            .bind(&f.file_format)
            .bind(&f.aligner)
            .bind(&f.target_reference)
            .bind(checksums)
            .bind(json!([loc]))
            .execute(&mut *tx)
            .await?;
            files_created += 1;
        }
    }

    tx.commit().await?;
    Ok(IngestReport { library_ids, files_created, skipped_existing: false })
}
