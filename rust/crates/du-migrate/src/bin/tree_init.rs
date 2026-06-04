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
    /// Path to isogg_full_tree.json (the foundational tree). Optional: omit to
    /// merge only the prod tree, or to --reprocess an already-loaded tree.
    #[arg(long)]
    isogg: Option<String>,
    /// Path to the FTDNA haplotree JSON (`allNodes` map). Always SNP-anchor
    /// grafted onto the loaded catalog; `kitsCount==0` nodes are private (skipped).
    #[arg(long)]
    ftdna: Option<String>,
    /// Optional: URL of the decoding-us production Y-tree to merge in.
    #[arg(long)]
    merge_prod: Option<String>,
    /// Use the SNP-anchored graft classifier (dry-run report) for --merge-prod
    /// instead of the exact-set merge (which cannot reconcile cross-source trees).
    #[arg(long)]
    snp_graft: bool,
    /// Phase 3: also graft the truly-novel source branches (no ISOGG SNP overlap)
    /// as new nodes under their parents' anchors. Dry-run unless --apply.
    #[arg(long)]
    graft: bool,
    /// Reattach bushes blocked by a flagged/ambiguous backbone ancestor to the
    /// nearest node their defining SNP points into (for complete-topology sources
    /// like FTDNA). Off by default (conservative: block & review).
    #[arg(long)]
    reattach: bool,
    /// Phase 4: write the curator-review worklist (flagged + name-collision +
    /// graft-blocked nodes, with SNP-scatter context) to this JSON path. Read-only.
    #[arg(long)]
    export_flags: Option<String>,
    /// Phase 4: stage the review items into a DRAFT change-set (tree.wip_*) for
    /// in-app curator adjudication at /curator/reviews. Writes (creates a change-set).
    #[arg(long)]
    stage_review: bool,
    /// Backfill core.variant.coordinates from the decoding-us API (the graft
    /// stores SNP names only; the API carries multi-build locus + anc/der alleles).
    #[arg(long)]
    backfill_prod_coords: bool,
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

/// ISOGG node: `{name, variants:[{name, aliases?}], children:[…]}`. The merge
/// matches on the canonical `name` of each variant (one row per physical SNP);
/// slash-synonyms travel in `aliases` and are applied to `core.variant.aliases`
/// post-load by [`apply_variant_aliases`].
fn parse_isogg(node: &Value) -> Option<SourceNode> {
    let name = node.get("name")?.as_str()?.to_string();
    let variants = node
        .get("variants")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|v| v.get("name").and_then(Value::as_str).map(str::to_string)).collect())
        .unwrap_or_default();
    let children = node
        .get("children")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(parse_isogg).collect())
        .unwrap_or_default();
    Some(SourceNode { name, variants, children })
}

/// Build a nested `SourceNode` forest from the decoding-us **flat** tree:
/// `[{name, parentName, variants:[{name,…}], …}]` (the `/api/v1/y-tree` shape).
/// Hierarchy comes from `parentName`; roots are nodes with no/absent parent.
fn parse_prod_flat(body: &Value) -> Vec<SourceNode> {
    use std::collections::HashMap;
    let arr = match body.as_array() {
        Some(a) => a,
        None => return Vec::new(),
    };
    let mut nodes: HashMap<String, SourceNode> = HashMap::new();
    let mut parent_of: HashMap<String, Option<String>> = HashMap::new();
    let mut order: Vec<String> = Vec::new();
    for n in arr {
        let Some(name) = n.get("name").and_then(Value::as_str) else { continue };
        let variants = n
            .get("variants")
            .and_then(Value::as_array)
            .map(|a| a.iter().filter_map(|v| v.get("name").and_then(Value::as_str).map(str::to_string)).collect())
            .unwrap_or_default();
        nodes.insert(name.to_string(), SourceNode { name: name.to_string(), variants, children: vec![] });
        parent_of.insert(name.to_string(), n.get("parentName").and_then(Value::as_str).map(str::to_string));
        order.push(name.to_string());
    }
    // Edges → children map (preserving input order); a missing/unknown parent = root.
    let mut children_of: HashMap<String, Vec<String>> = HashMap::new();
    let mut roots: Vec<String> = Vec::new();
    for name in &order {
        match parent_of.get(name).and_then(Clone::clone) {
            Some(p) if nodes.contains_key(&p) => children_of.entry(p).or_default().push(name.clone()),
            _ => roots.push(name.clone()),
        }
    }
    fn build(name: &str, nodes: &mut HashMap<String, SourceNode>, kids: &HashMap<String, Vec<String>>) -> SourceNode {
        let mut node = nodes.remove(name).expect("node present");
        if let Some(cs) = kids.get(name) {
            node.children = cs.iter().map(|c| build(c, nodes, kids)).collect();
        }
        node
    }
    roots.iter().map(|r| build(r, &mut nodes, &children_of)).collect()
}

