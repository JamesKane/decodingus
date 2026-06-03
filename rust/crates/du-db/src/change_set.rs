//! Tree versioning: change-set lifecycle + apply engine.
//!
//! A change set groups proposed `tree_change` rows (CREATE/UPDATE/DELETE/
//! REPARENT/VARIANT_EDIT). Curators review each change, then *apply* the set:
//! approved changes are written to the production tree using the temporal edge
//! model (close the current edge with `valid_until = now()`, open a new one).
//!
//! Lifecycle: DRAFT → READY_FOR_REVIEW → UNDER_REVIEW → APPLIED, with DISCARDED
//! reachable from any non-applied state.
//!
//! Scope: this is the direct `tree_change` path — changes reference *existing*
//! production haplogroup ids (REPARENT/UPDATE/DELETE/VARIANT_EDIT) or create a
//! node under an existing parent (CREATE). The WIP staging path (placeholder ids
//! + conflict resolutions) is produced by the merge algorithm and lands with it.

use crate::{DbError, Page};
use serde_json::Value;
use sqlx::types::chrono::{DateTime, Utc};
use sqlx::{PgPool, Postgres, Transaction};

// ── views ────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, sqlx::FromRow, serde::Serialize)]
pub struct ChangeSetSummary {
    pub id: i64,
    pub source: String,
    pub haplogroup_type: Option<String>,
    pub status: String,
    pub description: Option<String>,
    pub change_count: i64,
    pub created_by: Option<String>,
    pub created_at: DateTime<Utc>,
    pub promoted_by: Option<String>,
    pub promoted_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, sqlx::FromRow, serde::Serialize)]
pub struct TreeChangeView {
    pub id: i64,
    pub change_type: String,
    pub haplogroup_id: Option<i64>,
    pub haplogroup_name: Option<String>,
    pub old_values: Option<Value>,
    pub new_values: Option<Value>,
    pub status: String,
}

#[derive(Debug, Clone, sqlx::FromRow, serde::Serialize)]
pub struct CommentView {
    pub id: i64,
    pub commented_by: String,
    pub comment: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct ChangeSetDetail {
    pub summary: ChangeSetSummary,
    pub changes: Vec<TreeChangeView>,
    pub comments: Vec<CommentView>,
}

#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct DiffSummary {
    pub added: i64,
    pub removed: i64,
    pub modified: i64,
    pub reparented: i64,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct DiffEntry {
    pub diff_type: String,
    pub name: String,
    pub detail: Value,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct TreeDiff {
    pub entries: Vec<DiffEntry>,
    pub summary: DiffSummary,
}

#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct ApplyResult {
    pub created: i64,
    pub updated: i64,
    pub deleted: i64,
    pub reparented: i64,
    pub variant_edits: i64,
    pub skipped: i64,
}

const CS_COLS: &str = "id, source, haplogroup_type::text AS haplogroup_type, status::text AS status, \
    description, change_count::bigint AS change_count, created_by, created_at, promoted_by, promoted_at";

// ── lifecycle ─────────────────────────────────────────────────────────────────

pub async fn create(
    pool: &PgPool,
    source: &str,
    haplogroup_type: Option<&str>,
    description: Option<&str>,
    created_by: &str,
) -> Result<i64, DbError> {
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tree.change_set (source, haplogroup_type, description, created_by) \
         VALUES ($1, $2::core.dna_type, $3, $4) RETURNING id",
    )
    .bind(source)
    .bind(haplogroup_type)
    .bind(description)
    .bind(created_by)
    .fetch_one(pool)
    .await?;
    Ok(id)
}

/// Author a change within a set (also bumps `change_count`). Returns its id.
pub async fn add_change(
    pool: &PgPool,
    change_set_id: i64,
    change_type: &str,
    haplogroup_id: Option<i64>,
    old_values: Option<&Value>,
    new_values: Option<&Value>,
) -> Result<i64, DbError> {
    let mut tx = pool.begin().await?;
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO tree.tree_change (change_set_id, change_type, haplogroup_id, old_values, new_values) \
         VALUES ($1, $2::tree.tree_change_type, $3, $4, $5) RETURNING id",
    )
    .bind(change_set_id)
    .bind(change_type)
    .bind(haplogroup_id)
    .bind(old_values)
    .bind(new_values)
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query("UPDATE tree.change_set SET change_count = change_count + 1 WHERE id = $1")
        .bind(change_set_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(id)
}

