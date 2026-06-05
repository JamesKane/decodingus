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
    /// Skip migrating the legacy phylogenetic tree (haplogroup / relationship /
    /// variant-link). Use when the tree is built separately by `decodingus-tree-init`
    /// (ISOGG-founded + SNP-graft) into the empty tree namespace — biosamples carry
    /// their haplogroup names as JSON and resolve against that tree at read time.
    /// The 3 tree reconcile checks are skipped too. Run ETL first, then tree-init.
    #[arg(long)]
    skip_tree: bool,
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
        reconcile::run(&legacy, &target, args.skip_tree).await?;
        return Ok(());
    }

    // Ensure the redesigned schema exists on the target (idempotent).
    tracing::info!("applying target migrations");
    du_db::run_migrations(&target).await?;

    tracing::info!("ETL starting");
    // ident first: users before any FK, roles/permissions before their links.
    transform::users(&legacy, &target).await?;
    transform::roles(&legacy, &target).await?;
    transform::permissions(&legacy, &target).await?;
    transform::role_permissions(&legacy, &target).await?;
    transform::user_roles(&legacy, &target).await?;
    transform::user_login_info(&legacy, &target).await?;
    transform::user_oauth2_info(&legacy, &target).await?;
    transform::user_pds_info(&legacy, &target).await?;
    transform::cookie_consents(&legacy, &target).await?;
    transform::atproto_metadata(&legacy, &target).await?;
    transform::audit_log(&legacy, &target).await?;

    // Dependency order: donors before biosamples; variants/haplogroups before
    // their join tables; publications/studies before their links.
    transform::specimen_donor(&legacy, &target).await?;
    transform::biosample(&legacy, &target).await?;
    transform::variant(&legacy, &target).await?;
    // The phylogenetic tree is built separately by decodingus-tree-init (ISOGG
    // foundation + SNP-graft); --skip-tree leaves the namespace empty for it.
    if args.skip_tree {
        tracing::info!("--skip-tree: skipping legacy haplogroup tree (build via decodingus-tree-init)");
    } else {
        transform::haplogroup(&legacy, &target).await?;
        transform::haplogroup_relationship(&legacy, &target).await?;
        transform::haplogroup_variant(&legacy, &target).await?;
    }
    transform::genomic_study(&legacy, &target).await?;
    transform::publication(&legacy, &target).await?;
    transform::publication_biosample(&legacy, &target).await?;
    transform::publication_study(&legacy, &target).await?;

    // genomics: reference data first, then runs/files/coverage that depend on
    // core.biosample(sample_guid) (already migrated above) + the labs/test types.
    transform::genbank_contig(&legacy, &target).await?;
    transform::sequencing_lab(&legacy, &target).await?;
    transform::sequencer_instrument(&legacy, &target).await?;
    transform::test_type_definition(&legacy, &target).await?;
    transform::pangenome_graph(&legacy, &target).await?;
    transform::pangenome_path(&legacy, &target).await?;
    transform::canonical_pangenome_variant(&legacy, &target).await?;
    transform::sequence_library(&legacy, &target).await?;
    transform::sequence_file(&legacy, &target).await?;
    transform::alignment_metadata(&legacy, &target).await?;
    transform::pangenome_alignment_metadata(&legacy, &target).await?;
    transform::reported_variant_pangenome(&legacy, &target).await?;
    transform::genotype_data(&legacy, &target).await?;

    transform::fix_sequences(&target).await?;
    tracing::info!("ETL complete; reconciling");
    reconcile::run(&legacy, &target, args.skip_tree).await?;
    Ok(())
}
