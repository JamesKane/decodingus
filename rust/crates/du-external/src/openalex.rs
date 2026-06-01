//! OpenAlex client: enrich publications by DOI and discover candidates by search.
//! JSON→domain parsing (incl. abstract reconstruction from the inverted index) is
//! pure and unit-tested; the HTTP fetch is a thin wrapper.
//!
//! OpenAlex asks polite-pool users to pass a `mailto`.

use crate::error::ExternalError;
use chrono::NaiveDate;
use serde::Deserialize;
use std::collections::HashMap;

const DEFAULT_BASE: &str = "https://api.openalex.org";

/// Enrichment metadata for an existing publication.
#[derive(Debug, Clone, PartialEq)]
pub struct WorkMeta {
    pub openalex_id: Option<String>,
    pub title: Option<String>,
    pub journal: Option<String>,
    pub publication_date: Option<NaiveDate>,
    pub cited_by_count: Option<i32>,
    pub open_access_status: Option<String>,
    pub abstract_summary: Option<String>,
}

/// A discovered candidate publication (from a search).
#[derive(Debug, Clone, PartialEq)]
pub struct Candidate {
    pub openalex_id: String,
    pub doi: Option<String>,
    pub title: Option<String>,
    pub abstract_summary: Option<String>,
    pub publication_date: Option<NaiveDate>,
    pub journal: Option<String>,
}

// ── wire types ────────────────────────────────────────────────────────────────
#[derive(Deserialize)]
struct Work {
    id: Option<String>,
    doi: Option<String>,
    title: Option<String>,
    publication_date: Option<String>,
    cited_by_count: Option<i64>,
    open_access: Option<OpenAccess>,
    primary_location: Option<Location>,
    abstract_inverted_index: Option<HashMap<String, Vec<i64>>>,
}

#[derive(Deserialize)]
struct OpenAccess {
    oa_status: Option<String>,
}
#[derive(Deserialize)]
struct Location {
    source: Option<Source>,
}
#[derive(Deserialize)]
struct Source {
    display_name: Option<String>,
}

#[derive(Deserialize)]
struct WorksPage {
    #[serde(default)]
    results: Vec<Work>,
}

fn parse_date(s: &Option<String>) -> Option<NaiveDate> {
    s.as_deref().and_then(|d| NaiveDate::parse_from_str(d, "%Y-%m-%d").ok())
}

/// Reconstruct abstract text from OpenAlex's inverted index (word → positions).
fn reconstruct_abstract(idx: &HashMap<String, Vec<i64>>) -> Option<String> {
    if idx.is_empty() {
        return None;
    }
    let mut placed: Vec<(i64, &str)> = Vec::new();
    for (word, positions) in idx {
        for &p in positions {
            placed.push((p, word.as_str()));
        }
    }
    placed.sort_by_key(|(p, _)| *p);
    Some(placed.into_iter().map(|(_, w)| w).collect::<Vec<_>>().join(" "))
}

impl Work {
    fn into_meta(self) -> WorkMeta {
        WorkMeta {
            openalex_id: self.id.clone(),
            title: self.title.clone(),
            journal: self.primary_location.and_then(|l| l.source).and_then(|s| s.display_name),
            publication_date: parse_date(&self.publication_date),
            cited_by_count: self.cited_by_count.map(|c| c as i32),
            open_access_status: self.open_access.and_then(|o| o.oa_status),
            abstract_summary: self.abstract_inverted_index.as_ref().and_then(reconstruct_abstract),
        }
    }

    fn into_candidate(self) -> Option<Candidate> {
        let openalex_id = self.id.clone()?;
        Some(Candidate {
            openalex_id,
            doi: self.doi.clone(),
            title: self.title.clone(),
            abstract_summary: self.abstract_inverted_index.as_ref().and_then(reconstruct_abstract),
            publication_date: parse_date(&self.publication_date),
            journal: self.primary_location.and_then(|l| l.source).and_then(|s| s.display_name),
        })
    }
}

pub struct OpenAlexClient {
    http: reqwest::Client,
    base: String,
    mailto: Option<String>,
}

impl OpenAlexClient {
    pub fn new(mailto: Option<String>) -> Self {
        OpenAlexClient { http: reqwest::Client::new(), base: DEFAULT_BASE.to_string(), mailto }
    }

    /// Fetch enrichment metadata for a DOI. Returns None on 404.
    pub async fn work_by_doi(&self, doi: &str) -> Result<Option<WorkMeta>, ExternalError> {
        let url = format!("{}/works/doi:{}", self.base, doi.trim());
        let mut req = self.http.get(url);
        if let Some(m) = &self.mailto {
            req = req.query(&[("mailto", m.as_str())]);
        }
        let resp = req.send().await?;
        if resp.status() == reqwest::StatusCode::NOT_FOUND {
            return Ok(None);
        }
        let work: Work = resp.error_for_status()?.json().await?;
        Ok(Some(work.into_meta()))
    }

    /// Search works (publication discovery).
    pub async fn search(&self, query: &str, per_page: u32) -> Result<Vec<Candidate>, ExternalError> {
        let url = format!("{}/works", self.base);
        let pp = per_page.clamp(1, 200).to_string();
        let mut req = self.http.get(url).query(&[("search", query), ("per-page", pp.as_str())]);
        if let Some(m) = &self.mailto {
            req = req.query(&[("mailto", m.as_str())]);
        }
        let page: WorksPage = req.send().await?.error_for_status()?.json().await?;
        Ok(page.results.into_iter().filter_map(Work::into_candidate).collect())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_work_and_reconstructs_abstract() {
        let json = r#"{
            "id": "https://openalex.org/W123",
            "doi": "https://doi.org/10.1000/euro1",
            "title": "Peopling of Europe",
            "publication_date": "2021-03-15",
            "cited_by_count": 142,
            "open_access": { "oa_status": "green" },
            "primary_location": { "source": { "display_name": "Nature" } },
            "abstract_inverted_index": { "Ancient": [0], "European": [1], "genomes": [2] }
        }"#;
        let work: Work = serde_json::from_str(json).unwrap();
        let meta = work.into_meta();
        assert_eq!(meta.cited_by_count, Some(142));
        assert_eq!(meta.open_access_status.as_deref(), Some("green"));
        assert_eq!(meta.journal.as_deref(), Some("Nature"));
        assert_eq!(meta.publication_date, NaiveDate::from_ymd_opt(2021, 3, 15));
        assert_eq!(meta.abstract_summary.as_deref(), Some("Ancient European genomes"));
    }

    #[test]
    fn parses_search_results_to_candidates() {
        let json = r#"{ "results": [
            { "id": "https://openalex.org/W1", "doi": "https://doi.org/10.1/a", "title": "A", "publication_date": "2020-01-01" },
            { "title": "no id -> dropped" }
        ]}"#;
        let page: WorksPage = serde_json::from_str(json).unwrap();
        let cands: Vec<Candidate> = page.results.into_iter().filter_map(Work::into_candidate).collect();
        assert_eq!(cands.len(), 1);
        assert_eq!(cands[0].openalex_id, "https://openalex.org/W1");
        assert_eq!(cands[0].title.as_deref(), Some("A"));
    }
}
