//! YBrowse variant-ingest job. YBrowse's `snps_hg38.gff3` is the central
//! document Y-DNA naming authorities flow through: one line per Y-SNP on
//! **GRCh38** with the established name, ancestral/derived alleles, and authority
//! metadata (ISOGG/YCC haplogroup, YFull node, citation, primers, comment).
//!
//! We stream the GFF3 line-by-line (it's ~770 MB / 3M+ lines), lift each SNP
//! GRCh38 → GRCh37/hs1 via chain files, and bulk-upsert verbatim rows into the
//! `source.ybrowse_snp` **mirror** (one row per upstream name), then run
//! [`du_db::ybrowse::reconcile`] to derive the curated `core.variant` catalog —
//! so curator decisions survive the full-snapshot re-ingest (see `du_db::ybrowse`).
//! File-path + env driven (the GFF3 + chains are large deploy assets), so the job
//! only registers when `YBROWSE_GFF` is set.

use du_bio::liftover::Liftover;
use du_bio::ybrowse::LiftTarget;
use du_db::ybrowse::MirrorRow;
use du_db::PgPool;
use du_domain::enums::ReferenceBuild;
use du_domain::variant::{BuildCoordinate, Coordinates};
use std::io::{BufRead, BufReader};

/// Mirror rows buffered before a bulk upsert (upsert_mirror chunks internally).
const BATCH: usize = 5000;

#[derive(Clone)]
pub struct Config {
    /// Path to YBrowse `snps_hg38.gff3`.
    pub gff_path: String,
    /// GRCh38 -> GRCh37 chain file (optional).
    pub chain_grch37: Option<String>,
    /// GRCh38 -> hs1 (T2T-CHM13) chain file (optional).
    pub chain_hs1: Option<String>,
}

impl Config {
    pub fn from_env() -> Option<Config> {
        let gff_path = std::env::var("YBROWSE_GFF").ok().filter(|s| !s.is_empty())?;
        Some(Config {
            gff_path,
            chain_grch37: std::env::var("YBROWSE_CHAIN_GRCH37").ok().filter(|s| !s.is_empty()),
            chain_hs1: std::env::var("YBROWSE_CHAIN_HS1").ok().filter(|s| !s.is_empty()),
        })
    }
}

fn load_target(build: ReferenceBuild, path: &Option<String>) -> anyhow::Result<Option<LiftTarget>> {
    match path {
        Some(p) => {
            let text = std::fs::read_to_string(p)?;
            Ok(Some(LiftTarget { build, chain: Liftover::parse(&text)? }))
        }
        None => Ok(None),
    }
}

/// Lift a 1-based position through a (0-based) chain, returning a 1-based result.
fn lift_1based(chain: &Liftover, contig: &str, pos_1based: i64) -> Option<(String, i64)> {
    chain.lift(contig, pos_1based - 1).map(|(c, p)| (c, p + 1))
}

/// One parsed GFF3 SNP record (GRCh38).
#[derive(Debug, Clone, PartialEq)]
pub struct GffSnp {
    pub name: String,
    pub contig: String,
    pub pos: i64,
    pub anc: Option<String>,
    pub der: Option<String>,
    /// Cleaned authority attributes (placeholders dropped).
    pub attrs: Vec<(String, String)>,
}

/// YBrowse placeholder tokens that carry no information.
fn is_placeholder(v: &str) -> bool {
    let v = v.trim();
    v.is_empty() || matches!(v, "." | "TBD" | "not" | "not listed" | "none" | "n/a" | "NA")
}

/// Parse one GFF3 data line into a [`GffSnp`]. Returns `None` for comments,
/// blanks, malformed lines, or records with no `Name`.
pub fn parse_line(line: &str) -> Option<GffSnp> {
    if line.starts_with('#') || line.trim().is_empty() {
        return None;
    }
    let mut cols = line.split('\t');
    let contig = cols.next()?;
    let _source = cols.next()?;
    let _type = cols.next()?;
    let pos: i64 = cols.next()?.parse().ok()?;
    let _end = cols.next()?;
    let _score = cols.next()?;
    let _strand = cols.next()?;
    let _phase = cols.next()?;
    let attr_col = cols.next()?;

    let mut name: Option<String> = None;
    let mut anc: Option<String> = None;
    let mut der: Option<String> = None;
    let mut attrs: Vec<(String, String)> = Vec::new();
    for kv in attr_col.split(';') {
        let Some((k, v)) = kv.split_once('=') else { continue };
        let (k, v) = (k.trim(), v.trim());
        match k {
            "Name" | "ID" if name.is_none() => name = Some(v.to_string()),
            "allele_anc" if !is_placeholder(v) => anc = Some(v.to_string()),
            "allele_der" if !is_placeholder(v) => der = Some(v.to_string()),
            // Authority provenance — keep the informative ones.
            "isogg_haplogroup" | "ycc_haplogroup" | "yfull_node" | "ref" | "primer_f"
            | "primer_r" | "comment" | "mutation" | "count_tested" | "count_derived"
                if !is_placeholder(v) =>
            {
                attrs.push((k.to_string(), v.to_string()));
            }
            _ => {}
        }
    }
    let name = name.filter(|n| !n.trim().is_empty())?;
    Some(GffSnp { name, contig: contig.to_string(), pos, anc, der, attrs })
}

