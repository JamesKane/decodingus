//! Genomics coordinate math + text-format parsing for the DecodingUs AppView
//! (pure Rust). Scope is **aggregation/ingest support**, not raw-read processing:
//! BAM/CRAM extraction and variant *calling* are out of scope — Navigator (edge)
//! does local calling and the AppView aggregates the resulting summaries and
//! variant proposals.
//!
//! - `callable`: BED interval merge + callable-loci summary (from Navigator BEDs).
//! - `liftover`: UCSC chain-file parse + cross-build position liftover.
//! - `vcf`: VCF variant reader (text) for catalog ingest.
//! - `ybrowse`: GRCh38 variant ingestion with multi-build liftover.

pub mod callable;
pub mod error;
pub mod liftover;
pub mod vcf;
pub mod ybrowse;

pub use error::BioError;
