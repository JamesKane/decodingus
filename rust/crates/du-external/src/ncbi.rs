//! NCBI E-utilities client — PubMed publication metadata by PMID. Complements
//! the OpenAlex client (which enriches by DOI): PubMed enriches by `pubmed_id`
//! and can supply a DOI when one is missing. JSON→domain parsing (incl. the messy
//! `pubdate` formats) is pure and unit-tested; the HTTP fetch is a thin wrapper.
//!
//! NCBI asks callers to identify themselves with `tool` + `email`; an `api_key`
//! raises the rate limit. <https://www.ncbi.nlm.nih.gov/books/NBK25497/>

use crate::error::ExternalError;
use chrono::NaiveDate;
use serde::Deserialize;
use std::collections::HashMap;

const DEFAULT_BASE: &str = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils";
const TOOL: &str = "decodingus";

/// PubMed metadata for an existing publication (by PMID).
#[derive(Debug, Clone, PartialEq)]
pub struct PubMedMeta {
    pub pmid: String,
    pub title: Option<String>,
    pub journal: Option<String>,
    pub publication_date: Option<NaiveDate>,
    pub doi: Option<String>,
    pub authors: Option<String>,
}

// ── wire types (esummary v2.0, retmode=json) ─────────────────────────────────
#[derive(Deserialize)]
struct EsummaryResp {
    result: Option<HashMap<String, serde_json::Value>>,
}

#[derive(Deserialize, Default)]
struct DocSummary {
    title: Option<String>,
    fulljournalname: Option<String>,
    source: Option<String>,
    pubdate: Option<String>,
    #[serde(default)]
    authors: Vec<Author>,
    #[serde(default)]
    articleids: Vec<ArticleId>,
}

#[derive(Deserialize)]
struct Author {
    name: Option<String>,
}
#[derive(Deserialize)]
struct ArticleId {
    idtype: Option<String>,
    value: Option<String>,
}

/// Three-letter (lowercased) English month → number.
fn month_num(m: &str) -> Option<u32> {
    let key: String = m.chars().take(3).collect::<String>().to_lowercase();
    ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]
        .iter()
        .position(|&x| x == key)
        .map(|i| i as u32 + 1)
}

/// Parse PubMed's free-form `pubdate` ("2019 Jul 15", "2019 Jul", "2019",
/// "2019 Jul-Aug", "2019 Spring") best-effort; missing month/day default to 1.
fn parse_pubdate(s: &str) -> Option<NaiveDate> {
    let parts: Vec<&str> = s.split_whitespace().collect();
    let year: i32 = parts.first()?.parse().ok()?;
    let month = parts
        .get(1)
        .and_then(|m| month_num(m.split('-').next().unwrap_or(m)))
        .unwrap_or(1);
    let day: u32 = parts.get(2).and_then(|d| d.parse().ok()).unwrap_or(1);
    NaiveDate::from_ymd_opt(year, month, day)
}

impl From<(&str, DocSummary)> for PubMedMeta {
    fn from((pmid, d): (&str, DocSummary)) -> Self {
        let authors: Vec<String> = d.authors.into_iter().filter_map(|a| a.name).collect();
        let doi = d
            .articleids
            .into_iter()
            .find(|a| a.idtype.as_deref() == Some("doi"))
            .and_then(|a| a.value)
            .filter(|v| !v.is_empty());
        PubMedMeta {
            pmid: pmid.to_string(),
            title: d.title.filter(|s| !s.is_empty()),
            journal: d.fulljournalname.or(d.source).filter(|s| !s.is_empty()),
            publication_date: d.pubdate.as_deref().and_then(parse_pubdate),
            doi,
            authors: (!authors.is_empty()).then(|| authors.join(", ")),
        }
    }
}

