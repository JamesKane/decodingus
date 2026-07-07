//! Project crawl: given a study / project accession attached to a publication,
//! enumerate its ENA runs, create/link the biosamples, and link their read files.
//!
//! This is the study-level driver of the same file-linking the per-sample ops
//! script (`link-ena-sequence-files.py`) does: one ENA `filereport` call returns
//! every run across the project; runs are grouped by sample; each sample is
//! upserted (source `EXTERNAL`), linked to the study's publication(s), and its
//! files materialized via [`du_db::sequence::ingest_libraries`] (idempotent at
//! sample granularity, so re-crawls and the script never collide).

use du_db::sequence::{NewSeqFile, NewSeqLibrary};
use du_db::PgPool;
use du_domain::ids::PublicationId;
use du_external::ena::{EnaClient, EnaRunRow};
use std::collections::BTreeMap;
use std::time::Duration;

/// Crawl-created biosamples are academic/public.
const SAMPLE_SOURCE: &str = "EXTERNAL";
/// Politeness gap between per-study ENA calls.
const REQUEST_GAP: Duration = Duration::from_millis(150);

const ALIGNED: [&str; 2] = ["BAM", "CRAM"];
const INDEX: [&str; 2] = ["CRAI", "BAI"];

fn opt(s: &str) -> Option<String> {
    let s = s.trim();
    (!s.is_empty()).then(|| s.to_string())
}

fn basename(url: &str) -> String {
    url.rsplit('/').next().unwrap_or(url).to_string()
}

/// Parse a non-negative integer field; blanks / non-numeric → None (matches the
/// python driver's `isdigit` guard).
fn to_int(v: &str) -> Option<i64> {
    let v = v.trim();
    (!v.is_empty() && v.bytes().all(|b| b.is_ascii_digit())).then(|| v.parse().ok()).flatten()
}

/// Format for one file: trust the filename extension, fall back to ENA's column.
fn fmt_of(url: &str, declared: &str) -> String {
    let low = url.to_ascii_lowercase();
    if low.ends_with(".cram") {
        "CRAM".into()
    } else if low.ends_with(".bam") {
        "BAM".into()
    } else if low.ends_with(".crai") {
        "CRAI".into()
    } else if low.ends_with(".bai") {
        "BAI".into()
    } else if low.contains(".fastq") || low.contains(".fq") {
        "FASTQ".into()
    } else {
        declared.to_ascii_uppercase()
    }
}

/// `;`-split preserving empties, so parallel file/md5/bytes columns stay aligned.
fn split(field: &str) -> Vec<&str> {
    field.split(';').collect()
}

fn mk_lib(r: &EnaRunRow, files: Vec<NewSeqFile>) -> NewSeqLibrary {
    NewSeqLibrary {
        instrument: opt(&r.instrument_model),
        reads: to_int(&r.read_count),
        read_length: None,
        paired_end: opt(&r.library_layout).map(|l| l.eq_ignore_ascii_case("PAIRED")),
        run_date: chrono::NaiveDate::parse_from_str(r.first_public.trim(), "%Y-%m-%d").ok(),
        external_run_ref: r.run_accession.trim().to_string(),
        files,
    }
}

