//! Publication domain type (research papers, enriched via OpenAlex).

use crate::ids::PublicationId;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Publication {
    pub id: PublicationId,
    pub title: String,
    pub doi: Option<String>,
    pub pubmed_id: Option<String>,
    pub journal: Option<String>,
    pub publication_date: Option<chrono::NaiveDate>,
    pub authors: Option<String>,
    pub abstract_summary: Option<String>,
    pub url: Option<String>,
    pub cited_by_count: Option<i32>,
    pub open_access_status: Option<String>,
}
