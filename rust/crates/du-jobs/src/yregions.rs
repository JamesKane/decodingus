//! Y-chromosome reference-region ingest. Loads the T2T-CHM13v2.0 (`hs1`) Y
//! structural annotations — AZF intervals, DYZ heterochromatic satellites,
//! ampliconic colour-blocks, palindromes/inverted repeats, and the authoritative
//! chrXY **sequence-class partition** — from the HPRC annotation bucket into
//! `core.genome_region`.
//!
//! Two layers, distinct purposes:
//! - **Fine-grained flag annotations** (`palindromic`/`ampliconic`/`inverted_repeat`/
//!   `heterochromatin`/`azf`): which specific feature a variant sits in, drives
//!   `core.variant.annotations.region_overlaps` (low-confidence-for-placement).
//! - **`sequence_class` partition** (X-DEG/XTR/AMPL/HET/PAR/CEN/…): coarse,
//!   mutually-exclusive, exhaustive bp accounting. `X-DEG` is the single-copy
//!   reliable sequence the SNP-counting age denominator (and Navigator's callable
//!   intersection) are built on. Kept out of the flag types so it never
//!   double-flags variants already covered by the fine-grained layer.
//!
//! These are reference-frame regions (one CHM13v2.0 interval each). The source
//! BEDs are tiny and versioned, so this runs on demand
//! (`decodingus-jobs run-once yregions`), not on an interval.
//!
//! Re-runs are idempotent: each row is upserted on `(region_type, name)`. BED
//! `name`s repeat (the two arms of palindrome P8; inverted-repeat IR3 at two
//! loci), so the stored name carries the locus to keep each segment distinct.
//! `region_type` is assigned per-file, because a label such as `P5-AZFb` is
//! ampliconic in one file and palindromic in another.

use du_db::PgPool;
use serde_json::{json, Value};

/// Reference build these annotations live in (CHM13v2.0 == `hs1`).
const BUILD: &str = "hs1";
const SOURCE: &str = "T2T-CHM13v2.0 chrY structural annotations (HPRC)";
/// HPRC annotation bucket; overridable via `YREGIONS_BASE` (mirror / local server).
const DEFAULT_BASE: &str =
    "https://s3-us-west-2.amazonaws.com/human-pangenomics/T2T/CHM13/assemblies/annotation";

fn base() -> String {
    std::env::var("YREGIONS_BASE").ok().filter(|s| !s.is_empty()).unwrap_or_else(|| DEFAULT_BASE.into())
}

/// A source BED plus how to assign a `region_type` to each of its rows.
struct Source {
    file: &'static str,
    classify: fn(&str) -> Classification,
}

/// How a BED row maps into `core.genome_region`: the `region_type`, plus an
/// optional normalized `class` recorded in `properties.class` (only the
/// sequence-class partition carries one — it's the coarse, mutually-exclusive
/// bp-accounting layer the age denominator / Navigator intersect against).
struct Classification {
    region_type: &'static str,
    class: Option<&'static str>,
}

const SOURCES: &[Source] = &[
    Source { file: "chm13v2.0Y_AZF_DYZ_v1.bed", classify: classify_azf_dyz },
    Source { file: "chm13v2.0Y_amplicons_v1.bed", classify: |_| Classification { region_type: "ampliconic", class: None } },
    Source { file: "chm13v2.0Y_inverted_repeats_v1.bed", classify: classify_inverted },
    // The authoritative chrXY sequence-class partition (X-DEG/XTR/AMPL/HET/PAR/…).
    // Loaded as a separate `sequence_class` layer for bp accounting; deliberately
    // NOT one of the fine-grained flag types, so it never double-flags variants.
    Source { file: "chm13v2.0_chrXY_sequence_class_v1.bed", classify: classify_sequence_class },
];

/// AZFa/b/c are AZF intervals; DYZ* are heterochromatic satellite arrays.
fn classify_azf_dyz(label: &str) -> Classification {
    let region_type = if label.starts_with("DYZ") { "heterochromatin" } else { "azf" };
    Classification { region_type, class: None }
}

