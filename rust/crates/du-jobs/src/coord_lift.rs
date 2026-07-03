//! Y-DNA variant coordinate-lift backfill.
//!
//! Many `core.variant` rows carry only their source-build coordinate (usually GRCh38, from
//! the YBrowse ingest) and are missing the other tracked builds — so the Variant Browser
//! shows a single build. This job walks every Y variant that is missing a build and lifts
//! its GRCh38 position onto the others via UCSC chains, then writes the merged multi-build
//! `coordinates`. It corrects for reverse-strand chain blocks (Y has real assembly
//! inversions between GRCh38 and hs1) by reverse-complementing the alleles, and validates
//! each lift against the *target reference base* — which should be one of the two alleles.
//!
//! Ancestral/derived are phylogenetic labels, so they are preserved across the lift (only
//! reverse-complemented on an inverted block); we do NOT repolarize to the target reference.
//!
//! Chains + references default to `~/.decodingus/{liftover,references}` (overridable via
//! `DU_LIFTOVER_DIR` / `DU_REFERENCE_DIR`). Previews unless `--apply`.
//!
//!   decodingus-jobs run-once variant-coord-lift --apply

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use du_bio::liftover::{revcomp, Liftover};
use du_db::PgPool;
use du_domain::enums::ReferenceBuild;
use du_domain::variant::{BuildCoordinate, Coordinates};

use crate::faidx::{Contig, Reference};

/// Rows fetched per page from the DB (each page is one write transaction).
const PAGE: i64 = 2000;

pub struct Config {
    pub liftover_dir: PathBuf,
    pub reference_dir: PathBuf,
    pub apply: bool,
    /// Process at most N variants (0 = all).
    pub limit: usize,
}

impl Config {
    pub fn from_env(args: &[String]) -> Self {
        let home = std::env::var("HOME").unwrap_or_default();
        let dir = |key: &str, sub: &str| {
            std::env::var(key)
                .ok()
                .filter(|s| !s.is_empty())
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from(&home).join(".decodingus").join(sub))
        };
        let limit = args
            .iter()
            .position(|a| a == "--limit")
            .and_then(|i| args.get(i + 1))
            .and_then(|n| n.parse().ok())
            .unwrap_or(0);
        Config {
            liftover_dir: dir("DU_LIFTOVER_DIR", "liftover"),
            reference_dir: dir("DU_REFERENCE_DIR", "references"),
            apply: args.iter().any(|a| a == "--apply"),
            limit,
        }
    }
}

/// A build to lift GRCh38 coordinates into: its GRCh38→target chain, the stored contig name,
/// and (optionally) the target reference contig for base-level validation.
struct Target {
    build: ReferenceBuild,
    chain: Liftover,
    /// Contig name to store on the produced coordinate (matches existing rows: `chrY`).
    stored_contig: String,
    /// Target reference sequence for validation (None if the reference file was absent).
    reference: Option<Contig>,
}

#[derive(Default)]
struct Report {
    scanned: usize,
    variants_updated: usize,
    /// (variant, build) coordinates written.
    lifted: usize,
    /// position fell in a chain gap / non-syntenic region.
    unmapped: usize,
    /// reverse-strand lifts (alleles reverse-complemented).
    reverse: usize,
    /// target reference base matched one of the two alleles.
    ref_validated: usize,
    /// target reference base matched neither allele (kept, but flagged).
    ref_mismatch: usize,
    /// no reference / indel allele — validation skipped.
    ref_unchecked: usize,
    /// no GRCh38 source coordinate to pivot from.
    no_source: usize,
}

/// Load a chain file, trying a couple of known filenames.
fn load_chain(dir: &Path, names: &[&str]) -> Result<Liftover> {
    for n in names {
        let p = dir.join(n);
        if p.exists() {
            let text = std::fs::read_to_string(&p).with_context(|| format!("read {}", p.display()))?;
            return Liftover::parse(&text).with_context(|| format!("parse {}", p.display()));
        }
    }
    anyhow::bail!("no chain file found in {} (tried {:?})", dir.display(), names)
}

/// Load a reference contig for validation; returns None (with a warning) if the reference
/// file is absent, so the job still lifts positions without base-level checks.
fn load_ref_contig(dir: &Path, fasta: &str, contig: &str) -> Option<Contig> {
    let p = dir.join(fasta);
    if !p.exists() {
        tracing::warn!(path = %p.display(), "reference absent — skipping base validation for this build");
        return None;
    }
    match Reference::open(&p).and_then(|r| r.load_contig(contig)) {
        Ok(c) => Some(c),
        Err(e) => {
            tracing::warn!(path = %p.display(), error = %e, "reference load failed — skipping base validation");
            None
        }
    }
}

fn single_base(a: &Option<String>) -> Option<u8> {
    a.as_deref().filter(|s| s.len() == 1).map(|s| s.as_bytes()[0].to_ascii_uppercase())
}