/// Map a decoding-us coordinate genome key (`"chrY [b38]"`) to our build label.
fn prod_build(gk: &str) -> Option<&'static str> {
    if gk.contains("b38") || gk.contains("hg38") || gk.contains("GRCh38") {
        Some("GRCh38")
    } else if gk.contains("b37") || gk.contains("hg19") || gk.contains("GRCh37") {
        Some("GRCh37")
    } else if gk.contains("hs1") || gk.contains("T2T") || gk.contains("CHM13") {
        Some("hs1")
    } else {
        None
    }
}

/// Extract each decoding-us variant's multi-build coordinates as `(name,
/// universal-coordinates-jsonb)`, mapping the API shape
/// (`{ "chrY [b38]": {start, stop, anc, der} }`) to ours
/// (`{ GRCh38: {contig, position, ancestral, derived} }`).
fn prod_variant_coords(body: &Value) -> Vec<(String, Value)> {
    use std::collections::BTreeMap;
    let Some(arr) = body.as_array() else { return Vec::new() };
    let mut by_name: BTreeMap<String, serde_json::Map<String, Value>> = BTreeMap::new();
    for node in arr {
        let Some(vs) = node.get("variants").and_then(Value::as_array) else { continue };
        for v in vs {
            let Some(name) = v.get("name").and_then(Value::as_str) else { continue };
            let Some(coords) = v.get("coordinates").and_then(Value::as_object) else { continue };
            let entry = by_name.entry(name.to_string()).or_default();
            for (gk, c) in coords {
                let Some(build) = prod_build(gk) else { continue };
                let Some(pos) = c.get("start").and_then(Value::as_i64) else { continue };
                let contig = gk.split(" [").next().unwrap_or("chrY");
                entry.insert(
                    build.to_string(),
                    serde_json::json!({
                        "contig": contig,
                        "position": pos,
                        "ancestral": c.get("anc").and_then(Value::as_str),
                        "derived": c.get("der").and_then(Value::as_str),
                    }),
                );
            }
        }
    }
    by_name.into_iter().filter(|(_, m)| !m.is_empty()).map(|(n, m)| (n, Value::Object(m))).collect()
}

/// Adapt the decoding-us flat API tree to source-tree.v1 `SourceNode`s (the
/// source-agnostic input the SNP-graft classifier consumes).
fn prod_source_nodes(body: &Value) -> Vec<du_db::snp_graft::SourceNode> {
    let Some(arr) = body.as_array() else { return Vec::new() };
    arr.iter()
        .filter_map(|n| {
            let name = n.get("name")?.as_str()?.to_string();
            let defining_snps = n
                .get("variants")
                .and_then(Value::as_array)
                .map(|a| a.iter().filter_map(|v| v.get("name").and_then(Value::as_str).map(str::to_string)).collect())
                .unwrap_or_default();
            Some(du_db::snp_graft::SourceNode {
                name,
                parent_name: n.get("parentName").and_then(Value::as_str).map(str::to_string),
                defining_snps,
                is_backbone: n.get("isBackbone").and_then(Value::as_bool).unwrap_or(false),
                last_updated: n.get("lastUpdated").and_then(Value::as_str).map(str::to_string),
            })
        })
        .collect()
}

