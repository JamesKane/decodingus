//! Haplogroup (phylogenetic tree node) domain type.

use crate::enums::DnaType;
use crate::ids::HaplogroupId;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Haplogroup {
    pub id: HaplogroupId,
    pub name: String,
    pub haplogroup_type: DnaType,
    pub lineage: Option<String>,
    pub source: Option<String>,
    pub confidence_level: Option<String>,
    pub formed_ybp: Option<i32>,
    pub tmrca_ybp: Option<i32>,
    /// `tree.haplogroup.provenance` JSONB (multi-source attribution / age detail).
    pub provenance: serde_json::Value,
}
