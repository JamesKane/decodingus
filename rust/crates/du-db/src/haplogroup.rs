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

// ── private-node naming backfill ────────────────────────────────────────────

/// A private de-novo placeholder node (`<parent>:n<k>` name) that now has ≥1
/// namable SNP at one of its defining sites, plus the candidate names found.
#[derive(Debug, Clone)]
pub struct PrivateNodeNaming {
    pub id: i64,
    pub current_name: String,
    pub dna_label: String,
    /// Distinct namable SNP names sitting on this node's defining sites (unsorted).
    pub candidates: Vec<String>,
}

/// Every current private placeholder node (`name ~ ':n[0-9]+$'`) that carries at
/// least one namable SNP, with its candidate names. Naming is resolved **by site**,
/// not by the stored defining-variant link: for each defining variant we look for a
/// co-located named `core.variant` at the same hs1 coordinate+alleles. This matters
/// because YBrowse names a site *after* the de-novo tree already linked its coord
/// row — the link still points at the unnamed coord variant, but the name is now a
/// sibling row at the same site. Excluded as non-names: coordinate/placeholder-synthetic
/// names (anything containing a colon or whitespace — `chrY:pos…`, `CP086569.2:pos A->G`,
/// `(hs1)chrY:pos …`) and recurrent-region markers (heterochromatin / inverted-repeat),
/// mirroring the ytree stage-88 relabel rules. Rows come grouped per node; the caller
/// picks the lowest natural-sort non-colliding candidate.
pub async fn private_nodes_needing_names(pool: &PgPool) -> Result<Vec<PrivateNodeNaming>, DbError> {
    let mask = crate::variant::recurrent_region_mask_sql("nv");
    let sql = format!(
        "SELECT h.id, h.name, h.haplogroup_type::text AS dna, nv.canonical_name \
         FROM tree.haplogroup h \
         JOIN tree.haplogroup_variant hv ON hv.haplogroup_id = h.id AND hv.valid_until IS NULL \
         JOIN core.variant v ON v.id = hv.variant_id \
         JOIN core.variant nv ON \
              nv.coordinates @> jsonb_build_object('hs1', jsonb_build_object( \
                'contig', v.coordinates->'hs1'->>'contig', \
                'position', (v.coordinates->'hs1'->>'position')::bigint)) \
          AND nv.defining_haplogroup_id IS NULL \
          AND nv.canonical_name IS NOT NULL \
          AND nv.canonical_name !~ '[[:space:]:]' \
          AND ( (nv.coordinates->'hs1'->>'ancestral' = v.coordinates->'hs1'->>'ancestral' \
                 AND nv.coordinates->'hs1'->>'derived' = v.coordinates->'hs1'->>'derived') \
             OR (nv.coordinates->'hs1'->>'ancestral' = v.coordinates->'hs1'->>'derived' \
                 AND nv.coordinates->'hs1'->>'derived' = v.coordinates->'hs1'->>'ancestral') ) \
          AND {mask} \
         WHERE h.valid_until IS NULL AND h.name ~ ':n[0-9]+$' \
         ORDER BY h.id, nv.canonical_name"
    );
    let rows: Vec<(i64, String, String, String)> = sqlx::query_as(&sql).fetch_all(pool).await?;
    let mut out: Vec<PrivateNodeNaming> = Vec::new();
    for (id, name, dna, cand) in rows {
        match out.last_mut() {
            Some(n) if n.id == id => n.candidates.push(cand),
            _ => out.push(PrivateNodeNaming { id, current_name: name, dna_label: dna, candidates: vec![cand] }),
        }
    }
    Ok(out)
}

/// The set of names currently in use by live haplogroups of a given `dna_label`
/// (`Y`/`MT`), for collision-checking a proposed rename.
pub async fn live_names(pool: &PgPool, dna_label: &str) -> Result<Vec<String>, DbError> {
    Ok(sqlx::query_scalar(
        "SELECT name FROM tree.haplogroup WHERE haplogroup_type::text = $1 AND valid_until IS NULL",
    )
    .bind(dna_label)
    .fetch_all(pool)
    .await?)
}