/// Flatten the nested ISOGG tree into the SNP-graft's flat `SourceNode` list
/// (name + parent_name + defining SNP names). Lets ISOGG be SNP-anchor grafted
/// onto whatever catalog is loaded, instead of the topology-sensitive name merge.
fn isogg_graft_nodes(root: &Value) -> Vec<du_db::snp_graft::SourceNode> {
    fn walk(node: &Value, parent: Option<&str>, out: &mut Vec<du_db::snp_graft::SourceNode>) {
        let Some(name) = node.get("name").and_then(Value::as_str) else { return };
        let defining_snps = node
            .get("variants")
            .and_then(Value::as_array)
            .map(|a| a.iter().filter_map(|v| v.get("name").and_then(Value::as_str).map(str::to_string)).collect())
            .unwrap_or_default();
        out.push(du_db::snp_graft::SourceNode {
            name: name.to_string(),
            parent_name: parent.map(str::to_string),
            defining_snps,
            is_backbone: false,
            last_updated: None,
        });
        if let Some(kids) = node.get("children").and_then(Value::as_array) {
            for c in kids {
                walk(c, Some(name), out);
            }
        }
    }
    let mut out = Vec::new();
    walk(root, None, &mut out);
    out
}

/// Flatten the FTDNA haplotree (`{allNodes: {id: {haplogroupId, parentId, name,
/// kitsCount, variants[], isBackbone, …}}}`) into SNP-graft `SourceNode`s.
/// `kitsCount==0` nodes are **private** (skipped); a kept node's parent is its
/// nearest non-private ancestor (private nodes are spliced out). Defining SNPs are
/// the variant names. (Borrows the Scala `FtdnaTreeProvider` shape.)
fn ftdna_graft_nodes(root: &Value) -> Vec<du_db::snp_graft::SourceNode> {
    use std::collections::HashMap;
    let Some(all) = root.get("allNodes").and_then(Value::as_object) else { return Vec::new() };
    let by_id: HashMap<i64, &Value> =
        all.values().filter_map(|n| n.get("haplogroupId").and_then(Value::as_i64).map(|id| (id, n))).collect();
    let kits = |n: &Value| n.get("kitsCount").and_then(Value::as_i64).unwrap_or(0);
    // Nearest ancestor with kitsCount>=1 (public), walking parentId; None at the top.
    let public_parent = |n: &Value| -> Option<String> {
        let mut pid = n.get("parentId").and_then(Value::as_i64);
        while let Some(id) = pid {
            let Some(p) = by_id.get(&id) else { return None };
            if kits(p) >= 1 {
                return p.get("name").and_then(Value::as_str).map(str::to_string);
            }
            pid = p.get("parentId").and_then(Value::as_i64);
        }
        None
    };
    all.values()
        .filter(|n| kits(n) >= 1)
        .filter_map(|n| {
            let name = n.get("name")?.as_str()?.to_string();
            let defining_snps = n
                .get("variants")
                .and_then(Value::as_array)
                .map(|a| a.iter().filter_map(|v| v.get("variant").and_then(Value::as_str)).filter(|s| !s.is_empty()).map(str::to_string).collect())
                .unwrap_or_default();
            Some(du_db::snp_graft::SourceNode {
                name,
                parent_name: public_parent(n),
                defining_snps,
                is_backbone: n.get("isBackbone").and_then(Value::as_bool).unwrap_or(false),
                last_updated: None,
            })
        })
        .collect()
}

/// FTDNA per-variant GRCh38 coordinates as `(snp_name, coords-jsonb)` for
/// fill-if-absent enrichment. `abs(position)` works around FTDNA's negative-position
/// data bug; only rows with a position + both alleles are emitted.
fn ftdna_variant_coords(root: &Value, contig: &str) -> Vec<(String, Value)> {
    let Some(all) = root.get("allNodes").and_then(Value::as_object) else { return Vec::new() };
    let mut out = Vec::new();
    for n in all.values() {
        if n.get("kitsCount").and_then(Value::as_i64).unwrap_or(0) < 1 {
            continue;
        }
        for v in n.get("variants").and_then(Value::as_array).into_iter().flatten() {
            let (Some(name), Some(pos)) =
                (v.get("variant").and_then(Value::as_str).filter(|s| !s.is_empty()), v.get("position").and_then(Value::as_i64))
            else { continue };
            let (anc, der) = (v.get("ancestral").and_then(Value::as_str), v.get("derived").and_then(Value::as_str));
            if anc.is_none() && der.is_none() {
                continue;
            }
            out.push((
                name.to_string(),
                serde_json::json!({ "GRCh38": { "contig": contig, "position": pos.abs(), "ancestral": anc, "derived": der } }),
            ));
        }
    }
    out
}

