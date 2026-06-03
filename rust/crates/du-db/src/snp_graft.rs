//! SNP-anchored reconciliation of an external **source tree** into the ISOGG
//! foundation. Source trees (decoding-us now, ytree.net later) use their own
//! node names and topology but the **same SNPs** — so we ignore their names and
//! anchor each source node to the ISOGG node carrying the *plurality* of its
//! defining SNPs, then classify it:
//!
//! - **Match** — strong plurality anchor that is consistent with the parent's
//!   anchor → enrich the existing ISOGG node (alias, backbone, recency).
//! - **GraftNovel** — no ISOGG SNP overlap → a finer branch to create under the
//!   parent's anchor (the source's WGS-grade resolution).
//! - **Flag** — weak plurality or parent-inconsistent → curator review
//!   (recurrent-SNP noise or a genuine topology disagreement).
//!
//! This module is the **dry-run classifier**: it reads only, returns a report,
//! and never writes. The enrich/graft writers build on it later.

use crate::{pg_enum_label, DbError};
use du_domain::enums::DnaType;
use serde_json::{json, Value};
use sqlx::{PgPool, Postgres, Transaction};
use std::collections::{HashMap, HashSet};

/// One node of an external source tree (the `source-tree.v1` shape), joined to
/// ISOGG by SNP. Provider names/topology are theirs; `defining_snps` is the join.
#[derive(Debug, Clone)]
pub struct SourceNode {
    pub name: String,
    pub parent_name: Option<String>,
    pub defining_snps: Vec<String>,
    pub is_backbone: bool,
    pub last_updated: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum FlagReason {
    /// No single ISOGG node holds a majority of the node's defining SNPs.
    WeakPlurality,
    /// The node's anchor is not at/below its parent's anchor (topology clash).
    ParentInconsistent,
}

/// What the classifier decided for one source node.
#[derive(Debug, Clone, PartialEq)]
pub enum Disposition {
    Match { anchor: String, strength: f64 },
    GraftNovel { parent_anchor: Option<String> },
    Flag { reason: FlagReason, anchor: Option<String> },
}

#[derive(Debug, Clone)]
pub struct Classified {
    pub node: String,
    pub disposition: Disposition,
}

#[derive(Debug, Default)]
pub struct GraftReport {
    pub total: usize,
    pub matched: usize,
    pub graft_novel: usize,
    pub flag_weak: usize,
    pub flag_inconsistent: usize,
    /// Matched nodes the source marks as backbone (adopted curated flags).
    pub backbone_adopt: usize,
    pub items: Vec<Classified>,
}

impl GraftReport {
    /// First `n` classified nodes matching `pred` (for sampling in a report).
    pub fn sample(&self, n: usize, pred: impl Fn(&Disposition) -> bool) -> Vec<&Classified> {
        self.items.iter().filter(|c| pred(&c.disposition)).take(n).collect()
    }
}

/// Minimum absolute shared-SNP count for a node to count as a strong anchor
/// (kills tiny recurrent-SNP overlaps) and the plurality fraction required.
const MIN_ANCHOR_HITS: i64 = 3;
const MIN_PLURALITY: f64 = 0.5;

/// The plurality ISOGG anchor for a set of defining SNPs: `(node, top_hits,
/// total_hits)` where `top_hits` land on `node` and `total_hits` across all
/// nodes. `None` when no defining SNP is known to ISOGG (a novel branch).
fn anchor(snps: &[String], snp2nodes: &HashMap<String, Vec<String>>) -> Option<(String, i64, i64)> {
    let mut hits: HashMap<&str, i64> = HashMap::new();
    let mut total = 0i64;
    for s in snps {
        if let Some(nodes) = snp2nodes.get(&s.to_ascii_lowercase()) {
            for n in nodes {
                *hits.entry(n.as_str()).or_default() += 1;
                total += 1;
            }
        }
    }
    hits.into_iter().max_by_key(|&(_, c)| c).map(|(n, c)| (n.to_string(), c, total))
}

/// Is `a` an ancestor of `n` in the ISOGG tree (via current parent edges)?
fn is_ancestor(a: &str, n: &str, parent_of: &HashMap<String, String>) -> bool {
    let mut cur = n;
    for _ in 0..1000 {
        match parent_of.get(cur) {
            Some(p) if p == a => return true,
            Some(p) => cur = p,
            None => return false,
        }
    }
    false
}

/// Summary of an enrich pass (Phase 2).
#[derive(Debug, Default)]
pub struct EnrichReport {
    /// Distinct ISOGG nodes enriched (one per matched anchor).
    pub anchors: usize,
    /// Total source-name aliases attached.
    pub aliases_added: usize,
    /// Anchors whose `is_backbone` is set from the source's curated flag.
    pub backbone_set: usize,
    pub applied: bool,
    pub samples: Vec<String>,
}

/// **Phase 2 — enrich.** For every source node the classifier *matches* to an
/// existing ISOGG node, fold the source's curated metadata onto that node:
/// append the source name to `provenance.aliases` (union), adopt `is_backbone`
/// (additively — never un-backbones), and record `provenance.source_updated`.
/// Pure read + report when `apply` is false; a single transaction when true.
pub async fn enrich(
    pool: &PgPool,
    source: &[SourceNode],
    dna: DnaType,
    classified: &GraftReport,
    apply: bool,
) -> Result<EnrichReport, DbError> {
    let dna_label = pg_enum_label(&dna)?;
    let by_name: HashMap<&str, &SourceNode> = source.iter().map(|n| (n.name.as_str(), n)).collect();

    // Group matches by ISOGG anchor: (alias names, any-backbone, latest update).
    use std::collections::BTreeMap;
    let mut groups: BTreeMap<String, (Vec<String>, bool, Option<String>)> = BTreeMap::new();
    for c in &classified.items {
        if let Disposition::Match { anchor, .. } = &c.disposition {
            let sn = by_name[c.node.as_str()];
            let e = groups.entry(anchor.clone()).or_default();
            e.0.push(sn.name.clone());
            e.1 |= sn.is_backbone;
            if sn.last_updated.is_some() && sn.last_updated > e.2 {
                e.2 = sn.last_updated.clone();
            }
        }
    }

    let mut rep = EnrichReport { anchors: groups.len(), applied: apply, ..Default::default() };
    for (aliases, bb, _) in groups.values() {
        rep.aliases_added += aliases.len();
        if *bb {
            rep.backbone_set += 1;
        }
    }
    for (anchor, (aliases, bb, upd)) in groups.iter().take(8) {
        rep.samples.push(format!(
            "{anchor} += {aliases:?}{}{}",
            if *bb { " [backbone]" } else { "" },
            upd.as_deref().map(|u| format!(" @{u}")).unwrap_or_default()
        ));
    }

    if apply {
        let mut tx = pool.begin().await?;
        for (anchor, (aliases, bb, upd)) in &groups {
            sqlx::query(
                "UPDATE tree.haplogroup SET \
                   is_backbone = is_backbone OR $3, \
                   provenance = jsonb_set( \
                     jsonb_set(COALESCE(provenance, '{}'::jsonb), '{aliases}', \
                       (SELECT COALESCE(jsonb_agg(DISTINCT a), '[]'::jsonb) FROM ( \
                          SELECT jsonb_array_elements_text(COALESCE(provenance->'aliases', '[]'::jsonb)) AS a \
                          UNION SELECT unnest($2::text[])) u), true), \
                     '{source_updated}', to_jsonb($5::text), true) \
                 WHERE name = $1 AND haplogroup_type::text = $4 AND valid_until IS NULL",
            )
            .bind(anchor)
            .bind(aliases)
            .bind(bb)
            .bind(&dna_label)
            .bind(upd.as_deref())
            .execute(&mut *tx)
            .await?;
            // Durable curated-backbone marker (only for backbone anchors) so
            // recompute_backbone preserves the adopted flag instead of clearing it.
            if *bb {
                sqlx::query(
                    "UPDATE tree.haplogroup SET \
                       provenance = jsonb_set(COALESCE(provenance, '{}'::jsonb), '{backbone_source}', '\"decoding-us\"'::jsonb, true) \
                     WHERE name = $1 AND haplogroup_type::text = $2 AND valid_until IS NULL",
                )
                .bind(anchor)
                .bind(&dna_label)
                .execute(&mut *tx)
                .await?;
            }
        }
        tx.commit().await?;
    }
    Ok(rep)
}

/// Where a novel node attaches in the target tree.
#[derive(Debug, Clone)]
enum PTarget {
    /// An existing (ISOGG/already-grafted) node id.
    Existing(i64),
    /// Another novel node being created in this same pass (by source name) —
    /// resolved to a real id via the change-set placeholder mechanism.
    NewParent(String),
    /// No parent (a new root). Rare — only a parentless novel source node.
    Root,
}

/// Per-novel placement status after parent resolution + cascade.
#[derive(Debug, Clone)]
enum St {
    /// Will be created, attached at this target.
    Ok(PTarget),
    /// Its name already exists in the tree (unique (name,type) — can't create).
    NameExists,
    /// Parent can't be resolved (flagged w/o anchor, missing, or a blocked novel).
    Blocked,
}

/// Summary of a graft pass (Phase 3).
#[derive(Debug, Default)]
pub struct GraftWriteReport {
    /// Source nodes classified GraftNovel (no ISOGG SNP overlap).
    pub novel_total: usize,
    /// Novel nodes that resolve to a parent and will be / were created.
    pub creatable: usize,
    /// Of `creatable`, attached directly under an existing anchor.
    pub under_existing: usize,
    /// Of `creatable`, attached under another newly-grafted node.
    pub under_new: usize,
    /// Of `creatable`, created as a new root (parentless).
    pub roots: usize,
    /// Skipped — name already present in the tree.
    pub skipped_name_exists: Vec<String>,
    /// Skipped — parent unresolvable (flagged/missing, or depends on a skip).
    pub skipped_unresolved: Vec<String>,
    /// The DRAFT change-set written (only when `apply`).
    pub change_set_id: Option<i64>,
    pub applied: bool,
    /// A few `child ⤚under parent` lines for the log.
    pub samples: Vec<String>,
}

/// **Phase 3 — graft.** Create the source's truly-novel branches (classified
/// `GraftNovel`: no ISOGG SNP overlap) as new `tree.haplogroup` nodes under
/// their parent's anchor — the source's WGS-grade resolution that ISOGG lacks.
///
/// Resolution per novel node:
/// - parent **Match** → attach under that existing anchor id.
/// - parent is **also novel** → attach under the node we create for it
///   (change-set placeholder; emitted parent-before-child).
/// - parent **Flag w/ anchor** → lift to the flagged parent's best anchor.
/// - parent flagged-w/o-anchor / missing / a skipped novel → skip (cascades).
/// - name already in the tree (unique name+type) → skip.
///
/// Read-only + report when `apply` is false (computes the full plan, writes
/// nothing). When true, materializes a reviewable change-set (CREATE ops with
/// placeholders, `source='decoding-us'`, curated `is_backbone`, provenance) and
/// applies it. Idempotency note: the merge isn't re-runnable, so a second
/// `--apply` would duplicate — apply once.
pub async fn graft(
    pool: &PgPool,
    source: &[SourceNode],
    dna: DnaType,
    classified: &GraftReport,
    by: &str,
    apply: bool,
) -> Result<GraftWriteReport, DbError> {
    let dna_label = pg_enum_label(&dna)?;
    let by_name: HashMap<&str, &SourceNode> = source.iter().map(|n| (n.name.as_str(), n)).collect();
    let dispo: HashMap<&str, &Disposition> =
        classified.items.iter().map(|c| (c.node.as_str(), &c.disposition)).collect();

    // Current tree: name → id (anchors resolve here; also the collision guard).
    let id_rows: Vec<(String, i64)> = sqlx::query_as(
        "SELECT name, id FROM tree.haplogroup WHERE haplogroup_type::text = $1 AND valid_until IS NULL",
    )
    .bind(&dna_label)
    .fetch_all(pool)
    .await?;
    let name_of_id: HashMap<i64, String> = id_rows.iter().map(|(n, i)| (*i, n.clone())).collect();
    let id_of: HashMap<String, i64> = id_rows.into_iter().collect();

    // The novel set, and a quick membership test for "parent is also novel".
    let novels: Vec<&SourceNode> = classified
        .items
        .iter()
        .filter(|c| matches!(c.disposition, Disposition::GraftNovel { .. }))
        .filter_map(|c| by_name.get(c.node.as_str()).copied())
        .collect();
    let novel_names: HashSet<&str> = novels.iter().map(|n| n.name.as_str()).collect();

    // Immediate (one-hop) parent target, before cascade.
    let immediate = |sn: &SourceNode| -> St {
        match &sn.parent_name {
            None => St::Ok(PTarget::Root),
            Some(p) => {
                if novel_names.contains(p.as_str()) {
                    return St::Ok(PTarget::NewParent(p.clone()));
                }
                match dispo.get(p.as_str()) {
                    Some(Disposition::Match { anchor, .. }) => {
                        id_of.get(anchor).map(|&id| St::Ok(PTarget::Existing(id))).unwrap_or(St::Blocked)
                    }
                    // Parent not in source at all → maybe it names an existing tree node.
                    None => id_of.get(p).map(|&id| St::Ok(PTarget::Existing(id))).unwrap_or(St::Blocked),
                    // Flagged parent (weak/parent-inconsistent) → its anchor is
                    // exactly the placement we DON'T trust; grafting novel children
                    // onto it would manufacture false (often cross-lineage)
                    // structure. Block — surface for Phase 4 curator review instead.
                    _ => St::Blocked,
                }
            }
        }
    };

    // Initial status: name-collision guard first, then immediate parent target.
    let mut status: HashMap<String, St> = HashMap::with_capacity(novels.len());
    for sn in &novels {
        let st = if id_of.contains_key(&sn.name) { St::NameExists } else { immediate(sn) };
        status.insert(sn.name.clone(), st);
    }

    // Cascade blocking up NewParent chains: a node whose new-parent is blocked /
    // skipped / absent is itself unbuildable. Iterate to a fixpoint.
    loop {
        let mut to_block: Vec<String> = Vec::new();
        for sn in &novels {
            if let Some(St::Ok(PTarget::NewParent(p))) = status.get(&sn.name) {
                let parent_ok = matches!(status.get(p), Some(St::Ok(_)));
                if !parent_ok {
                    to_block.push(sn.name.clone());
                }
            }
        }
        if to_block.is_empty() {
            break;
        }
        for n in to_block {
            status.insert(n, St::Blocked);
        }
    }

    // Topo order the buildable set: parent before child (placeholders resolve in
    // change-set id order during apply).
    let mut emitted: HashSet<String> = HashSet::new();
    let mut order: Vec<&SourceNode> = Vec::new();
    loop {
        let mut progressed = false;
        for sn in &novels {
            if emitted.contains(&sn.name) {
                continue;
            }
            let ready = match status.get(&sn.name) {
                Some(St::Ok(PTarget::Existing(_))) | Some(St::Ok(PTarget::Root)) => true,
                Some(St::Ok(PTarget::NewParent(p))) => emitted.contains(p),
                _ => false,
            };
            if ready {
                order.push(sn);
                emitted.insert(sn.name.clone());
                progressed = true;
            }
        }
        if !progressed {
            break;
        }
    }

    // Build the report (counts + samples) — this is the whole dry-run output.
    let mut rep = GraftWriteReport { novel_total: novels.len(), applied: apply, ..Default::default() };
    for sn in &novels {
        match status.get(&sn.name) {
            Some(St::NameExists) => rep.skipped_name_exists.push(sn.name.clone()),
            Some(St::Blocked) => rep.skipped_unresolved.push(sn.name.clone()),
            _ => {}
        }
    }
    rep.creatable = order.len();
    for sn in &order {
        match status.get(&sn.name) {
            Some(St::Ok(PTarget::Existing(_))) => rep.under_existing += 1,
            Some(St::Ok(PTarget::NewParent(_))) => rep.under_new += 1,
            Some(St::Ok(PTarget::Root)) => rep.roots += 1,
            _ => {}
        }
    }
    // Label shows the *resolved* attach point (existing anchor name / new parent
    // / root), not the source parent name.
    let target_label = |sn: &SourceNode| -> String {
        match status.get(&sn.name) {
            Some(St::Ok(PTarget::Existing(id))) => {
                name_of_id.get(id).cloned().unwrap_or_else(|| format!("#{id}"))
            }
            Some(St::Ok(PTarget::NewParent(p))) => format!("{p} (new)"),
            _ => "(root)".to_string(),
        }
    };
    for sn in order.iter().take(8) {
        rep.samples.push(format!("{} ⤚under {} [{} snps]", sn.name, target_label(sn), sn.defining_snps.len()));
    }

    if !apply {
        return Ok(rep);
    }

    // ── materialize as a reviewable change-set, then apply ──────────────────────
    let mut ph_of: HashMap<&str, i64> = HashMap::with_capacity(order.len());
    for (i, sn) in order.iter().enumerate() {
        ph_of.insert(sn.name.as_str(), -(i as i64 + 1));
    }

    let mut tx = pool.begin().await?;
    let cs_id: i64 = sqlx::query_scalar(
        "INSERT INTO tree.change_set (source, haplogroup_type, status, description, created_by) \
         VALUES ($1, $2::core.dna_type, 'READY_FOR_REVIEW', $3, $4) RETURNING id",
    )
    .bind("decoding-us")
    .bind(&dna_label)
    .bind(format!("SNP-graft Phase 3: graft {} novel decoding-us branches", order.len()))
    .bind(by)
    .fetch_one(&mut *tx)
    .await?;

    let mut vcache: HashMap<String, i64> = HashMap::new();
    let mut count: i64 = 0;
    for sn in &order {
        let mut vids = Vec::with_capacity(sn.defining_snps.len());
        for v in &sn.defining_snps {
            vids.push(get_or_create_variant(&mut tx, &mut vcache, v).await?);
        }
        let mut nv = serde_json::Map::new();
        nv.insert("name".into(), json!(sn.name));
        nv.insert("haplogroup_type".into(), json!(dna_label));
        nv.insert("source".into(), json!("decoding-us"));
        nv.insert("variant_ids".into(), json!(vids));
        nv.insert("placeholder".into(), json!(ph_of[sn.name.as_str()]));
        nv.insert("is_backbone".into(), json!(sn.is_backbone));
        // The node name IS the decoding-us name, so no self-alias; record source.
        let mut prov = serde_json::Map::new();
        prov.insert("source".into(), json!("decoding-us"));
        if let Some(u) = &sn.last_updated {
            prov.insert("source_updated".into(), json!(u));
        }
        // Curated-backbone marker so recompute_backbone preserves the flag.
        if sn.is_backbone {
            prov.insert("backbone_source".into(), json!("decoding-us"));
        }
        nv.insert("provenance".into(), Value::Object(prov));
        match status.get(&sn.name) {
            Some(St::Ok(PTarget::Existing(id))) => {
                nv.insert("parent_haplogroup_id".into(), json!(id));
            }
            Some(St::Ok(PTarget::NewParent(p))) => {
                nv.insert("parent_placeholder".into(), json!(ph_of[p.as_str()]));
            }
            _ => {} // Root → no parent ref
        }
        sqlx::query(
            "INSERT INTO tree.tree_change (change_set_id, change_type, haplogroup_id, new_values) \
             VALUES ($1, 'CREATE'::tree.tree_change_type, NULL, $2)",
        )
        .bind(cs_id)
        .bind(Value::Object(nv))
        .execute(&mut *tx)
        .await?;
        count += 1;
    }
    sqlx::query("UPDATE tree.change_set SET change_count = $2 WHERE id = $1")
        .bind(cs_id)
        .bind(count)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    rep.change_set_id = Some(cs_id);

    // Approve + apply the set (its own transaction, via the apply engine).
    crate::change_set::start_review(pool, cs_id).await?;
    crate::change_set::approve_all(pool, cs_id).await?;
    crate::change_set::apply(pool, cs_id, by).await?;

    Ok(rep)
}

/// Get-or-create a `core.variant` by canonical name (mirrors the merge
/// materializer); new rows land as UNNAMED SNPs.
async fn get_or_create_variant(
    tx: &mut Transaction<'_, Postgres>,
    cache: &mut HashMap<String, i64>,
    name: &str,
) -> Result<i64, DbError> {
    if let Some(&id) = cache.get(name) {
        return Ok(id);
    }
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status) \
         VALUES ($1, 'SNP'::core.mutation_type, 'UNNAMED'::core.naming_status) \
         ON CONFLICT (canonical_name) DO UPDATE SET canonical_name = EXCLUDED.canonical_name RETURNING id",
    )
    .bind(name)
    .fetch_one(&mut **tx)
    .await?;
    cache.insert(name.to_string(), id);
    Ok(id)
}

