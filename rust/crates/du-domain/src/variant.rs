//! Variant domain model and its JSONB payload shapes.
//!
//! This is the canonical example of the schema redesign: the legacy `variant` +
//! `variant_alias` tables (and per-build coordinate rows) collapse into one
//! `core.variant` row whose `aliases`, `coordinates`, and `annotations` columns
//! are JSONB. These structs ARE that JSONB contract.

use crate::enums::{MutationType, NamingStatus, ReferenceBuild};
use crate::ids::VariantId;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// A coordinate of a variant on one reference build.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BuildCoordinate {
    /// Contig/accession on this build (e.g. "chrY", "CM000686.2").
    pub contig: String,
    pub position: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reference_allele: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub alternate_allele: Option<String>,
}

/// `core.variant.coordinates` JSONB — keyed by reference build.
/// Stored as `{ "GRCh38": {...}, "hs1": {...} }`.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Coordinates(pub BTreeMap<String, BuildCoordinate>);

impl Coordinates {
    pub fn get(&self, build: ReferenceBuild) -> Option<&BuildCoordinate> {
        self.0.get(build.as_str())
    }

    pub fn set(&mut self, build: ReferenceBuild, coord: BuildCoordinate) {
        self.0.insert(build.as_str().to_string(), coord);
    }
}

/// `core.variant.aliases` JSONB — consolidates the old `variant_alias` rows.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Aliases {
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub common_names: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub rs_ids: Vec<String>,
    /// alias -> source attribution (e.g. "M269" -> "ISOGG").
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub sources: BTreeMap<String, String>,
}

/// `core.variant.annotations` JSONB.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Annotations {
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub cytobands: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub str_overlaps: Vec<String>,
}

/// A variant to ingest/upsert (no DB id). Produced by ingestion (e.g. YBrowse)
/// and upserted by canonical name; carries multi-build `coordinates`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NewVariant {
    pub canonical_name: String,
    pub mutation_type: MutationType,
    pub aliases: Aliases,
    pub coordinates: Coordinates,
}

/// A fully-hydrated variant (scalar columns + decoded JSONB payloads).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Variant {
    pub id: VariantId,
    pub canonical_name: String,
    pub mutation_type: MutationType,
    pub naming_status: NamingStatus,
    pub aliases: Aliases,
    pub coordinates: Coordinates,
    pub annotations: Annotations,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn coordinates_json_is_keyed_by_build_label() {
        let mut c = Coordinates::default();
        c.set(
            ReferenceBuild::GRCh38,
            BuildCoordinate {
                contig: "chrY".into(),
                position: 2_787_319,
                reference_allele: Some("C".into()),
                alternate_allele: Some("T".into()),
            },
        );
        let json = serde_json::to_value(&c).unwrap();
        assert!(json.get("GRCh38").is_some(), "keyed by build label: {json}");
        assert_eq!(json["GRCh38"]["position"], 2_787_319);

        // round-trips and is queryable by typed build.
        let back: Coordinates = serde_json::from_value(json).unwrap();
        assert_eq!(back.get(ReferenceBuild::GRCh38).unwrap().contig, "chrY");
        assert!(back.get(ReferenceBuild::Hs1).is_none());
    }

    #[test]
    fn empty_alias_fields_are_omitted_from_json() {
        let a = Aliases {
            common_names: vec!["M269".into()],
            ..Default::default()
        };
        let json = serde_json::to_string(&a).unwrap();
        assert_eq!(json, r#"{"common_names":["M269"]}"#);
    }
}
