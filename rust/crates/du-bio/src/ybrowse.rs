//! YBrowse variant ingestion. YBrowse publishes Y-chromosome variants on
//! **GRCh38**; this turns parsed records into [`NewVariant`]s with multi-build
//! `coordinates` by lifting each GRCh38 position to the other tracked builds
//! (GRCh37, hs1) via chain files.
//!
//! Coordinate systems: VCF positions are 1-based; UCSC chains are 0-based
//! half-open. Liftover converts 1-based -> 0-based, maps, then back to 1-based.

use crate::liftover::Liftover;
use crate::vcf::VcfVariant;
use du_domain::enums::{MutationType, ReferenceBuild};
use du_domain::variant::{Aliases, BuildCoordinate, Coordinates, NewVariant};

/// A build to lift GRCh38 coordinates into, with its GRCh38->target chain set.
pub struct LiftTarget {
    pub build: ReferenceBuild,
    pub chain: Liftover,
}

/// Lift a 1-based position through a (0-based) chain, returning a 1-based result.
fn lift_1based(chain: &Liftover, contig: &str, pos_1based: i64) -> Option<(String, i64)> {
    chain.lift(contig, pos_1based - 1).map(|(c, p)| (c, p + 1))
}

/// Classify a variant by ref/alt length (SNP when both single-base).
fn classify(v: &VcfVariant) -> MutationType {
    let alt_len = v.alternate.first().map(String::len).unwrap_or(1);
    if v.reference.len() == 1 && alt_len == 1 {
        MutationType::Snp
    } else {
        MutationType::Indel
    }
}

#[derive(Debug, Default)]
pub struct IngestResult {
    pub variants: Vec<NewVariant>,
    /// Count of (record, target-build) pairs that failed to lift (gaps/out-of-range).
    pub unmapped_lifts: usize,
}

/// Build [`NewVariant`]s from YBrowse GRCh38 VCF records, lifting to `targets`.
/// The first VCF ID becomes the canonical name; remaining IDs become aliases.
pub fn from_grch38_vcf(records: &[VcfVariant], targets: &[LiftTarget]) -> IngestResult {
    let mut out = IngestResult::default();
    for r in records {
        let alt = r.alternate.first().cloned();
        let mut coords = Coordinates::default();
        coords.set(
            ReferenceBuild::GRCh38,
            BuildCoordinate {
                contig: r.chrom.clone(),
                position: r.pos,
                reference_allele: Some(r.reference.clone()),
                alternate_allele: alt.clone(),
            },
        );
        for t in targets {
            match lift_1based(&t.chain, &r.chrom, r.pos) {
                Some((contig, position)) => coords.set(
                    t.build,
                    BuildCoordinate {
                        contig,
                        position,
                        reference_allele: Some(r.reference.clone()),
                        alternate_allele: alt.clone(),
                    },
                ),
                None => out.unmapped_lifts += 1,
            }
        }
        let canonical_name = r
            .ids
            .first()
            .cloned()
            .unwrap_or_else(|| format!("{}:{}", r.chrom, r.pos));
        let aliases = Aliases {
            common_names: r.ids.iter().skip(1).cloned().collect(),
            ..Default::default()
        };
        out.variants.push(NewVariant {
            canonical_name,
            mutation_type: classify(r),
            aliases,
            coordinates: coords,
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::vcf;

    // chrY GRCh38 -> chrY GRCh37 with a +1,000,000 shift for the first block and
    // a gap so one variant fails to lift. Chains are 0-based.
    const CHAIN_38_TO_37: &str = "\
chain 1 chrY 60000000 + 2000000 2900000 chrY 59000000 + 3000000 3900000 1
500000 100000 0
300000
";

    #[test]
    fn ingests_grch38_and_lifts_to_grch37() {
        // Variant A at 1-based 2,200,001 (0-based 2,200,000) -> within block 1.
        // Variant B at 2,650,001 -> within block 2.
        // Variant C at 2,550,001 -> in the gap [2,500,000, 2,600,000): no lift.
        let vcf_text = "\
#CHROM\tPOS\tID\tREF\tALT
chrY\t2200001\tM-A;PF1\tC\tT
chrY\t2650001\tM-B\tG\tA
chrY\t2550001\tM-C\tA\tG
";
        let records = vcf::parse(vcf_text.as_bytes()).unwrap();
        let targets = vec![LiftTarget {
            build: ReferenceBuild::GRCh37,
            chain: Liftover::parse(CHAIN_38_TO_37).unwrap(),
        }];
        let res = from_grch38_vcf(&records, &targets);

        assert_eq!(res.variants.len(), 3);
        assert_eq!(res.unmapped_lifts, 1); // variant C in the gap

        let a = &res.variants[0];
        assert_eq!(a.canonical_name, "M-A");
        assert_eq!(a.aliases.common_names, vec!["PF1"]);
        // GRCh38 carried verbatim
        let g38 = a.coordinates.get(ReferenceBuild::GRCh38).unwrap();
        assert_eq!((g38.contig.as_str(), g38.position), ("chrY", 2_200_001));
        // GRCh37: q_start 3,000,000, t_start 2,000,000 -> offset +1,000,000 in block 1
        let g37 = a.coordinates.get(ReferenceBuild::GRCh37).unwrap();
        assert_eq!((g37.contig.as_str(), g37.position), ("chrY", 3_200_001));

        // Variant C only has GRCh38 (lift fell in the gap).
        let c = &res.variants[2];
        assert!(c.coordinates.get(ReferenceBuild::GRCh38).is_some());
        assert!(c.coordinates.get(ReferenceBuild::GRCh37).is_none());
    }
}
