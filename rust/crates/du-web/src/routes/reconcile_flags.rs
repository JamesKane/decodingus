//! Curator review of YBrowse **reconcile flags** — synonym clusters whose names
//! map to MORE THAN ONE existing variant (the catalog has them split across
//! rows, possibly tree-linked, so reconciliation won't auto-merge them). Two-panel
//! HTMX screen: the flag queue (left) + a panel (right) showing the YBrowse
//! synonyms and the conflicting variants (canonical + branches each defines),
//! with a pick-the-keeper **merge** action.

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

const CHANGED: &str = "flag-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator/reconcile-flags", get(page))
        .route("/curator/reconcile-flags/fragment", get(list))
        .route("/curator/reconcile-flags/:id/panel", get(panel))
        .route("/curator/reconcile-flags/:id/merge", post(merge))
}

// ── list ──────────────────────────────────────────────────────────────────────

struct Row {
    id: i64,
    locus: String,
    names: String,
    variant_count: i32,
}

struct ListView {
    rows: Vec<Row>,
    page: i64,
    total: i64,
    total_pages: i64,
}

#[derive(Deserialize)]
struct ListQuery {
    page: Option<i64>,
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<ListView, AppError> {
    let result = du_db::ybrowse::list_flags(&st.pool, q.page.unwrap_or(1), 25).await?;
    let (page, total, total_pages) = (result.page, result.total, result.total_pages());
    let rows = result
        .items
        .into_iter()
        .map(|f| Row {
            id: f.id,
            locus: f.locus,
            names: f.names.join(", "),
            variant_count: f.variant_count,
        })
        .collect();
    Ok(ListView { rows, page, total, total_pages })
}

#[derive(askama::Template)]
#[template(path = "curator/reconcile-flags/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: ListView,
}
#[derive(askama::Template)]
#[template(path = "curator/reconcile-flags/list.html")]
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

// ── detail / merge panel ────────────────────────────────────────────────────

struct VarRow {
    id: i64,
    canonical: String,
    defines: String,
}

struct DetailView {
    id: i64,
    locus: String,
    names: String,
    variants: Vec<VarRow>,
    notice: Option<String>,
    resolved: bool,
}

#[derive(askama::Template)]
#[template(path = "curator/reconcile-flags/detail.html")]
struct DetailTemplate {
    t: T,
    f: DetailView,
}

async fn build_detail(st: &AppState, id: i64, notice: Option<String>) -> Result<DetailView, AppError> {
    match du_db::ybrowse::flag(&st.pool, id).await? {
        None => Ok(DetailView { id, locus: String::new(), names: String::new(), variants: vec![], notice, resolved: true }),
        Some(d) => Ok(DetailView {
            id: d.id,
            locus: d.locus,
            names: d.names.join(", "),
            variants: d
                .variants
                .into_iter()
                .map(|v| VarRow {
                    id: v.id,
                    canonical: v.canonical_name.unwrap_or_else(|| format!("#{}", v.id)),
                    defines: if v.defines.is_empty() { "—".into() } else { v.defines.join(", ") },
                })
                .collect(),
            notice,
            resolved: false,
        }),
    }
}

async fn detail_response(st: &AppState, t: T, id: i64, notice: Option<String>) -> Result<Response, AppError> {
    let f = build_detail(st, id, notice).await?;
    Ok(html(&DetailTemplate { t, f }))
}

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

#[derive(Deserialize)]
struct MergeForm {
    /// The variant id to keep as canonical; the others are merged into it.
    keep: i64,
}

async fn merge(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<MergeForm>,
) -> Result<Response, AppError> {
    let detail = du_db::ybrowse::flag(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("flag {id}")))?;
    let mut merged = 0;
    for v in &detail.variants {
        if v.id != f.keep {
            du_db::variant::merge_into(&st.pool, f.keep, v.id).await?;
            merged += 1;
        }
    }
    du_db::ybrowse::delete_flag(&st.pool, id).await?;
    let notice = format!("{} {merged}", locale.t.get("rf.notice.merged"));
    changed_response(&st, locale.t, id, Some(notice)).await
}
