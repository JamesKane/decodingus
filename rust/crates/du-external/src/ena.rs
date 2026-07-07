//! ENA (European Nucleotide Archive) portal client — study metadata lookup and
//! study/project-level run enumeration. Parsing is pure and unit-tested; the HTTP
//! fetch is a thin wrapper.

use crate::error::ExternalError;
use chrono::NaiveDate;
use serde::Deserialize;

const DEFAULT_BASE: &str = "https://www.ebi.ac.uk/ena/portal/api";

/// The `read_run` fields requested for a project crawl (mirrors the ops driver
/// `link-ena-sequence-files.py`). Multi-file columns arrive `;`-joined.
const RUN_FIELDS: &str = "run_accession,sample_accession,submitted_ftp,submitted_md5,\
submitted_bytes,submitted_format,fastq_ftp,fastq_md5,fastq_bytes,instrument_model,\
library_layout,read_count,first_public";

/// One ENA `read_run` row for a project — the raw fields as ENA returns them
/// (`;`-joined for multi-file runs; the file-selection policy lives in the crawl job).
#[derive(Debug, Clone, PartialEq, Default)]
pub struct EnaRunRow {
    pub run_accession: String,
    pub sample_accession: String,
    pub submitted_ftp: String,
    pub submitted_md5: String,
    pub submitted_bytes: String,
    pub submitted_format: String,
    pub fastq_ftp: String,
    pub fastq_md5: String,
    pub fastq_bytes: String,
    pub instrument_model: String,
    pub library_layout: String,
    pub read_count: String,
    pub first_public: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct StudyMeta {
    pub accession: String,
    pub title: Option<String>,
    pub center_name: Option<String>,
    pub first_public: Option<NaiveDate>,
}

#[derive(Deserialize)]
struct StudyRow {
    study_accession: String,
    study_title: Option<String>,
    center_name: Option<String>,
    first_public: Option<String>,
}

impl From<StudyRow> for StudyMeta {
    fn from(r: StudyRow) -> Self {
        StudyMeta {
            accession: r.study_accession,
            title: r.study_title,
            center_name: r.center_name,
            first_public: r.first_public.as_deref().and_then(|d| NaiveDate::parse_from_str(d, "%Y-%m-%d").ok()),
        }
    }
}

/// Parse the ENA portal JSON array into the first study, if any.
fn parse_studies(json: &str) -> Result<Option<StudyMeta>, ExternalError> {
    let rows: Vec<StudyRow> = serde_json::from_str(json).map_err(|e| ExternalError::Parse(e.to_string()))?;
    Ok(rows.into_iter().next().map(StudyMeta::from))
}

/// Parse the tab-separated `filereport` body into run rows. Column order is taken
/// from the header, so it is robust to ENA reordering fields. A header-only (or
/// empty) body yields no rows.
fn parse_run_report(tsv: &str) -> Vec<EnaRunRow> {
    let mut lines = tsv.lines().filter(|l| !l.trim().is_empty());
    let header = match lines.next() {
        Some(h) => h,
        None => return Vec::new(),
    };
    let cols: Vec<&str> = header.split('\t').collect();
    let col = |name: &str| cols.iter().position(|c| *c == name);
    let (run, samp, sftp, smd5, sbytes, sfmt, fq, fqmd5, fqbytes, instr, layout, reads, pubd) = (
        col("run_accession"),
        col("sample_accession"),
        col("submitted_ftp"),
        col("submitted_md5"),
        col("submitted_bytes"),
        col("submitted_format"),
        col("fastq_ftp"),
        col("fastq_md5"),
        col("fastq_bytes"),
        col("instrument_model"),
        col("library_layout"),
        col("read_count"),
        col("first_public"),
    );
    let get = |f: &[&str], i: Option<usize>| i.and_then(|i| f.get(i)).map(|s| s.trim().to_string()).unwrap_or_default();
    lines
        .map(|line| {
            let f: Vec<&str> = line.split('\t').collect();
            EnaRunRow {
                run_accession: get(&f, run),
                sample_accession: get(&f, samp),
                submitted_ftp: get(&f, sftp),
                submitted_md5: get(&f, smd5),
                submitted_bytes: get(&f, sbytes),
                submitted_format: get(&f, sfmt),
                fastq_ftp: get(&f, fq),
                fastq_md5: get(&f, fqmd5),
                fastq_bytes: get(&f, fqbytes),
                instrument_model: get(&f, instr),
                library_layout: get(&f, layout),
                read_count: get(&f, reads),
                first_public: get(&f, pubd),
            }
        })
        .collect()
}

pub struct EnaClient {
    http: reqwest::Client,
    base: String,
}

impl Default for EnaClient {
    fn default() -> Self {
        Self::new()
    }
}

impl EnaClient {
    pub fn new() -> Self {
        EnaClient { http: reqwest::Client::new(), base: DEFAULT_BASE.to_string() }
    }