pub async fn list(
    pool: &PgPool,
    haplogroup_type: Option<&str>,
    status: Option<&str>,
    page: i64,
    page_size: i64,
) -> Result<Page<ChangeSetSummary>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let where_sql = "WHERE ($1::text IS NULL OR haplogroup_type::text = $1) \
                     AND ($2::text IS NULL OR status::text = $2)";
    let total: i64 = sqlx::query_scalar(&format!("SELECT count(*) FROM tree.change_set {where_sql}"))
        .bind(haplogroup_type)
        .bind(status)
        .fetch_one(pool)
        .await?;
    let items: Vec<ChangeSetSummary> = sqlx::query_as(&format!(
        "SELECT {CS_COLS} FROM tree.change_set {where_sql} ORDER BY created_at DESC, id DESC LIMIT $3 OFFSET $4"
    ))
    .bind(haplogroup_type)
    .bind(status)
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    Ok(Page { items, total, page: page.max(1), page_size: limit })
}

pub async fn get(pool: &PgPool, id: i64) -> Result<Option<ChangeSetDetail>, DbError> {
    let summary: Option<ChangeSetSummary> =
        sqlx::query_as(&format!("SELECT {CS_COLS} FROM tree.change_set WHERE id = $1"))
            .bind(id)
            .fetch_optional(pool)
            .await?;
    let Some(summary) = summary else { return Ok(None) };

    let changes: Vec<TreeChangeView> = sqlx::query_as(
        "SELECT tc.id, tc.change_type::text AS change_type, tc.haplogroup_id, h.name AS haplogroup_name, \
                tc.old_values, tc.new_values, tc.status \
         FROM tree.tree_change tc LEFT JOIN tree.haplogroup h ON h.id = tc.haplogroup_id \
         WHERE tc.change_set_id = $1 ORDER BY tc.id",
    )
    .bind(id)
    .fetch_all(pool)
    .await?;

    let comments: Vec<CommentView> = sqlx::query_as(
        "SELECT id, commented_by, comment, created_at FROM tree.change_set_comment \
         WHERE change_set_id = $1 ORDER BY created_at, id",
    )
    .bind(id)
    .fetch_all(pool)
    .await?;

    Ok(Some(ChangeSetDetail { summary, changes, comments }))
}

pub async fn add_comment(pool: &PgPool, id: i64, by: &str, comment: &str) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar(
        "INSERT INTO tree.change_set_comment (change_set_id, commented_by, comment) VALUES ($1,$2,$3) RETURNING id",
    )
    .bind(id)
    .bind(by)
    .bind(comment)
    .fetch_one(pool)
    .await?)
}

/// DRAFT/READY_FOR_REVIEW -> UNDER_REVIEW.
pub async fn start_review(pool: &PgPool, id: i64) -> Result<bool, DbError> {
    let n = sqlx::query(
        "UPDATE tree.change_set SET status = 'UNDER_REVIEW' \
         WHERE id = $1 AND status IN ('DRAFT','READY_FOR_REVIEW')",
    )
    .bind(id)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n > 0)
}

/// Any non-applied state -> DISCARDED.
pub async fn discard(pool: &PgPool, id: i64, by: &str) -> Result<bool, DbError> {
    let n = sqlx::query(
        "UPDATE tree.change_set SET status = 'DISCARDED', promoted_by = $2 \
         WHERE id = $1 AND status <> 'APPLIED'",
    )
    .bind(id)
    .bind(by)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n > 0)
}

/// Set a single change's review status. `approve` -> APPROVED, else REJECTED.
pub async fn review_change(pool: &PgPool, change_id: i64, approve: bool) -> Result<bool, DbError> {
    let status = if approve { "APPROVED" } else { "REJECTED" };
    let n = sqlx::query(
        "UPDATE tree.tree_change SET status = $2 \
         FROM tree.change_set cs \
         WHERE tree_change.id = $1 AND tree_change.change_set_id = cs.id \
           AND cs.status NOT IN ('APPLIED','DISCARDED')",
    )
    .bind(change_id)
    .bind(status)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n > 0)
}

