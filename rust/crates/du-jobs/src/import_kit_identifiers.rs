//! Backfill vendor kit identifiers from the cohort manifest into
//! `core.biosample_identifier` — Phase 2 of the identifier-dedup design
//! (`proposals/biosample-identifier-dedup.md`).
//!
//! The manifest (`COHORT_MANIFEST`, the same TSV `ftdna-str` uses) is the authoritative
//! cross-reference: `subject_id` (a UUID) **is** the de-novo tip's `core.biosample.accession`,
//! and the comma-aligned `lab`/`kit` lists are the donor's vendor identifiers. We attach one
//! `core.biosample_identifier(namespace=lab, value=kit, is_public=false)` per kit, **without
//! touching the UUID accession** — the tip stays anonymized; we only add the background dedup key.
//!
//! Namespace: 99.4% of donors are single-lab → namespace = that lab, unambiguous and
//! collision-safe (YSEQ:229 != DANTE:229). The few multi-lab donors have mis-aligned lists
//! (the `lab` list is alphabetically sorted), so their kits are namespaced by a best-effort
//! format inference with a `VENDORKIT` fallback, and counted separately for transparency.
//!
//! MDKA columns (`surname/origin/birth_yr`) are deliberately NOT touched — genealogical context
//! enters only when a PDS publishes the sample, never from this bulk load.

use anyhow::{Context, Result};
use du_db::dedup::{self, IdentifierPair};
use du_db::identifier::{self, NewIdentifier};
use du_db::PgPool;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::PathBuf;
use uuid::Uuid;

const SOURCE: &str = "cohort-manifest";

pub struct Config {
    pub manifest: PathBuf,
    pub apply: bool,
}

impl Config {
    pub fn from_env(args: &[String]) -> Option<Self> {
        let manifest = std::env::var("COHORT_MANIFEST").ok().filter(|s| !s.is_empty())?;
        Some(Config { manifest: PathBuf::from(manifest), apply: args.iter().any(|a| a == "--apply") })
    }
}

#[derive(Default)]
pub struct Report {
    pub rows_total: u64,
    pub rows_matched: u64,       // subject_id resolved to a biosample accession
    pub rows_unmatched: u64,
    pub multi_lab_rows: u64,
    pub staged: u64,             // identifiers to insert (pre-dedup)
    pub inserted: u64,           // rows actually written (ON CONFLICT skips existing)
    pub fallback_ns: u64,        // kits that fell back to the VENDORKIT namespace
    pub per_namespace: BTreeMap<String, u64>,
    // Duplicate-biosample detection: an identifier on >1 sample, adjudicated by Y placement.
    pub dup_pairs: u64,          // (namespace,value) collisions expanded to sample pairs
    pub confirmed: u64,          // concordant Y placement (SAME_TERMINAL/PARENT_CHILD/SIBLING)
    pub disputed: u64,           // divergent placement (kit matches, DNA disagrees)
    pub unplaced: u64,           // one/both samples have no Y placement to check
    pub candidates_written: u64, // dedup.duplicate_candidate rows written/refreshed (apply only)
}

/// Normalize a lab token to a namespace (trim, upper). Empty → None.
fn lab_namespace(lab: &str) -> Option<String> {
    let s = lab.trim().to_ascii_uppercase();
    (!s.is_empty()).then_some(s)
}

/// Best-effort vendor from a kit-id shape, for the rare multi-lab donor whose lab/kit
/// lists don't positionally align. Confident cases only; ambiguous numerics → None.
fn infer_vendor(kit: &str) -> Option<&'static str> {
    let k = kit.trim().to_ascii_uppercase();
    if k.starts_with("GFX") || k.starts_with("GF") {
        return Some("FGC");
    }
    // FTDNA lettered kit prefixes (B/N/IN/MK/MI/E/H/BP/M/J…) — a leading letter + digits.
    if k.chars().next().is_some_and(|c| c.is_ascii_alphabetic()) && k.chars().any(|c| c.is_ascii_digit()) {
        return Some("FTDNA");
    }
    // Dante kits are long numeric strings.
    if k.chars().all(|c| c.is_ascii_digit()) && k.len() >= 12 {
        return Some("DANTE");
    }
    None
}

