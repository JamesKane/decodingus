//! `decodingus-tree-init` — initialize the Y haplogroup tree.
//!
//! 1. Seed the **foundational tree from ISOGG** (`isogg_full_tree.json`, the
//!    `{haplogroupType, sourceName, sourceTree}` shape the merge engine expects).
//!    ISOGG carries the deeper upstream-of-modern-humans branches.
//! 2. Optionally **merge the decoding-us production tree into it** (fetched from
//!    `/api/v1/y-tree`), which adds the AppView's extra downstream layers.
//!
//! Each step runs the tree-merge engine (Identify-Match-Graft, subtree-scoped SNP
//! matching) into a **reviewable change set**. By default the change set is left
//! DRAFT for curator review (`/curator/change-sets/<id>`); `--apply` approves +
//! applies it directly (sensible for a clean foundational load).
//!
//!   DATABASE_URL=… decodingus-tree-init --isogg /Volumes/nas/ISOGG/isogg_full_tree.json --apply
//!   DATABASE_URL=… decodingus-tree-init --isogg … --merge-prod https://decoding-us.com/api/v1/y-tree

use clap::Parser;
use du_db::PgPool;
use du_domain::enums::DnaType;
use du_domain::merge::SourceNode;
use serde_json::Value;

#[derive(Parser, Debug)]
#[command(name = "decodingus-tree-init", about = "Seed the Y tree from ISOGG; optionally merge the decoding-us prod tree in")]
struct Args {
    /// Target DB (else $DATABASE_URL).
    #[arg(long)]
    database_url: Option<String>,
    /// Path to isogg_full_tree.json (the foundational tree).
    #[arg(long)]
    isogg: String,
    /// Optional: URL of the decoding-us production Y-tree to merge in.
    #[arg(long)]
    merge_prod: Option<String>,
    /// Approve + apply each change set (else leave DRAFT for curator review).
    #[arg(long)]
    apply: bool,
    /// Skip the merge entirely; only recompute backbone + aliases on the tree
    /// already loaded (the merge is not safely re-runnable against itself).
    #[arg(long)]
    reprocess: bool,
    /// DNA type (Y or MT). ISOGG/this tool target Y.
    #[arg(long, default_value = "Y")]
    dna: String,
    #[arg(long, default_value = "tree-init")]
    by: String,
}

fn parse_dna(s: &str) -> anyhow::Result<(DnaType, &'static str)> {
    match s.to_ascii_uppercase().as_str() {
        "Y" | "Y_DNA" => Ok((DnaType::YDna, "Y_DNA")),
        "MT" | "MT_DNA" => Ok((DnaType::MtDna, "MT_DNA")),
        other => anyhow::bail!("unknown dna type {other:?} (use Y or MT)"),
    }
}

/// ISOGG node: `{name, variants:[String], children:[…]}`.
fn parse_isogg(node: &Value) -> Option<SourceNode> {
    let name = node.get("name")?.as_str()?.to_string();
    let variants = node
        .get("variants")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
        .unwrap_or_default();
    let children = node
        .get("children")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(parse_isogg).collect())
        .unwrap_or_default();
    Some(SourceNode { name, variants, children })
}

/// decoding-us prod node: `{name, variants:[{name,…}], children:[…]}` — pull the
/// SNP name out of each variant object.
fn parse_prod(node: &Value) -> Option<SourceNode> {
    let name = node.get("name")?.as_str()?.to_string();
    let variants = node
        .get("variants")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|v| v.get("name").and_then(Value::as_str).map(str::to_string)).collect())
        .unwrap_or_default();
    let children = node
        .get("children")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(parse_prod).collect())
        .unwrap_or_default();
    Some(SourceNode { name, variants, children })
}

