//! Y-DNA variant coordinate-lift backfill.
//!
//! Many `core.variant` rows carry only their source-build coordinate and are missing the
//! other tracked builds — so the Variant Browser shows a single build. This job walks every
//! Y variant that is missing a build and fills the gaps via UCSC chains, pivoting through
//! GRCh38: rows with GRCh38 lift forward to GRCh37/hs1, and de-novo hs1 calls or imported
//! GRCh37 sites that arrived *without* GRCh38 (e.g. from the Jetstream ingest) are first
//! reverse-lifted up to GRCh38 (via the `*ToHg38` chains) so the forward pass can complete
//! them. It corrects for reverse-strand chain blocks (Y has real assembly inversions between
//! GRCh38 and hs1) by reverse-complementing the alleles, and validates each lift against the
//! *target reference base* — which should be one of the two alleles.
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
    /// no GRCh38 source coordinate to pivot from (and no reverse chain could establish one).
    no_source: usize,
    /// GRCh38 pivots established by reverse-lifting an hs1/GRCh37-only variant up to GRCh38.
    pivots_established: usize,
}

/// Load a chain file, trying known filenames (each also probed with a `.gz` suffix)
/// and transparently decompressing gzipped chains. Returns None if none are present.
fn load_chain_opt(dir: &Path, names: &[&str]) -> Result<Option<Liftover>> {
    for n in names {
        for name in [n.to_string(), format!("{n}.gz")] {
            let p = dir.join(&name);
            if p.exists() {
                let text = crate::gzio::read_to_string(&p)
                    .with_context(|| format!("read {}", p.display()))?;
                let lo = Liftover::parse(&text).with_context(|| format!("parse {}", p.display()))?;
                return Ok(Some(lo));
            }
        }
    }
    Ok(None)
}

/// Load a **required** chain — the forward pivot aborts the job if it's missing.
fn load_chain(dir: &Path, names: &[&str]) -> Result<Liftover> {
    load_chain_opt(dir, names)?.ok_or_else(|| {
        anyhow::anyhow!("no chain file found in {} (tried {:?}, each ±.gz)", dir.display(), names)
    })
}

