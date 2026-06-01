//! Curator tools (role-gated). Demonstrates the HTMX two-panel write-flow: a
//! searchable list on the left and a detail/form panel on the right; mutations
//! return the updated panel plus an `HX-Trigger` that makes the list reload.

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
use du_domain::enums::DnaType;
use du_domain::ids::HaplogroupId;
use serde::Deserialize;

/// Event other elements listen for to refresh after a mutation.
const CHANGED: &str = "hg-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator", get(dashboard))
        .route("/curator/haplogroups", get(hg_page))
        .route("/curator/haplogroups/fragment", get(hg_list))
        .route("/curator/haplogroups/new", get(hg_new))
        .route("/curator/haplogroups", post(hg_create))
        .route("/curator/haplogroups/:id/panel", get(hg_panel))
        .route("/curator/haplogroups/:id/edit", get(hg_edit))
        .route("/curator/haplogroups/:id", post(hg_update))
        .route("/curator/haplogroups/:id", axum::routing::delete(hg_delete))
}

// ── dashboard ────────────────────────────────────────────────────────────────
#[derive(askama::Template)]
#[template(path = "curator/dashboard.html")]
struct DashTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    display_name: String,
    roles: String,
}

async fn dashboard(Curator(s): Curator, locale: Locale) -> Response {
    html(&DashTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { display_name: s.display_name.clone(), is_curator: true }),
        display_name: s.display_name,
        roles: s.roles.join(", "),
    })
}

// ── haplogroup list ──────────────────────────────────────────────────────────
#[derive(Deserialize)]
struct ListQuery {
    query: Option<String>,
    dna: Option<String>,
    page: Option<i64>,
}

fn parse_dna(s: Option<&str>) -> Option<DnaType> {
    match s {
        Some("Y_DNA") => Some(DnaType::YDna),
        Some("MT_DNA") => Some(DnaType::MtDna),
        _ => None,
    }
}

struct HgRow {
    id: i64,
    name: String,
    dna: String,
    lineage: String,
}

struct HgListView {
    query: String,
    dna: String,
    rows: Vec<HgRow>,
    page: i64,
    total: i64,
    total_pages: i64,
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<HgListView, AppError> {
    let dna = parse_dna(q.dna.as_deref());
    let result =
        du_db::haplogroup::list_paginated(&st.pool, q.query.as_deref(), dna, q.page.unwrap_or(1), 20)
            .await?;
    let rows = result
        .items
        .iter()
        .map(|h| HgRow {
            id: h.id.0,
            name: h.name.clone(),
            dna: h.haplogroup_type.label().to_string(),
            lineage: h.lineage.clone().unwrap_or_default(),
        })
        .collect();
    Ok(HgListView {
        query: q.query.clone().unwrap_or_default(),
        dna: q.dna.clone().unwrap_or_default(),
        rows,
        page: result.page,
        total: result.total,
        total_pages: result.total_pages(),
    })
}

#[derive(askama::Template)]
#[template(path = "curator/haplogroups/page.html")]
struct HgPageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: HgListView,
}

#[derive(askama::Template)]
#[template(path = "curator/haplogroups/list.html")]
struct HgListTemplate {
    t: T,
    list: HgListView,
}

async fn hg_page(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&HgPageTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { display_name: s.display_name, is_curator: true }),
        list,
    }))
}

async fn hg_list(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<ListQuery>,
) -> Result<Response, AppError> {
    let list = load_list(&st, &q).await?;
    Ok(html(&HgListTemplate { t: locale.t, list }))
}

// ── detail panel ─────────────────────────────────────────────────────────────
struct HgDetailView {
    id: i64,
    name: String,
    dna: String,
    lineage: String,
    source: String,
    formed_ybp: String,
    tmrca_ybp: String,
}

#[derive(askama::Template)]
#[template(path = "curator/haplogroups/detail.html")]
struct HgDetailTemplate {
    t: T,
    hg: HgDetailView,
    can_delete: bool,
    error: Option<String>,
}

async fn detail_view(st: &AppState, id: HaplogroupId) -> Result<HgDetailView, AppError> {
    let h = du_db::haplogroup::get_by_id(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("haplogroup {}", id.0)))?;
    Ok(HgDetailView {
        id: h.id.0,
        name: h.name,
        dna: h.haplogroup_type.label().to_string(),
        lineage: h.lineage.unwrap_or_default(),
        source: h.source.unwrap_or_default(),
        formed_ybp: h.formed_ybp.map(|v| v.to_string()).unwrap_or_default(),
        tmrca_ybp: h.tmrca_ybp.map(|v| v.to_string()).unwrap_or_default(),
    })
}