/// The shared SNP-anchored graft pipeline (classify → enrich → graft → review),
/// source-agnostic. Dry-run by default; writes gated by `--apply`/`--graft`.
async fn run_snp_graft(
    pool: &PgPool,
    source: &[du_db::snp_graft::SourceNode],
    dna: DnaType,
    label: &str,
    args: &Args,
) -> anyhow::Result<()> {
    anyhow::ensure!(!source.is_empty(), "no source nodes to graft");
    tracing::info!(%label, nodes = source.len(), "classifying source tree by SNP anchor (dry-run)");
    let report = du_db::snp_graft::classify(pool, source, dna, label).await?;
    print_graft_report(&report);
    let enr = du_db::snp_graft::enrich(pool, source, dna, label, &report, args.apply).await?;
    tracing::info!(
        applied = enr.applied, anchors = enr.anchors,
        aliases_added = enr.aliases_added, backbone_set = enr.backbone_set,
        samples = ?enr.samples, "Phase 2 enrich"
    );
    let want_review = args.export_flags.is_some() || args.stage_review;
    let graft_rep = if args.graft || want_review {
        let apply_graft = args.graft && args.apply;
        let g = du_db::snp_graft::graft(pool, source, dna, label, &report, &args.by, apply_graft, args.reattach).await?;
        if args.graft {
            print_graft_write_report(&g);
        }
        Some(g)
    } else {
        None
    };
    if want_review {
        let g = graft_rep.as_ref().expect("graft report computed when reviewing");
        let ex = du_db::snp_graft::export_review(pool, source, dna, label, &report, g).await?;
        if let Some(path) = &args.export_flags {
            std::fs::write(path, serde_json::to_string_pretty(&ex)?)?;
            tracing::info!(
                path = %path, items = ex.items.len(),
                weak = ex.summary.weak_plurality, parent_inconsistent = ex.summary.parent_inconsistent,
                name_collision = ex.summary.name_collision, graft_blocked = ex.summary.graft_blocked,
                "Phase 4 curator-review export written"
            );
        }
        if args.stage_review {
            let (cs_id, n) = du_db::snp_graft::stage_review(pool, source, dna, label, &ex, &args.by).await?;
            tracing::info!(change_set_id = cs_id, staged = n, "Phase 4 staged for curator review (/curator/reviews)");
        }
    }
    Ok(())
}

/// Log the dry-run classification breakdown + a few samples per category.
fn print_graft_report(r: &du_db::snp_graft::GraftReport) {
    use du_db::snp_graft::{Disposition, FlagReason};
    tracing::info!(
        total = r.total, matched = r.matched, graft_novel = r.graft_novel,
        flag_weak = r.flag_weak, flag_inconsistent = r.flag_inconsistent,
        backbone_adopt = r.backbone_adopt,
        "SNP-graft dry-run classification"
    );
    let fmt = |c: &du_db::snp_graft::Classified| match &c.disposition {
        Disposition::Match { anchor, strength } => format!("{} → {} ({:.0}%)", c.node, anchor, strength * 100.0),
        Disposition::GraftNovel { parent_anchor } => {
            format!("{} ⤚graft under {}", c.node, parent_anchor.as_deref().unwrap_or("?"))
        }
        Disposition::Flag { reason, anchor } => {
            let why = match reason { FlagReason::WeakPlurality => "weak", FlagReason::ParentInconsistent => "parent≠" };
            format!("{} ⚑{} (~{})", c.node, why, anchor.as_deref().unwrap_or("?"))
        }
    };
    for (label, pred) in [
        ("match", &(|d: &Disposition| matches!(d, Disposition::Match { .. })) as &dyn Fn(&Disposition) -> bool),
        ("graft", &(|d: &Disposition| matches!(d, Disposition::GraftNovel { .. }))),
        ("flag", &(|d: &Disposition| matches!(d, Disposition::Flag { .. }))),
    ] {
        let s: Vec<String> = r.sample(6, pred).into_iter().map(fmt).collect();
        tracing::info!(category = label, samples = ?s, "graft samples");
    }
    // Spot-check well-known nodes spread across the tree (not alphabetical-first)
    // to gauge quality on the SNP-rich bulk vs the hard deep-African upstream.
    let spot = [
        "R1b-L21", "R1b-P312", "R1b-S10", "R1a-M198", "I1", "I2", "J2", "J1",
        "E1b-CTS19", "G2a-L1259", "N-M231", "Q-M242", "O2-M122", "T-M184",
    ];
    let checks: Vec<String> = spot
        .iter()
        .filter_map(|name| r.items.iter().find(|c| c.node == *name))
        .map(&fmt)
        .collect();
    tracing::info!(spot_checks = ?checks, "graft spot-check (known nodes)");
}

