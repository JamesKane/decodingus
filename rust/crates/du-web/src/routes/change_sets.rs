//! Curator change-set / merge review UI. The two-panel HTMX screen over the
//! tree-versioning engine: a list of change sets (left) and a review panel
//! (right) showing the diff, per-change approve/reject, comments, and the
//! lifecycle actions (start review → approve → apply / discard). The JSON
//! management API lives in `versioning.rs`; this mirrors the proposals UI.

use crate::auth::{Curator, NavUser};
use crate::error::AppError;
use crate::htmx::HxHeaders;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use crate::extract::Query;
use axum::extract::{Path, State};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use serde::Deserialize;

const CHANGED: &str = "change-set-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator/change-sets", get(page))
        .route("/curator/change-sets/fragment", get(list))
        .route("/curator/change-sets/:id/panel", get(panel))
        .route("/curator/change-sets/:id/start-review", post(start_review))
        .route("/curator/change-sets/:id/approve-all", post(approve_all))
        .route("/curator/change-sets/:id/changes/:change_id/review", post(review_change))
        .route("/curator/change-sets/:id/apply", post(apply))
        .route("/curator/change-sets/:id/discard", post(discard))
        .route("/curator/change-sets/:id/comments", post(add_comment))
}

// ── list ──────────────────────────────────────────────────────────────────────

struct Row {
    id: i64,
    source: String,
    dna_type: String,
    status: String,
    change_count: i64,
    created_by: String,
    created_at: String,
}

struct ListView {
    status: String,
    rows: Vec<Row>,
    page: i64,
    total: i64,
    total_pages: i64,
}

#[derive(Deserialize)]
struct ListQuery {
    status: Option<String>,
    page: Option<i64>,
}

fn fmt_dt(dt: chrono::DateTime<chrono::Utc>) -> String {
    dt.format("%Y-%m-%d %H:%M").to_string()
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<ListView, AppError> {
    let status = q.status.as_deref().filter(|s| !s.is_empty());
    let result = du_db::change_set::list(&st.pool, None, status, q.page.unwrap_or(1), 20).await?;
    let (page, total, total_pages) = (result.page, result.total, result.total_pages());
    let rows = result
        .items
        .into_iter()
        .map(|s| Row {
            id: s.id,
            source: s.source,
            dna_type: s.haplogroup_type.unwrap_or_else(|| "—".into()),
            status: s.status,
            change_count: s.change_count,
            created_by: s.created_by.unwrap_or_else(|| "—".into()),
            created_at: fmt_dt(s.created_at),
        })
        .collect();
    Ok(ListView { status: q.status.clone().unwrap_or_default(), rows, page, total, total_pages })
}

#[derive(askama::Template)]
#[template(path = "curator/change-sets/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: ListView,
}
#[derive(askama::Template)]
#[template(path = "curator/change-sets/list.html")]
struct ListTemplate {
    t: T,
    list: ListView,
}

async fn page(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&PageTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { display_name: s.display_name, is_curator: true }),
        list,
    }))
}

async fn list(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&ListTemplate { t: locale.t, list }))
}

// ── detail / review panel ───────────────────────────────────────────────────

struct ChangeRow {
    id: i64,
    change_type: String,
    name: String,
    status: String,
    new_values: String,
}

struct DiffRow {
    diff_type: String,
    name: String,
    detail: String,
}

struct CommentRow {
    by: String,
    comment: String,
    at: String,
}

struct DetailView {
    id: i64,
    source: String,
    dna_type: String,
    status: String,
    description: String,
    change_count: i64,
    created_by: String,
    created_at: String,
    promoted_by: Option<String>,
    promoted_at: Option<String>,
    added: i64,
    removed: i64,
    modified: i64,
    reparented: i64,
    diff: Vec<DiffRow>,
    changes: Vec<ChangeRow>,
    comments: Vec<CommentRow>,
    /// DRAFT/READY_FOR_REVIEW → can move to review.
    can_start: bool,
    /// UNDER_REVIEW → per-change approve/reject + approve-all are live.
    can_review: bool,
    /// READY_FOR_REVIEW/UNDER_REVIEW → applying APPROVED changes is allowed.
    can_apply: bool,
    /// Anything not yet terminal can be discarded.
    can_discard: bool,
    /// Optional banner after an action (e.g. apply summary, error).
    notice: Option<String>,
}

#[derive(askama::Template)]
#[template(path = "curator/change-sets/detail.html")]
struct DetailTemplate {
    t: T,
    cs: DetailView,
}

fn pretty(v: &serde_json::Value) -> String {
    serde_json::to_string_pretty(v).unwrap_or_default()
}