/// Turn one sample's ENA runs into ingest payloads. Prefer aligned CRAM/BAM (with
/// their index sidecar); if NO run had an aligned file, fall back to that sample's
/// FASTQ. VCF/analysis products are ignored. Port of the python `build_libraries`.
fn build_libraries(runs: &[&EnaRunRow]) -> Vec<NewSeqLibrary> {
    let mut aligned: Vec<NewSeqLibrary> = Vec::new();
    let mut fastq: Vec<NewSeqLibrary> = Vec::new();

    for r in runs {
        // ── aligned submitted files (CRAM/BAM + index) ──────────────────────────
        let ftp = split(&r.submitted_ftp);
        let md5 = split(&r.submitted_md5);
        let byt = split(&r.submitted_bytes);
        let dfmt = split(&r.submitted_format);
        // (url, fmt, md5, bytes)
        let mut entries: Vec<(String, String, String, Option<i64>)> = Vec::new();
        for (i, u) in ftp.iter().enumerate() {
            if u.is_empty() {
                continue;
            }
            let fmt = fmt_of(u, dfmt.get(i).copied().unwrap_or(""));
            entries.push((
                u.to_string(),
                fmt,
                md5.get(i).copied().unwrap_or("").to_string(),
                byt.get(i).and_then(|b| to_int(b)),
            ));
        }
        let primaries: Vec<&(String, String, String, Option<i64>)> =
            entries.iter().filter(|e| ALIGNED.contains(&e.1.as_str())).collect();
        let indexes: Vec<&(String, String, String, Option<i64>)> =
            entries.iter().filter(|e| INDEX.contains(&e.1.as_str())).collect();
        if !primaries.is_empty() {
            let files = primaries
                .iter()
                .map(|p| {
                    // An index sidecar shares the primary's stem (foo.cram -> foo.cram.crai).
                    let idx = indexes.iter().find(|ix| ix.0.starts_with(&p.0)).map(|ix| ix.0.clone());
                    NewSeqFile {
                        file_name: basename(&p.0),
                        file_format: Some(p.1.clone()),
                        file_size_bytes: p.3,
                        file_url: p.0.clone(),
                        file_index_url: idx,
                        md5: opt(&p.2),
                        aligner: None,
                        target_reference: None,
                    }
                })
                .collect();
            aligned.push(mk_lib(r, files));
            continue; // this run contributed aligned files; don't also stage its fastq
        }

        // ── fastq fallback (only used if NO run had aligned files) ──────────────
        let fq = split(&r.fastq_ftp);
        let fqmd5 = split(&r.fastq_md5);
        let fqbytes = split(&r.fastq_bytes);
        let fq_files: Vec<NewSeqFile> = fq
            .iter()
            .enumerate()
            .filter(|(_, u)| !u.is_empty())
            .map(|(i, u)| NewSeqFile {
                file_name: basename(u),
                file_format: Some("FASTQ".into()),
                file_size_bytes: fqbytes.get(i).and_then(|b| to_int(b)),
                file_url: u.to_string(),
                file_index_url: None,
                md5: opt(fqmd5.get(i).copied().unwrap_or("")),
                aligner: None,
                target_reference: None,
            })
            .collect();
        if !fq_files.is_empty() {
            fastq.push(mk_lib(r, fq_files));
        }
    }

    if !aligned.is_empty() {
        aligned
    } else {
        fastq
    }
}

/// Outcome counts for one crawled study.
pub struct CrawlOutcome {
    pub samples: usize,
    pub runs: usize,
}

/// Crawl one study: pull its runs, and for each distinct sample upsert the
/// biosample, link it to the study's publication(s), and ingest its read files.
pub async fn crawl_study(
    pool: &PgPool,
    ena: &EnaClient,
    study_id: i64,
    accession: &str,
) -> anyhow::Result<CrawlOutcome> {
    let rows = ena.run_files(accession).await?;
    let publications = du_db::study::publications_for_study(pool, study_id).await?;

    // Group runs by sample accession (skip rows without one).
    let mut by_sample: BTreeMap<String, Vec<&EnaRunRow>> = BTreeMap::new();
    for r in &rows {
        let sa = r.sample_accession.trim();
        if !sa.is_empty() {
            by_sample.entry(sa.to_string()).or_default().push(r);
        }
    }

    for (sample_acc, sample_runs) in &by_sample {
        let (guid, created) =
            du_db::biosample::upsert_by_accession(pool, sample_acc, SAMPLE_SOURCE, None).await?;
        if created {
            tracing::debug!(sample = %sample_acc, "crawl-project: created biosample");
        }
        for pid in &publications {
            du_db::publication::link_biosample(pool, PublicationId(*pid), guid.0).await?;
        }
        let libs = build_libraries(sample_runs);
        if !libs.is_empty() {
            // Idempotent at sample granularity — skips samples that already have files.
            if let Err(e) = du_db::sequence::ingest_libraries(pool, guid, &libs).await {
                tracing::warn!(sample = %sample_acc, error = %e, "crawl-project: ingest failed");
            }
        }
    }

    Ok(CrawlOutcome { samples: by_sample.len(), runs: rows.len() })
}