/// Approve all PENDING changes in a (non-applied) set. Returns the count.
pub async fn approve_all(pool: &PgPool, id: i64) -> Result<u64, DbError> {
    let n = sqlx::query(
        "UPDATE tree.tree_change SET status = 'APPROVED' \
         FROM tree.change_set cs \
         WHERE tree_change.change_set_id = $1 AND cs.id = $1 \
           AND tree_change.status = 'PENDING' AND cs.status NOT IN ('APPLIED','DISCARDED')",
    )
    .bind(id)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n)
}

// ── diff ──────────────────────────────────────────────────────────────────────

pub async fn diff(pool: &PgPool, id: i64) -> Result<TreeDiff, DbError> {
    let changes: Vec<TreeChangeView> = sqlx::query_as(
        "SELECT tc.id, tc.change_type::text AS change_type, tc.haplogroup_id, h.name AS haplogroup_name, \
                tc.old_values, tc.new_values, tc.status \
         FROM tree.tree_change tc LEFT JOIN tree.haplogroup h ON h.id = tc.haplogroup_id \
         WHERE tc.change_set_id = $1 AND tc.status <> 'REJECTED' ORDER BY tc.id",
    )
    .bind(id)
    .fetch_all(pool)
    .await?;

    let mut summary = DiffSummary::default();
    let mut entries = Vec::with_capacity(changes.len());
    for c in changes {
        let name = c
            .haplogroup_name
            .clone()
            .or_else(|| c.new_values.as_ref().and_then(|v| jstr(v, "name")))
            .unwrap_or_else(|| "(unnamed)".to_string());
        let diff_type = match c.change_type.as_str() {
            "CREATE" => {
                summary.added += 1;
                "ADDED"
            }
            "DELETE" => {
                summary.removed += 1;
                "REMOVED"
            }
            "REPARENT" => {
                summary.reparented += 1;
                "REPARENTED"
            }
            _ => {
                summary.modified += 1;
                "MODIFIED"
            }
        };
        entries.push(DiffEntry {
            diff_type: diff_type.to_string(),
            name,
            detail: serde_json::json!({ "change_type": c.change_type, "old": c.old_values, "new": c.new_values }),
        });
    }
    Ok(TreeDiff { entries, summary })
}

// ── apply ─────────────────────────────────────────────────────────────────────

/// Apply all APPROVED changes to the production tree (temporal model) and mark
/// the set APPLIED. Idempotent on status: re-applying an APPLIED set is a no-op
/// error. The whole apply runs in one transaction.
pub async fn apply(pool: &PgPool, id: i64, by: &str) -> Result<ApplyResult, DbError> {
    let mut tx = pool.begin().await?;

    // Lock the set; gate on a reviewable status.
    let (status, cs_dna): (String, Option<String>) = sqlx::query_as(
        "SELECT status::text, haplogroup_type::text FROM tree.change_set WHERE id = $1 FOR UPDATE",
    )
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| DbError::Conflict(format!("change set {id} not found")))?;
    if !matches!(status.as_str(), "UNDER_REVIEW" | "READY_FOR_REVIEW") {
        return Err(DbError::Conflict(format!(
            "change set must be UNDER_REVIEW or READY_FOR_REVIEW to apply (is {status})"
        )));
    }

    let changes: Vec<TreeChangeRow> = sqlx::query_as(
        "SELECT id, change_type::text AS change_type, haplogroup_id, new_values \
         FROM tree.tree_change WHERE change_set_id = $1 AND status = 'APPROVED' ORDER BY id",
    )
    .bind(id)
    .fetch_all(&mut *tx)
    .await?;

    let mut result = ApplyResult::default();
    // Maps a CREATE's negative placeholder id to the real id it gets, so later
    // changes in the set (children, reparents) can reference nodes created
    // earlier in this same apply. Changes are ordered by id = insertion order =
    // parent-before-child (the merge emits them that way).
    let mut placeholders: std::collections::HashMap<i64, i64> = std::collections::HashMap::new();
    for c in &changes {
        apply_change(&mut tx, c, cs_dna.as_deref(), &mut placeholders, &mut result).await?;
        sqlx::query("UPDATE tree.tree_change SET status = 'APPLIED' WHERE id = $1")
            .bind(c.id)
            .execute(&mut *tx)
            .await?;
    }

    sqlx::query("UPDATE tree.change_set SET status = 'APPLIED', promoted_by = $2, promoted_at = now() WHERE id = $1")
        .bind(id)
        .bind(by)
        .execute(&mut *tx)
        .await?;

    tx.commit().await?;
    Ok(result)
}

