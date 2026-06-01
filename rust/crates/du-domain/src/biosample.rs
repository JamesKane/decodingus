//! Biosample domain type — the unified sample (standard/citizen/pgp/external/
//! ancient) discriminated by `source`, with source-specific fields and the
//! AT Protocol reference carried in JSONB (plan §2).

use crate::enums::BiosampleSource;
use crate::ids::SampleGuid;
use serde::{Deserialize, Serialize};

/// A mappable biosample location (from the donor's `geocoord`, WGS84).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GeoPoint {
    pub lat: f64,
    pub lon: f64,
    pub accession: Option<String>,
    pub source: BiosampleSource,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Biosample {
    pub sample_guid: SampleGuid,
    pub source: BiosampleSource,
    pub accession: Option<String>,
    pub alias: Option<String>,
    pub description: Option<String>,
    pub center_name: Option<String>,
    pub locked: bool,
    /// `core.biosample.source_attrs` JSONB (source-specific fields).
    pub source_attrs: serde_json::Value,
    /// `core.biosample.atproto` JSONB ({uri, cid, repo_did}) or None.
    pub atproto: Option<serde_json::Value>,
}