async fn render_detail(
    st: &AppState,
    t: T,
    id: HaplogroupId,
    error: Option<String>,
) -> Result<Response, AppError> {
    let hg = detail_view(st, id).await?;
    let can_delete = !du_db::haplogroup::has_current_edges(&st.pool, id).await?;
    Ok(html(&HgDetailTemplate { t, hg, can_delete, error }))
}

async fn hg_panel(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    render_detail(&st, locale.t, HaplogroupId(id), None).await
}

// ── create / edit forms ──────────────────────────────────────────────────────
#[derive(askama::Template)]
#[template(path = "curator/haplogroups/form.html")]
struct HgFormTemplate {
    t: T,
    action: String,
    is_edit: bool,
    id: i64,
    name: String,
    dna: String,
    lineage: String,
    source: String,
    formed_ybp: String,
    tmrca_ybp: String,
}

async fn hg_new(_c: Curator, locale: Locale) -> Response {
    html(&HgFormTemplate {
        t: locale.t,
        action: "/curator/haplogroups".into(),
        is_edit: false,
        id: 0,
        name: String::new(),
        dna: "Y_DNA".into(),
        lineage: String::new(),
        source: String::new(),
        formed_ybp: String::new(),
        tmrca_ybp: String::new(),
    })
}

async fn hg_edit(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let d = detail_view(&st, HaplogroupId(id)).await?;
    Ok(html(&HgFormTemplate {
        t: locale.t,
        action: format!("/curator/haplogroups/{id}"),
        is_edit: true,
        id,
        name: d.name,
        dna: d.dna,
        lineage: d.lineage,
        source: d.source,
        formed_ybp: d.formed_ybp,
        tmrca_ybp: d.tmrca_ybp,
    }))
}

#[derive(Deserialize)]
struct HgForm {
    name: String,
    dna: Option<String>,
    lineage: Option<String>,
    source: Option<String>,
    formed_ybp: Option<String>,
    tmrca_ybp: Option<String>,
}

fn opt(s: Option<String>) -> Option<String> {
    s.map(|v| v.trim().to_string()).filter(|v| !v.is_empty())
}
fn opt_i32(s: Option<String>) -> Option<i32> {
    s.and_then(|v| v.trim().parse().ok())
}

/// On a successful mutation, return the saved detail panel and trigger the list
/// to reload (server-driven via HX-Trigger).
fn changed(body: Response) -> Response {
    (HxHeaders::new().trigger(CHANGED), body).into_response()
}

async fn hg_create(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Form(f): Form<HgForm>,
) -> Result<Response, AppError> {
    let name = f.name.trim();
    if name.is_empty() {
        return Err(AppError::BadRequest("name is required".into()));
    }
    let dna = parse_dna(f.dna.as_deref()).unwrap_or(DnaType::YDna);
    let id = du_db::haplogroup::create(
        &st.pool,
        name,
        dna,
        opt(f.lineage).as_deref(),
        opt(f.source).as_deref(),
        opt_i32(f.formed_ybp),
        opt_i32(f.tmrca_ybp),
    )
    .await?;
    Ok(changed(render_detail(&st, locale.t, id, None).await?))
}

async fn hg_update(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<HgForm>,
) -> Result<Response, AppError> {
    let name = f.name.trim();
    if name.is_empty() {
        return Err(AppError::BadRequest("name is required".into()));
    }
    du_db::haplogroup::update(
        &st.pool,
        HaplogroupId(id),
        name,
        opt(f.lineage).as_deref(),
        opt(f.source).as_deref(),
        opt_i32(f.formed_ybp),
        opt_i32(f.tmrca_ybp),
    )
    .await?;
    Ok(changed(render_detail(&st, locale.t, HaplogroupId(id), None).await?))
}

async fn hg_delete(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    let hid = HaplogroupId(id);
    if du_db::haplogroup::has_current_edges(&st.pool, hid).await? {
        // Blocked: re-render the detail with an inline error, no reload.
        let msg = locale.t.get("hg.deleteBlocked").to_string();
        return render_detail(&st, locale.t, hid, Some(msg)).await;
    }
    du_db::haplogroup::delete(&st.pool, hid).await?;

    #[derive(askama::Template)]
    #[template(path = "curator/haplogroups/empty.html")]
    struct Empty {
        t: T,
    }
    Ok(changed(html(&Empty { t: locale.t })))
}