#[derive(sqlx::FromRow)]
struct TreeChangeRow {
    id: i64,
    change_type: String,
    haplogroup_id: Option<i64>,
    new_values: Option<Value>,
}

async fn apply_change(
    tx: &mut Transaction<'_, Postgres>,
    c: &TreeChangeRow,
    cs_dna: Option<&str>,
    placeholders: &mut std::collections::HashMap<i64, i64>,
    result: &mut ApplyResult,
) -> Result<(), DbError> {
    let nv = c.new_values.clone().unwrap_or(Value::Null);
    match c.change_type.as_str() {
        "CREATE" => {
            let name = jstr(&nv, "name")
                .ok_or_else(|| DbError::Conflict("CREATE change missing new_values.name".into()))?;
            let dna = jstr(&nv, "haplogroup_type")
                .or_else(|| cs_dna.map(str::to_string))
                .ok_or_else(|| DbError::Conflict("CREATE change has no haplogroup_type".into()))?;
            // `is_backbone` / `provenance` are optional in new_values: the merge
            // engine omits them (COALESCE keeps the column defaults), while the
            // SNP-graft writer carries the source's curated backbone flag and a
            // provenance record (source name, source_updated).
            let new_id: i64 = sqlx::query_scalar(
                "INSERT INTO tree.haplogroup (name, haplogroup_type, lineage, source, formed_ybp, tmrca_ybp, is_backbone, provenance) \
                 VALUES ($1, $2::core.dna_type, $3, $4, $5, $6, COALESCE($7, false), COALESCE($8, '{}'::jsonb)) RETURNING id",
            )
            .bind(&name)
            .bind(&dna)
            .bind(jstr(&nv, "lineage"))
            .bind(jstr(&nv, "source"))
            .bind(jint(&nv, "formed_ybp").map(|v| v as i32))
            .bind(jint(&nv, "tmrca_ybp").map(|v| v as i32))
            .bind(jbool(&nv, "is_backbone"))
            .bind(jval(&nv, "provenance"))
            .fetch_one(&mut **tx)
            .await?;
            // Parent may be an existing id or a placeholder created earlier in
            // this set; None makes a root (no parent edge).
            let parent = resolve_ref(&nv, placeholders, "parent_haplogroup_id", "parent_placeholder")?;
            if parent.is_some() {
                open_edge(tx, new_id, parent, jstr(&nv, "source").as_deref()).await?;
            }
            for vid in jids(&nv, "variant_ids") {
                link_variant(tx, new_id, vid).await?;
            }
            if let Some(ph) = jint(&nv, "placeholder") {
                placeholders.insert(ph, new_id);
            }
            result.created += 1;
        }
        "UPDATE" => {
            let hid = c.haplogroup_id.ok_or_else(|| DbError::Conflict("UPDATE change missing haplogroup_id".into()))?;
            // COALESCE keeps existing values when a field is absent from new_values.
            sqlx::query(
                "UPDATE tree.haplogroup SET name = COALESCE($2, name), lineage = COALESCE($3, lineage), \
                   source = COALESCE($4, source), formed_ybp = COALESCE($5, formed_ybp), \
                   tmrca_ybp = COALESCE($6, tmrca_ybp) WHERE id = $1",
            )
            .bind(hid)
            .bind(jstr(&nv, "name"))
            .bind(jstr(&nv, "lineage"))
            .bind(jstr(&nv, "source"))
            .bind(jint(&nv, "formed_ybp").map(|v| v as i32))
            .bind(jint(&nv, "tmrca_ybp").map(|v| v as i32))
            .execute(&mut **tx)
            .await?;
            result.updated += 1;
        }
        "DELETE" => {
            let hid = c.haplogroup_id.ok_or_else(|| DbError::Conflict("DELETE change missing haplogroup_id".into()))?;
            // Temporal delete: expire the node, then detach by closing all
            // current edges + variant links. The tree-navigation queries
            // (roots/children/subtree) exclude expired nodes.
            sqlx::query("UPDATE tree.haplogroup SET valid_until = now() WHERE id = $1 AND valid_until IS NULL")
                .bind(hid)
                .execute(&mut **tx)
                .await?;
            close_current_edges_for(tx, hid).await?;
            sqlx::query(
                "UPDATE tree.haplogroup_variant SET valid_until = now() \
                 WHERE haplogroup_id = $1 AND valid_until IS NULL",
            )
            .bind(hid)
            .execute(&mut **tx)
            .await?;
            result.deleted += 1;
        }
        "REPARENT" => {
            let hid = c.haplogroup_id.ok_or_else(|| DbError::Conflict("REPARENT change missing haplogroup_id".into()))?;
            let new_parent = match resolve_ref(&nv, placeholders, "new_parent_haplogroup_id", "new_parent_placeholder")? {
                Some(p) => Some(p),
                None => jint(&nv, "parent_haplogroup_id"),
            };
            // Close the current parent edge, then open the new one.
            sqlx::query(
                "UPDATE tree.haplogroup_relationship SET valid_until = now() \
                 WHERE child_haplogroup_id = $1 AND valid_until IS NULL",
            )
            .bind(hid)
            .execute(&mut **tx)
            .await?;
            open_edge(tx, hid, new_parent, jstr(&nv, "source").as_deref()).await?;
            result.reparented += 1;
        }
        "VARIANT_EDIT" => {
            let hid = c.haplogroup_id.ok_or_else(|| DbError::Conflict("VARIANT_EDIT change missing haplogroup_id".into()))?;
            for vid in jids(&nv, "add") {
                link_variant(tx, hid, vid).await?;
            }
            let remove = jids(&nv, "remove");
            if !remove.is_empty() {
                sqlx::query(
                    "UPDATE tree.haplogroup_variant SET valid_until = now() \
                     WHERE haplogroup_id = $1 AND variant_id = ANY($2) AND valid_until IS NULL",
                )
                .bind(hid)
                .bind(&remove)
                .execute(&mut **tx)
                .await?;
            }
            result.variant_edits += 1;
        }
        other => {
            tracing::warn!(change_type = other, "unknown tree_change type; skipped");
            result.skipped += 1;
        }
    }
    Ok(())
}

