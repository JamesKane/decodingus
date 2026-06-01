//! Domain enums mirroring the Postgres native enums in the redesigned schema.
//!
//! `serde(rename_all = "SCREAMING_SNAKE_CASE")` keeps the wire/JSONB form equal
//! to the Postgres enum labels so `du-db` can map these directly with
//! `#[derive(sqlx::Type)]` and JSONB round-trips are stable.

use serde::{Deserialize, Serialize};

/// Y-DNA vs mtDNA. (legacy `dna_type` / `HaplogroupType`)
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DnaType {
    YDna,
    MtDna,
}

/// Origin of a biosample. Replaces the three separate legacy tables
/// (`biosample`, `citizen_biosample`, `pgp_biosample`) — see plan §2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum BiosampleSource {
    Standard,
    Citizen,
    Pgp,
    External,
    Ancient,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum BiologicalSex {
    Male,
    Female,
    Intersex,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DataGenerationMethod {
    Sequencing,
    Genotyping,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TargetType {
    WholeGenome,
    YChromosome,
    MtDna,
    Autosomal,
    XChromosome,
    Mixed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum MutationType {
    Snp,
    Indel,
    Str,
    Del,
    Ins,
    Mnp,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum NamingStatus {
    Unnamed,
    PendingReview,
    Named,
}

/// Reference genome builds tracked across the platform. The redesigned variant
/// `coordinates` JSONB is keyed by these.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum ReferenceBuild {
    #[serde(rename = "GRCh37")]
    GRCh37,
    #[serde(rename = "GRCh38")]
    GRCh38,
    /// T2T-CHM13 / hs1.
    #[serde(rename = "hs1")]
    Hs1,
}

impl ReferenceBuild {
    pub fn as_str(&self) -> &'static str {
        match self {
            ReferenceBuild::GRCh37 => "GRCh37",
            ReferenceBuild::GRCh38 => "GRCh38",
            ReferenceBuild::Hs1 => "hs1",
        }
    }

    pub fn parse(s: &str) -> Result<Self, crate::DomainError> {
        match s {
            "GRCh37" => Ok(ReferenceBuild::GRCh37),
            "GRCh38" => Ok(ReferenceBuild::GRCh38),
            "hs1" | "CHM13" | "T2T-CHM13" => Ok(ReferenceBuild::Hs1),
            other => Err(crate::DomainError::InvalidBuild(other.to_string())),
        }
    }
}

/// Tree change-set lifecycle (legacy `tree.change_set_status`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ChangeSetStatus {
    Draft,
    ReadyForReview,
    UnderReview,
    Applied,
    Discarded,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reference_build_roundtrips_through_json() {
        for b in [ReferenceBuild::GRCh37, ReferenceBuild::GRCh38, ReferenceBuild::Hs1] {
            let s = serde_json::to_string(&b).unwrap();
            let back: ReferenceBuild = serde_json::from_str(&s).unwrap();
            assert_eq!(b, back);
            assert_eq!(format!("\"{}\"", b.as_str()), s);
        }
    }

    #[test]
    fn enum_labels_match_screaming_snake_case() {
        assert_eq!(serde_json::to_string(&DnaType::YDna).unwrap(), "\"Y_DNA\"");
        assert_eq!(
            serde_json::to_string(&BiosampleSource::External).unwrap(),
            "\"EXTERNAL\""
        );
        assert_eq!(
            serde_json::to_string(&NamingStatus::PendingReview).unwrap(),
            "\"PENDING_REVIEW\""
        );
    }
}