/// Rename a haplogroup, preserving the old (placeholder) name in `provenance.aliases`
/// (deduped). Only touches the live row. Returns whether a row changed. Generic over
/// the executor so the backfill can batch renames + the revision bump in one txn.
pub async fn rename_with_alias<'e, E>(executor: E, id: i64, new_name: &str, old_name: &str) -> Result<bool, DbError>
where
    E: sqlx::PgExecutor<'e>,
{
    let n = sqlx::query(
        "UPDATE tree.haplogroup SET name = $2, \
           provenance = jsonb_set(provenance, '{aliases}', \
             to_jsonb(ARRAY(SELECT DISTINCT e FROM jsonb_array_elements_text( \
               COALESCE(provenance->'aliases','[]'::jsonb) || to_jsonb($3::text)) e)), true) \
         WHERE id = $1 AND valid_until IS NULL",
    )
    .bind(id)
    .bind(new_name)
    .bind(old_name)
    .execute(executor)
    .await?
    .rows_affected();
    Ok(n > 0)
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

/// Clear just **one DNA type's** de-novo tree (so Y and mt coexist), the
/// per-lineage greenfield front-end for a `--denovo-{y,mt}` reload. The de-novo
/// loader populates only `haplogroup` + `haplogroup_relationship` +
/// `haplogroup_variant` (+ later `haplogroup_sample`); the FKs into `haplogroup`
/// are NO ACTION, so dependents are deleted first. Unlike a full tree wipe this
/// leaves the other lineage (and the rest of `tree.*`) untouched. Returns the
/// number of haplogroups removed.
/// Clear one lineage so its de-novo ingest can be (re)loaded greenfield. Beyond the
/// tree-owned child rows (variants/edges/samples/conflicts), this neutralizes **every
/// other** reference into the lineage's nodes — none of those FKs cascade, so a stale
/// reference from a *previous* load (private singletons, discovery proposals, recurrence
/// back-links, …) would otherwise block the final haplogroup delete. The reload is run
/// repeatedly during tree iteration, so this must leave no danglers either way:
///   * tree-owned + computed rows → deleted (the load / a recompute re-creates them);
///   * federation-mirrored privates → kept but unlinked (discovery-consensus re-resolves
///     `terminal_haplogroup_id` from the durable `fed.*` mirror);
///   * curated anchors → deleted (re-seeded by name post-load via the seed scripts);
///   * nullable cross-refs (proposals, audit, variant recurrence) → set NULL.
///
/// `tree.wip_*` curation sessions are intentionally not touched: a WIP edit against a
/// tree you're about to greenfield-reload is contradictory, and they're change-set scoped.
pub async fn clear_dna(pool: &PgPool, dna_type: DnaType) -> Result<u64, DbError> {
    let dna = pg_enum_label(&dna_type)?;
    let mut tx = pool.begin().await?;
    let ids = "SELECT id FROM tree.haplogroup WHERE haplogroup_type::text = $1";
    sqlx::query(&format!("DELETE FROM tree.haplogroup_variant WHERE haplogroup_id IN ({ids})"))
        .bind(&dna)
        .execute(&mut *tx)
        .await?;
    sqlx::query(&format!(
        "DELETE FROM tree.haplogroup_relationship \
         WHERE child_haplogroup_id IN ({ids}) OR parent_haplogroup_id IN ({ids})"
    ))
    .bind(&dna)
    .execute(&mut *tx)
    .await?;
    sqlx::query("DELETE FROM tree.haplogroup_sample WHERE dna_type::text = $1")
        .bind(&dna)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM tree.denovo_conflict WHERE dna_type::text = $1")
        .bind(&dna)
        .execute(&mut *tx)
        .await?;
    // Loader-seeded private singletons: re-seeded by this load's private collapse.
    sqlx::query("DELETE FROM tree.biosample_private_variant WHERE haplogroup_type::text = $1 AND origin = 'DENOVO'")
        .bind(&dna)
        .execute(&mut *tx)
        .await?;
    // Federation-mirrored privates: survive the reload, but their node link is now
    // stale — null it so the FK clears; discovery-consensus re-resolves it from fed.*.
    sqlx::query("UPDATE tree.biosample_private_variant SET terminal_haplogroup_id = NULL \
                 WHERE haplogroup_type::text = $1 AND terminal_haplogroup_id IS NOT NULL")
        .bind(&dna)
        .execute(&mut *tx)
        .await?;
    // Computed STR ancestral states (regenerated by ystr::recompute_signatures).
    sqlx::query(&format!("DELETE FROM tree.haplogroup_ancestral_str WHERE haplogroup_id IN ({ids})"))
        .bind(&dna)
        .execute(&mut *tx)
        .await?;
    // Curated anchors: re-seeded by node name post-load (scripts/seed-*-anchors.sql).
    sqlx::query(&format!("DELETE FROM tree.genealogical_anchor WHERE haplogroup_id IN ({ids})"))
        .bind(&dna)
        .execute(&mut *tx)
        .await?;
    // Nullable cross-references: keep the row, drop the now-invalid node link.
    sqlx::query(&format!("UPDATE tree.proposed_branch SET parent_haplogroup_id = NULL WHERE parent_haplogroup_id IN ({ids})"))
        .bind(&dna)
        .execute(&mut *tx)
        .await?;
    sqlx::query(&format!("UPDATE tree.tree_change SET haplogroup_id = NULL WHERE haplogroup_id IN ({ids})"))
        .bind(&dna)
        .execute(&mut *tx)
        .await?;
    sqlx::query(&format!("UPDATE core.variant SET defining_haplogroup_id = NULL WHERE defining_haplogroup_id IN ({ids})"))
        .bind(&dna)
        .execute(&mut *tx)
        .await?;
    let n = sqlx::query("DELETE FROM tree.haplogroup WHERE haplogroup_type::text = $1")
        .bind(&dna)
        .execute(&mut *tx)
        .await?
        .rows_affected();
    tx.commit().await?;
    Ok(n)
}

/// Mark the **backbone** of a lineage: the computed spine (single-letter major
/// clades `A`–`T` + every ancestor up to the root) **unioned with curated
/// backbone** adopted from a source tree (nodes carrying a
/// `provenance.backbone_source` marker, stamped by the SNP-graft enrich/graft
/// writers). The major-clade seed matches either the **node name** (the ISOGG
/// import names backbone nodes `A`–`T` outright) or the **`provenance.isogg`
/// mapping** (the de-novo tree names nodes by defining SNP, e.g. `R-M269`, and
/// carries the matched ISOGG clade in provenance) — so one pass serves both
/// naming schemes. Recomputed wholesale (clears then sets) so the *computed*
/// spine stays correct as the tree changes, while curated flags are preserved.
/// Returns the number of backbone nodes.
pub async fn recompute_backbone(pool: &PgPool, dna_type: DnaType) -> Result<i64, DbError> {
    let dna = pg_enum_label(&dna_type)?;
    sqlx::query(
        "WITH RECURSIVE seeds AS ( \
            SELECT id FROM tree.haplogroup \
            WHERE haplogroup_type::text = $1 AND valid_until IS NULL \
              AND (name ~ '^[A-Z]$' OR provenance->>'isogg' ~ '^[A-Z]$') \
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

/// Resolve a tree-search query to a haplogroup name: try a direct name match,
/// then an alternate (old ISOGG) name in `provenance.aliases`, then a defining
/// variant name; return the most-recent match. `None` if nothing matches.
///
/// If the raw query resolves nothing, retry against the normalized candidate
/// tokens from [`normalize_haplogroup_call`] — heterogeneous publication calls
/// (FTDNA terminal-SNP shorthand `R-M269`, path strings `R-DF27 > Z195 > Z198`,
/// SNP synonyms `L151/PF6542`) resolve via the defining-variant phase once their
/// SNP token is isolated. Old YCC nested-letter longhand (`R1b1a2a1a2c1g`) has no
/// SNP to recover and stays unresolved (a historical crosswalk would be needed).
pub async fn resolve_name_or_variant(
    pool: &PgPool,
    query: &str,
    dna_type: DnaType,
) -> Result<Option<String>, DbError> {
    let dna = pg_enum_label(&dna_type)?;
    let q = query.trim();
    if let Some(name) = resolve_one(pool, q, dna_type, &dna).await? {
        return Ok(Some(name));
    }
    // Fallback: normalize a heterogeneous publication call to candidate SNP/name
    // tokens (most-specific first) and retry each.
    for cand in normalize_haplogroup_call(q) {
        if let Some(name) = resolve_one(pool, &cand, dna_type, &dna).await? {
            return Ok(Some(name));
        }
    }
    Ok(None)
}

/// One resolution attempt for an already-prepared query token: direct name →
/// `provenance.aliases` → defining-variant name. `dna` is the `pg_enum_label`.
async fn resolve_one(
    pool: &PgPool,
    q: &str,
    dna_type: DnaType,
    dna: &str,
) -> Result<Option<String>, DbError> {
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
    .bind(dna)
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
    .bind(dna)
    .bind(q)
    .fetch_optional(pool)
    .await?;
    Ok(name)
}

/// Normalize a heterogeneous publication haplogroup call into ordered candidate
/// tokens to retry resolution against, most-specific first. Handles:
/// - FTDNA terminal-SNP shorthand: `R-M269` → `M269`
/// - path strings: `R-DF27 > Z195 > Z198` → `Z198`, `Z195`, `DF27` (terminal first)
/// - SNP synonyms: `L151/PF6542` → `L151`, `PF6542`
///
/// Returns empty for non-calls (`n/a`, `NA`, blank) and never re-emits the raw
/// trimmed input (the caller already tried that).
fn normalize_haplogroup_call(raw: &str) -> Vec<String> {
    let q = raw.trim();
    if q.is_empty() {
        return Vec::new();
    }
    let low = q.to_ascii_lowercase();
    if low == "na" || low == "?" || low == "unknown" || low.starts_with("n/a") {
        return Vec::new();
    }
    // Path string: '>'-separated clades, terminal (most specific) first.
    let segments: Vec<&str> = q.split('>').map(str::trim).filter(|s| !s.is_empty()).collect();
    let mut out: Vec<String> = Vec::new();
    for seg in segments.iter().rev() {
        let stripped = strip_haplogroup_prefix(seg);
        // SNP synonyms ('/'-separated); a no-slash token splits to itself.
        for cand in stripped.split('/') {
            let c = cand.trim().trim_matches(|ch: char| "*?.,()".contains(ch)).trim();
            if !c.is_empty() && c != q && !out.iter().any(|e| e == c) {
                out.push(c.to_string());
            }
        }
    }
    out
}

/// Strip a leading haplogroup-label prefix (`R-`, `I-`, `E1b1b-`) from FTDNA
/// terminal-SNP shorthand, leaving the SNP token. No dash, or a non-label head,
/// returns the segment unchanged.
fn strip_haplogroup_prefix(seg: &str) -> &str {
    if let Some(idx) = seg.find('-') {
        let head = &seg[..idx];
        let looks_like_label = !head.is_empty()
            && head.chars().next().is_some_and(|c| c.is_ascii_uppercase())
            && head.chars().all(|c| c.is_ascii_alphanumeric());
        if looks_like_label {
            return &seg[idx + 1..];
        }
    }
    seg
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
    Ok(variants_of_chain(pool, std::slice::from_ref(&name.to_string()), dna_type)
        .await?
        .remove(name)
        .unwrap_or_default())
}

/// Batched [`variants_of`]: defining SNPs for every named node in ONE query,
/// grouped by haplogroup name. Backs the per-sample pathway (root→tip) so it
/// resolves the whole lineage in a single round-trip instead of N (the N+1 that
/// made deep-lineage sample pages take seconds).
pub async fn variants_of_chain(
    pool: &PgPool,
    names: &[String],
    dna_type: DnaType,
) -> Result<std::collections::HashMap<String, Vec<VariantInfo>>, DbError> {
    #[derive(sqlx::FromRow)]
    struct Row {
        hg_name: String,
        canonical_name: Option<String>,
        mutation_type: String,
        aliases: serde_json::Value,
        coordinates: serde_json::Value,
        link_ancestral: Option<String>,
        link_derived: Option<String>,
        recurrent: bool,
    }
    // A defining SNP the de-novo loader minted a coordinate name for (`chrY:pos…>…`)
    // because the inverted-block liftover left the catalog's copy reverse-complemented,
    // so the exact-allele match at load missed the real named entry. Recover the real
    // name for display by finding a real-named catalog SNP at the same hs1 position whose
    // alleles equal this link's set OR its reverse-complement. (GIN-indexed containment
    // on the position keeps the per-row lookup cheap; it runs only for coordinate-named /
    // unnamed links.) See documents/proposals/denovo-ingest-confidence-flags.md.
    let rows: Vec<Row> = sqlx::query_as(
        "SELECT h.name AS hg_name, COALESCE(named.nm, v.canonical_name) AS canonical_name, \
                v.mutation_type::text AS mutation_type, v.aliases, v.coordinates, \
                hv.ancestral_allele AS link_ancestral, hv.derived_allele AS link_derived, \
                EXISTS (SELECT 1 FROM tree.haplogroup_variant hv2 \
                        WHERE hv2.variant_id = v.id AND hv2.valid_until IS NULL \
                          AND hv2.haplogroup_id <> hv.haplogroup_id) AS recurrent \
         FROM core.variant v \
         JOIN tree.haplogroup_variant hv ON hv.variant_id = v.id AND hv.valid_until IS NULL \
         JOIN tree.haplogroup h ON h.id = hv.haplogroup_id \
         LEFT JOIN LATERAL ( \
            SELECT v2.canonical_name AS nm FROM core.variant v2 \
            WHERE v2.id <> v.id AND v2.mutation_type = v.mutation_type \
              AND v2.canonical_name IS NOT NULL AND v2.canonical_name !~ '^chr' \
              AND v2.coordinates @> jsonb_build_object('hs1', jsonb_build_object('position', v.coordinates->'hs1'->'position')) \
              AND least(v2.coordinates->'hs1'->>'ancestral', v2.coordinates->'hs1'->>'derived') \
                  || greatest(v2.coordinates->'hs1'->>'ancestral', v2.coordinates->'hs1'->>'derived') IN ( \
                    least(v.coordinates->'hs1'->>'ancestral', v.coordinates->'hs1'->>'derived') \
                      || greatest(v.coordinates->'hs1'->>'ancestral', v.coordinates->'hs1'->>'derived'), \
                    least(translate(v.coordinates->'hs1'->>'ancestral','ACGT','TGCA'), translate(v.coordinates->'hs1'->>'derived','ACGT','TGCA')) \
                      || greatest(translate(v.coordinates->'hs1'->>'ancestral','ACGT','TGCA'), translate(v.coordinates->'hs1'->>'derived','ACGT','TGCA')) ) \
            ORDER BY core.ysnp_name_rank(v2.canonical_name), v2.canonical_name LIMIT 1 \
         ) named ON v.canonical_name IS NULL OR v.canonical_name ~ '^chr[0-9A-Za-z]*:' \
         WHERE h.name = ANY($1) AND h.haplogroup_type::text = $2 AND h.valid_until IS NULL \
         ORDER BY h.name, COALESCE(named.nm, v.canonical_name)",
    )
    .bind(names)
    .bind(pg_enum_label(&dna_type)?)
    .fetch_all(pool)
    .await?;
    let mut map: std::collections::HashMap<String, Vec<VariantInfo>> = std::collections::HashMap::new();
    for r in rows {
        map.entry(r.hg_name).or_default().push(VariantInfo {
            canonical_name: r.canonical_name,
            mutation_type: r.mutation_type,
            aliases: r.aliases,
            coordinates: r.coordinates,
            link_ancestral: r.link_ancestral,
            link_derived: r.link_derived,
            recurrent: r.recurrent,
        });
    }
    Ok(map)
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

// ── phylogenetic pathway (for the public per-sample report) ───────────────────

/// One clade on the root→tip pathway: the node, its ages, and its defining SNPs.
#[derive(Debug, Clone)]
pub struct PathwayStep {
    pub haplogroup_id: HaplogroupId,
    pub name: String,
    pub formed_ybp: Option<i32>,
    pub tmrca_ybp: Option<i32>,
    pub defining_snps: Vec<VariantInfo>,
}

/// A called haplogroup resolved to its place in the tree, root→tip.
#[derive(Debug, Clone)]
pub struct Pathway {
    pub dna_type: DnaType,
    /// The name as called on the sample (raw input).
    pub called_name: String,
    /// The matched tree node name, or `None` when the call isn't placed in the
    /// tree (raw caller output, deprecated nomenclature, provisional `~` names).
    pub resolved_name: Option<String>,
    /// Root → tip clades. Empty when `resolved_name` is `None`.
    pub steps: Vec<PathwayStep>,
}

/// Turn a called haplogroup NAME into its root→tip pathway with branch ages and
/// defining SNPs, reusing [`resolve_name_or_variant`] / [`get_by_name`] /
/// [`ancestors`] / [`variants_of`]. When the name can't be resolved to a tree
/// node, returns a `Pathway` with `resolved_name: None` and no steps (the gap
/// case) rather than erroring — the report shows the raw call as "not placed".
pub async fn pathway(pool: &PgPool, called_name: &str, dna_type: DnaType) -> Result<Pathway, DbError> {
    let gap = |resolved: Option<String>| Pathway {
        dna_type,
        called_name: called_name.to_string(),
        resolved_name: resolved,
        steps: Vec::new(),
    };
    let Some(resolved) = resolve_name_or_variant(pool, called_name, dna_type).await? else {
        return Ok(gap(None));
    };
    let Some(tip) = get_by_name(pool, &resolved, dna_type).await? else {
        return Ok(gap(None));
    };

    // root → parent, then append the tip itself for the full chain.
    let mut chain = ancestors(pool, tip.id).await?;
    chain.push((tip.id, tip.name.clone()));

    // Resolve the WHOLE lineage in two queries (ages + defining SNPs) rather than
    // an N+1 over the chain — deep Y lineages were ~2×depth sequential round-trips.
    let ids: Vec<i64> = chain.iter().map(|(id, _)| id.0).collect();
    let names: Vec<String> = chain.iter().map(|(_, n)| n.clone()).collect();
    let ages: std::collections::HashMap<i64, (Option<i32>, Option<i32>)> =
        sqlx::query_as::<_, (i64, Option<i32>, Option<i32>)>(
            "SELECT id, formed_ybp, tmrca_ybp FROM tree.haplogroup WHERE id = ANY($1)",
        )
        .bind(&ids)
        .fetch_all(pool)
        .await?
        .into_iter()
        .map(|(id, f, t)| (id, (f, t)))
        .collect();
    let mut variants = variants_of_chain(pool, &names, dna_type).await?;

    let steps = chain
        .into_iter()
        .map(|(id, name)| {
            let (formed_ybp, tmrca_ybp) = ages.get(&id.0).copied().unwrap_or((None, None));
            let defining_snps = variants.remove(&name).unwrap_or_default();
            PathwayStep { haplogroup_id: id, name, formed_ybp, tmrca_ybp, defining_snps }
        })
        .collect();
    Ok(Pathway { dna_type, called_name: called_name.to_string(), resolved_name: Some(resolved), steps })
}