/// Assemble the `evidence` JSONB: source + the cleaned authority attributes
/// (counts coerced to numbers when possible).
fn build_evidence(snp: &GffSnp) -> serde_json::Value {
    let mut m = serde_json::Map::new();
    m.insert("source".into(), serde_json::json!("YBrowse"));
    for (k, v) in &snp.attrs {
        let val = match k.as_str() {
            "count_tested" | "count_derived" => {
                v.parse::<i64>().map(serde_json::Value::from).unwrap_or_else(|_| serde_json::json!(v))
            }
            _ => serde_json::json!(v),
        };
        m.insert(k.clone(), val);
    }
    serde_json::Value::Object(m)
}

/// Build a verbatim [`MirrorRow`] from a GFF SNP, lifting GRCh38 → targets for
/// the stored multi-build `coordinates`. Returns the row and the number of
/// target-build lifts that failed (gaps/out-of-range).
fn to_mirror(snp: &GffSnp, targets: &[LiftTarget]) -> (MirrorRow, usize) {
    let mut coords = Coordinates::default();
    coords.set(
        ReferenceBuild::GRCh38,
        BuildCoordinate {
            contig: snp.contig.clone(),
            position: snp.pos,
            ancestral: snp.anc.clone(),
            derived: snp.der.clone(),
        },
    );
    let mut unmapped = 0usize;
    for t in targets {
        match lift_1based(&t.chain, &snp.contig, snp.pos) {
            Some((contig, position)) => coords.set(
                t.build,
                BuildCoordinate {
                    contig,
                    position,
                    ancestral: snp.anc.clone(),
                    derived: snp.der.clone(),
                },
            ),
            None => unmapped += 1,
        }
    }
    let row = MirrorRow {
        name: snp.name.clone(),
        contig: snp.contig.clone(),
        position: snp.pos,
        allele_anc: snp.anc.clone(),
        allele_der: snp.der.clone(),
        coordinates: serde_json::to_value(&coords).unwrap_or_default(),
        evidence: build_evidence(snp),
    };
    (row, unmapped)
}

