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
use sqlx::PgPool;
use std::collections::HashMap;

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
        }
        tx.commit().await?;
    }
    Ok(rep)
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
