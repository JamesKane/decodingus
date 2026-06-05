//! Queries for `tree.haplogroup` + current parent/child edges. These back the
//! Y/MT tree views. "Current" edges are those with `valid_until IS NULL`.

use crate::{parse_pg_enum, pg_enum_label, DbError, Page};
use du_domain::enums::DnaType;
use du_domain::haplogroup::Haplogroup;
use du_domain::ids::HaplogroupId;
use sqlx::PgPool;

#[derive(sqlx::FromRow)]
struct HaplogroupRow {
    id: i64,
    name: String,
    haplogroup_type: String,
    lineage: Option<String>,
    source: Option<String>,
    confidence_level: Option<String>,
    formed_ybp: Option<i32>,
    tmrca_ybp: Option<i32>,
    provenance: serde_json::Value,
}

impl HaplogroupRow {
    fn into_domain(self) -> Result<Haplogroup, DbError> {
        Ok(Haplogroup {
            id: HaplogroupId(self.id),
            name: self.name,
            haplogroup_type: parse_pg_enum(&self.haplogroup_type, "haplogroup_type")?,
            lineage: self.lineage,
            source: self.source,
            confidence_level: self.confidence_level,
            formed_ybp: self.formed_ybp,
            tmrca_ybp: self.tmrca_ybp,
            provenance: self.provenance,
        })
    }
}

// Columns qualified with alias `h` so joins (which also carry an `id`) stay
// unambiguous; output column names still match HaplogroupRow's fields.
const COLS: &str = "h.id, h.name, h.haplogroup_type::text AS haplogroup_type, h.lineage, h.source, \
    h.confidence_level, h.formed_ybp, h.tmrca_ybp, h.provenance";

fn collect(rows: Vec<HaplogroupRow>) -> Result<Vec<Haplogroup>, DbError> {
    rows.into_iter().map(HaplogroupRow::into_domain).collect()
}

pub async fn get_by_id(pool: &PgPool, id: HaplogroupId) -> Result<Option<Haplogroup>, DbError> {
    let row: Option<HaplogroupRow> =
        sqlx::query_as(&format!("SELECT {COLS} FROM tree.haplogroup h WHERE h.id = $1"))
            .bind(id.0)
            .fetch_optional(pool)
            .await?;
    row.map(HaplogroupRow::into_domain).transpose()
}

pub async fn get_by_name(
    pool: &PgPool,
    name: &str,
    dna_type: DnaType,
) -> Result<Option<Haplogroup>, DbError> {
    let row: Option<HaplogroupRow> = sqlx::query_as(&format!(
        "SELECT {COLS} FROM tree.haplogroup h WHERE h.name = $1 AND h.haplogroup_type::text = $2"
    ))
    .bind(name)
    .bind(pg_enum_label(&dna_type)?)
    .fetch_optional(pool)
    .await?;
    row.map(HaplogroupRow::into_domain).transpose()
}

/// Direct children of a haplogroup (current edges), ordered by name.
pub async fn children(pool: &PgPool, parent: HaplogroupId) -> Result<Vec<Haplogroup>, DbError> {
    let rows: Vec<HaplogroupRow> = sqlx::query_as(&format!(
        "SELECT {COLS} FROM tree.haplogroup h \
         JOIN tree.haplogroup_relationship r ON r.child_haplogroup_id = h.id \
         WHERE r.parent_haplogroup_id = $1 AND r.valid_until IS NULL AND h.valid_until IS NULL \
         ORDER BY h.name"
    ))
    .bind(parent.0)
    .fetch_all(pool)
    .await?;
    collect(rows)
}

/// Flat, paginated, optionally name-filtered / lineage-filtered list for the
/// curator UI (the public tree views use roots/children instead).
pub async fn list_paginated(
    pool: &PgPool,
    query: Option<&str>,
    dna_type: Option<DnaType>,
    page: i64,
    page_size: i64,
) -> Result<Page<Haplogroup>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let like = query
        .map(str::trim)
        .filter(|q| !q.is_empty())
        .map(|q| format!("%{q}%"));
    let dna = dna_type.map(|d| pg_enum_label(&d)).transpose()?;

    // $1 = name filter (NULL = any), $2 = dna label (NULL = any).
    let where_sql = "WHERE ($1::text IS NULL OR h.name ILIKE $1) \
                     AND ($2::text IS NULL OR h.haplogroup_type::text = $2)";

    let total: i64 = sqlx::query_scalar(&format!(
        "SELECT count(*) FROM tree.haplogroup h {where_sql}"
    ))
    .bind(&like)
    .bind(&dna)
    .fetch_one(pool)
    .await?;

    let rows: Vec<HaplogroupRow> = sqlx::query_as(&format!(
        "SELECT {COLS} FROM tree.haplogroup h {where_sql} ORDER BY h.name LIMIT $3 OFFSET $4"
    ))
    .bind(&like)
    .bind(&dna)
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;

    Ok(Page { items: collect(rows)?, total, page: page.max(1), page_size: limit })
}

/// Create a haplogroup; returns the new id.
pub async fn create(
    pool: &PgPool,
    name: &str,
    dna_type: DnaType,
    lineage: Option<&str>,
    source: Option<&str>,
    formed_ybp: Option<i32>,
    tmrca_ybp: Option<i32>,
) -> Result<HaplogroupId, DbError> {
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type, lineage, source, formed_ybp, tmrca_ybp) \
         VALUES ($1, $2::core.dna_type, $3, $4, $5, $6) RETURNING id",
    )
    .bind(name)
    .bind(pg_enum_label(&dna_type)?)
    .bind(lineage)
    .bind(source)
    .bind(formed_ybp)
    .bind(tmrca_ybp)
    .fetch_one(pool)
    .await?;
    Ok(HaplogroupId(id))
}

