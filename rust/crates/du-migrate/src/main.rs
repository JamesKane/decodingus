//! One-time ETL: legacy DecodingUs Postgres -> redesigned schema (plan §8).
//!
//! The production source is a self-managed Postgres on EC2; point `--legacy` at
//! it (typically `...?sslmode=require`, via an opened security group or an SSH
//! tunnel). `--target` is the new schema's database.
//!
//! Strategy: preserve legacy primary keys (via `OVERRIDING SYSTEM VALUE`) and
//! existing `sample_guid` UUIDs, so foreign keys carry over 1:1 with no id
//! remapping. Transformers run in dependency order, upsert idempotently
//! (re-runnable / resumable), then identity sequences are advanced and a
//! reconciliation pass compares counts.
//!
//!   decodingus-migrate --legacy <DSN> --target <DSN>            run + reconcile
//!   decodingus-migrate --legacy <DSN> --target <DSN> --verify   reconcile only

use clap::Parser;

mod reconcile;
mod transform;

#[derive(Parser, Debug)]
#[command(name = "decodingus-migrate", about = "Legacy -> redesigned schema ETL")]
struct Args {
    /// Legacy (source) DSN, e.g. postgres://user:pass@ec2-host:5432/decodingus?sslmode=require
    #[arg(long)]
    legacy: String,
    /// Target (new schema) DSN.
    #[arg(long)]
    target: String,
    /// Reconcile counts only; perform no writes.
    #[arg(long)]
    verify: bool,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,du_migrate=debug".into()),
        )
        .init();

    let args = Args::parse();
    let legacy = du_db::connect(&args.legacy, 4).await?;
    let target = du_db::connect(&args.target, 4).await?;

    if args.verify {
        reconcile::run(&legacy, &target).await?;
        return Ok(());
    }

    // Ensure the redesigned schema exists on the target (idempotent).
    tracing::info!("applying target migrations");
    du_db::run_migrations(&target).await?;

    tracing::info!("ETL starting");
    // Dependency order: donors before biosamples; variants/haplogroups before
    // their join tables; publications/studies before their links.
    transform::specimen_donor(&legacy, &target).await?;
    transform::biosample(&legacy, &target).await?;
    transform::variant(&legacy, &target).await?;
    transform::haplogroup(&legacy, &target).await?;
    transform::haplogroup_relationship(&legacy, &target).await?;
    transform::haplogroup_variant(&legacy, &target).await?;
    transform::genomic_study(&legacy, &target).await?;
    transform::publication(&legacy, &target).await?;
    transform::publication_biosample(&legacy, &target).await?;
    transform::publication_study(&legacy, &target).await?;

    transform::fix_sequences(&target).await?;
    tracing::info!("ETL complete; reconciling");
    reconcile::run(&legacy, &target).await?;
    Ok(())
}
