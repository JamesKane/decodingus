//! De-novo tree foundation loader.
//!
//! Ingests the normalized JSON emitted by `~/Genomics/ytree/bin/68_export_ingest.py`
//! (schema: `documents/proposals/denovo-tree-ingestion.md`) as the **sole tree
//! foundation** — the tree we built ourselves from genotypes, not an import to
//! graft onto. Greenfield by design: the caller clears the lineage
//! ([`crate::haplogroup::clear_dna`]) first; this inserts nodes, edges, and
//! defining-variant links, then recomputes the backbone.
//!
//! Builds the full lineage: topology + defining SNPs, sample-leaf placement of the
//! tips (`tree.haplogroup_sample`), and de-novo-vs-reference conflicts for curator
//! triage (`tree.denovo_conflict`).
//!
//! Variant identity: each defining SNP is matched to `core.variant` by **hs1
//! coordinate** (contig + position + allele-set), reusing the YBrowse-loaded
//! catalog so known SNPs keep their `canonical_name`; unmatched SNPs are minted
//! as de-novo coordinate-named variants.

use std::collections::{HashMap, HashSet};

use du_domain::enums::DnaType;
use serde::Deserialize;
use serde_json::json;
use sqlx::PgPool;
use uuid::Uuid;

use crate::{pg_enum_label, DbError, Page};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DenovoTree {
    pub chromosome: String,
    pub haplogroup_type: String,
    pub build: String,
    pub source: String,
    pub root: String,
    pub nodes: Vec<DenovoNode>,
    #[serde(default)]
    pub tips: Vec<DenovoTip>,
    #[serde(default)]
    pub conflicts: Vec<DenovoConflict>,
}

/// A de-novo-vs-reference placement conflict, surfaced for curator triage.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DenovoConflict {
    pub isogg: Option<String>,
    pub label: Option<String>,
    pub n_tips: i32,
    pub magnitude: i32,
    pub home_node: Option<String>,
    pub foreign_in: i32,
    pub members_away: i32,
}

/// A tree tip = a cohort sample, placed as a leaf under its `parent_node`.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DenovoTip {
    pub sample: String,
    pub parent_node: String,
    pub cohort: Option<String>,
    pub sex: Option<String>,
    pub terminal_label: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DenovoNode {
    pub id: String,
    pub parent: Option<String>,
    pub support: Option<i32>,
    pub branch_length: Option<f64>,
    pub label: Option<String>,
    pub isogg: Option<String>,
    pub markers_matched: Option<i32>,
    pub markers_expected: Option<i32>,
    pub n_mut: Option<i32>,
    pub n_reversion: Option<i32>,
    #[serde(default)]
    pub defining_variants: Vec<DenovoVariant>,
    /// SNPs from weakly-supported branches collapsed below this node — recorded as
    /// a tagged block in provenance, NOT as strict defining links (the node's other
    /// children don't carry them; their exact placement in the subtree is unresolved).
    #[serde(default)]
    pub unresolved_variants: Vec<DenovoVariant>,
}

#[derive(Debug, Deserialize)]
pub struct DenovoVariant {
    pub chrom: String,
    pub pos: i64,
    #[serde(rename = "ref")]
    pub ref_: String,
    pub alt: String,
    pub ancestral: String,
    pub derived: String,
    #[serde(default)]
    pub reversion: bool,
    pub polarity: Option<String>,
}

#[derive(Debug, Default)]
pub struct LoadReport {
    pub nodes: usize,
    pub edges: usize,
    pub variant_links: usize,
    pub variants_reused: usize,
    pub variants_created: usize,
    /// Collapsed-branch SNPs recorded as tagged provenance blocks (not links).
    pub unresolved_block: usize,
    /// Tips placed as `tree.haplogroup_sample` leaves under their terminal node.
    pub tips_placed: usize,
    /// New `core.biosample` rows minted for tips (vs reused by accession).
    pub biosamples_created: usize,
    /// De-novo-vs-reference conflicts recorded for curator triage.
    pub conflicts_loaded: usize,
}

/// UFBoot support → the `confidence_level` text bucket.
fn confidence(support: Option<i32>) -> &'static str {
    match support {
        Some(s) if s >= 95 => "HIGH",
        Some(s) if s >= 70 => "MEDIUM",
        Some(_) => "LOW",
        None => "UNKNOWN",
    }
}

