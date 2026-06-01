//! ENA (European Nucleotide Archive) portal client — study metadata lookup.
//! Parsing is pure and unit-tested; the HTTP fetch is a thin wrapper.

use crate::error::ExternalError;
use chrono::NaiveDate;
use serde::Deserialize;

const DEFAULT_BASE: &str = "https://www.ebi.ac.uk/ena/portal/api";

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
}