pub async fn run(pool: &PgPool, cfg: &Config) -> Result<()> {
    // GRCh38 → GRCh37 and GRCh38 → hs1. For hs1 prefer the authoritative T2T/marbl 1:1 chain
    // (`GRCh38-to-chm13v2.0.chain`, per docs/chm13-reference-resources.md) over the UCSC
    // `hg38ToHs1.over.chain` — the two disagree on ~a quarter of Y positions (the ampliconic /
    // inverted regions) and UCSC over-maps sites the stricter 1:1 chain flags non-syntenic.
    // GRCh37's reference contig is `Y` (chain q_name is chrY), so read validation bases from
    // `Y` but store `chrY`.
    let targets = vec![
        Target {
            build: ReferenceBuild::GRCh37,
            chain: load_chain(&cfg.liftover_dir, &["GRCh38-to-GRCh37.chain"])?,
            stored_contig: "chrY".into(),
            reference: load_ref_contig(&cfg.reference_dir, "GRCh37.fa", "Y"),
        },
        Target {
            build: ReferenceBuild::Hs1,
            chain: load_chain(&cfg.liftover_dir, &["GRCh38-to-chm13v2.0.chain", "hg38ToHs1.over.chain"])?,
            stored_contig: "chrY".into(),
            reference: load_ref_contig(&cfg.reference_dir, "chm13v2.0.fa", "chrY"),
        },
    ];
    tracing::info!(
        liftover = %cfg.liftover_dir.display(), reference = %cfg.reference_dir.display(),
        targets = targets.len(), apply = cfg.apply, "variant-coord-lift starting"
    );

    let mut rep = Report::default();
    let mut after_id: i64 = 0;
    loop {
        if cfg.limit > 0 && rep.scanned >= cfg.limit {
            break;
        }
        let page = du_db::variant::y_variants_needing_lift(pool, after_id, PAGE).await?;
        if page.is_empty() {
            break;
        }
        let mut writes: Vec<(du_domain::ids::VariantId, Coordinates)> = Vec::new();
        for (id, mut coords) in page {
            after_id = id.0;
            rep.scanned += 1;

            // Pivot from GRCh38 (what nearly every row has). Clone the source fields so we can
            // mutate `coords` while lifting.
            let Some(src) = coords.get(ReferenceBuild::GRCh38).cloned() else {
                rep.no_source += 1;
                continue;
            };
            let mut changed = false;
            for t in &targets {
                if coords.get(t.build).is_some() {
                    continue; // already present
                }
                // Chains are 0-based half-open; coordinates are 1-based.
                let Some((_qcontig, pos0, reverse)) = t.chain.lift_detail(&src.contig, src.position - 1) else {
                    rep.unmapped += 1;
                    continue;
                };
                let pos = pos0 + 1;
                if reverse {
                    rep.reverse += 1;
                }
                let (anc, der) = if reverse {
                    (src.ancestral.as_deref().map(revcomp), src.derived.as_deref().map(revcomp))
                } else {
                    (src.ancestral.clone(), src.derived.clone())
                };

                // Validate against the target reference base (should be one of the alleles).
                match t.reference.as_ref().and_then(|r| r.base_at(pos)) {
                    Some(rb) => {
                        let a = single_base(&anc);
                        let d = single_base(&der);
                        if a.is_none() && d.is_none() {
                            rep.ref_unchecked += 1; // indel / no alleles
                        } else if a == Some(rb) || d == Some(rb) {
                            rep.ref_validated += 1;
                        } else {
                            rep.ref_mismatch += 1;
                        }
                    }
                    None => rep.ref_unchecked += 1,
                }

                coords.set(
                    t.build,
                    BuildCoordinate { contig: t.stored_contig.clone(), position: pos, ancestral: anc, derived: der },
                );
                rep.lifted += 1;
                changed = true;
            }
            if changed {
                rep.variants_updated += 1;
                writes.push((id, coords));
            }
        }
        if cfg.apply && !writes.is_empty() {
            du_db::variant::set_coordinates_batch(pool, &writes).await?;
        }
        if rep.scanned % 50_000 < PAGE as usize {
            tracing::info!(
                scanned = rep.scanned, updated = rep.variants_updated, lifted = rep.lifted,
                unmapped = rep.unmapped, "…lifting"
            );
        }
    }

    if cfg.apply && rep.variants_updated > 0 {
        du_db::tree_revision::bump(pool).await?;
    }
    tracing::info!(
        apply = cfg.apply, scanned = rep.scanned, variants_updated = rep.variants_updated,
        lifted = rep.lifted, reverse = rep.reverse, unmapped = rep.unmapped,
        ref_validated = rep.ref_validated, ref_mismatch = rep.ref_mismatch,
        ref_unchecked = rep.ref_unchecked, no_source = rep.no_source,
        "variant-coord-lift complete{}", if cfg.apply { "" } else { " (preview — pass --apply to write)" }
    );
    Ok(())
}