pub async fn run(pool: &PgPool, cfg: &Config) -> anyhow::Result<()> {
    let mut targets = Vec::new();
    targets.extend(load_target(ReferenceBuild::GRCh37, &cfg.chain_grch37)?);
    targets.extend(load_target(ReferenceBuild::Hs1, &cfg.chain_hs1)?);

    let file = std::fs::File::open(&cfg.gff_path)?;
    let reader = BufReader::new(file);

    // 1. Refresh the mirror verbatim (one row per upstream name).
    let mut batch: Vec<MirrorRow> = Vec::with_capacity(BATCH);
    let (mut parsed, mut skipped, mut unmapped) = (0usize, 0usize, 0usize);
    let mut mirrored = 0u64;
    for line in reader.lines() {
        let line = line?;
        match parse_line(&line) {
            Some(snp) => {
                let (row, um) = to_mirror(&snp, &targets);
                unmapped += um;
                batch.push(row);
                parsed += 1;
                if batch.len() >= BATCH {
                    mirrored += du_db::ybrowse::upsert_mirror(pool, &batch).await?;
                    batch.clear();
                }
            }
            None if line.starts_with('#') || line.trim().is_empty() => {}
            None => skipped += 1,
        }
    }
    if !batch.is_empty() {
        mirrored += du_db::ybrowse::upsert_mirror(pool, &batch).await?;
    }

    // 2. Derive the curated catalog (idempotent; preserves curator decisions).
    let rec = du_db::ybrowse::reconcile(pool).await?;

    tracing::info!(
        parsed, mirrored, skipped, unmapped_lifts = unmapped, targets = targets.len(),
        clusters = rec.clusters, created = rec.created, enriched = rec.enriched,
        flagged = rec.flagged, region_flagged = rec.region_flagged,
        "ybrowse GFF3 ingest + reconcile complete"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const LINE: &str = "chrY\tpoint\tsnp\t8936213\t8936213\t.\t+\t.\tID=BY32772;Name=BY32772;allele_anc=T;allele_der=C;primer_f=BY32772_F AGTGCTTCATGGGGGAATG;ycc_haplogroup=R1b;isogg_haplogroup=R1b;mutation=T to C;count_tested=3;count_derived=0;ref=Rik Harper (2018);comment=.;yfull_node=R-Y131908";

    #[test]
    fn parses_a_snp_line() {
        let snp = parse_line(LINE).unwrap();
        assert_eq!(snp.name, "BY32772");
        assert_eq!((snp.contig.as_str(), snp.pos), ("chrY", 8_936_213));
        assert_eq!(snp.anc.as_deref(), Some("T"));
        assert_eq!(snp.der.as_deref(), Some("C"));
        // comment=. is a placeholder → dropped; informative attrs kept.
        assert!(snp.attrs.iter().any(|(k, v)| k == "isogg_haplogroup" && v == "R1b"));
        assert!(snp.attrs.iter().any(|(k, v)| k == "yfull_node" && v == "R-Y131908"));
        assert!(!snp.attrs.iter().any(|(k, _)| k == "comment"));
    }

    #[test]
    fn skips_comments_and_nameless() {
        assert!(parse_line("## gff-version 3").is_none());
        assert!(parse_line("").is_none());
        assert!(parse_line("chrY\tpoint\tsnp\t1\t1\t.\t+\t.\tID=;foo=bar").is_none());
    }

    #[test]
    fn builds_mirror_row_and_coords() {
        let snp = parse_line(LINE).unwrap();
        let (row, unmapped) = to_mirror(&snp, &[]);
        assert_eq!(unmapped, 0);
        assert_eq!(row.name, "BY32772");
        assert_eq!((row.contig.as_str(), row.position), ("chrY", 8_936_213));
        assert_eq!(row.allele_anc.as_deref(), Some("T"));
        assert_eq!(row.coordinates["GRCh38"]["position"], 8_936_213);
        assert_eq!(row.evidence["source"], "YBrowse");
        assert_eq!(row.evidence["isogg_haplogroup"], "R1b");
        assert_eq!(row.evidence["count_tested"], 3);
    }

    /// End-to-end: a self-written synthetic GFF3 (isolated `TESTYB-` SNPs) →
    /// mirror → reconcile → curated `core.variant`. Runs against a private,
    /// throwaway database (reconcile processes the WHOLE mirror, so it must not
    /// touch the shared dev catalog). Skips when DATABASE_URL is unset.
    #[tokio::test]
    async fn ingest_synthetic_gff_end_to_end() {
        let Ok(url) = std::env::var("DATABASE_URL") else {
            eprintln!("DATABASE_URL unset — skipping GFF3 ingest smoke test");
            return;
        };
        if url.is_empty() {
            return;
        }
        // Real-shape lines + skip paths (comment, blank, nameless). TESTYB-1 and
        // TESTYB-2 are at distinct positions → two folded clusters.
        let gff = "## gff-version 3\n\
chrY\tpoint\tsnp\t8900001\t8900001\t.\t+\t.\tID=TESTYB-1;Name=TESTYB-1;allele_anc=T;allele_der=C;isogg_haplogroup=R1b;ref=Test (2026);count_tested=3;count_derived=0;yfull_node=R-Test;comment=.\n\
chrY\tpoint\tsnp\t8900002\t8900002\t.\t+\t.\tID=TESTYB-2;Name=TESTYB-2;allele_anc=A;allele_der=G;isogg_haplogroup=J2;ref=TBD\n\
\n\
chrY\tpoint\tsnp\t8900003\t8900003\t.\t+\t.\tID=;allele_anc=A\n";
        let path = std::env::temp_dir().join(format!("testyb_slice_{}.gff3", std::process::id()));
        std::fs::write(&path, gff).unwrap();

        // Private throwaway DB — dropped when `db` goes out of scope.
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();

        let cfg = Config { gff_path: path.to_string_lossy().into(), chain_grch37: None, chain_hs1: None };
        run(&pool, &cfg).await.expect("ingest");

        // Two folded variants in the catalog (nameless line skipped).
        let n = du_db::variant::count_by_evidence_source(&pool, "YBrowse").await.unwrap();
        assert_eq!(n, 2, "two reconciled variants (nameless skipped)");
        let found = du_db::variant::search(&pool, Some("TESTYB-1"), 1, 5).await.unwrap();
        assert!(found.items.iter().any(|v| v.canonical_name == "TESTYB-1"), "TESTYB-1 in catalog");

        let _ = std::fs::remove_file(&path);
    }
}