/// Update editable haplogroup fields. Returns whether a row was affected.
pub async fn update(
    pool: &PgPool,
    id: HaplogroupId,
    name: &str,
    lineage: Option<&str>,
    source: Option<&str>,
    formed_ybp: Option<i32>,
    tmrca_ybp: Option<i32>,
) -> Result<bool, DbError> {
    let affected = sqlx::query(
        "UPDATE tree.haplogroup SET name=$2, lineage=$3, source=$4, formed_ybp=$5, tmrca_ybp=$6 WHERE id=$1",
    )
    .bind(id.0)
    .bind(name)
    .bind(lineage)
    .bind(source)
    .bind(formed_ybp)
    .bind(tmrca_ybp)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(affected > 0)
}

/// Whether the haplogroup participates in any current relationship (a guard the
/// curator UI uses before allowing deletion).
pub async fn has_current_edges(pool: &PgPool, id: HaplogroupId) -> Result<bool, DbError> {
    let n: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup_relationship \
         WHERE (child_haplogroup_id = $1 OR parent_haplogroup_id = $1) AND valid_until IS NULL",
    )
    .bind(id.0)
    .fetch_one(pool)
    .await?;
    Ok(n > 0)
}

/// Delete a haplogroup. Returns whether a row was removed.
pub async fn delete(pool: &PgPool, id: HaplogroupId) -> Result<bool, DbError> {
    let affected = sqlx::query("DELETE FROM tree.haplogroup WHERE id=$1")
        .bind(id.0)
        .execute(pool)
        .await?
        .rows_affected();
    Ok(affected > 0)
}

/// Root haplogroups of a lineage: no current edge to a parent.
pub async fn roots(pool: &PgPool, dna_type: DnaType) -> Result<Vec<Haplogroup>, DbError> {
    let rows: Vec<HaplogroupRow> = sqlx::query_as(&format!(
        "SELECT {COLS} FROM tree.haplogroup h \
         WHERE h.haplogroup_type::text = $1 AND h.valid_until IS NULL \
           AND NOT EXISTS ( \
             SELECT 1 FROM tree.haplogroup_relationship r \
             WHERE r.child_haplogroup_id = h.id AND r.parent_haplogroup_id IS NOT NULL \
               AND r.valid_until IS NULL) \
         ORDER BY h.name"
    ))
    .bind(pg_enum_label(&dna_type)?)
    .fetch_all(pool)
    .await?;
    collect(rows)
}

/// Load the current production tree for a lineage as a nested
/// `du_domain::merge::ExistingNode` forest (current nodes/edges/variant links
/// only). Backs the merge algorithm's "existing tree" input.
pub async fn existing_tree(
    pool: &PgPool,
    dna_type: DnaType,
) -> Result<Vec<du_domain::merge::ExistingNode>, DbError> {
    let dna = pg_enum_label(&dna_type)?;
    let nodes: Vec<(i64, String)> = sqlx::query_as(
        "SELECT id, name FROM tree.haplogroup WHERE haplogroup_type::text = $1 AND valid_until IS NULL",
    )
    .bind(&dna)
    .fetch_all(pool)
    .await?;
    let edges: Vec<(i64, Option<i64>)> = sqlx::query_as(
        "SELECT r.child_haplogroup_id, r.parent_haplogroup_id FROM tree.haplogroup_relationship r \
         JOIN tree.haplogroup h ON h.id = r.child_haplogroup_id \
         WHERE r.valid_until IS NULL AND h.haplogroup_type::text = $1 AND h.valid_until IS NULL",
    )
    .bind(&dna)
    .fetch_all(pool)
    .await?;
    // Merge matches branches by defining-SNP *name*, so UNNAMED variants
    // (canonical_name NULL — e.g. folded legacy homoplasy/duplicate rows)
    // contribute nothing and are excluded (also keeps the column non-null).
    let vars: Vec<(i64, String)> = sqlx::query_as(
        "SELECT hv.haplogroup_id, v.canonical_name FROM tree.haplogroup_variant hv \
         JOIN core.variant v ON v.id = hv.variant_id \
         JOIN tree.haplogroup h ON h.id = hv.haplogroup_id \
         WHERE hv.valid_until IS NULL AND h.haplogroup_type::text = $1 AND h.valid_until IS NULL \
           AND v.canonical_name IS NOT NULL",
    )
    .bind(&dna)
    .fetch_all(pool)
    .await?;

    use std::collections::BTreeMap;
    let name_of: BTreeMap<i64, String> = nodes.iter().cloned().collect();
    let mut vars_of: BTreeMap<i64, Vec<String>> = BTreeMap::new();
    for (hid, name) in vars {
        vars_of.entry(hid).or_default().push(name);
    }
    let parent_of: BTreeMap<i64, Option<i64>> = edges.into_iter().collect();
    let mut children_of: BTreeMap<i64, Vec<i64>> = BTreeMap::new();
    for (&id, parent) in &parent_of {
        if let Some(p) = parent {
            children_of.entry(*p).or_default().push(id);
        }
    }
    // Roots: nodes with no current parent edge.
    let mut roots: Vec<i64> = name_of
        .keys()
        .copied()
        .filter(|id| parent_of.get(id).copied().flatten().is_none())
        .collect();
    roots.sort_unstable();

    fn build(
        id: i64,
        depth: u16,
        name_of: &std::collections::BTreeMap<i64, String>,
        vars_of: &std::collections::BTreeMap<i64, Vec<String>>,
        children_of: &std::collections::BTreeMap<i64, Vec<i64>>,
    ) -> du_domain::merge::ExistingNode {
        let children = if depth > 1000 {
            Vec::new()
        } else {
            let mut kids = children_of.get(&id).cloned().unwrap_or_default();
            kids.sort_unstable();
            kids.into_iter().map(|c| build(c, depth + 1, name_of, vars_of, children_of)).collect()
        };
        du_domain::merge::ExistingNode {
            id,
            name: name_of.get(&id).cloned().unwrap_or_default(),
            variants: vars_of.get(&id).cloned().unwrap_or_default(),
            children,
        }
    }

    Ok(roots.into_iter().map(|r| build(r, 0, &name_of, &vars_of, &children_of)).collect())
}

