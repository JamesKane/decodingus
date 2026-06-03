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
    let vars: Vec<(i64, String)> = sqlx::query_as(
        "SELECT hv.haplogroup_id, v.canonical_name FROM tree.haplogroup_variant hv \
         JOIN core.variant v ON v.id = hv.variant_id \
         JOIN tree.haplogroup h ON h.id = hv.haplogroup_id \
         WHERE hv.valid_until IS NULL AND h.haplogroup_type::text = $1 AND h.valid_until IS NULL",
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

/// Mark the **backbone** of a lineage: the single-letter major clades (`A`–`T`)
/// and every ancestor on the path from them to the root. Recomputed wholesale
/// (clears then sets), so it stays correct as the tree changes. Returns the
/// number of backbone nodes.
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
            SET is_backbone = (h.id IN (SELECT id FROM up)) \
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

/// A defining variant of a haplogroup, for the SNP-detail sidebar.
#[derive(Debug, Clone)]
pub struct VariantInfo {
    pub canonical_name: String,
    pub mutation_type: String,
    pub aliases: serde_json::Value,
    pub coordinates: serde_json::Value,
}

/// All current defining variants of the named haplogroup, ordered by name.
pub async fn variants_of(
    pool: &PgPool,
    name: &str,
    dna_type: DnaType,
) -> Result<Vec<VariantInfo>, DbError> {
    let rows: Vec<(String, String, serde_json::Value, serde_json::Value)> = sqlx::query_as(
        "SELECT v.canonical_name, v.mutation_type::text, v.aliases, v.coordinates \
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
        .map(|(canonical_name, mutation_type, aliases, coordinates)| VariantInfo {
            canonical_name,
            mutation_type,
            aliases,
            coordinates,
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
