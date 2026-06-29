//! FTDNA Y-STR import. Bulk-loads the vendor "DYS Results" exports on the NAS
//! into `genomics.biosample_str_profile`, matched to the academic / D2C cohort.
//!
//! Layout: `FTDNA_STR_DIR/<kit>/DYS_Results.csv` (or `strs.csv`) — one kit per
//! subdirectory. The directory name is the FTDNA kit number; a few are already a
//! cohort `subject_id` (UUID). Resolution is two hops:
//!   kit  ─(cohort_manifest.tsv: `kit` column, comma-split)→  subject_id
//!   subject_id ─(= core.biosample.accession)→  sample_guid
//! The manifest's `kit` column is a comma-separated list (multi-vendor donors),
//! so every token is indexed and matched, not just single-lab rows.
//!
//! Idempotent + safe to re-run: parsing is pure ([`du_db::str_import`]) and the
//! upsert keeps each sample's most complete profile. Defaults to a preview that
//! rolls nothing back because it simply doesn't write — pass apply to commit.
//!
//!   FTDNA_STR_DIR=/Volumes/nas/Genomics/FTDNA_STR \
//!   COHORT_MANIFEST=/Volumes/nas/Genomics/d2c/cohort_manifest.tsv \
//!   decodingus-jobs run-once ftdna-str --apply

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use du_db::str_import;
use du_db::PgPool;

const IMPORTED_FROM: &str = "FTDNA";
const PANEL: &str = "FTDNA Y-DNA DYS";

pub struct Config {
    pub str_dir: PathBuf,
    pub manifest: PathBuf,
    pub apply: bool,
    pub limit: usize,
    /// Direct kit-token → biosample-accession overrides, for samples whose
    /// biosample accession isn't the cohort `subject_id` (own/de-novo genomes
    /// loaded under a friendly accession — e.g. FTDNA kit `B5163` is `WGS229`).
    /// Bypasses the manifest kit→subject_id→accession chain.
    pub aliases: HashMap<String, String>,
}

impl Config {
    /// Build from env (`FTDNA_STR_DIR`, `COHORT_MANIFEST`, optional
    /// `FTDNA_STR_ALIAS="KIT=ACCESSION,KIT=ACCESSION"`) + the trailing CLI flags
    /// (`--apply`, `--limit N`). Returns `None` if a required path is unset.
    pub fn from_env(args: &[String]) -> Option<Self> {
        let str_dir = env_path("FTDNA_STR_DIR")?;
        let manifest = env_path("COHORT_MANIFEST")?;
        let apply = args.iter().any(|a| a == "--apply");
        let limit = args
            .iter()
            .position(|a| a == "--limit")
            .and_then(|i| args.get(i + 1))
            .and_then(|n| n.parse().ok())
            .unwrap_or(0);
        let aliases = std::env::var("FTDNA_STR_ALIAS")
            .ok()
            .map(|s| {
                s.split(',')
                    .filter_map(|pair| pair.split_once('='))
                    .map(|(k, v)| (k.trim().to_string(), v.trim().to_string()))
                    .filter(|(k, v)| !k.is_empty() && !v.is_empty())
                    .collect()
            })
            .unwrap_or_default();
        Some(Config { str_dir, manifest, apply, limit, aliases })
    }
}

fn env_path(key: &str) -> Option<PathBuf> {
    std::env::var(key).ok().filter(|s| !s.is_empty()).map(PathBuf::from)
}

/// kit-token → subject_id from the cohort manifest. The `kit` column is a
/// comma-separated list aligned (loosely) to the `lab` list; every token is
/// indexed so an FTDNA kit resolves regardless of the donor's other vendors.
fn load_kit_map(manifest: &Path) -> Result<HashMap<String, String>> {
    let text = fs::read_to_string(manifest)
        .with_context(|| format!("read manifest {}", manifest.display()))?;
    let mut lines = text.lines();
    let header = lines.next().context("empty manifest")?;
    let cols: Vec<&str> = header.split('\t').collect();
    let subj_i = cols.iter().position(|c| *c == "subject_id").context("no subject_id column")?;
    let kit_i = cols.iter().position(|c| *c == "kit").context("no kit column")?;

    let mut map = HashMap::new();
    for line in lines {
        let f: Vec<&str> = line.split('\t').collect();
        let (Some(subj), Some(kits)) = (f.get(subj_i), f.get(kit_i)) else { continue };
        let subj = subj.trim();
        if subj.is_empty() {
            continue;
        }
        for kit in kits.split(',') {
            let kit = kit.trim();
            if !kit.is_empty() {
                map.insert(kit.to_string(), subj.to_string());
            }
        }
    }
    Ok(map)
}

