//! Genome region domain type — multi-build structural regions (centromere,
//! telomere, PAR, …). Coordinates and properties are JSONB documents keyed by
//! reference build, e.g. `{ "GRCh38": {contig, start, end}, "hs1": {...} }`.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GenomeRegion {
    pub id: i64,
    pub region_type: String,
    pub name: String,
    pub coordinates: serde_json::Value,
    pub properties: serde_json::Value,
}
