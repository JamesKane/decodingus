//! Curator **Variant Naming Authority** UI. Two-panel HTMX screen over
//! `du_db::naming`: the naming queue (left) and a variant panel (right) showing
//! coordinates, current name/aliases, the branch it defines, and any same-coord
//! named variant (dedup) — with **Assign DU name**, **Flag for review**, and
//! **Send back to unnamed** actions. Minting goes through `assign_du_name`.

use crate::auth::{Curator, NavUser};
use crate::error::AppError;
use crate::htmx::HxHeaders;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use serde::Deserialize;

const CHANGED: &str = "naming-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator/naming", get(page))
        .route("/curator/naming/fragment", get(list))
        .route("/curator/naming/:id/panel", get(panel))
        .route("/curator/naming/:id/assign", post(assign))
        .route("/curator/naming/:id/status", post(status))
}

// ── helpers ─────────────────────────────────────────────────────────────────

/// "chrY:2781234" from the GRCh38 coordinate JSONB, or "—".
fn coord_label(coords: &serde_json::Value) -> String {
    let g = coords.get("GRCh38");
    match g {
        Some(g) => {
            let contig = g.get("contig").and_then(|v| v.as_str());
            let pos = g.get("position").and_then(|v| v.as_str().map(str::to_string).or_else(|| v.as_i64().map(|n| n.to_string())));
            match (contig, pos) {
                (Some(c), Some(p)) => format!("{c}:{p}"),
                _ => "—".into(),
            }
        }
        None => "—".into(),
    }
}

fn common_names(aliases: &serde_json::Value) -> Vec<String> {
    aliases
        .get("common_names")
        .and_then(|v| v.as_array())
        .map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect())
        .unwrap_or_default()
}

// ── list ──────────────────────────────────────────────────────────────────────

struct Row {
    id: i64,
    name: String,
    status: String,
    coord: String,
    defining: String,
}

struct ListView {
    mode: String,
    rows: Vec<Row>,
    page: i64,
    total: i64,
    total_pages: i64,
}

#[derive(Deserialize)]
struct ListQuery {
    mode: Option<String>,
    page: Option<i64>,
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<ListView, AppError> {
    let mode = q.mode.clone().unwrap_or_else(|| "needs_name".into());
    let result = du_db::naming::queue(&st.pool, &mode, q.page.unwrap_or(1), 25).await?;
    let (page, total, total_pages) = (result.page, result.total, result.total_pages());
    let rows = result
        .items
        .into_iter()
        .map(|i| Row {
            id: i.id,
            name: i.canonical_name.unwrap_or_else(|| "(unnamed)".into()),
            status: i.naming_status,
            coord: coord_label(&i.coordinates),
            defining: i.defining.unwrap_or_else(|| "—".into()),
        })
        .collect();
    Ok(ListView { mode, rows, page, total, total_pages })
}

#[derive(askama::Template)]
#[template(path = "curator/naming/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: ListView,
}
#[derive(askama::Template)]
#[template(path = "curator/naming/list.html")]
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

// ── detail panel ──────────────────────────────────────────────────────────────

struct Candidate {
    name: String,
}

struct DetailView {
    id: i64,
    name: Option<String>,
    status: String,
    mutation_type: String,
    coord: String,
    aliases: Vec<String>,
    defining: Option<String>,
    dedup: Vec<Candidate>,
    can_assign: bool,
    notice: Option<String>,
}

#[derive(askama::Template)]
#[template(path = "curator/naming/detail.html")]
struct DetailTemplate {
    t: T,
    v: DetailView,
}

async fn build_detail(st: &AppState, id: i64, notice: Option<String>) -> Result<DetailView, AppError> {
    let i = du_db::naming::get(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("variant {id}")))?;
    let dedup = du_db::naming::dedup_by_coordinates(&st.pool, id)
        .await?
        .into_iter()
        .map(|(_, name)| Candidate { name })
        .collect();
    Ok(DetailView {
        id: i.id,
        name: i.canonical_name.clone(),
        status: i.naming_status.clone(),
        mutation_type: i.mutation_type,
        coord: coord_label(&i.coordinates),
        aliases: common_names(&i.aliases),
        defining: i.defining,
        dedup,
        can_assign: i.naming_status != "NAMED",
        notice,
    })
}

async fn detail_response(st: &AppState, t: T, id: i64, notice: Option<String>) -> Result<Response, AppError> {
    let v = build_detail(st, id, notice).await?;
    Ok(html(&DetailTemplate { t, v }))
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

// ── actions ─────────────────────────────────────────────────────────────────

async fn assign(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let notice = match du_db::naming::assign_du_name(&st.pool, id).await {
        Ok(du) => format!("{} {du}", locale.t.get("nm.notice.minted")),
        Err(du_db::DbError::Conflict(m)) => m,
        Err(e) => return Err(e.into()),
    };
    changed_response(&st, locale.t, id, Some(notice)).await
}

#[derive(Deserialize)]
struct StatusForm {
    /// PENDING_REVIEW | UNNAMED
    status: String,
}

async fn status(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<StatusForm>,
) -> Result<Response, AppError> {
    let new_status = match f.status.as_str() {
        "PENDING_REVIEW" => "PENDING_REVIEW",
        _ => "UNNAMED",
    };
    du_db::naming::set_status(&st.pool, id, new_status).await?;
    changed_response(&st, locale.t, id, None).await
}