/// Ancestor chain of a haplogroup, ordered root → immediate parent (the node
/// itself is excluded). Backs the tree-view breadcrumb trail.
pub async fn ancestors(pool: &PgPool, id: HaplogroupId) -> Result<Vec<(HaplogroupId, String)>, DbError> {
    // Walk parent edges upward, tagging each step with its distance so we can
    // return them root-first.
    let rows: Vec<(i64, String, i32)> = sqlx::query_as(
        "WITH RECURSIVE up AS ( \
            SELECT r.parent_haplogroup_id AS id, 1 AS dist \
            FROM tree.haplogroup_relationship r \
            WHERE r.child_haplogroup_id = $1 AND r.parent_haplogroup_id IS NOT NULL AND r.valid_until IS NULL \
          UNION ALL \
            SELECT r.parent_haplogroup_id, up.dist + 1 \
            FROM tree.haplogroup_relationship r \
            JOIN up ON up.id = r.child_haplogroup_id \
            WHERE r.parent_haplogroup_id IS NOT NULL AND r.valid_until IS NULL AND up.dist < 1000) \
         SELECT h.id, h.name, up.dist FROM up JOIN tree.haplogroup h ON h.id = up.id \
         WHERE h.valid_until IS NULL ORDER BY up.dist DESC",
    )
    .bind(id.0)
    .fetch_all(pool)
    .await?;
    Ok(rows.into_iter().map(|(id, name, _)| (HaplogroupId(id), name)).collect())
}

/// One node of a depth-bounded tree window for the public tree view.
#[derive(Debug, Clone)]
pub struct WindowNode {
    pub id: i64,
    pub name: String,
    pub parent_id: Option<i64>,
    pub depth: i32,
    pub formed_ybp: Option<i32>,
    pub tmrca_ybp: Option<i32>,
    /// `source == 'backbone'` — the established spine, rendered green.
    pub is_backbone: bool,
    /// Edited within the last year — rendered amber.
    pub is_recent: bool,
    /// Defining-variant count (current links).
    pub variant_count: i64,
    /// True when this node sits at the window boundary AND has children that
    /// were not included — the view shows a "+" affordance to re-root into it.
    pub has_hidden: bool,
}

/// The subtree under `root_name`, limited to `max_depth` levels below the root
/// (root = depth 0). Follows current edges. Returns a flat list with parent
/// linkage, per-node variant counts, backbone/recency flags, and a `has_hidden`
/// marker on boundary nodes whose children were clipped. The web layer nests it.
pub async fn subtree_window(
    pool: &PgPool,
    dna_type: DnaType,
    root_name: &str,
    max_depth: i32,
) -> Result<Vec<WindowNode>, DbError> {
    #[derive(sqlx::FromRow)]
    struct WinRow {
        id: i64,
        name: String,
        parent_id: Option<i64>,
        depth: i32,
        formed_ybp: Option<i32>,
        tmrca_ybp: Option<i32>,
        is_backbone: bool,
        is_recent: bool,
        variant_count: i64,
        has_hidden: bool,
    }
    let rows: Vec<WinRow> = sqlx::query_as(
        "WITH RECURSIVE sub AS ( \
            SELECT h.id, h.name, NULL::bigint AS parent_id, 0 AS depth \
            FROM tree.haplogroup h \
            WHERE h.haplogroup_type::text = $1 AND h.valid_until IS NULL AND h.name = $2 \
          UNION ALL \
            SELECT c.id, c.name, r.parent_haplogroup_id, sub.depth + 1 \
            FROM tree.haplogroup c \
            JOIN tree.haplogroup_relationship r \
              ON r.child_haplogroup_id = c.id AND r.valid_until IS NULL \
            JOIN sub ON sub.id = r.parent_haplogroup_id \
            WHERE c.valid_until IS NULL AND sub.depth < $3) \
         SELECT s.id, s.name, s.parent_id, s.depth, h.formed_ybp, h.tmrca_ybp, \
                h.is_backbone, \
                (h.valid_from > now() - interval '1 year') AS is_recent, \
                (SELECT count(*) FROM tree.haplogroup_variant hv \
                   WHERE hv.haplogroup_id = s.id AND hv.valid_until IS NULL) AS variant_count, \
                (s.depth >= $3 AND EXISTS ( \
                   SELECT 1 FROM tree.haplogroup_relationship r2 \
                   WHERE r2.parent_haplogroup_id = s.id AND r2.valid_until IS NULL)) AS has_hidden \
         FROM sub s JOIN tree.haplogroup h ON h.id = s.id \
         ORDER BY s.depth, s.name",
    )
    .bind(pg_enum_label(&dna_type)?)
    .bind(root_name)
    .bind(max_depth)
    .fetch_all(pool)
    .await?;
    Ok(rows
        .into_iter()
        .map(|r| WindowNode {
            id: r.id,
            name: r.name,
            parent_id: r.parent_id,
            depth: r.depth,
            formed_ybp: r.formed_ybp,
            tmrca_ybp: r.tmrca_ybp,
            is_backbone: r.is_backbone,
            is_recent: r.is_recent,
            variant_count: r.variant_count,
            has_hidden: r.has_hidden,
        })
        .collect())
}