fn normalize_kit(kit: &str) -> Option<String> {
    let s = kit.trim().to_ascii_uppercase();
    (!s.is_empty()).then_some(s)
}

pub async fn run(pool: &PgPool, cfg: &Config) -> Result<Report> {
    let text = std::fs::read_to_string(&cfg.manifest)
        .with_context(|| format!("read manifest {}", cfg.manifest.display()))?;
    let mut lines = text.lines();
    let header = lines.next().context("empty manifest")?;
    let cols: Vec<&str> = header.split('\t').collect();
    let col = |name: &str| cols.iter().position(|c| *c == name).with_context(|| format!("no {name} column"));
    let (subj_i, lab_i, kit_i) = (col("subject_id")?, col("lab")?, col("kit")?);

    let guid_by_accession = identifier::accession_to_guid(pool).await?;
    let mut rep = Report::default();
    let mut staged: Vec<NewIdentifier> = Vec::new();

    for line in lines {
        let f: Vec<&str> = line.split('\t').collect();
        let Some(subj) = f.get(subj_i).map(|s| s.trim()).filter(|s| !s.is_empty()) else { continue };
        rep.rows_total += 1;

        let Some(&guid) = guid_by_accession.get(subj) else {
            rep.rows_unmatched += 1;
            continue;
        };
        rep.rows_matched += 1;

        let labs: Vec<String> = f.get(lab_i).unwrap_or(&"").split(',').filter_map(lab_namespace).collect();
        let kits: Vec<String> = f.get(kit_i).unwrap_or(&"").split(',').filter_map(normalize_kit).collect();
        let single_lab = labs.len() == 1;
        if labs.len() > 1 {
            rep.multi_lab_rows += 1;
        }

        for kit in &kits {
            let ns = if single_lab {
                labs[0].clone()
            } else {
                // Multi-lab (mis-aligned lists): infer by format, else fall back.
                match infer_vendor(kit) {
                    Some(v) if labs.iter().any(|l| l == v) => v.to_string(),
                    _ => {
                        rep.fallback_ns += 1;
                        "VENDORKIT".to_string()
                    }
                }
            };
            *rep.per_namespace.entry(ns.clone()).or_default() += 1;
            let is_public = identifier::is_public_namespace(&ns); // vendor kits → false
            staged.push(NewIdentifier {
                sample_guid: guid,
                namespace: ns,
                value: kit.clone(),
                is_public,
                source: SOURCE.to_string(),
            });
        }
    }
    rep.staged = staged.len() as u64;

    if cfg.apply {
        rep.inserted = identifier::insert_identifiers(pool, &staged).await?;
    }

    // Duplicate-biosample detection: any (namespace, value) attached to more than one
    // distinct sample means one donor was loaded twice. Expand each collision to canonical
    // sample pairs (sample_a < sample_b), adjudicate by terminal-Y concordance, and (on
    // apply) upsert them as IDENTIFIER-tier candidates for curator review.
    let mut by_id: HashMap<(&str, &str), HashSet<Uuid>> = HashMap::new();
    for s in &staged {
        by_id.entry((&s.namespace, &s.value)).or_default().insert(s.sample_guid);
    }
    let mut pairs: Vec<IdentifierPair> = Vec::new();
    for ((ns, val), guids) in &by_id {
        if guids.len() < 2 {
            continue;
        }
        let mut g: Vec<Uuid> = guids.iter().copied().collect();
        g.sort(); // canonical ordering → sample_a < sample_b
        for i in 0..g.len() {
            for j in (i + 1)..g.len() {
                pairs.push(IdentifierPair {
                    sample_a: g[i],
                    sample_b: g[j],
                    namespace: (*ns).to_string(),
                    value: (*val).to_string(),
                });
            }
        }
    }
    rep.dup_pairs = pairs.len() as u64;

    let classified = dedup::classify_identifier_pairs(pool, &pairs).await?;
    for c in &classified {
        match c.disposition() {
            "CONFIRMED" => rep.confirmed += 1,
            "DISPUTED" => rep.disputed += 1,
            _ => rep.unplaced += 1,
        }
    }
    if cfg.apply {
        rep.candidates_written = dedup::write_identifier_candidates(pool, &classified).await?;
    }
    Ok(rep)
}