/// Classify every source node against the current ISOGG tree (read-only).
pub async fn classify(pool: &PgPool, source: &[SourceNode], dna: DnaType) -> Result<GraftReport, DbError> {
    let dna_label = pg_enum_label(&dna)?;

    // SNP name (canonical OR alias, lowercased) → ISOGG node name(s). Indexing
    // aliases too lets a source tree that uses a synonym (e.g. L1284 for AF6)
    // still anchor. A name on several nodes is recurrent.
    let snp_rows: Vec<(String, String)> = sqlx::query_as(
        "SELECT lower(v.canonical_name), h.name FROM core.variant v \
         JOIN tree.haplogroup_variant hv ON hv.variant_id = v.id AND hv.valid_until IS NULL \
         JOIN tree.haplogroup h ON h.id = hv.haplogroup_id \
         WHERE h.haplogroup_type::text = $1 AND h.valid_until IS NULL \
         UNION ALL \
         SELECT lower(a.alias), h.name FROM core.variant v \
         CROSS JOIN LATERAL jsonb_array_elements_text(v.aliases->'common_names') AS a(alias) \
         JOIN tree.haplogroup_variant hv ON hv.variant_id = v.id AND hv.valid_until IS NULL \
         JOIN tree.haplogroup h ON h.id = hv.haplogroup_id \
         WHERE h.haplogroup_type::text = $1 AND h.valid_until IS NULL",
    )
    .bind(&dna_label)
    .fetch_all(pool)
    .await?;
    let mut snp2nodes: HashMap<String, Vec<String>> = HashMap::new();
    for (snp, node) in snp_rows {
        snp2nodes.entry(snp).or_default().push(node);
    }

    // ISOGG child → parent (current edges), for parent-consistency checks.
    let edges: Vec<(String, String)> = sqlx::query_as(
        "SELECT c.name, p.name FROM tree.haplogroup_relationship r \
         JOIN tree.haplogroup c ON c.id = r.child_haplogroup_id \
         JOIN tree.haplogroup p ON p.id = r.parent_haplogroup_id \
         WHERE r.valid_until IS NULL AND c.haplogroup_type::text = $1 AND c.valid_until IS NULL",
    )
    .bind(&dna_label)
    .fetch_all(pool)
    .await?;
    let parent_of: HashMap<String, String> = edges.into_iter().collect();

    // Pre-anchor every node so a node can look up its parent's anchor.
    let mut anchors: HashMap<String, Option<(String, i64, i64)>> = HashMap::with_capacity(source.len());
    for sn in source {
        anchors.insert(sn.name.clone(), anchor(&sn.defining_snps, &snp2nodes));
    }
    let anchor_node = |name: &Option<String>| -> Option<String> {
        name.as_ref().and_then(|p| anchors.get(p)).and_then(|o| o.as_ref()).map(|(n, _, _)| n.clone())
    };

    let mut report = GraftReport { total: source.len(), ..Default::default() };
    for sn in source {
        let disposition = match &anchors[&sn.name] {
            None => {
                report.graft_novel += 1;
                Disposition::GraftNovel { parent_anchor: anchor_node(&sn.parent_name) }
            }
            Some((node, top, total)) => {
                let strength = if *total > 0 { *top as f64 / *total as f64 } else { 0.0 };
                let strong = *top >= MIN_ANCHOR_HITS && strength >= MIN_PLURALITY;
                let parent_anchor = anchor_node(&sn.parent_name);
                let consistent = match &parent_anchor {
                    None => true, // parent is novel/root — nothing to contradict
                    Some(pa) => pa == node || is_ancestor(pa, node, &parent_of),
                };
                // Major-clade guard: a source name encodes its clade (R1b-…, B-…),
                // and ISOGG names are phylogenetic paths (R1b1a1b…), so the leading
                // letter must agree — kills recurrent-SNP cross-lineage anchors
                // (e.g. R1b-S5676 → B2) that are otherwise strong + consistent.
                let clade_ok = sn.name.chars().next().map(|c| c.to_ascii_uppercase())
                    == node.chars().next().map(|c| c.to_ascii_uppercase());
                if !strong {
                    report.flag_weak += 1;
                    Disposition::Flag { reason: FlagReason::WeakPlurality, anchor: Some(node.clone()) }
                } else if !consistent || !clade_ok {
                    report.flag_inconsistent += 1;
                    Disposition::Flag { reason: FlagReason::ParentInconsistent, anchor: Some(node.clone()) }
                } else {
                    report.matched += 1;
                    if sn.is_backbone {
                        report.backbone_adopt += 1;
                    }
                    Disposition::Match { anchor: node.clone(), strength }
                }
            }
        };
        report.items.push(Classified { node: sn.name.clone(), disposition });
    }
    Ok(report)
}
