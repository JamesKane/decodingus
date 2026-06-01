//! YBrowse variant-ingest job: read the GRCh38 VCF, lift each position to the
//! other tracked builds via chain files, and upsert multi-build variants into
//! `core.variant`. Reuses `du-bio` (parse + liftover) + `du-db` (upsert).
//!
//! Config is file-path based (the VCF + chain files are large external assets
//! supplied at deploy time), env-driven so the job only registers when present.

use du_bio::liftover::Liftover;
use du_bio::ybrowse::{from_grch38_vcf, LiftTarget};
use du_db::PgPool;
use du_domain::enums::ReferenceBuild;

#[derive(Clone)]
pub struct Config {
    pub vcf_path: String,
    /// GRCh38 -> GRCh37 chain file (optional).
    pub chain_grch37: Option<String>,
    /// GRCh38 -> hs1 (T2T-CHM13) chain file (optional).
    pub chain_hs1: Option<String>,
}

impl Config {
    pub fn from_env() -> Option<Config> {
        let vcf_path = std::env::var("YBROWSE_VCF").ok().filter(|s| !s.is_empty())?;
        Some(Config {
            vcf_path,
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

pub async fn run(pool: &PgPool, cfg: &Config) -> anyhow::Result<()> {
    let vcf = std::fs::read_to_string(&cfg.vcf_path)?;
    let records = du_bio::vcf::parse(vcf.as_bytes())?;

    let mut targets = Vec::new();
    targets.extend(load_target(ReferenceBuild::GRCh37, &cfg.chain_grch37)?);
    targets.extend(load_target(ReferenceBuild::Hs1, &cfg.chain_hs1)?);

    let result = from_grch38_vcf(&records, &targets);

    let mut upserted = 0usize;
    for v in &result.variants {
        du_db::variant::upsert_by_name(pool, v).await?;
        upserted += 1;
    }
    tracing::info!(
        records = records.len(),
        upserted,
        unmapped_lifts = result.unmapped_lifts,
        targets = targets.len(),
        "ybrowse ingest complete"
    );
    Ok(())
}