/// Drain the pending-crawl queue (the scheduled/curator-triggered path).
pub async fn crawl_pending(pool: &PgPool, ena: &EnaClient) -> anyhow::Result<()> {
    let pending = du_db::study::pending_crawls(pool, 200).await?;
    if pending.is_empty() {
        return Ok(());
    }
    tracing::info!(count = pending.len(), "crawl-project: draining pending studies");
    for s in pending {
        match crawl_study(pool, ena, s.id, &s.accession).await {
            Ok(o) => {
                du_db::study::mark_crawled(pool, s.id, o.samples as i32, o.runs as i32, None).await?;
                tracing::info!(accession = %s.accession, samples = o.samples, runs = o.runs, "crawl-project: done");
            }
            Err(e) => {
                du_db::study::mark_crawled(pool, s.id, 0, 0, Some(&e.to_string())).await?;
                tracing::warn!(accession = %s.accession, error = %e, "crawl-project: failed");
            }
        }
        tokio::time::sleep(REQUEST_GAP).await;
    }
    Ok(())
}

/// Ad-hoc crawl of a single accession (`run-once crawl-project <accession>`).
/// Ensures the study row exists first; links no publication (ops/testing path).
pub async fn crawl_one_accession(pool: &PgPool, ena: &EnaClient, accession: &str) -> anyhow::Result<()> {
    let source = du_db::study::source_for_accession(accession);
    let id = du_db::study::upsert_by_accession(pool, accession, source).await?;
    match crawl_study(pool, ena, id, accession).await {
        Ok(o) => {
            du_db::study::mark_crawled(pool, id, o.samples as i32, o.runs as i32, None).await?;
            tracing::info!(accession = %accession, samples = o.samples, runs = o.runs, "crawl-project: done");
            Ok(())
        }
        Err(e) => {
            du_db::study::mark_crawled(pool, id, 0, 0, Some(&e.to_string())).await?;
            Err(e)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(sample: &str, submitted: &str, fmt: &str, fastq: &str) -> EnaRunRow {
        EnaRunRow {
            run_accession: "ERR1".into(),
            sample_accession: sample.into(),
            submitted_ftp: submitted.into(),
            submitted_format: fmt.into(),
            fastq_ftp: fastq.into(),
            library_layout: "PAIRED".into(),
            first_public: "2020-01-15".into(),
            ..Default::default()
        }
    }

    #[test]
    fn prefers_aligned_cram_with_index_sidecar() {
        let r = row("SAMEA1", "ftp/x.cram;ftp/x.cram.crai", "CRAM;CRAI", "ftp/x_1.fastq.gz");
        let libs = build_libraries(&[&r]);
        assert_eq!(libs.len(), 1);
        assert_eq!(libs[0].files.len(), 1, "index folds into the primary, not a second file");
        let f = &libs[0].files[0];
        assert_eq!(f.file_format.as_deref(), Some("CRAM"));
        assert_eq!(f.file_name, "x.cram");
        assert_eq!(f.file_index_url.as_deref(), Some("ftp/x.cram.crai"));
        assert_eq!(libs[0].paired_end, Some(true));
        assert_eq!(libs[0].run_date, chrono::NaiveDate::from_ymd_opt(2020, 1, 15));
    }

    #[test]
    fn falls_back_to_fastq_when_no_aligned() {
        let r = row("SAMEA2", "", "", "ftp/y_1.fastq.gz;ftp/y_2.fastq.gz");
        let libs = build_libraries(&[&r]);
        assert_eq!(libs.len(), 1);
        assert_eq!(libs[0].files.len(), 2);
        assert!(libs[0].files.iter().all(|f| f.file_format.as_deref() == Some("FASTQ")));
    }

    #[test]
    fn ignores_runs_with_no_linkable_files() {
        let r = row("SAMEA3", "ftp/z.vcf.gz", "VCF", "");
        assert!(build_libraries(&[&r]).is_empty());
    }
}