/// Validate a lifted allele against the target reference base (which should equal one of the
/// two alleles) and tally the outcome. Shared by the forward and reverse-pivot lifts.
fn validate(rep: &mut Report, reference: Option<&Contig>, pos: i64, anc: &Option<String>, der: &Option<String>) {
    match reference.and_then(|r| r.base_at(pos)) {
        Some(rb) => {
            let a = single_base(anc);
            let d = single_base(der);
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
}

/// Load a reference contig for validation, trying known filenames/subdirs (the
/// faidx reader transparently handles bgzipped `.fasta.gz`). Returns None (with a
/// warning) when none are present, so the job still lifts positions without
/// base-level checks.
fn load_ref_contig(dir: &Path, names: &[&str], contig: &str) -> Option<Contig> {
    for n in names {
        let p = dir.join(n);
        if !p.exists() {
            continue;
        }
        return match Reference::open(&p).and_then(|r| r.load_contig(contig)) {
            Ok(c) => Some(c),
            Err(e) => {
                tracing::warn!(path = %p.display(), error = %e, "reference load failed — skipping base validation");
                None
            }
        };
    }
    tracing::warn!(dir = %dir.display(), contig, "no reference FASTA found — skipping base validation");
    None
}

fn single_base(a: &Option<String>) -> Option<u8> {
    a.as_deref().filter(|s| s.len() == 1).map(|s| s.as_bytes()[0].to_ascii_uppercase())
}

/// A non-GRCh38 build we can reverse-lift *into* GRCh38 to establish a pivot for a variant
/// that arrived without a GRCh38 coordinate (de-novo hs1 calls, imported GRCh37 sites from
/// the Jetstream ingest).
struct Source {
    build: ReferenceBuild,
    /// `<build>` → GRCh38 chain (`hs1ToHg38` / `hg19ToHg38`).
    chain: Liftover,
}

/// Reverse-lift the first available non-GRCh38 build up to GRCh38, mutating `coords` in place
/// and returning the new GRCh38 coordinate. None if no source build maps (stays `no_source`).
fn establish_grch38(
    coords: &mut Coordinates,
    sources: &[Source],
    grch38_ref: Option<&Contig>,
    rep: &mut Report,
) -> Option<BuildCoordinate> {
    for s in sources {
        let Some(bc) = coords.get(s.build).cloned() else { continue };
        // GRCh37 sites are sometimes stored with contig `Y`; the hg19→hg38 chain keys on `chrY`
        // (same coordinates, different name). hs1 is already `chrY`.
        let contig = if bc.contig == "Y" { "chrY" } else { bc.contig.as_str() };
        let Some((_q, pos0, reverse)) = s.chain.lift_detail(contig, bc.position - 1) else {
            continue;
        };
        let pos = pos0 + 1;
        if reverse {
            rep.reverse += 1;
        }
        let (anc, der) = if reverse {
            (bc.ancestral.as_deref().map(revcomp), bc.derived.as_deref().map(revcomp))
        } else {
            (bc.ancestral.clone(), bc.derived.clone())
        };
        validate(rep, grch38_ref, pos, &anc, &der);
        let g38 =
            BuildCoordinate { contig: "chrY".into(), position: pos, ancestral: anc, derived: der };
        coords.set(ReferenceBuild::GRCh38, g38.clone());
        rep.lifted += 1;
        rep.pivots_established += 1;
        return Some(g38);
    }
    None
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
            // UCSC `hg38ToHg19` == GRCh38→GRCh37. b37's chrY is the `Y` contig.
            chain: load_chain(&cfg.liftover_dir, &["GRCh38-to-GRCh37.chain", "hg38ToHg19.over.chain"])?,
            stored_contig: "chrY".into(),
            reference: load_ref_contig(
                &cfg.reference_dir,
                &["GRCh37.fa", "b37/human_g1k_v37.fasta.gz", "human_g1k_v37.fasta.gz"],
                "Y",
            ),
        },
        Target {
            build: ReferenceBuild::Hs1,
            chain: load_chain(&cfg.liftover_dir, &["GRCh38-to-chm13v2.0.chain", "hg38ToHs1.over.chain"])?,
            stored_contig: "chrY".into(),
            reference: load_ref_contig(
                &cfg.reference_dir,
                &["chm13v2.0.fa", "hs1/chm13v2.0.fa.gz", "chm13v2.0.fa.gz"],
                "chrY",
            ),
        },
    ];

    // Reverse chains (optional): lift a de-novo hs1 call / imported GRCh37 site that has no
    // GRCh38 coordinate *up* to GRCh38, so the forward pivot above can then complete it. Absent
    // chains just mean those variants stay `no_source` (the pre-reverse-lift behavior). The
    // GRCh38 reference validates the reverse lift (b38 is staged on prod).
    let sources: Vec<Source> = [
        (ReferenceBuild::Hs1, &["chm13v2.0-to-GRCh38.chain", "hs1ToHg38.over.chain"][..]),
        (ReferenceBuild::GRCh37, &["GRCh37-to-GRCh38.chain", "hg19ToHg38.over.chain"][..]),
    ]
    .into_iter()
    .filter_map(|(build, names)| match load_chain_opt(&cfg.liftover_dir, names) {
        Ok(Some(chain)) => Some(Source { build, chain }),
        Ok(None) => {
            tracing::info!(?build, "no reverse chain — variants missing GRCh38 from this build stay unpivoted");
            None
        }
        Err(e) => {
            tracing::warn!(?build, error = %e, "reverse chain failed to load — skipping");
            None
        }
    })
    .collect();
    let grch38_ref = load_ref_contig(
        &cfg.reference_dir,
        &["GRCh38.fa", "b38/Homo_sapiens_assembly38.fasta.gz", "Homo_sapiens_assembly38.fasta.gz"],
        "chrY",
    );

    tracing::info!(
        liftover = %cfg.liftover_dir.display(), reference = %cfg.reference_dir.display(),
        targets = targets.len(), reverse_sources = sources.len(), apply = cfg.apply,
        "variant-coord-lift starting"
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

            // Pivot from GRCh38 (what nearly every row has). When it's absent — a de-novo hs1
            // call or imported GRCh37 site — reverse-lift an available build up to GRCh38 first
            // so the forward pass below can complete it. Clone the source so we can mutate
            // `coords` while lifting.
            let mut changed = false;
            let src = match coords.get(ReferenceBuild::GRCh38).cloned() {
                Some(s) => s,
                None => match establish_grch38(&mut coords, &sources, grch38_ref.as_ref(), &mut rep) {
                    Some(g38) => {
                        changed = true; // we just added GRCh38 — ensure the row is written back
                        g38
                    }
                    None => {
                        rep.no_source += 1;
                        continue;
                    }
                },
            };
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
                validate(&mut rep, t.reference.as_ref(), pos, &anc, &der);

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
        // A newly-lifted coordinate can fold a variant into a twin it now shares a build
        // with (e.g. a GRCh38-only row that just gained hs1), so recompute the catalog
        // representatives before the revision bump — else the browser keeps showing the
        // now-collapsible rows separately until the next reconcile.
        du_db::variant::recompute_catalog_representatives(pool).await?;
        du_db::tree_revision::bump(pool).await?;
    }
    tracing::info!(
        apply = cfg.apply, scanned = rep.scanned, variants_updated = rep.variants_updated,
        lifted = rep.lifted, reverse = rep.reverse, unmapped = rep.unmapped,
        pivots_established = rep.pivots_established,
        ref_validated = rep.ref_validated, ref_mismatch = rep.ref_mismatch,
        ref_unchecked = rep.ref_unchecked, no_source = rep.no_source,
        "variant-coord-lift complete{}", if cfg.apply { "" } else { " (preview — pass --apply to write)" }
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn chain(text: &str) -> Liftover {
        Liftover::parse(text).unwrap()
    }

    fn hs1_only(pos: i64, anc: &str, der: &str) -> Coordinates {
        let mut c = Coordinates::default();
        c.set(
            ReferenceBuild::Hs1,
            BuildCoordinate {
                contig: "chrY".into(),
                position: pos,
                ancestral: Some(anc.into()),
                derived: Some(der.into()),
            },
        );
        c
    }

    // hs1 → GRCh38 identity chain over chrY.
    const HS1_TO_HG38: &str = "chain 100 chrY 1000 + 0 1000 chrY 1000 + 0 1000 1\n1000\n";

    #[test]
    fn reverse_lift_establishes_grch38_pivot_from_hs1() {
        let sources = vec![Source { build: ReferenceBuild::Hs1, chain: chain(HS1_TO_HG38) }];
        let mut coords = hs1_only(500, "A", "G");
        let mut rep = Report::default();

        let g38 = establish_grch38(&mut coords, &sources, None, &mut rep);

        assert!(g38.is_some(), "an hs1-only variant should gain a GRCh38 pivot");
        let g38 = coords.get(ReferenceBuild::GRCh38).expect("GRCh38 now present");
        assert_eq!(g38.contig, "chrY");
        assert_eq!(g38.position, 500, "identity chain, 1-based round-trip");
        assert_eq!(g38.ancestral.as_deref(), Some("A"));
        assert_eq!(g38.derived.as_deref(), Some("G"));
        assert_eq!(rep.pivots_established, 1);
    }

    #[test]
    fn reverse_lift_without_matching_source_stays_no_source() {
        // GRCh37-only variant, but only an hs1 reverse chain is configured → no pivot.
        let sources = vec![Source { build: ReferenceBuild::Hs1, chain: chain(HS1_TO_HG38) }];
        let mut coords = Coordinates::default();
        coords.set(
            ReferenceBuild::GRCh37,
            BuildCoordinate { contig: "Y".into(), position: 500, ancestral: Some("A".into()), derived: Some("G".into()) },
        );
        let mut rep = Report::default();

        assert!(establish_grch38(&mut coords, &sources, None, &mut rep).is_none());
        assert!(coords.get(ReferenceBuild::GRCh38).is_none());
        assert_eq!(rep.pivots_established, 0);
    }

    #[test]
    fn reverse_lift_revcomps_alleles_on_inverted_block() {
        // hs1 → GRCh38 reverse-strand block: alleles are reverse-complemented.
        let rev = "chain 1 chrY 100 + 0 10 chrY 100 - 0 10 1\n10\n";
        let sources = vec![Source { build: ReferenceBuild::Hs1, chain: chain(rev) }];
        let mut coords = hs1_only(1, "A", "G"); // 1-based pos 1 → t_pos 0
        let mut rep = Report::default();

        establish_grch38(&mut coords, &sources, None, &mut rep).expect("pivot established");

        let g38 = coords.get(ReferenceBuild::GRCh38).unwrap();
        assert_eq!(g38.ancestral.as_deref(), Some("T"), "A reverse-complements to T");
        assert_eq!(g38.derived.as_deref(), Some("C"), "G reverse-complements to C");
        assert_eq!(rep.reverse, 1);
    }
}