/// Load a de-novo tree document as the tree foundation. Assumes `tree.*` is
/// already cleared (greenfield). Commits the topology, then recomputes backbone
/// and bumps the tree revision.
pub async fn load(pool: &PgPool, doc: &DenovoTree) -> Result<LoadReport, DbError> {
    let dna: DnaType = match doc.haplogroup_type.as_str() {
        "Y_DNA" => DnaType::YDna,
        "MT_DNA" => DnaType::MtDna,
        other => return Err(DbError::Decode(format!("unknown haplogroupType {other:?}"))),
    };
    let dna_label = pg_enum_label(&dna)?;
    let mut rep = LoadReport::default();

    let mut tx = pool.begin().await?;

    // 1. Resolve every defining SNP to a core.variant id (catalog reuse or mint),
    //    caching by (contig, pos, ancestral, derived). Records each node's links
    //    and remembers any catalog name (for SNP-based node naming below).
    let mut vcache: HashMap<(String, i64, String, String), (i64, Option<String>)> = HashMap::new();
    // node id -> Vec<(variant_id, ancestral, derived)>
    let mut node_links: HashMap<&str, Vec<(i64, &str, &str)>> = HashMap::new();
    // node id -> first catalog SNP name (by position order) for naming fallback
    let mut node_snp_name: HashMap<&str, String> = HashMap::new();

    for node in &doc.nodes {
        let mut links = Vec::with_capacity(node.defining_variants.len());
        // position-ordered so the naming fallback is deterministic
        let mut vars: Vec<&DenovoVariant> = node.defining_variants.iter().collect();
        vars.sort_by_key(|v| v.pos);
        for v in vars {
            let key = (v.chrom.clone(), v.pos, v.ancestral.clone(), v.derived.clone());
            let (vid, name) = if let Some(hit) = vcache.get(&key) {
                hit.clone()
            } else {
                let resolved = resolve_variant(&mut tx, v, &mut rep).await?;
                vcache.insert(key, resolved.clone());
                resolved
            };
            if let Some(n) = &name {
                node_snp_name.entry(node.id.as_str()).or_insert_with(|| n.clone());
            }
            links.push((vid, v.ancestral.as_str(), v.derived.as_str()));
        }
        node_links.insert(node.id.as_str(), links);
    }

    // 2. Assign a display name to each node: ISOGG/PhyloTree label → a catalog SNP
    //    name on the defining branch → the de-novo NodeN id. Disambiguate
    //    collisions with the (globally-unique) NodeN. Labeled nodes are processed
    //    first so they claim their name before fallbacks.
    let mut order: Vec<&DenovoNode> = doc.nodes.iter().collect();
    order.sort_by_key(|n| (n.label.is_none(), n.id.as_str().to_string()));
    let mut used: HashSet<String> = HashSet::new();
    let mut name_of: HashMap<&str, String> = HashMap::new();
    for node in order {
        let base = node
            .label
            .clone()
            .or_else(|| node_snp_name.get(node.id.as_str()).cloned())
            .unwrap_or_else(|| node.id.clone());
        let name = if used.insert(base.clone()) {
            base
        } else {
            // collision → suffix the unique de-novo id
            let alt = format!("{base} [{}]", node.id);
            used.insert(alt.clone());
            alt
        };
        name_of.insert(node.id.as_str(), name);
    }

    // 3. Insert nodes, building the NodeN -> db id map.
    let mut idmap: HashMap<&str, i64> = HashMap::new();
    for node in &doc.nodes {
        let name = &name_of[node.id.as_str()];
        // Collapsed-branch SNPs lifted to this node: a tagged provenance block, not
        // defining links (so they don't pollute the strict defining-SNP model).
        let unresolved: Vec<String> = node
            .unresolved_variants
            .iter()
            .map(|v| format!("{}:{}{}>{}", v.chrom, v.pos, v.ancestral, v.derived))
            .collect();
        rep.unresolved_block += unresolved.len();
        let prov = json!({
            "source": doc.source,
            "denovo_node": node.id,
            "isogg": node.isogg,
            "label": node.label,
            "support": node.support,
            "branch_length": node.branch_length,
            "markers_matched": node.markers_matched,
            "markers_expected": node.markers_expected,
            "n_mut": node.n_mut,
            "n_reversion": node.n_reversion,
            "unresolved_count": unresolved.len(),
            "unresolved_variants": unresolved,
            "aliases": node.isogg.iter().chain(node.label.iter()).filter(|a| *a != name).collect::<Vec<_>>(),
        });
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO tree.haplogroup \
               (name, haplogroup_type, source, confidence_level, is_backbone, provenance) \
             VALUES ($1, $2::core.dna_type, $3, $4, false, $5) RETURNING id",
        )
        .bind(name)
        .bind(&dna_label)
        .bind(&doc.source)
        .bind(confidence(node.support))
        .bind(&prov)
        .fetch_one(&mut *tx)
        .await?;
        idmap.insert(node.id.as_str(), id);
        rep.nodes += 1;
    }

    // 4. Insert edges (parent → child).
    for node in &doc.nodes {
        let Some(parent) = &node.parent else { continue };
        let child_id = idmap[node.id.as_str()];
        let parent_id = *idmap
            .get(parent.as_str())
            .ok_or_else(|| DbError::Decode(format!("node {} references unknown parent {parent}", node.id)))?;
        sqlx::query(
            "INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id, source) \
             VALUES ($1, $2, $3)",
        )
        .bind(child_id)
        .bind(parent_id)
        .bind(&doc.source)
        .execute(&mut *tx)
        .await?;
        rep.edges += 1;
    }

    // 5. Link defining variants to their node (the branch leading to it).
    for node in &doc.nodes {
        let hg_id = idmap[node.id.as_str()];
        for (vid, anc, der) in &node_links[node.id.as_str()] {
            sqlx::query(
                "INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id, ancestral_allele, derived_allele) \
                 VALUES ($1, $2, $3, $4)",
            )
            .bind(hg_id)
            .bind(vid)
            .bind(*anc)
            .bind(*der)
            .execute(&mut *tx)
            .await?;
            rep.variant_links += 1;
        }
    }

    // 6. Place tips as biosample leaves under their terminal (surviving) node. A
    //    sample (1000G/HGDP/…) is one biosample shared across lineages — get-or-create
    //    by accession; the placement is dna-scoped (cleared by clear_dna). The de-novo
    //    tree position is authoritative, so this is a direct placement, not a
    //    call-resolution (unlike tree_sample::recompute_placements).
    for tip in &doc.tips {
        let Some(&hg_id) = idmap.get(tip.parent_node.as_str()) else { continue };
        // Public reference panels (PRJEB*) → EXTERNAL/public; anything else (e.g. an
        // own genome) stays STANDARD/private.
        let (src, is_public) = match tip.cohort.as_deref() {
            Some(c) if c.starts_with("PRJEB") => ("EXTERNAL", true),
            _ => ("STANDARD", false),
        };
        let attrs = json!({ "cohort": tip.cohort, "sex": tip.sex, "denovo": true });
        let guid: Uuid = match sqlx::query_scalar(
            "INSERT INTO core.biosample (source, accession, center_name, source_attrs, is_public) \
             VALUES ($1::core.biosample_source, $2, $3, $4, $5) \
             ON CONFLICT (accession) WHERE accession IS NOT NULL DO NOTHING RETURNING sample_guid",
        )
        .bind(src)
        .bind(&tip.sample)
        .bind(tip.cohort.as_deref())
        .bind(&attrs)
        .bind(is_public)
        .fetch_optional(&mut *tx)
        .await?
        {
            Some(g) => {
                rep.biosamples_created += 1;
                g
            }
            None => {
                sqlx::query_scalar("SELECT sample_guid FROM core.biosample WHERE accession = $1")
                    .bind(&tip.sample)
                    .fetch_one(&mut *tx)
                    .await?
            }
        };
        let call = tip.terminal_label.clone().unwrap_or_else(|| tip.sample.clone());
        sqlx::query(
            "INSERT INTO tree.haplogroup_sample (sample_guid, dna_type, haplogroup_id, call_text, status, refreshed_at) \
             VALUES ($1, $2::core.dna_type, $3, $4, 'PLACED', now()) \
             ON CONFLICT (sample_guid, dna_type) DO UPDATE \
               SET haplogroup_id = EXCLUDED.haplogroup_id, call_text = EXCLUDED.call_text, \
                   status = 'PLACED', refreshed_at = now()",
        )
        .bind(guid)
        .bind(&dna_label)
        .bind(hg_id)
        .bind(&call)
        .execute(&mut *tx)
        .await?;
        rep.tips_placed += 1;
    }

    // 7. Record de-novo-vs-reference conflicts for curator triage (this dna's prior
    //    rows were cleared by clear_dna before the load).
    for c in &doc.conflicts {
        sqlx::query(
            "INSERT INTO tree.denovo_conflict \
               (dna_type, haplogroup, label, n_tips, magnitude, home_node, foreign_in, members_away, source) \
             VALUES ($1::core.dna_type, $2, $3, $4, $5, $6, $7, $8, $9)",
        )
        .bind(&dna_label)
        .bind(c.isogg.as_deref().unwrap_or("?"))
        .bind(c.label.as_deref())
        .bind(c.n_tips)
        .bind(c.magnitude)
        .bind(c.home_node.as_deref())
        .bind(c.foreign_in)
        .bind(c.members_away)
        .bind(&doc.source)
        .execute(&mut *tx)
        .await?;
        rep.conflicts_loaded += 1;
    }

    tx.commit().await?;

    // 8. Post-process (own transactions): backbone + revision bump.
    crate::haplogroup::recompute_backbone(pool, dna).await?;
    crate::tree_revision::bump(pool).await?;

    Ok(rep)
}