/// Open a new current edge (child under parent). `parent` None makes a root.
async fn open_edge(
    tx: &mut Transaction<'_, Postgres>,
    child: i64,
    parent: Option<i64>,
    source: Option<&str>,
) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO tree.haplogroup_relationship (child_haplogroup_id, parent_haplogroup_id, source) \
         VALUES ($1, $2, $3)",
    )
    .bind(child)
    .bind(parent)
    .bind(source)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

/// Close every current edge touching a node (as child or parent).
async fn close_current_edges_for(tx: &mut Transaction<'_, Postgres>, hid: i64) -> Result<(), DbError> {
    sqlx::query(
        "UPDATE tree.haplogroup_relationship SET valid_until = now() \
         WHERE (child_haplogroup_id = $1 OR parent_haplogroup_id = $1) AND valid_until IS NULL",
    )
    .bind(hid)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

async fn link_variant(tx: &mut Transaction<'_, Postgres>, hid: i64, vid: i64) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1, $2) \
         ON CONFLICT DO NOTHING",
    )
    .bind(hid)
    .bind(vid)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

// ── small JSON helpers ────────────────────────────────────────────────────────

/// Resolve a node reference that may be an existing id (`id_key`) or a
/// placeholder (`ph_key`) created earlier in this apply. A placeholder with no
/// mapping (its CREATE was rejected/not applied) is an unsatisfied dependency.
fn resolve_ref(
    nv: &Value,
    placeholders: &std::collections::HashMap<i64, i64>,
    id_key: &str,
    ph_key: &str,
) -> Result<Option<i64>, DbError> {
    if let Some(ph) = jint(nv, ph_key) {
        return placeholders
            .get(&ph)
            .copied()
            .map(Some)
            .ok_or_else(|| DbError::Conflict(format!("unresolved placeholder {ph} (its CREATE was not applied)")));
    }
    Ok(jint(nv, id_key))
}

fn jstr(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(Value::as_str).map(str::to_string)
}
fn jint(v: &Value, k: &str) -> Option<i64> {
    v.get(k).and_then(Value::as_i64)
}
fn jbool(v: &Value, k: &str) -> Option<bool> {
    v.get(k).and_then(Value::as_bool)
}
fn jval(v: &Value, k: &str) -> Option<Value> {
    v.get(k).filter(|x| !x.is_null()).cloned()
}
fn jids(v: &Value, k: &str) -> Vec<i64> {
    v.get(k)
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_i64).collect())
        .unwrap_or_default()
}