/// Result of [`reconcile_tilde_twins`].
#[derive(Debug, Default)]
pub struct TwinReconcile {
    /// Sibling twins folded into their non-`~` survivor.
    pub folded: i64,
    /// Cross-parent `X`/`X~` pairs left untouched (possible ISOGG curation) —
    /// surfaced for review rather than merged automatically.
    pub skipped: Vec<String>,
}

/// Reconcile ISOGG stitch artifacts where one clade was split into a
/// SNP-carrying node `X` and a subtree-carrying provisional twin `X~` **under
/// the same parent** (a SNP search for `X`'s marker would otherwise land on the
/// childless `X` while its subtree hangs off the invisible sibling `X~`).
///
/// Folds each same-parent sibling twin into its survivor: reparents `X~`'s
/// children to `X`, unions `X~`'s variants into `X`, and removes `X~`. Only
/// same-parent siblings are changed — cross-parent `X`/`X~` pairs (which may be
/// deliberate ISOGG curation) are returned in `skipped`, and genuine standalone
/// `~` provisional nodes are left alone.
pub async fn reconcile_tilde_twins(pool: &PgPool, dna_type: DnaType) -> Result<TwinReconcile, DbError> {
    let dna = pg_enum_label(&dna_type)?;
    let mut tx = pool.begin().await?;

    // Cross-parent twins: report, do not change.
    let skipped: Vec<String> = sqlx::query_scalar(
        "SELECT t.name FROM tree.haplogroup t \
         JOIN tree.haplogroup s ON s.name = left(t.name, length(t.name)-1) \
              AND s.haplogroup_type = t.haplogroup_type AND s.valid_until IS NULL \
         JOIN tree.haplogroup_relationship rt ON rt.child_haplogroup_id = t.id AND rt.valid_until IS NULL \
         JOIN tree.haplogroup_relationship rs ON rs.child_haplogroup_id = s.id AND rs.valid_until IS NULL \
         WHERE t.haplogroup_type::text = $1 AND t.valid_until IS NULL AND t.name LIKE '%~' \
           AND rt.parent_haplogroup_id <> rs.parent_haplogroup_id \
         ORDER BY t.name",
    )
    .bind(&dna)
    .fetch_all(&mut *tx)
    .await?;

    // Same-parent sibling twins → the fold set.
    sqlx::query(
        "CREATE TEMP TABLE _tw ON COMMIT DROP AS \
         SELECT t.id AS twin_id, s.id AS surv_id FROM tree.haplogroup t \
         JOIN tree.haplogroup s ON s.name = left(t.name, length(t.name)-1) \
              AND s.haplogroup_type = t.haplogroup_type AND s.valid_until IS NULL \
         JOIN tree.haplogroup_relationship rt ON rt.child_haplogroup_id = t.id AND rt.valid_until IS NULL \
         JOIN tree.haplogroup_relationship rs ON rs.child_haplogroup_id = s.id AND rs.valid_until IS NULL \
         WHERE t.haplogroup_type::text = $1 AND t.valid_until IS NULL AND t.name LIKE '%~' \
           AND rt.parent_haplogroup_id = rs.parent_haplogroup_id",
    )
    .bind(&dna)
    .execute(&mut *tx)
    .await?;
    let folded: i64 = sqlx::query_scalar("SELECT count(*) FROM _tw").fetch_one(&mut *tx).await?;

    // Reparent each twin's children onto its survivor (current edges).
    sqlx::query(
        "UPDATE tree.haplogroup_relationship r SET parent_haplogroup_id = tw.surv_id \
         FROM _tw tw WHERE r.parent_haplogroup_id = tw.twin_id AND r.valid_until IS NULL",
    )
    .execute(&mut *tx)
    .await?;

    // Union the twin's defining variants into the survivor (skip dupes).
    sqlx::query(
        "INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) \
         SELECT tw.surv_id, hv.variant_id FROM _tw tw \
         JOIN tree.haplogroup_variant hv ON hv.haplogroup_id = tw.twin_id AND hv.valid_until IS NULL \
         WHERE NOT EXISTS (SELECT 1 FROM tree.haplogroup_variant e \
             WHERE e.haplogroup_id = tw.surv_id AND e.variant_id = hv.variant_id AND e.valid_until IS NULL)",
    )
    .execute(&mut *tx)
    .await?;

    // Remove the now-empty twin nodes (variant links, all edges, the row).
    sqlx::query("DELETE FROM tree.haplogroup_variant WHERE haplogroup_id IN (SELECT twin_id FROM _tw)")
        .execute(&mut *tx)
        .await?;
    sqlx::query(
        "DELETE FROM tree.haplogroup_relationship \
         WHERE child_haplogroup_id IN (SELECT twin_id FROM _tw) \
            OR parent_haplogroup_id IN (SELECT twin_id FROM _tw)",
    )
    .execute(&mut *tx)
    .await?;
    sqlx::query("DELETE FROM tree.haplogroup WHERE id IN (SELECT twin_id FROM _tw)")
        .execute(&mut *tx)
        .await?;

    tx.commit().await?;
    Ok(TwinReconcile { folded, skipped })
}