/// Log the Phase 3 graft plan: how many novel branches will be created, where
/// they attach, and what was skipped (name collisions / unresolvable parents).
fn print_graft_write_report(g: &du_db::snp_graft::GraftWriteReport) {
    tracing::info!(
        applied = g.applied,
        novel_total = g.novel_total,
        creatable = g.creatable,
        under_existing = g.under_existing,
        under_new = g.under_new,
        roots = g.roots,
        reattached = g.reattached,
        skipped_name_exists = g.skipped_name_exists.len(),
        skipped_unresolved = g.skipped_unresolved.len(),
        change_set_id = g.change_set_id,
        "Phase 3 graft plan"
    );
    tracing::info!(samples = ?g.samples, "graft samples");
    if !g.skipped_name_exists.is_empty() {
        let s: Vec<&String> = g.skipped_name_exists.iter().take(10).collect();
        tracing::warn!(count = g.skipped_name_exists.len(), sample = ?s, "skipped — name already in tree");
    }
    if !g.skipped_unresolved.is_empty() {
        let s: Vec<&String> = g.skipped_unresolved.iter().take(10).collect();
        tracing::warn!(count = g.skipped_unresolved.len(), sample = ?s, "skipped — parent unresolvable");
    }
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
        .clone()
        .or_else(|| std::env::var("DATABASE_URL").ok())
        .ok_or_else(|| anyhow::anyhow!("set --database-url or DATABASE_URL"))?;
    let (dna, dna_label) = parse_dna(&args.dna)?;
    let pool = du_db::connect(&url, 4).await?;
    du_db::run_migrations(&pool).await?;

    // The ISOGG JSON (if given) is parsed up front — its `aliases` feed the
    // post-process step. Kept around for alias population below.
    let isogg: Option<Value> = match &args.isogg {
        Some(path) => {
            tracing::info!(%path, "loading ISOGG tree");
            Some(serde_json::from_str(&std::fs::read_to_string(path)?)?)
        }
        None => None,
    };
    let isogg_root: Option<&Value> = isogg.as_ref().map(|j| {
        j.get("sourceTree").unwrap_or(j)
    });

    if !args.reprocess {
        // 1. ISOGG foundation (only when --isogg is given; the merge is not
        //    safely re-runnable against an already-loaded ISOGG tree).
        if let Some(root) = isogg_root {
            let source_name = isogg
                .as_ref()
                .and_then(|j| j.get("sourceName").and_then(Value::as_str))
                .unwrap_or("ISOGG")
                .to_string();
            if args.snp_graft {
                // SNP-anchored graft — robust to topology/naming differences (the
                // name merge cascades to NEW when the roots don't align).
                let source = isogg_graft_nodes(root);
                run_snp_graft(&pool, &source, dna, &source_name, &args).await?;
            } else {
                let isogg_roots = parse_isogg(root).map(|r| vec![r]).ok_or_else(|| anyhow::anyhow!("could not parse ISOGG sourceTree"))?;
                merge_into(&pool, &isogg_roots, &source_name, dna, dna_label, &args.by, args.apply).await?;
            }
        }

        // 2. Optional: merge the decoding-us production tree in.
        if let Some(url) = &args.merge_prod {
            tracing::info!(%url, "fetching decoding-us production tree");
            let body: Value = reqwest::Client::new().get(url).send().await?.error_for_status()?.json().await?;

            // Backfill multi-build SNP coordinates the graft dropped (it stores
            // names only). The API carries locus + anc/der per build; merge them
            // into core.variant.coordinates (existing builds win).
            if args.backfill_prod_coords {
                let coords = prod_variant_coords(&body);
                anyhow::ensure!(!coords.is_empty(), "no variant coordinates parsed from prod tree");
                if args.apply {
                    let n = du_db::variant::set_coordinates_bulk(&pool, &coords).await?;
                    tracing::info!(variants = coords.len(), updated = n, "backfilled decoding-us variant coordinates");
                } else {
                    let sample: Vec<&String> = coords.iter().take(5).map(|(n, _)| n).collect();
                    tracing::info!(variants = coords.len(), ?sample, "DRY-RUN: would backfill decoding-us variant coordinates (pass --apply)");
                }
                // Coordinate backfill is a standalone enrichment — don't fall
                // through into the merge/graft pipeline.
            } else if args.snp_graft {
                let source = prod_source_nodes(&body);
                run_snp_graft(&pool, &source, dna, "decoding-us", &args).await?;
            } else {
                // Legacy exact-set merge (cannot reconcile cross-source trees).
                let prod_roots = parse_prod_flat(&body);
                anyhow::ensure!(!prod_roots.is_empty(), "no nodes parsed from prod tree");
                tracing::info!(roots = prod_roots.len(), "parsed prod tree");
                merge_into(&pool, &prod_roots, "decoding-us", dna, dna_label, &args.by, args.apply).await?;
            }
        }

        // 3. Optional: SNP-anchor graft the FTDNA haplotree onto the loaded catalog.
        if let Some(path) = &args.ftdna {
            tracing::info!(%path, "loading FTDNA haplotree");
            let body: Value = serde_json::from_str(&std::fs::read_to_string(path)?)?;
            // Enrich coordinates fill-if-absent from FTDNA's anc/der + GRCh38 position.
            let coords = ftdna_variant_coords(&body, if dna == DnaType::YDna { "chrY" } else { "chrM" });
            if args.apply {
                let n = du_db::variant::set_coordinates_bulk(&pool, &coords).await?;
                tracing::info!(rows = coords.len(), updated = n, "enriched coordinates from FTDNA (existing builds win)");
            } else {
                tracing::info!(rows = coords.len(), "DRY-RUN: would enrich coordinates from FTDNA (pass --apply)");
            }
            let source = ftdna_graft_nodes(&body);
            run_snp_graft(&pool, &source, dna, "FTDNA", &args).await?;
        }
    }

    // 4. Post-process the materialized tree (after a fresh --apply, or on demand
    //    via --reprocess; the merge itself is not safely re-runnable).
    if args.apply || args.reprocess {
        // Reconcile ISOGG's split-clade stitch artifacts (X / X~ sibling twins)
        // BEFORE backbone, since it changes tree structure.
        let tw = du_db::haplogroup::reconcile_tilde_twins(&pool, dna).await?;
        tracing::info!(folded = tw.folded, "reconciled same-parent ~ twins");
        if !tw.skipped.is_empty() {
            tracing::warn!(
                count = tw.skipped.len(), nodes = ?tw.skipped,
                "cross-parent ~ twins left for review (possible ISOGG curation)"
            );
        }
        let backbone = du_db::haplogroup::recompute_backbone(&pool, dna).await?;
        tracing::info!(backbone, "recomputed backbone (single-letter clades + ancestors)");
        if let Some(root) = isogg_root {
            let aliased = apply_aliases(&pool, root, dna).await?;
            tracing::info!(aliased, "stored haplogroup name aliases from ISOGG");
            // Populate core.variant.aliases (slash-synonyms) per the universal model.
            let mut va: Vec<(String, Vec<String>)> = Vec::new();
            collect_variant_aliases(root, &mut va);
            let n = du_db::variant::set_aliases_bulk(&pool, &va).await?;
            tracing::info!(variant_aliases = n, groups = va.len(), "populated core.variant.aliases");
        }
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

/// Walk the ISOGG tree collecting each variant's `(canonical_name, [aliases])`
/// from the `{name, aliases}` variant objects, for `core.variant.aliases`.
fn collect_variant_aliases(node: &Value, out: &mut Vec<(String, Vec<String>)>) {
    if let Some(vs) = node.get("variants").and_then(Value::as_array) {
        for v in vs {
            let Some(name) = v.get("name").and_then(Value::as_str) else { continue };
            let aliases: Vec<String> = v
                .get("aliases")
                .and_then(Value::as_array)
                .map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect())
                .unwrap_or_default();
            if !aliases.is_empty() {
                out.push((name.to_string(), aliases));
            }
        }
    }
    if let Some(children) = node.get("children").and_then(Value::as_array) {
        for c in children {
            collect_variant_aliases(c, out);
        }
    }
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