/// Resolve one de-novo SNP to a `core.variant` id: reuse a catalog row at the
/// same hs1 site (contig + position + allele-set, biallelic, polarity-agnostic),
/// else mint a de-novo coordinate-named variant. Returns `(id, canonical_name?)`.
async fn resolve_variant(
    tx: &mut sqlx::PgConnection,
    v: &DenovoVariant,
    rep: &mut LoadReport,
) -> Result<(i64, Option<String>), DbError> {
    // Catalog match by hs1 coordinate. The GIN @> handles the contig+position
    // prefilter; the allele-set check disambiguates multiallelic sites. Prefer a
    // named row over an unnamed one.
    if let Some((id, name)) = sqlx::query_as::<_, (i64, Option<String>)>(
        "SELECT id, canonical_name FROM core.variant \
         WHERE coordinates @> jsonb_build_object('hs1', jsonb_build_object('contig', $1::text, 'position', $2::bigint)) \
           AND defining_haplogroup_id IS NULL \
           AND ( (coordinates->'hs1'->>'ancestral' = $3 AND coordinates->'hs1'->>'derived' = $4) \
              OR (coordinates->'hs1'->>'ancestral' = $4 AND coordinates->'hs1'->>'derived' = $3) ) \
         ORDER BY (canonical_name IS NULL), id LIMIT 1",
    )
    .bind(&v.chrom)
    .bind(v.pos)
    .bind(&v.ancestral)
    .bind(&v.derived)
    .fetch_optional(&mut *tx)
    .await?
    {
        rep.variants_reused += 1;
        return Ok((id, name));
    }

    // Mint a de-novo, coordinate-named variant (hs1 frame). Deterministic synthetic
    // name so re-runs dedupe; UNNAMED so the curator can later fold it onto a real
    // name. Same no-op-write discipline as ensure_variant_by_coords.
    let synth = format!("{}:{}{}>{}", v.chrom, v.pos, v.ancestral, v.derived);
    let coords = json!({ "hs1": {
        "contig": v.chrom, "position": v.pos, "ancestral": v.ancestral, "derived": v.derived
    }});
    if let Some(id) = sqlx::query_scalar::<_, i64>(
        "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, coordinates) \
         VALUES ($1, 'SNP'::core.mutation_type, 'UNNAMED'::core.naming_status, $2) \
         ON CONFLICT (canonical_name, COALESCE(defining_haplogroup_id, -1)) WHERE canonical_name IS NOT NULL \
         DO NOTHING RETURNING id",
    )
    .bind(&synth)
    .bind(&coords)
    .fetch_optional(&mut *tx)
    .await?
    {
        rep.variants_created += 1;
        return Ok((id, Some(synth)));
    }
    let id = sqlx::query_scalar::<_, i64>(
        "SELECT id FROM core.variant WHERE canonical_name = $1 AND defining_haplogroup_id IS NULL",
    )
    .bind(&synth)
    .fetch_one(&mut *tx)
    .await?;
    Ok((id, Some(synth)))
}