/// Run one merge: existing tree → plan → materialized change set, optionally applied.
async fn merge_into(
    pool: &PgPool,
    roots: &[SourceNode],
    source: &str,
    dna: DnaType,
    dna_label: &str,
    by: &str,
    apply: bool,
) -> anyhow::Result<()> {
    let existing = du_db::haplogroup::existing_tree(pool, dna).await?;
    let plan = du_domain::merge::merge(&existing, roots, source);
    tracing::info!(
        source, roots = roots.len(), existing = existing.len(),
        processed = plan.stats.processed, matched = plan.stats.matched, created = plan.stats.created,
        contracted = plan.stats.contracted, ambiguous = plan.stats.ambiguous,
        ambiguities = plan.ambiguities.len(),
        "merge plan computed"
    );
    let m = du_db::merge::materialize(pool, &plan, source, dna_label, by).await?;
    tracing::info!(change_set_id = m.change_set_id, change_count = m.change_count, "materialized change set");

    if apply {
        du_db::change_set::start_review(pool, m.change_set_id).await?;
        let approved = du_db::change_set::approve_all(pool, m.change_set_id).await?;
        let r = du_db::change_set::apply(pool, m.change_set_id, by).await?;
        tracing::info!(
            change_set_id = m.change_set_id, approved,
            created = r.created, updated = r.updated, deleted = r.deleted,
            reparented = r.reparented, variant_edits = r.variant_edits, skipped = r.skipped,
            "applied"
        );
    } else {
        tracing::info!(
            change_set_id = m.change_set_id,
            "left DRAFT — review at /curator/change-sets/{} (or re-run with --apply)",
            m.change_set_id
        );
    }
    if !plan.ambiguities.is_empty() {
        tracing::warn!(count = plan.ambiguities.len(), "ambiguities flagged — review before applying");
    }
    Ok(())
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,tree_init=debug".into()),
        )
        .init();
    let args = Args::parse();
    let url = args
        .database_url
        .or_else(|| std::env::var("DATABASE_URL").ok())
        .ok_or_else(|| anyhow::anyhow!("set --database-url or DATABASE_URL"))?;
    let (dna, dna_label) = parse_dna(&args.dna)?;
    let pool = du_db::connect(&url, 4).await?;
    du_db::run_migrations(&pool).await?;

    // 1. ISOGG foundation (the JSON is always parsed — its `aliases` feed the
    //    post-process step even in --reprocess mode).
    tracing::info!(path = %args.isogg, "loading ISOGG tree");
    let isogg: Value = serde_json::from_str(&std::fs::read_to_string(&args.isogg)?)?;
    let source_name = isogg.get("sourceName").and_then(Value::as_str).unwrap_or("ISOGG").to_string();
    let root = isogg.get("sourceTree").ok_or_else(|| anyhow::anyhow!("isogg json missing `sourceTree`"))?;

    if !args.reprocess {
        let isogg_roots = parse_isogg(root).map(|r| vec![r]).ok_or_else(|| anyhow::anyhow!("could not parse ISOGG sourceTree"))?;
        merge_into(&pool, &isogg_roots, &source_name, dna, dna_label, &args.by, args.apply).await?;

        // 2. Optional: merge the decoding-us production tree in.
        if let Some(url) = &args.merge_prod {
            tracing::info!(%url, "fetching decoding-us production tree");
            let body: Value = reqwest::Client::new().get(url).send().await?.error_for_status()?.json().await?;
            let nodes = body.as_array().cloned().unwrap_or_else(|| vec![body]);
            let prod_roots: Vec<SourceNode> = nodes.iter().filter_map(parse_prod).collect();
            anyhow::ensure!(!prod_roots.is_empty(), "no nodes parsed from prod tree");
            merge_into(&pool, &prod_roots, "decoding-us", dna, dna_label, &args.by, args.apply).await?;
        }
    }

    // 3. Post-process the materialized tree (after a fresh --apply, or on demand
    //    via --reprocess; the merge itself is not safely re-runnable).
    if args.apply || args.reprocess {
        let backbone = du_db::haplogroup::recompute_backbone(&pool, dna).await?;
        tracing::info!(backbone, "recomputed backbone (single-letter clades + ancestors)");
        let aliased = apply_aliases(&pool, root, dna).await?;
        tracing::info!(aliased, "stored haplogroup name aliases from ISOGG");
    }

    tracing::info!("tree-init done");
    Ok(())
}

/// Persist each ISOGG node's `aliases` (deprecated bracket-names) to
/// `provenance.aliases` for alternate search. Returns the number of nodes set.
async fn apply_aliases(pool: &PgPool, root: &Value, dna: DnaType) -> anyhow::Result<usize> {
    let mut pairs: Vec<(String, Vec<String>)> = Vec::new();
    collect_aliases(root, &mut pairs);
    let mut n = 0;
    for (name, aliases) in pairs {
        if du_db::haplogroup::set_aliases(pool, &name, dna, &aliases).await? {
            n += 1;
        }
    }
    Ok(n)
}

fn collect_aliases(node: &Value, out: &mut Vec<(String, Vec<String>)>) {
    if let (Some(name), Some(aliases)) =
        (node.get("name").and_then(Value::as_str), node.get("aliases").and_then(Value::as_array))
    {
        let a: Vec<String> = aliases.iter().filter_map(|v| v.as_str().map(str::to_string)).collect();
        if !a.is_empty() {
            out.push((name.to_string(), a));
        }
    }
    if let Some(children) = node.get("children").and_then(Value::as_array) {
        for c in children {
            collect_aliases(c, out);
        }
    }
}