/// Mark the **backbone** of a lineage: the computed ISOGG spine (single-letter
/// major clades `A`–`T` + every ancestor up to the root) **unioned with curated
/// backbone** adopted from a source tree (nodes carrying a
/// `provenance.backbone_source` marker, stamped by the SNP-graft enrich/graft
/// writers). Recomputed wholesale (clears then sets) so the *computed* spine
/// stays correct as the tree changes, while curated flags are preserved.
/// Returns the number of backbone nodes.
pub async fn recompute_backbone(pool: &PgPool, dna_type: DnaType) -> Result<i64, DbError> {
    let dna = pg_enum_label(&dna_type)?;
    sqlx::query(
        "WITH RECURSIVE seeds AS ( \
            SELECT id FROM tree.haplogroup \
            WHERE haplogroup_type::text = $1 AND valid_until IS NULL AND name ~ '^[A-Z]$' \
         ), up AS ( \
            SELECT id FROM seeds \
          UNION \
            SELECT r.parent_haplogroup_id FROM tree.haplogroup_relationship r \
            JOIN up ON up.id = r.child_haplogroup_id \
            WHERE r.parent_haplogroup_id IS NOT NULL AND r.valid_until IS NULL \
         ) \
         UPDATE tree.haplogroup h \
            SET is_backbone = (h.id IN (SELECT id FROM up)) OR (h.provenance ? 'backbone_source') \
          WHERE h.haplogroup_type::text = $1 AND h.valid_until IS NULL",
    )
    .bind(&dna)
    .execute(pool)
    .await?;
    let n: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM tree.haplogroup WHERE haplogroup_type::text = $1 AND valid_until IS NULL AND is_backbone",
    )
    .bind(&dna)
    .fetch_one(pool)
    .await?;
    Ok(n)
}

/// Summary of a recurrence scrub: how many multi-linked variants were examined,
/// how many were recurrent (links off a single lineage), the off-lineage links
/// soft-deleted, and a few human-readable samples.
#[derive(Debug, Clone)]
pub struct ScrubReport {
    pub variants_examined: usize,
    pub variants_scrubbed: usize,
    pub links_removed: usize,
    pub samples: Vec<String>,
}

/// Remove "recurrent" (homoplasic / ASR-scatter) defining-variant links. A single
/// variant linked to haplogroups that do NOT all lie on one ancestor-descendant
/// lineage is treated as recurrent: keep only the link(s) on its primary
/// (most-concentrated) lineage and soft-delete the off-lineage occurrences. Uses
/// the tree's ancestry, not names — so `CTS9108` keeps its O-lineage and drops the
/// scattered I/J occurrences. `apply=false` reports without writing.
pub async fn scrub_recurrent_links(
    pool: &PgPool,
    dna_type: DnaType,
    apply: bool,
) -> Result<ScrubReport, DbError> {
    use std::collections::{HashMap, HashSet};
    let dna = pg_enum_label(&dna_type)?;

    // Parent edges (child -> parent) for this DNA tree.
    let edges: Vec<(i64, Option<i64>)> = sqlx::query_as(
        "SELECT r.child_haplogroup_id, r.parent_haplogroup_id \
           FROM tree.haplogroup_relationship r \
           JOIN tree.haplogroup h ON h.id = r.child_haplogroup_id AND h.valid_until IS NULL \
          WHERE r.valid_until IS NULL AND h.haplogroup_type::text = $1",
    )
    .bind(&dna)
    .fetch_all(pool)
    .await?;
    let mut parent_of: HashMap<i64, i64> = HashMap::new();
    for (c, p) in edges {
        if let Some(p) = p {
            parent_of.insert(c, p);
        }
    }

    // Current defining-variant links: (link_id, variant_id, haplogroup_id, hg_name, variant_name).
    let links: Vec<(i64, i64, i64, String, Option<String>)> = sqlx::query_as(
        "SELECT hv.id, hv.variant_id, hv.haplogroup_id, h.name, v.canonical_name \
           FROM tree.haplogroup_variant hv \
           JOIN tree.haplogroup h ON h.id = hv.haplogroup_id AND h.valid_until IS NULL \
           JOIN core.variant v ON v.id = hv.variant_id \
          WHERE hv.valid_until IS NULL AND h.haplogroup_type::text = $1",
    )
    .bind(&dna)
    .fetch_all(pool)
    .await?;

    // Group links by variant: variant_id -> (variant_name, [(link_id, hg_id, hg_name)]).
    let mut by_variant: HashMap<i64, (Option<String>, Vec<(i64, i64, String)>)> = HashMap::new();
    for (lid, vid, hid, hname, vname) in links {
        let e = by_variant.entry(vid).or_insert_with(|| (vname, Vec::new()));
        e.1.push((lid, hid, hname));
    }

    // node + all its ancestors (root-ward path), with a cycle guard.
    let path_to_root = |start: i64| -> HashSet<i64> {
        let mut seen = HashSet::new();
        let mut cur = Some(start);
        while let Some(n) = cur {
            if !seen.insert(n) {
                break;
            }
            cur = parent_of.get(&n).copied();
        }
        seen
    };

    let mut to_remove: Vec<i64> = Vec::new();
    let mut variants_examined = 0usize;
    let mut variants_scrubbed = 0usize;
    let mut samples: Vec<String> = Vec::new();

    for (vname, group) in by_variant.values() {
        if group.len() < 2 {
            continue;
        }
        variants_examined += 1;
        let hids: Vec<i64> = group.iter().map(|(_, h, _)| *h).collect();
        let vn = vname.as_deref().unwrap_or("");
        // The linked node whose root-ward path captures the most other linked
        // nodes is the tip of the primary lineage. When concentration ties (e.g.
        // a fully-scattered recurrent SNP, every count == 1), prefer the branch
        // NAMED after the SNP (`CTS9108` → `O-CTS9108`), then the deeper node.
        let mut best_path: HashSet<i64> = HashSet::new();
        let mut best_key = (0usize, false, 0usize); // (concentration, self_named, depth)
        for (_, hid, hname) in group {
            let path = path_to_root(*hid);
            let count = hids.iter().filter(|h| path.contains(h)).count();
            let self_named = !vn.is_empty()
                && hname.split(['-', '/']).any(|seg| seg.eq_ignore_ascii_case(vn));
            let key = (count, self_named, path.len());
            if key > best_key {
                best_key = key;
                best_path = path;
            }
        }
        let best_count = best_key.0;
        if best_count == group.len() {
            continue; // all links on one lineage → legitimate chain, keep all
        }
        variants_scrubbed += 1;
        let mut dropped: Vec<String> = Vec::new();
        for (lid, hid, hname) in group {
            if !best_path.contains(hid) {
                to_remove.push(*lid);
                dropped.push(hname.clone());
            }
        }
        if samples.len() < 12 {
            let v = vname.as_deref().unwrap_or("(unnamed)");
            samples.push(format!("{v}: dropped {}", dropped.join(", ")));
        }
    }

    let mut links_removed = 0usize;
    if apply {
        for chunk in to_remove.chunks(1000) {
            links_removed += sqlx::query(
                "UPDATE tree.haplogroup_variant SET valid_until = now() WHERE id = ANY($1)",
            )
            .bind(chunk)
            .execute(pool)
            .await?
            .rows_affected() as usize;
        }
    } else {
        links_removed = to_remove.len();
    }

    Ok(ScrubReport {
        variants_examined,
        variants_scrubbed,
        links_removed,
        samples,
    })
}