    /// Look up a study by accession (e.g. "PRJEB12345").
    pub async fn study(&self, accession: &str) -> Result<Option<StudyMeta>, ExternalError> {
        let url = format!("{}/search", self.base);
        let query = format!("study_accession=\"{}\"", accession.trim());
        let body = self
            .http
            .get(url)
            .query(&[
                ("result", "study"),
                ("query", query.as_str()),
                ("fields", "study_accession,study_title,center_name,first_public"),
                ("format", "json"),
                ("limit", "1"),
            ])
            .send()
            .await?
            .error_for_status()?
            .text()
            .await?;
        parse_studies(&body)
    }

    /// Enumerate every sequencing run for a study / project accession. Works for
    /// both ENA studies (`PRJEB…` → `ERR…`/`SAMEA…`) and NCBI BioProjects
    /// (`PRJNA…` → `SRR…`/`SAMN…`), since ENA mirrors the INSDC collaboration.
    /// Returns one row per run across all samples; an empty vec when the project
    /// has no reads (204/404 — e.g. genotype-only or unknown accession).
    pub async fn run_files(&self, accession: &str) -> Result<Vec<EnaRunRow>, ExternalError> {
        let url = format!("{}/filereport", self.base);
        let resp = self
            .http
            .get(url)
            .query(&[
                ("accession", accession.trim()),
                ("result", "read_run"),
                ("fields", RUN_FIELDS),
                ("format", "tsv"),
                ("limit", "0"),
            ])
            .send()
            .await?;
        if matches!(resp.status().as_u16(), 204 | 404) {
            return Ok(Vec::new());
        }
        let tsv = resp.error_for_status()?.text().await?;
        Ok(parse_run_report(&tsv))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_ena_study_row() {
        let json = r#"[{
            "study_accession": "PRJEB12345",
            "study_title": "Steppe ancient genomes",
            "center_name": "Some Institute",
            "first_public": "2019-07-01"
        }]"#;
        let s = parse_studies(json).unwrap().unwrap();
        assert_eq!(s.accession, "PRJEB12345");
        assert_eq!(s.title.as_deref(), Some("Steppe ancient genomes"));
        assert_eq!(s.center_name.as_deref(), Some("Some Institute"));
        assert_eq!(s.first_public, NaiveDate::from_ymd_opt(2019, 7, 1));
    }

    #[test]
    fn empty_result_is_none() {
        assert!(parse_studies("[]").unwrap().is_none());
    }

    #[test]
    fn parses_run_report_by_header_order() {
        // Deliberately reordered columns + a header-only trailing case.
        let tsv = "sample_accession\trun_accession\tsubmitted_ftp\tfastq_ftp\tinstrument_model\n\
                   SAMEA1\tERR1\tftp/x.cram\t\tIllumina NovaSeq 6000\n\
                   SAMEA2\tERR2\t\tftp/y_1.fastq.gz;ftp/y_2.fastq.gz\tIllumina HiSeq 2000\n";
        let rows = parse_run_report(tsv);
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].sample_accession, "SAMEA1");
        assert_eq!(rows[0].run_accession, "ERR1");
        assert_eq!(rows[0].submitted_ftp, "ftp/x.cram");
        assert_eq!(rows[1].fastq_ftp, "ftp/y_1.fastq.gz;ftp/y_2.fastq.gz");
        assert_eq!(rows[1].instrument_model, "Illumina HiSeq 2000");
        // Columns absent from the header default to empty (not a panic).
        assert_eq!(rows[0].submitted_md5, "");
    }

    #[test]
    fn empty_run_report_is_no_rows() {
        assert!(parse_run_report("").is_empty());
        assert!(parse_run_report("run_accession\tsample_accession\n").is_empty());
    }
}
