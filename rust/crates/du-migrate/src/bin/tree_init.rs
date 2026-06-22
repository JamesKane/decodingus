//! `decodingus-tree-init` — load the de-novo Y / mt haplogroup tree.
//!
//! Greenfield loader for the normalized ingest JSON produced by the ytree
//! pipeline's `68_export_ingest.py` (IQ-TREE ML + ancestral-state reconstruction
//! → topology + per-branch defining SNPs + sample leaves + conflicts). Each
//! lineage is loaded independently and the two coexist: `--denovo-y` clears and
//! reloads only the Y tree, `--denovo-mt` only the mt tree. Defining SNPs are
//! matched to `core.variant` by hs1 coordinate (catalog names reused, novel sites
//! minted). Mutates the tree, so `--apply` is required.
//!
//!   DATABASE_URL=… decodingus-tree-init --denovo-y results/chrY.ingest.json --apply
//!   DATABASE_URL=… decodingus-tree-init --denovo-mt results/chrM.ingest.json --apply

use clap::Parser;
use du_db::PgPool;
use du_domain::enums::DnaType;

#[derive(Parser, Debug)]
#[command(name = "decodingus-tree-init", about = "Load the de-novo Y / mt haplogroup tree from the ytree ingest JSON")]
struct Args {
    /// Target DB (else $DATABASE_URL).
    #[arg(long)]
    database_url: Option<String>,
    /// Load the de-novo Y tree (`chrY.ingest.json`): clears the Y lineage and
    /// inserts nodes + edges + defining-variant links + sample leaves + conflicts.
    /// Greenfield; leaves the mt tree intact. Requires `--apply`.
    #[arg(long)]
    denovo_y: Option<String>,
    /// Load the de-novo mt tree (`chrM.ingest.json`): clears the mt lineage and
    /// loads it. Greenfield; leaves the Y tree intact. Requires `--apply`.
    #[arg(long)]
    denovo_mt: Option<String>,
    /// Apply the load (the de-novo path mutates the tree; required).
    #[arg(long)]
    apply: bool,
}

/// Clear one lineage and load its de-novo ingest JSON. `expect` is the document's
/// declared `haplogroup_type` (`Y_DNA`/`MT_DNA`) — a mismatch aborts before any write.
async fn load_denovo(pool: &PgPool, path: &str, dna: DnaType, expect: &str, apply: bool) -> anyhow::Result<()> {
    anyhow::ensure!(apply, "--denovo-* mutates the tree; pass --apply");
    let doc: du_db::denovo::DenovoTree = serde_json::from_str(&std::fs::read_to_string(path)?)?;
    anyhow::ensure!(doc.haplogroup_type == expect, "expected a {expect} document, got {}", doc.haplogroup_type);
    tracing::info!(%path, hgtype = expect, nodes = doc.nodes.len(), tips = doc.tips.len(), root = %doc.root, "de-novo: loading foundation");
    let cleared = du_db::haplogroup::clear_dna(pool, dna).await?;
    tracing::info!(cleared_haplogroups = cleared, hgtype = expect, "de-novo: cleared lineage");
    let rep = du_db::denovo::load(pool, &doc).await?;
    tracing::info!(
        hgtype = expect, nodes = rep.nodes, edges = rep.edges, variant_links = rep.variant_links,
        variants_reused = rep.variants_reused, variants_created = rep.variants_created,
        unresolved_block = rep.unresolved_block, tips_placed = rep.tips_placed,
        biosamples_created = rep.biosamples_created, conflicts = rep.conflicts_loaded,
        private_collapsed = rep.private_collapsed, private_seeded = rep.private_seeded,
        "de-novo: loaded"
    );
    Ok(())
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info,tree_init=debug".into()),
        )
        .init();
    let args = Args::parse();
    let url = args
        .database_url
        .clone()
        .or_else(|| std::env::var("DATABASE_URL").ok())
        .ok_or_else(|| anyhow::anyhow!("set --database-url or DATABASE_URL"))?;
    let pool = du_db::connect(&url, 4).await?;
    du_db::run_migrations(&pool).await?;

    match (&args.denovo_y, &args.denovo_mt) {
        (Some(path), _) => load_denovo(&pool, path, DnaType::YDna, "Y_DNA", args.apply).await?,
        (None, Some(path)) => load_denovo(&pool, path, DnaType::MtDna, "MT_DNA", args.apply).await?,
        (None, None) => anyhow::bail!("pass --denovo-y <chrY.ingest.json> or --denovo-mt <chrM.ingest.json>"),
    }
    Ok(())
}