/// IR1, IR3, IR1-g2… are inverted repeats; P1, P3-AZFc, P8… are palindromes.
fn classify_inverted(label: &str) -> Classification {
    let region_type = if label.starts_with("IR") { "inverted_repeat" } else { "palindromic" };
    Classification { region_type, class: None }
}

/// The chrXY sequence-class partition: every row is `region_type = "sequence_class"`,
/// with the raw class folded to a normalized `properties.class`. `X-DEG` is the
/// single-copy X-degenerate sequence — the reliable basis for SNP-counting ages.
fn classify_sequence_class(label: &str) -> Classification {
    let class = match label {
        "X-DEG" => "xdegen",
        "XTR" | "XTR1" | "XTR2" => "x_transposed",
        "AMPL" => "ampliconic",
        "PAR1" | "PAR2" => "par",
        "SAT" | "HET" | "DYZ17" | "DYZ19" => "heterochromatin",
        "CEN" => "centromere",
        _ => "other",
    };
    Classification { region_type: "sequence_class", class: Some(class) }
}

/// One region row ready to upsert into `core.genome_region`.
#[derive(Debug, PartialEq)]
struct Region {
    region_type: &'static str,
    name: String,
    coordinates: Value,
    properties: Value,
}

/// Parse a BED into region rows, assigning `region_type` (and an optional
/// normalized `class`) via `classify`. Non-chrY rows are skipped — the
/// sequence-class partition also carries chrX PAR/XTR, which this Y-focused
/// table doesn't model.
///
/// BED is 0-based half-open; we store 1-based inclusive (`[start+1, end]`) so a
/// variant at 1-based `position` is "inside" when `start <= position <= end` —
/// matching how Y-SNP positions are stored elsewhere.
fn parse_bed(text: &str, file: &str, classify: fn(&str) -> Classification) -> Vec<Region> {
    let mut out = Vec::new();
    for line in text.lines() {
        let line = line.trim_end();
        if line.is_empty()
            || line.starts_with('#')
            || line.starts_with("track")
            || line.starts_with("browser")
        {
            continue;
        }
        let cols: Vec<&str> = line.split('\t').collect();
        if cols.len() < 4 {
            continue;
        }
        let contig = cols[0];
        if contig != "chrY" {
            continue;
        }
        let (Ok(start0), Ok(end)) = (cols[1].parse::<i64>(), cols[2].parse::<i64>()) else {
            continue;
        };
        let label = cols[3].trim();
        if label.is_empty() {
            continue;
        }
        let start = start0 + 1; // BED 0-based half-open -> 1-based inclusive.
        let strand = cols.get(5).copied().unwrap_or(".");
        let item_rgb = cols.get(8).copied().unwrap_or("");
        let classification = classify(label);

        let mut props = serde_json::Map::new();
        props.insert("source".into(), json!(SOURCE));
        props.insert("source_file".into(), json!(file));
        props.insert("label".into(), json!(label));
        props.insert("length_bp".into(), json!(end - start + 1));
        if let Some(class) = classification.class {
            props.insert("class".into(), json!(class));
        }
        if strand == "+" || strand == "-" {
            props.insert("strand".into(), json!(strand));
        }
        // The amplicon colour-block hue (blue/green/red/…) is meaningful; the
        // black default (0,0,0) on repeats is not.
        if !item_rgb.is_empty() && item_rgb != "0,0,0" {
            props.insert("item_rgb".into(), json!(item_rgb));
        }

        out.push(Region {
            region_type: classification.region_type,
            // Locus-qualified so repeated labels stay unique per (region_type, name).
            name: format!("{label} ({contig}:{start}-{end})"),
            coordinates: json!({ BUILD: { "contig": contig, "start": start, "end": end } }),
            properties: Value::Object(props),
        });
    }
    out
}

async fn fetch(url: &str) -> anyhow::Result<String> {
    Ok(reqwest::get(url).await?.error_for_status()?.text().await?)
}