/// Store a haplogroup's alternate (deprecated) names under `provenance.aliases`
/// for alternate search. Replaces any existing alias list.
pub async fn set_aliases(
    pool: &PgPool,
    name: &str,
    dna_type: DnaType,
    aliases: &[String],
) -> Result<bool, DbError> {
    let json = serde_json::Value::from(aliases.to_vec());
    let affected = sqlx::query(
        "UPDATE tree.haplogroup \
            SET provenance = jsonb_set(COALESCE(provenance, '{}'::jsonb), '{aliases}', $3, true) \
          WHERE name = $1 AND haplogroup_type::text = $2 AND valid_until IS NULL",
    )
    .bind(name)
    .bind(pg_enum_label(&dna_type)?)
    .bind(json)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(affected > 0)
}

/// Resolve a tree-search query to a haplogroup name: try a direct name match,
/// then an alternate (old ISOGG) name in `provenance.aliases`, then a defining
/// variant name; return the most-recent match. `None` if nothing matches.
pub async fn resolve_name_or_variant(
    pool: &PgPool,
    query: &str,
    dna_type: DnaType,
) -> Result<Option<String>, DbError> {
    let dna = pg_enum_label(&dna_type)?;
    let q = query.trim();
    // Direct name hit.
    if let Some(h) = get_by_name(pool, q, dna_type).await? {
        return Ok(Some(h.name));
    }
    // Alternate (deprecated ISOGG) name → current name.
    let by_alias: Option<String> = sqlx::query_scalar(
        "SELECT name FROM tree.haplogroup \
         WHERE haplogroup_type::text = $1 AND valid_until IS NULL \
           AND jsonb_exists(provenance->'aliases', $2) \
         ORDER BY valid_from DESC LIMIT 1",
    )
    .bind(&dna)
    .bind(q)
    .fetch_optional(pool)
    .await?;
    if by_alias.is_some() {
        return Ok(by_alias);
    }
    // Variant name → defining haplogroup (latest by valid_from).
    let name: Option<String> = sqlx::query_scalar(
        "SELECT h.name FROM tree.haplogroup h \
         JOIN tree.haplogroup_variant hv ON hv.haplogroup_id = h.id AND hv.valid_until IS NULL \
         JOIN core.variant v ON v.id = hv.variant_id \
         WHERE h.haplogroup_type::text = $1 AND h.valid_until IS NULL \
           AND lower(v.canonical_name) = lower($2) \
         ORDER BY h.valid_from DESC LIMIT 1",
    )
    .bind(&dna)
    .bind(q)
    .fetch_optional(pool)
    .await?;
    Ok(name)
}

/// A defining variant of a haplogroup, for the SNP-detail sidebar. Carries this
/// branch's ancestral->derived transition (ASR) and whether the SNP is recurrent
/// (occurs on other branches too — homoplasy).
#[derive(Debug, Clone)]
pub struct VariantInfo {
    /// NULL for UNNAMED variants (e.g. a homoplasy site whose name went to the
    /// primary locus); the UI falls back to an alias.
    pub canonical_name: Option<String>,
    pub mutation_type: String,
    pub aliases: serde_json::Value,
    pub coordinates: serde_json::Value,
    /// This branch's ancestral/derived alleles (NULL for legacy/forward links).
    pub link_ancestral: Option<String>,
    pub link_derived: Option<String>,
    /// True if this SNP also defines/occurs on another current branch.
    pub recurrent: bool,
}

/// All current defining variants of the named haplogroup, ordered by name.
pub async fn variants_of(
    pool: &PgPool,
    name: &str,
    dna_type: DnaType,
) -> Result<Vec<VariantInfo>, DbError> {
    #[derive(sqlx::FromRow)]
    struct Row {
        canonical_name: Option<String>,
        mutation_type: String,
        aliases: serde_json::Value,
        coordinates: serde_json::Value,
        link_ancestral: Option<String>,
        link_derived: Option<String>,
        recurrent: bool,
    }
    let rows: Vec<Row> = sqlx::query_as(
        "SELECT v.canonical_name, v.mutation_type::text AS mutation_type, v.aliases, v.coordinates, \
                hv.ancestral_allele AS link_ancestral, hv.derived_allele AS link_derived, \
                EXISTS (SELECT 1 FROM tree.haplogroup_variant hv2 \
                        WHERE hv2.variant_id = v.id AND hv2.valid_until IS NULL \
                          AND hv2.haplogroup_id <> hv.haplogroup_id) AS recurrent \
         FROM core.variant v \
         JOIN tree.haplogroup_variant hv ON hv.variant_id = v.id AND hv.valid_until IS NULL \
         JOIN tree.haplogroup h ON h.id = hv.haplogroup_id \
         WHERE h.name = $1 AND h.haplogroup_type::text = $2 AND h.valid_until IS NULL \
         ORDER BY v.canonical_name",
    )
    .bind(name)
    .bind(pg_enum_label(&dna_type)?)
    .fetch_all(pool)
    .await?;
    Ok(rows
        .into_iter()
        .map(|r| VariantInfo {
            canonical_name: r.canonical_name,
            mutation_type: r.mutation_type,
            aliases: r.aliases,
            coordinates: r.coordinates,
            link_ancestral: r.link_ancestral,
            link_derived: r.link_derived,
            recurrent: r.recurrent,
        })
        .collect())
}