/// Normalize a kit directory name to its kit token: strip the loader-suffixes
/// some dirs carry (`MK74582_str`, `N256757_YDNA_DYS`) and recover the kit from
/// the one dir saved under a full download path (`-Users-…-169776_YDNA_DYS…`).
fn kit_token(dir_name: &str) -> String {
    if let Some(rest) = dir_name.strip_prefix("-Users") {
        if let Some(pos) = rest.find("_YDNA_DYS") {
            if let Some(kit) = rest[..pos].rsplit('-').next() {
                return kit.to_string();
            }
        }
    }
    for suf in ["_str", "_YDNA_DYS"] {
        if let Some(base) = dir_name.strip_suffix(suf) {
            return base.to_string();
        }
    }
    dir_name.to_string()
}

fn looks_like_uuid(s: &str) -> bool {
    s.len() == 36 && s.as_bytes().iter().filter(|&&b| b == b'-').count() == 4
}

/// The first `*.csv` in a kit directory (the export name varies:
/// `DYS_Results.csv` / `strs.csv`).
fn find_csv(dir: &Path) -> Option<PathBuf> {
    fs::read_dir(dir)
        .ok()?
        .flatten()
        .map(|e| e.path())
        .find(|p| p.extension().and_then(|e| e.to_str()) == Some("csv"))
}

#[derive(Default)]
struct Report {
    dirs: usize,
    no_csv: usize,
    unresolved_kit: usize,    // dir didn't map to a subject_id
    not_in_db: usize,         // subject_id not a loaded biosample
    skipped: HashMap<&'static str, usize>, // by SkipReason
    loaded: usize,
}

pub async fn run(pool: &PgPool, cfg: &Config) -> Result<()> {
    let kit_map = load_kit_map(&cfg.manifest)?;

    // Discover kit dirs and resolve each to a subject_id (kit token, or a dir
    // that is itself a subject_id UUID).
    let mut dirs: Vec<(String, PathBuf, String)> = Vec::new(); // (subject_id, csv, dir_name)
    let mut rep = Report::default();
    let mut entries: Vec<PathBuf> = fs::read_dir(&cfg.str_dir)
        .with_context(|| format!("read {}", cfg.str_dir.display()))?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir())
        .collect();
    entries.sort();
    for dir in entries {
        rep.dirs += 1;
        let name = dir.file_name().and_then(|s| s.to_str()).unwrap_or("").to_string();
        let token = kit_token(&name);
        // Alias overrides win over the manifest (own/de-novo genomes keyed by a
        // friendly accession rather than the cohort subject_id).
        let subject_id = match cfg.aliases.get(&token).or_else(|| kit_map.get(&token)) {
            Some(s) => s.clone(),
            None if looks_like_uuid(&name) => name.clone(),
            None => {
                rep.unresolved_kit += 1;
                continue;
            }
        };
        let Some(csv) = find_csv(&dir) else {
            rep.no_csv += 1;
            continue;
        };
        dirs.push((subject_id, csv, name));
    }

    // Resolve subject_ids to biosample guids in one query.
    let accs: Vec<String> = dirs.iter().map(|(s, _, _)| s.clone()).collect();
    let guids = str_import::guids_by_accessions(pool, &accs).await?;

    let mut sample = Vec::new();
    for (subject_id, csv, name) in &dirs {
        if cfg.limit > 0 && rep.loaded >= cfg.limit {
            break;
        }
        let Some(&guid) = guids.get(subject_id) else {
            rep.not_in_db += 1;
            continue;
        };
        let text = match fs::read_to_string(csv) {
            Ok(t) => t,
            Err(e) => {
                tracing::warn!(dir = %name, error = %e, "read failed");
                *rep.skipped.entry("read-error").or_default() += 1;
                continue;
            }
        };
        let profile = match str_import::parse_wide_csv(&text) {
            Ok(p) => p,
            Err(reason) => {
                *rep.skipped.entry(reason.as_str()).or_default() += 1;
                continue;
            }
        };
        let source_file = csv.strip_prefix(&cfg.str_dir).unwrap_or(csv).to_string_lossy().into_owned();
        if cfg.apply {
            str_import::upsert_profile(pool, guid, IMPORTED_FROM, PANEL, &profile, &source_file).await?;
        }
        rep.loaded += 1;
        if sample.len() < 8 {
            sample.push((name.clone(), profile.total_markers));
        }
    }

    for (name, markers) in &sample {
        println!("{name:<20} markers={markers}");
    }
    let skipped_total: usize = rep.skipped.values().sum();
    let skipped_detail: Vec<String> = {
        let mut s: Vec<_> = rep.skipped.iter().map(|(k, v)| format!("{k}={v}")).collect();
        s.sort();
        s
    };
    println!(
        "\n{} dirs | {} loaded | {} kit-unresolved | {} not-in-db | {} no-csv | {} skipped [{}] | {}",
        rep.dirs,
        rep.loaded,
        rep.unresolved_kit,
        rep.not_in_db,
        rep.no_csv,
        skipped_total,
        skipped_detail.join(", "),
        if cfg.apply { "COMMITTED" } else { "preview (no writes) — pass --apply to load" }
    );
    if cfg.apply {
        println!("Run `decodingus-jobs run-once branch-age` to fold the STRs into branch ages.");
    }
    Ok(())
}