async fn build_detail(st: &AppState, id: i64, notice: Option<String>) -> Result<DetailView, AppError> {
    let d = du_db::change_set::get(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("change set {id}")))?;
    let diff = du_db::change_set::diff(&st.pool, id).await?;
    let status = d.summary.status.clone();
    let can_start = matches!(status.as_str(), "DRAFT" | "READY_FOR_REVIEW");
    let can_review = status == "UNDER_REVIEW";
    let can_apply = matches!(status.as_str(), "READY_FOR_REVIEW" | "UNDER_REVIEW");
    let can_discard = !matches!(status.as_str(), "APPLIED" | "DISCARDED");

    Ok(DetailView {
        id: d.summary.id,
        source: d.summary.source,
        dna_type: d.summary.haplogroup_type.unwrap_or_else(|| "—".into()),
        status,
        description: d.summary.description.unwrap_or_default(),
        change_count: d.summary.change_count,
        created_by: d.summary.created_by.unwrap_or_else(|| "—".into()),
        created_at: fmt_dt(d.summary.created_at),
        promoted_by: d.summary.promoted_by,
        promoted_at: d.summary.promoted_at.map(fmt_dt),
        added: diff.summary.added,
        removed: diff.summary.removed,
        modified: diff.summary.modified,
        reparented: diff.summary.reparented,
        diff: diff
            .entries
            .into_iter()
            .map(|e| DiffRow { diff_type: e.diff_type, name: e.name, detail: pretty(&e.detail) })
            .collect(),
        changes: d
            .changes
            .into_iter()
            .map(|c| ChangeRow {
                id: c.id,
                change_type: c.change_type,
                name: c.haplogroup_name.unwrap_or_else(|| "—".into()),
                status: c.status,
                new_values: c.new_values.as_ref().map(pretty).unwrap_or_default(),
            })
            .collect(),
        comments: d
            .comments
            .into_iter()
            .map(|c| CommentRow { by: c.commented_by, comment: c.comment, at: fmt_dt(c.created_at) })
            .collect(),
        can_start,
        can_review,
        can_apply,
        can_discard,
        notice,
    })
}

async fn detail_response(st: &AppState, t: T, id: i64, notice: Option<String>) -> Result<Response, AppError> {
    let cs = build_detail(st, id, notice).await?;
    Ok(html(&DetailTemplate { t, cs }))
}

/// Render the panel and fire the list-refresh trigger (used after every action).
async fn changed_response(st: &AppState, t: T, id: i64, notice: Option<String>) -> Result<Response, AppError> {
    let body = detail_response(st, t, id, notice).await?;
    Ok((HxHeaders::new().trigger(CHANGED), body).into_response())
}

async fn panel(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    detail_response(&st, locale.t, id, None).await
}

// ── lifecycle actions ─────────────────────────────────────────────────────────

async fn start_review(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let ok = du_db::change_set::start_review(&st.pool, id).await?;
    let notice = (!ok).then(|| locale.t.get("cs.notice.no_transition").to_string());
    changed_response(&st, locale.t, id, notice).await
}

async fn approve_all(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let n = du_db::change_set::approve_all(&st.pool, id).await?;
    let notice = Some(format!("{} {}", n, locale.t.get("cs.notice.approved")));
    changed_response(&st, locale.t, id, notice).await
}

#[derive(Deserialize)]
struct ReviewForm {
    action: String,
}

async fn review_change(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path((id, change_id)): Path<(i64, i64)>,
    Form(f): Form<ReviewForm>,
) -> Result<Response, AppError> {
    let approve = f.action == "APPROVE";
    du_db::change_set::review_change(&st.pool, change_id, approve).await?;
    changed_response(&st, locale.t, id, None).await
}

async fn apply(
    cur: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    match du_db::change_set::apply(&st.pool, id, &cur.0.display_name).await {
        Ok(r) => {
            let notice = Some(format!(
                "{}: +{} ~{} -{} ⤳{} ✎{} (skip {})",
                locale.t.get("cs.notice.applied"),
                r.created,
                r.updated,
                r.deleted,
                r.reparented,
                r.variant_edits,
                r.skipped,
            ));
            changed_response(&st, locale.t, id, notice).await
        }
        Err(du_db::DbError::Conflict(msg)) => changed_response(&st, locale.t, id, Some(msg)).await,
        Err(e) => Err(e.into()),
    }
}

async fn discard(
    cur: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let ok = du_db::change_set::discard(&st.pool, id, &cur.0.display_name).await?;
    let notice = (!ok).then(|| locale.t.get("cs.notice.no_transition").to_string());
    changed_response(&st, locale.t, id, notice).await
}

#[derive(Deserialize)]
struct CommentForm {
    comment: String,
}

async fn add_comment(
    cur: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<CommentForm>,
) -> Result<Response, AppError> {
    if !f.comment.trim().is_empty() {
        du_db::change_set::add_comment(&st.pool, id, &cur.0.display_name, f.comment.trim()).await?;
    }
    // No list-state change → just re-render the panel (keeps the comment thread fresh).
    detail_response(&st, locale.t, id, None).await
}