/// A node in a subtree fetch: the haplogroup plus its current parent (`None` at
/// the subtree root). The JSON tree API assembles the nesting in-process.
#[derive(Debug, Clone)]
pub struct SubtreeNode {
    pub id: i64,
    pub name: String,
    pub parent_id: Option<i64>,
    pub haplogroup_type: String,
    pub formed_ybp: Option<i32>,
    pub tmrca_ybp: Option<i32>,
}

/// All haplogroups in the subtree under `root_name` (or every root of the
/// lineage when `None`), following current edges (`valid_until IS NULL`), as a
/// flat list with parent linkage.
pub async fn subtree(
    pool: &PgPool,
    dna_type: DnaType,
    root_name: Option<&str>,
) -> Result<Vec<SubtreeNode>, DbError> {
    #[derive(sqlx::FromRow)]
    struct SubRow {
        id: i64,
        name: String,
        parent_id: Option<i64>,
        haplogroup_type: String,
        formed_ybp: Option<i32>,
        tmrca_ybp: Option<i32>,
    }
    let rows: Vec<SubRow> = sqlx::query_as(
        "WITH RECURSIVE sub AS ( \
            SELECT h.id, h.name, NULL::bigint AS parent_id, h.haplogroup_type::text AS haplogroup_type, \
                   h.formed_ybp, h.tmrca_ybp \
            FROM tree.haplogroup h \
            WHERE h.haplogroup_type::text = $1 AND h.valid_until IS NULL AND ( \
              ($2::text IS NULL AND NOT EXISTS ( \
                 SELECT 1 FROM tree.haplogroup_relationship r \
                 WHERE r.child_haplogroup_id = h.id AND r.parent_haplogroup_id IS NOT NULL \
                   AND r.valid_until IS NULL)) \
              OR ($2::text IS NOT NULL AND h.name = $2)) \
          UNION ALL \
            SELECT c.id, c.name, r.parent_haplogroup_id, c.haplogroup_type::text, c.formed_ybp, c.tmrca_ybp \
            FROM tree.haplogroup c \
            JOIN tree.haplogroup_relationship r \
              ON r.child_haplogroup_id = c.id AND r.valid_until IS NULL \
            JOIN sub ON sub.id = r.parent_haplogroup_id \
            WHERE c.valid_until IS NULL) \
         SELECT id, name, parent_id, haplogroup_type, formed_ybp, tmrca_ybp FROM sub",
    )
    .bind(pg_enum_label(&dna_type)?)
    .bind(root_name)
    .fetch_all(pool)
    .await?;
    Ok(rows
        .into_iter()
        .map(|r| SubtreeNode {
            id: r.id,
            name: r.name,
            parent_id: r.parent_id,
            haplogroup_type: r.haplogroup_type,
            formed_ybp: r.formed_ybp,
            tmrca_ybp: r.tmrca_ybp,
        })
        .collect())
}

// ── curator structural ops (direct temporal edits) ──────────────────────────

/// The current parent of a node (id, name), or `None` at a root.
pub async fn current_parent(
    pool: &PgPool,
    id: HaplogroupId,
) -> Result<Option<(HaplogroupId, String)>, DbError> {
    let row: Option<(i64, String)> = sqlx::query_as(
        "SELECT p.id, p.name FROM tree.haplogroup_relationship r \
         JOIN tree.haplogroup p ON p.id = r.parent_haplogroup_id \
         WHERE r.child_haplogroup_id = $1 AND r.parent_haplogroup_id IS NOT NULL \
           AND r.valid_until IS NULL AND p.valid_until IS NULL LIMIT 1",
    )
    .bind(id.0)
    .fetch_optional(pool)
    .await?;
    Ok(row.map(|(id, name)| (HaplogroupId(id), name)))
}

/// Current defining-variant links of a node: `(variant_id, canonical_name)`,
/// ordered by name (backs the split variant-picker).
pub async fn current_variant_links(
    pool: &PgPool,
    id: HaplogroupId,
) -> Result<Vec<(i64, String)>, DbError> {
    Ok(sqlx::query_as(
        "SELECT v.id, v.canonical_name FROM tree.haplogroup_variant hv \
         JOIN core.variant v ON v.id = hv.variant_id \
         WHERE hv.haplogroup_id = $1 AND hv.valid_until IS NULL ORDER BY v.canonical_name",
    )
    .bind(id.0)
    .fetch_all(pool)
    .await?)
}