/// Parse an esummary response for `pmid` into metadata, or None if absent/errored.
fn parse_summary(json: &str, pmid: &str) -> Result<Option<PubMedMeta>, ExternalError> {
    let resp: EsummaryResp = serde_json::from_str(json).map_err(|e| ExternalError::Parse(e.to_string()))?;
    let Some(entry) = resp.result.and_then(|mut m| m.remove(pmid)) else {
        return Ok(None);
    };
    // esummary marks a bad/unknown id with an `error` field on the entry.
    if entry.get("error").is_some() {
        return Ok(None);
    }
    let doc: DocSummary = serde_json::from_value(entry).map_err(|e| ExternalError::Parse(e.to_string()))?;
    Ok(Some(PubMedMeta::from((pmid, doc))))
}

pub struct NcbiClient {
    http: reqwest::Client,
    base: String,
    email: Option<String>,
    api_key: Option<String>,
}

impl NcbiClient {
    pub fn new(email: Option<String>, api_key: Option<String>) -> Self {
        NcbiClient { http: reqwest::Client::new(), base: DEFAULT_BASE.to_string(), email, api_key }
    }

    /// Fetch PubMed metadata for a PMID via esummary.
    pub async fn pubmed_summary(&self, pmid: &str) -> Result<Option<PubMedMeta>, ExternalError> {
        let pmid = pmid.trim();
        let url = format!("{}/esummary.fcgi", self.base);
        let mut req = self
            .http
            .get(url)
            .query(&[("db", "pubmed"), ("id", pmid), ("retmode", "json"), ("tool", TOOL)]);
        if let Some(e) = &self.email {
            req = req.query(&[("email", e.as_str())]);
        }
        if let Some(k) = &self.api_key {
            req = req.query(&[("api_key", k.as_str())]);
        }
        let body = req.send().await?.error_for_status()?.text().await?;
        parse_summary(&body, pmid)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"{
      "header": {"type":"esummary","version":"0.3"},
      "result": {
        "uids": ["31036960"],
        "31036960": {
          "uid": "31036960",
          "title": "The formation of human populations in South and Central Asia.",
          "fulljournalname": "Science (New York, N.Y.)",
          "source": "Science",
          "pubdate": "2019 Sep 6",
          "authors": [{"name":"Narasimhan VM"}, {"name":"Patterson N"}],
          "articleids": [
            {"idtype":"pubmed","value":"31036960"},
            {"idtype":"doi","value":"10.1126/science.aat7487"}
          ]
        }
      }
    }"#;

    #[test]
    fn parses_pubmed_summary() {
        let m = parse_summary(SAMPLE, "31036960").unwrap().unwrap();
        assert_eq!(m.pmid, "31036960");
        assert_eq!(m.title.as_deref(), Some("The formation of human populations in South and Central Asia."));
        assert_eq!(m.journal.as_deref(), Some("Science (New York, N.Y.)"));
        assert_eq!(m.doi.as_deref(), Some("10.1126/science.aat7487"));
        assert_eq!(m.authors.as_deref(), Some("Narasimhan VM, Patterson N"));
        assert_eq!(m.publication_date, NaiveDate::from_ymd_opt(2019, 9, 6));
    }

    #[test]
    fn missing_id_is_none() {
        assert!(parse_summary(SAMPLE, "99999999").unwrap().is_none());
    }

    #[test]
    fn errored_entry_is_none() {
        let json = r#"{"result":{"uids":["1"],"1":{"uid":"1","error":"cannot get document summary"}}}"#;
        assert!(parse_summary(json, "1").unwrap().is_none());
    }

    #[test]
    fn pubdate_variants() {
        assert_eq!(parse_pubdate("2019 Jul 15"), NaiveDate::from_ymd_opt(2019, 7, 15));
        assert_eq!(parse_pubdate("2019 Jul"), NaiveDate::from_ymd_opt(2019, 7, 1));
        assert_eq!(parse_pubdate("2019"), NaiveDate::from_ymd_opt(2019, 1, 1));
        assert_eq!(parse_pubdate("2019 Jul-Aug"), NaiveDate::from_ymd_opt(2019, 7, 1));
        assert_eq!(parse_pubdate("2019 Spring"), NaiveDate::from_ymd_opt(2019, 1, 1));
        assert_eq!(parse_pubdate("garbage"), None);
    }
}
