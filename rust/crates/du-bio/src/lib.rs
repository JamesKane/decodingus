//! Genomics file I/O and coordinate math for DecodingUs — pure Rust, replacing
//! the JVM `htsjdk` (plan §6).
//!
//! - `callable`: BED interval merge + callable-loci summary.
//! - `liftover`: UCSC chain-file parse + cross-build position liftover.
//! - `vcf`: VCF variant reader.
//! - `ybrowse`: GRCh38 variant ingestion with multi-build liftover.

pub mod callable;
pub mod error;
pub mod liftover;
pub mod vcf;
pub mod ybrowse;

pub use error::BioError;