/// A row of the curator de-novo-conflict queue.
#[derive(Debug, sqlx::FromRow)]
pub struct ConflictRow {
    pub id: i64,
    pub dna_type: String,
    pub haplogroup: String,
    pub label: Option<String>,
    pub n_tips: i32,
    pub magnitude: i32,
    pub home_node: Option<String>,
    pub foreign_in: i32,
    pub members_away: i32,
}

/// Paginated de-novo-vs-reference conflicts, worst (highest magnitude) first,
/// optionally filtered to one lineage. Read-only triage queue for `/curator/denovo-conflicts`.
pub async fn list_conflicts(
    pool: &PgPool,
    dna: Option<DnaType>,
    page: i64,
    page_size: i64,
) -> Result<Page<ConflictRow>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let dna_label = match dna {
        Some(d) => Some(pg_enum_label(&d)?),
        None => None,
    };
    let total: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.denovo_conflict WHERE ($1::text IS NULL OR dna_type::text = $1)",
    )
    .bind(&dna_label)
    .fetch_one(pool)
    .await?;
    let items: Vec<ConflictRow> = sqlx::query_as(
        "SELECT id, dna_type::text AS dna_type, haplogroup, label, n_tips, magnitude, \
                home_node, foreign_in, members_away \
         FROM tree.denovo_conflict WHERE ($1::text IS NULL OR dna_type::text = $1) \
         ORDER BY magnitude DESC, n_tips DESC, id LIMIT $2 OFFSET $3",
    )
    .bind(&dna_label)
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    Ok(Page { items, total, page: page.max(1), page_size: limit })
}