/// Fetch, parse, and upsert every Y annotation BED in [`SOURCES`].
pub async fn run(pool: &PgPool) -> anyhow::Result<()> {
    let base = base();
    let (mut total, mut inserted, mut updated) = (0usize, 0usize, 0usize);
    for src in SOURCES {
        let url = format!("{base}/{}", src.file);
        let text = fetch(&url).await?;
        let rows = parse_bed(&text, src.file, src.classify);
        tracing::info!(file = src.file, rows = rows.len(), "parsed Y-region BED");
        for r in &rows {
            let new = du_db::genome_region::upsert_by_key(
                pool,
                r.region_type,
                &r.name,
                &r.coordinates,
                &r.properties,
            )
            .await?;
            if new {
                inserted += 1;
            } else {
                updated += 1;
            }
            total += 1;
        }
    }
    // Regions changed → recompute variant placement flags so the new geometry
    // takes effect immediately (idempotent; the reconcile job also calls this).
    let region_flagged = du_db::variant::refresh_region_overlaps(pool).await?;

    tracing::info!(total, inserted, updated, region_flagged, build = BUILD, "y-region ingest complete");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    // Real lines from chm13v2.0Y_AZF_DYZ_v1.bed and chm13v2.0Y_inverted_repeats_v1.bed.
    const AZF_DYZ: &str = "chrY\t13228406\t14019819\tAZFa\t100\t.\t13228406\t14019819\t102,0,153\t791,413\n\
chrY\t27811283\t28660072\tDYZ1\t100\t.\t27811283\t28660072\t0,0,0\t848,789\n";
    const INVERTED: &str = "chrY\t5877201\t6200447\tIR3\t100\t-\t5877201\t6200447\t0,0,0\t323,246\n\
chrY\t9932469\t10255715\tIR3\t100\t+\t9932469\t10255715\t0,0,0\t323,246\n\
chrY\t14891107\t14926304\tP8\t100\t-\t14891107\t14926304\t153,153,153\t35,197\n";
    // Real lines from chm13v2.0_chrXY_sequence_class_v1.bed (incl. 2 chrX rows to skip).
    const SEQCLASS: &str = "chrX\t0\t2394410\tPAR1\t100\t.\t0\t2394410\t151,203,153\n\
chrX\t155701382\t156030895\tPAR2\t100\t.\t155701382\t156030895\t151,203,153\n\
chrY\t2458320\t2727072\tX-DEG\t100\t.\t2458320\t2727072\t255,239,87\n\
chrY\t2727072\t5914561\tXTR1\t100\t.\t2727072\t5914561\t238,169,186\n\
chrY\t7234999\t10389946\tAMPL\t100\t.\t7234999\t10389946\t136,192,234\n\
chrY\t27449931\t62072743\tHET\t100\t.\t27449931\t62072743\t119,119,119\n";

    #[test]
    fn classifies_azf_vs_heterochromatin() {
        let r = parse_bed(AZF_DYZ, "f", classify_azf_dyz);
        assert_eq!(r[0].region_type, "azf"); // AZFa
        assert_eq!(r[1].region_type, "heterochromatin"); // DYZ1
    }

    #[test]
    fn classifies_inverted_repeat_vs_palindrome() {
        let r = parse_bed(INVERTED, "f", classify_inverted);
        assert_eq!(r[0].region_type, "inverted_repeat"); // IR3
        assert_eq!(r[2].region_type, "palindromic"); // P8
    }

    #[test]
    fn sequence_class_partition_loads_chry_only_with_normalized_class() {
        let r = parse_bed(SEQCLASS, "seqclass", classify_sequence_class);
        // chrX PAR rows are skipped → only the 4 chrY rows remain.
        assert_eq!(r.len(), 4);
        assert!(r.iter().all(|x| x.region_type == "sequence_class"));
        assert!(r.iter().all(|x| x.coordinates["hs1"]["contig"] == "chrY"));
        // Raw class folds to a normalized properties.class.
        let class_of = |label_prefix: &str| {
            r.iter()
                .find(|x| x.properties["label"].as_str().unwrap().starts_with(label_prefix))
                .map(|x| x.properties["class"].as_str().unwrap())
        };
        assert_eq!(class_of("X-DEG"), Some("xdegen")); // the reliable denominator
        assert_eq!(class_of("XTR1"), Some("x_transposed"));
        assert_eq!(class_of("AMPL"), Some("ampliconic"));
        assert_eq!(class_of("HET"), Some("heterochromatin"));
    }

    #[test]
    fn converts_bed_to_one_based_inclusive() {
        let r = parse_bed(AZF_DYZ, "f", classify_azf_dyz);
        // BED 13228406..14019819 (0-based half-open) -> 13228407..14019819 (1-based incl).
        assert_eq!(r[0].coordinates["hs1"]["contig"], "chrY");
        assert_eq!(r[0].coordinates["hs1"]["start"], 13_228_407);
        assert_eq!(r[0].coordinates["hs1"]["end"], 14_019_819);
        assert_eq!(r[0].properties["length_bp"], 14_019_819 - 13_228_407 + 1);
    }

    #[test]
    fn locus_qualified_names_keep_repeated_labels_distinct() {
        let r = parse_bed(INVERTED, "f", classify_inverted);
        // IR3 appears twice; the locus suffix makes the keys unique.
        assert_eq!(r[0].name, "IR3 (chrY:5877202-6200447)");
        assert_eq!(r[1].name, "IR3 (chrY:9932470-10255715)");
        assert_ne!(r[0].name, r[1].name);
    }

    #[test]
    fn keeps_meaningful_colour_drops_black_default() {
        let r = parse_bed(INVERTED, "f", classify_inverted);
        assert!(r[0].properties.get("item_rgb").is_none()); // 0,0,0 dropped
        assert_eq!(r[2].properties["item_rgb"], "153,153,153"); // P8 grey kept
        assert_eq!(r[0].properties["strand"], "-");
        assert_eq!(r[0].properties["source"], SOURCE);
    }

    #[test]
    fn skips_headers_and_short_lines() {
        let text = "# comment\ntrack name=foo\nchrY\t1\t2\n\nchrY\t10\t20\tFOO\t0\t+\n";
        let r = parse_bed(text, "f", classify_inverted);
        assert_eq!(r.len(), 1);
        assert_eq!(r[0].properties["label"], "FOO");
    }

    /// End-to-end DB path (no network): parse real BED lines, upsert into a
    /// private throwaway database, and re-run to prove idempotency — the second
    /// pass updates rather than inserts and the row count is stable. Skips when
    /// DATABASE_URL is unset.
    #[tokio::test]
    async fn upsert_is_idempotent() {
        let Ok(url) = std::env::var("DATABASE_URL") else {
            eprintln!("DATABASE_URL unset — skipping y-region upsert test");
            return;
        };
        if url.is_empty() {
            return;
        }
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();

        let rows = parse_bed(AZF_DYZ, "chm13v2.0Y_AZF_DYZ_v1.bed", classify_azf_dyz);
        let upsert = |rows: &[Region]| {
            let pool = pool.clone();
            let rows: Vec<_> = rows.iter().map(|r| (r.region_type, r.name.clone(), r.coordinates.clone(), r.properties.clone())).collect();
            async move {
                let mut inserted = 0usize;
                for (rt, name, coords, props) in &rows {
                    if du_db::genome_region::upsert_by_key(&pool, rt, name, coords, props).await.unwrap() {
                        inserted += 1;
                    }
                }
                inserted
            }
        };

        assert_eq!(upsert(&rows).await, 2, "first pass inserts both rows");
        assert_eq!(upsert(&rows).await, 0, "second pass updates, inserts nothing");

        let loaded = du_db::genome_region::for_build(&pool, BUILD).await.unwrap();
        assert_eq!(loaded.len(), 2, "still two rows after re-run");
        assert!(loaded.iter().any(|r| r.region_type == "azf" && r.name.starts_with("AZFa")));
        assert!(loaded.iter().any(|r| r.region_type == "heterochromatin" && r.name.starts_with("DYZ1")));
    }
}
