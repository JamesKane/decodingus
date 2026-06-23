//! decodingus-aging-preload — seeds the per-sample callable-bp denominator for
//! the SNP-Poisson branch-age model.
//!
//! The tree producer already extracts each sample's terminal private branch
//! (seeded into tree.biosample_private_variant by the de-novo loader), so the
//! only missing age-model input is the per-sample Y-callable bp. This reads the
//! academic cohorts' GATK CallableLoci BEDs, partitions callable bp against the
//! Y sequence classes in core.genome_region, and writes
//! genomics.biosample_callable_loci, matched to the already-loaded samples by
//! accession (= the per-sample directory name).

mod callable;
mod db;

use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use clap::Parser;
use sqlx::postgres::PgPoolOptions;

#[derive(Parser, Debug)]
#[command(name = "decodingus-aging-preload")]
struct Cli {
    /// Cohort directory of per-sample subdirs (e.g. /Volumes/nas/Genomics/PRJEB31736).
    #[arg(long)]
    cohort_dir: PathBuf,
    /// Contig in the callable BED (hs1/CHM13 frame).
    #[arg(long, default_value = "chrY")]
    contig: String,
    /// Process at most N samples (0 = all).
    #[arg(long, default_value_t = 0)]
    limit: usize,
    /// Commit writes (otherwise a connected run previews counts and rolls back).
    #[arg(long)]
    apply: bool,
}

/// A cohort sample's callable BED + its accession (= directory name).
struct SampleBed {
    accession: String,
    bed: PathBuf,
}

/// Locate a sample's chrY callable BED within its subdir.
fn discover(dir: &Path) -> Option<SampleBed> {
    let accession = dir.file_name()?.to_str()?.to_string();
    let mut bed = None;
    for entry in fs::read_dir(dir).ok()?.flatten() {
        let p = entry.path();
        let Some(fname) = p.file_name().and_then(|s| s.to_str()) else { continue };
        if fname.ends_with("callable.bed") && (bed.is_none() || fname.contains("chrYM")) {
            bed = Some(p);
        }
    }
    bed.map(|bed| SampleBed { accession, bed })
}

fn cohort_samples(cohort_dir: &Path) -> Result<Vec<SampleBed>> {
    let mut out = Vec::new();
    for entry in fs::read_dir(cohort_dir).with_context(|| format!("read {}", cohort_dir.display()))? {
        let p = entry?.path();
        if p.is_dir() {
            if let Some(s) = discover(&p) {
                out.push(s);
            }
        }
    }
    out.sort_by(|a, b| a.accession.cmp(&b.accession));
    Ok(out)
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .init();
    let cli = Cli::parse();

    let mut samples = cohort_samples(&cli.cohort_dir)?;
    if cli.limit > 0 {
        samples.truncate(cli.limit);
    }

    let url = std::env::var("DATABASE_URL").context("DATABASE_URL not set")?;
    let pool = PgPoolOptions::new().max_connections(4).connect(&url).await?;
    let regions = db::load_y_regions(&pool).await?;
    tracing::info!(
        cohort = %cli.cohort_dir.display(),
        samples = samples.len(),
        xdegen_bp = callable::total_bp(&regions.xdegen),
        ampliconic_bp = callable::total_bp(&regions.ampliconic),
        palindromic_bp = callable::total_bp(&regions.palindromic),
        apply = cli.apply,
        "callable-loci preload"
    );

    let (mut seeded, mut unmatched, mut empty) = (0usize, 0usize, 0usize);
    for s in &samples {
        let Some(guid) = db::guid_by_accession(&pool, &s.accession).await? else {
            unmatched += 1;
            continue; // sample not loaded into this tree
        };
        let ivs = callable::callable_intervals(&s.bed, &cli.contig)?;
        if ivs.is_empty() {
            empty += 1; // female / no chrY callable
            continue;
        }
        let cbp = callable::partition(&ivs, &regions.xdegen, &regions.ampliconic, &regions.palindromic);
        let bed_hash = fs::metadata(&s.bed).map(|m| format!("len:{}", m.len())).unwrap_or_default();

        let mut tx = pool.begin().await?;
        db::seed_callable(&mut tx, guid, &cbp, ivs.len() as i64, &bed_hash).await?;
        if cli.apply {
            tx.commit().await?;
        } else {
            tx.rollback().await?;
        }
        seeded += 1;
        if seeded <= 8 || seeded % 250 == 0 {
            println!(
                "{:<16} total={:<10} x/a/p={}/{}/{}",
                s.accession, cbp.total, cbp.xdegen, cbp.ampliconic, cbp.palindromic
            );
        }
    }

    println!(
        "\n{} seeded | {} not in tree | {} no-chrY | {}",
        seeded,
        unmatched,
        empty,
        if cli.apply { "COMMITTED" } else { "preview (rolled back) — pass --apply to write" }
    );
    Ok(())
}