/// **Reparent** a node (and its subtree) under a new parent: close the current
/// parent edge and open a new one (temporal). Rejects a no-op, a self-parent, or
/// a `new_parent` inside the node's own subtree (which would create a cycle).
pub async fn reparent(
    pool: &PgPool,
    child: HaplogroupId,
    new_parent: HaplogroupId,
) -> Result<(), DbError> {
    if child.0 == new_parent.0 {
        return Err(DbError::Conflict("a node cannot be its own parent".into()));
    }
    // Cycle guard: new_parent must not be at/below child in the current tree.
    let cycles: bool = sqlx::query_scalar(
        "WITH RECURSIVE down AS ( \
            SELECT $1::bigint AS id \
          UNION \
            SELECT r.child_haplogroup_id FROM tree.haplogroup_relationship r \
            JOIN down ON down.id = r.parent_haplogroup_id WHERE r.valid_until IS NULL) \
         SELECT EXISTS(SELECT 1 FROM down WHERE id = $2)",
    )
    .bind(child.0)
    .bind(new_parent.0)
    .fetch_one(pool)
    .await?;
    if cycles {
        return Err(DbError::Conflict("new parent is within the node's own subtree (would cycle)".into()));
    }

    let mut tx = pool.begin().await?;
    sqlx::query(
        "UPDATE tree.haplogroup_relationship SET valid_until = now() \
         WHERE child_haplogroup_id = $1 AND valid_until IS NULL",
    )
    .bind(child.0)
    .execute(&mut *tx)
    .await?;
    sqlx::query(
        "INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id, source) \
         VALUES ($1, $2, 'curator')",
    )
    .bind(child.0)
    .bind(new_parent.0)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(())
}

/// **Merge a node into its parent**: reparent the node's children onto its
/// parent, union its defining variants into the parent, then temporal-delete the
/// node (expire it + close its edges/variant links). Errors if the node is a root.
pub async fn merge_into_parent(pool: &PgPool, node: HaplogroupId) -> Result<(), DbError> {
    let parent = current_parent(pool, node)
        .await?
        .ok_or_else(|| DbError::Conflict("node has no parent to merge into".into()))?
        .0;
    let mut tx = pool.begin().await?;

    // Reparent the node's current children onto the parent.
    sqlx::query(
        "UPDATE tree.haplogroup_relationship SET parent_haplogroup_id = $2 \
         WHERE parent_haplogroup_id = $1 AND valid_until IS NULL",
    )
    .bind(node.0)
    .bind(parent.0)
    .execute(&mut *tx)
    .await?;

    // Union the node's variants into the parent (skip dupes).
    sqlx::query(
        "INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) \
         SELECT $2, hv.variant_id FROM tree.haplogroup_variant hv \
         WHERE hv.haplogroup_id = $1 AND hv.valid_until IS NULL \
           AND NOT EXISTS (SELECT 1 FROM tree.haplogroup_variant e \
             WHERE e.haplogroup_id = $2 AND e.variant_id = hv.variant_id AND e.valid_until IS NULL)",
    )
    .bind(node.0)
    .bind(parent.0)
    .execute(&mut *tx)
    .await?;

    // Temporal-delete the node: expire it, close its remaining edges + variant links.
    sqlx::query("UPDATE tree.haplogroup SET valid_until = now() WHERE id = $1 AND valid_until IS NULL")
        .bind(node.0)
        .execute(&mut *tx)
        .await?;
    sqlx::query(
        "UPDATE tree.haplogroup_relationship SET valid_until = now() \
         WHERE (child_haplogroup_id = $1 OR parent_haplogroup_id = $1) AND valid_until IS NULL",
    )
    .bind(node.0)
    .execute(&mut *tx)
    .await?;
    sqlx::query("UPDATE tree.haplogroup_variant SET valid_until = now() WHERE haplogroup_id = $1 AND valid_until IS NULL")
        .bind(node.0)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(())
}

/// **Split** a node: create a new child `new_child_name` under it and move the
/// given variant links from the node onto the new child (close on the node, open
/// on the child). Returns the new child id. Rejects a taken name; ignores ids not
/// currently linked to the node.
pub async fn split(
    pool: &PgPool,
    node: HaplogroupId,
    new_child_name: &str,
    variant_ids: &[i64],
    dna_type: DnaType,
    source: Option<&str>,
) -> Result<HaplogroupId, DbError> {
    let dna = pg_enum_label(&dna_type)?;
    let name = new_child_name.trim();
    if name.is_empty() {
        return Err(DbError::Conflict("new child name is required".into()));
    }
    let exists: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM tree.haplogroup \
           WHERE name = $1 AND haplogroup_type::text = $2 AND valid_until IS NULL)",
    )
    .bind(name)
    .bind(&dna)
    .fetch_one(pool)
    .await?;
    if exists {
        return Err(DbError::Conflict(format!("a haplogroup named {name} already exists")));
    }

    let mut tx = pool.begin().await?;
    let child_id: i64 = sqlx::query_scalar(
        "INSERT INTO tree.haplogroup (name, haplogroup_type, source) \
         VALUES ($1, $2::core.dna_type, $3) RETURNING id",
    )
    .bind(name)
    .bind(&dna)
    .bind(source)
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query(
        "INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id, source) \
         VALUES ($1, $2, 'curator')",
    )
    .bind(child_id)
    .bind(node.0)
    .execute(&mut *tx)
    .await?;

    // Move only links currently on the node: close on node, open on the child.
    sqlx::query(
        "UPDATE tree.haplogroup_variant SET valid_until = now() \
         WHERE haplogroup_id = $1 AND variant_id = ANY($2) AND valid_until IS NULL",
    )
    .bind(node.0)
    .bind(variant_ids)
    .execute(&mut *tx)
    .await?;
    sqlx::query(
        "INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) \
         SELECT $1, v FROM unnest($2::bigint[]) AS v \
         WHERE EXISTS (SELECT 1 FROM core.variant cv WHERE cv.id = v)",
    )
    .bind(child_id)
    .bind(variant_ids)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(HaplogroupId(child_id))
}
