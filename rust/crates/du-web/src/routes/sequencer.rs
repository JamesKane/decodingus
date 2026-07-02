//! Sequencer instrument→lab consensus review surfaces. Two faces over the same
//! `du_db::sequencer` proposal queue, both Curator-gated (session + Curator role):
//! a JSON API at `/manage/instrument-proposals/*` (Navigator/programmatic) and a
//! two-panel HTMX review UI at `/curator/instrument-proposals`. Accepting a
//! proposal sets `sequencer_instrument.lab_id` (what the public
//! `/api/v1/sequencer/lab` lookup resolves); accept/reject are audited to
//! `ident.audit_log`. Neither is part of the public OpenAPI document.

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
use axum::{Form, Json, Router};
use du_db::sequencer::{ObservationView, ProposalView};
use serde::Deserialize;
use serde_json::{json, Value};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/manage/instrument-proposals", get(list))
        .route("/manage/instrument-proposals/:id", get(detail))
        .route("/manage/instrument-proposals/:id/accept", post(accept))
        .route("/manage/instrument-proposals/:id/reject", post(reject))
        // Curator HTMX review UI (two-panel) over the same proposal queue.
        .route("/curator/instrument-proposals", get(ui_page))
        .route("/curator/instrument-proposals/fragment", get(ui_list))
        .route("/curator/instrument-proposals/:id/panel", get(ui_panel))
        .route("/curator/instrument-proposals/:id/accept", post(ui_accept))
        .route("/curator/instrument-proposals/:id/reject", post(ui_reject))
        // "Established" tab: maintain already-associated instrument→lab mappings
        // directly (distinct path prefix so it never collides with the `:id` routes
        // above). Same page, second pane.
        .route("/curator/instrument-labs/fragment", get(ui_established_list))
        .route("/curator/instrument-labs/:id/panel", get(ui_established_panel))
        .route("/curator/instrument-labs/:id", post(ui_established_update))
}

fn proposal_json(p: &ProposalView) -> Value {
    json!({
        "id": p.id,
        "instrument_id": p.instrument_id,
        "proposed_lab_name": p.proposed_lab_name,
        "proposed_model": p.proposed_model,
        "observation_count": p.observation_count,
        "distinct_citizen_count": p.distinct_citizen_count,
        "confidence_score": p.confidence_score,
        "status": p.status,
    })
}

fn observation_json(o: &ObservationView) -> Value {
    json!({
        "lab_name": o.lab_name,
        "biosample_ref": o.biosample_ref,
        "platform": o.platform,
        "instrument_model": o.instrument_model,
        "repo_did": o.repo_did,
        "confidence": o.confidence,
    })
}

#[derive(Deserialize)]
struct ListQuery {
    status: Option<String>,
    page: Option<i64>,
    page_size: Option<i64>,
}

async fn list(_cur: Curator, State(st): State<AppState>, Query(q): Query<ListQuery>) -> Result<Json<Value>, AppError> {
    let page = du_db::sequencer::list_proposals(
        &st.pool,
        q.status.as_deref().filter(|s| !s.is_empty()),
        q.page.unwrap_or(1),
        q.page_size.unwrap_or(50),
    )
    .await?;
    Ok(Json(json!({
        "items": page.items.iter().map(proposal_json).collect::<Vec<_>>(),
        "total": page.total,
        "page": page.page,
        "page_size": page.page_size,
    })))
}

async fn detail(_cur: Curator, State(st): State<AppState>, Path(id): Path<i64>) -> Result<Json<Value>, AppError> {
    let (p, obs) = du_db::sequencer::proposal_detail(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("proposal {id}")))?;
    Ok(Json(json!({
        "proposal": proposal_json(&p),
        "observations": obs.iter().map(observation_json).collect::<Vec<_>>(),
    })))
}

#[derive(Deserialize)]
struct AcceptBody {
    /// The lab to associate (may differ from the proposed name).
    lab_name: String,
    manufacturer: Option<String>,
    model: Option<String>,
    is_d2c: Option<bool>,
}

async fn accept(cur: Curator, State(st): State<AppState>, Path(id): Path<i64>, Json(b): Json<AcceptBody>) -> Result<Json<Value>, AppError> {
    let lab_name = b.lab_name.trim();
    if lab_name.is_empty() {
        return Err(AppError::BadRequest("lab_name is required".into()));
    }
    let hit = du_db::sequencer::accept_proposal(
        &st.pool,
        id,
        cur.0.user_id,
        lab_name,
        b.manufacturer.as_deref(),
        b.model.as_deref(),
        b.is_d2c,
    )
    .await?;
    Ok(Json(json!({
        "instrument_id": hit.instrument_id,
        "lab_name": hit.lab_name,
        "is_d2c": hit.is_d2c,
        "manufacturer": hit.manufacturer,
        "model_name": hit.model_name,
        "website_url": hit.website_url,
    })))
}

#[derive(Deserialize, Default)]
struct RejectBody {
    reason: Option<String>,
}

async fn reject(cur: Curator, State(st): State<AppState>, Path(id): Path<i64>, body: Option<Json<RejectBody>>) -> Result<Json<Value>, AppError> {
    let reason = body.and_then(|b| b.0.reason);
    let (instrument, _lab) = du_db::sequencer::reject_proposal(&st.pool, id, cur.0.user_id, reason.as_deref())
        .await?
        .ok_or_else(|| AppError::NotFound(format!("reviewable proposal {id}")))?;
    Ok(Json(json!({ "id": id, "status": "REJECTED", "instrument_id": instrument })))
}

// ── curator HTMX review UI ──────────────────────────────────────────────────

const PROPOSAL_CHANGED: &str = "proposal-changed";

fn status_class(status: &str) -> &'static str {
    match status {
        "READY_FOR_REVIEW" => "text-bg-success",
        "ACCEPTED" => "text-bg-primary",
        "REJECTED" => "text-bg-secondary",
        _ => "text-bg-warning", // PENDING / conflict
    }
}

fn fmt_conf(score: Option<f64>) -> String {
    match score {
        Some(s) => format!("{:.0}%", (s * 100.0).round()),
        None => "—".into(),
    }
}

struct ProposalRow {
    id: i64,
    instrument_id: String,
    lab: String,
    obs: i32,
    citizens: i32,
    confidence: String,
    status: String,
    status_class: String,
}

struct ListView {
    rows: Vec<ProposalRow>,
    /// The active status filter (`ALL` or a concrete status) — drives the chips.
    status: String,
    page: i64,
    total: i64,
    total_pages: i64,
}

#[derive(Deserialize)]
struct UiListQuery {
    status: Option<String>,
    page: Option<i64>,
}

fn proposal_row(p: ProposalView) -> ProposalRow {
    ProposalRow {
        id: p.id,
        instrument_id: p.instrument_id,
        lab: p.proposed_lab_name.unwrap_or_else(|| "—".into()),
        obs: p.observation_count,
        citizens: p.distinct_citizen_count,
        confidence: fmt_conf(p.confidence_score),
        status_class: status_class(&p.status).to_string(),
        status: p.status,
    }
}

async fn load_ui_list(st: &AppState, q: &UiListQuery) -> Result<ListView, AppError> {
    let filter = q.status.as_deref().filter(|s| !s.is_empty() && *s != "ALL");
    let page = du_db::sequencer::list_proposals(&st.pool, filter, q.page.unwrap_or(1), 25).await?;
    let (cur_page, total, total_pages) = (page.page, page.total, page.total_pages());
    Ok(ListView {
        rows: page.items.into_iter().map(proposal_row).collect(),
        status: q.status.clone().filter(|s| !s.is_empty()).unwrap_or_else(|| "ALL".into()),
        page: cur_page,
        total,
        total_pages,
    })
}

#[derive(askama::Template)]
#[template(path = "curator/instrument-proposals/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: ListView,
}
#[derive(askama::Template)]
#[template(path = "curator/instrument-proposals/list.html")]
struct ListTemplate {
    t: T,
    list: ListView,
}

async fn ui_page(Curator(s): Curator, State(st): State<AppState>, locale: Locale, Query(q): Query<UiListQuery>) -> Result<Response, AppError> {
    let list = load_ui_list(&st, &q).await?;
    Ok(html(&PageTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { display_name: s.display_name, is_curator: true }),
        list,
    }))
}

async fn ui_list(_c: Curator, State(st): State<AppState>, locale: Locale, Query(q): Query<UiListQuery>) -> Result<Response, AppError> {
    let list = load_ui_list(&st, &q).await?;
    Ok(html(&ListTemplate { t: locale.t, list }))
}

// ── detail / accept / reject panel ──────────────────────────────────────────

struct ObsRow {
    lab: String,
    platform: String,
    model: String,
    citizen: String,
}

struct DetailView {
    id: i64,
    instrument_id: String,
    proposed_lab: String,
    status: String,
    status_class: String,
    obs_count: i32,
    citizen_count: i32,
    confidence: String,
    observations: Vec<ObsRow>,
    /// PENDING / READY_FOR_REVIEW → show the accept/reject forms.
    actionable: bool,
    notice: Option<String>,
    /// The proposal is gone / terminal — show only the resolved note.
    resolved: bool,
}

#[derive(askama::Template)]
#[template(path = "curator/instrument-proposals/detail.html")]
struct DetailTemplate {
    t: T,
    p: DetailView,
}

/// Last DID path segment, for a compact citizen label.
fn short_did(did: &str) -> String {
    did.rsplit(['/', ':']).next().unwrap_or(did).to_string()
}

async fn build_detail(st: &AppState, id: i64, notice: Option<String>) -> Result<DetailView, AppError> {
    let Some((p, obs)) = du_db::sequencer::proposal_detail(&st.pool, id).await? else {
        return Ok(DetailView {
            id,
            instrument_id: String::new(),
            proposed_lab: String::new(),
            status: String::new(),
            status_class: String::new(),
            obs_count: 0,
            citizen_count: 0,
            confidence: String::new(),
            observations: vec![],
            actionable: false,
            notice,
            resolved: true,
        });
    };
    let actionable = matches!(p.status.as_str(), "PENDING" | "READY_FOR_REVIEW");
    let observations = obs
        .into_iter()
        .map(|o| ObsRow {
            lab: o.lab_name.unwrap_or_else(|| "—".into()),
            platform: o.platform.unwrap_or_default(),
            model: o.instrument_model.unwrap_or_default(),
            citizen: o.repo_did.as_deref().map(short_did).unwrap_or_default(),
        })
        .collect();
    Ok(DetailView {
        id: p.id,
        instrument_id: p.instrument_id,
        proposed_lab: p.proposed_lab_name.unwrap_or_default(),
        status_class: status_class(&p.status).to_string(),
        status: p.status,
        obs_count: p.observation_count,
        citizen_count: p.distinct_citizen_count,
        confidence: fmt_conf(p.confidence_score),
        observations,
        actionable,
        notice,
        resolved: false,
    })
}

async fn detail_response(st: &AppState, t: T, id: i64, notice: Option<String>) -> Result<Response, AppError> {
    Ok(html(&DetailTemplate { t, p: build_detail(st, id, notice).await? }))
}

async fn changed_response(st: &AppState, t: T, id: i64, notice: Option<String>) -> Result<Response, AppError> {
    let body = detail_response(st, t, id, notice).await?;
    Ok((HxHeaders::new().trigger(PROPOSAL_CHANGED), body).into_response())
}

async fn ui_panel(_c: Curator, State(st): State<AppState>, locale: Locale, Path(id): Path<i64>) -> Result<Response, AppError> {
    detail_response(&st, locale.t, id, None).await
}

#[derive(Deserialize)]
struct UiAcceptForm {
    lab_name: String,
    manufacturer: Option<String>,
    model: Option<String>,
    /// Checkbox: present only when ticked. Absent ⇒ leave an existing lab's flag
    /// untouched (the safe default — see `accept_proposal`).
    is_d2c: Option<String>,
}

async fn ui_accept(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<UiAcceptForm>,
) -> Result<Response, AppError> {
    let lab_name = f.lab_name.trim();
    if lab_name.is_empty() {
        return Err(AppError::BadRequest("lab_name is required".into()));
    }
    let clean = |o: Option<String>| o.map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    let manufacturer = clean(f.manufacturer);
    let model = clean(f.model);
    let is_d2c = f.is_d2c.map(|_| true); // ticked ⇒ Some(true); absent ⇒ None
    let hit = du_db::sequencer::accept_proposal(
        &st.pool,
        id,
        s.user_id,
        lab_name,
        manufacturer.as_deref(),
        model.as_deref(),
        is_d2c,
    )
    .await?;
    let notice = format!("{} {}", locale.t.get("ip.notice.accepted"), hit.lab_name);
    changed_response(&st, locale.t, id, Some(notice)).await
}

#[derive(Deserialize)]
struct UiRejectForm {
    reason: Option<String>,
}

async fn ui_reject(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<UiRejectForm>,
) -> Result<Response, AppError> {
    let reason = f.reason.map(|r| r.trim().to_string()).filter(|r| !r.is_empty());
    du_db::sequencer::reject_proposal(&st.pool, id, s.user_id, reason.as_deref())
        .await?
        .ok_or_else(|| AppError::NotFound(format!("reviewable proposal {id}")))?;
    let notice = locale.t.get("ip.notice.rejected").to_string();
    changed_response(&st, locale.t, id, Some(notice)).await
}

// ── "Established" maintenance tab ────────────────────────────────────────────

const ESTABLISHED_CHANGED: &str = "established-changed";

struct EstablishedRow {
    id: i64,
    instrument_id: String,
    model: String,
    lab: String,
    /// True when the instrument has no lab yet — flagged for the curator's eye.
    unassigned: bool,
}

struct EstablishedList {
    rows: Vec<EstablishedRow>,
    page: i64,
    total: i64,
    total_pages: i64,
}

#[derive(Deserialize)]
struct EstablishedQuery {
    query: Option<String>,
    page: Option<i64>,
}

async fn load_established(st: &AppState, q: &EstablishedQuery) -> Result<EstablishedList, AppError> {
    let page = du_db::sequencer::list_established(&st.pool, q.query.as_deref(), q.page.unwrap_or(1), 25).await?;
    let (cur_page, total, total_pages) = (page.page, page.total, page.total_pages());
    Ok(EstablishedList {
        rows: page
            .items
            .into_iter()
            .map(|r| EstablishedRow {
                id: r.id,
                instrument_id: r.instrument_id,
                model: r.model_name.unwrap_or_default(),
                lab: r.lab_name.clone().unwrap_or_default(),
                unassigned: r.lab_name.is_none(),
            })
            .collect(),
        page: cur_page,
        total,
        total_pages,
    })
}

#[derive(askama::Template)]
#[template(path = "curator/instrument-proposals/established-list.html")]
struct EstablishedListTemplate {
    t: T,
    list: EstablishedList,
}

async fn ui_established_list(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Query(q): Query<EstablishedQuery>,
) -> Result<Response, AppError> {
    let list = load_established(&st, &q).await?;
    Ok(html(&EstablishedListTemplate { t: locale.t, list }))
}

struct EstablishedDetail {
    id: i64,
    instrument_id: String,
    lab: String,
    manufacturer: String,
    model: String,
    is_d2c: bool,
    unassigned: bool,
    /// Existing lab names for the reassignment datalist.
    labs: Vec<String>,
    notice: Option<String>,
}

#[derive(askama::Template)]
#[template(path = "curator/instrument-proposals/established-detail.html")]
struct EstablishedDetailTemplate {
    t: T,
    e: EstablishedDetail,
}

async fn build_established_detail(st: &AppState, id: i64, notice: Option<String>) -> Result<EstablishedDetail, AppError> {
    let r = du_db::sequencer::established_detail(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("instrument {id}")))?;
    let labs = du_db::sequencer::list_labs(&st.pool).await?.into_iter().map(|l| l.name).collect();
    Ok(EstablishedDetail {
        id: r.id,
        instrument_id: r.instrument_id,
        lab: r.lab_name.clone().unwrap_or_default(),
        manufacturer: r.manufacturer.unwrap_or_default(),
        model: r.model_name.unwrap_or_default(),
        is_d2c: r.is_d2c.unwrap_or(false),
        unassigned: r.lab_name.is_none(),
        labs,
        notice,
    })
}

async fn established_detail_response(st: &AppState, t: T, id: i64, notice: Option<String>) -> Result<Response, AppError> {
    Ok(html(&EstablishedDetailTemplate { t, e: build_established_detail(st, id, notice).await? }))
}

async fn ui_established_panel(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    established_detail_response(&st, locale.t, id, None).await
}

#[derive(Deserialize)]
struct EstablishedForm {
    lab_name: String,
    manufacturer: Option<String>,
    model: Option<String>,
    /// Checkbox: present only when ticked (absent ⇒ leave the lab's flag untouched).
    is_d2c: Option<String>,
}

async fn ui_established_update(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<EstablishedForm>,
) -> Result<Response, AppError> {
    let lab_name = f.lab_name.trim();
    if lab_name.is_empty() {
        return Err(AppError::BadRequest("lab_name is required".into()));
    }
    let clean = |o: Option<String>| o.map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    let manufacturer = clean(f.manufacturer);
    let model = clean(f.model);
    let is_d2c = f.is_d2c.map(|_| true); // ticked ⇒ Some(true); absent ⇒ None
    let hit = du_db::sequencer::update_instrument_lab(
        &st.pool,
        s.user_id,
        id,
        lab_name,
        manufacturer.as_deref(),
        model.as_deref(),
        is_d2c,
    )
    .await?;
    let notice = format!("{} {}", locale.t.get("il.notice.saved"), hit.lab_name);
    let body = established_detail_response(&st, locale.t, id, Some(notice)).await?;
    Ok((HxHeaders::new().trigger(ESTABLISHED_CHANGED), body).into_response())
}

#[cfg(test)]
mod tests {
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt;

    /// The proposal queue sits behind the Curator guard — an unauthenticated
    /// request is redirected to /login, never served.
    #[tokio::test]
    async fn proposals_require_curator() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping curator-guard test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let state = crate::state::AppState { pool: db.pool().clone(), key: tower_cookies::Key::generate(), oauth: None };
        // Both the JSON management API and the HTMX curator UI are guarded.
        for uri in ["/manage/instrument-proposals", "/curator/instrument-proposals"] {
            let app = crate::routes::app(state.clone());
            let r = app
                .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
                .await
                .unwrap();
            assert_eq!(r.status(), StatusCode::SEE_OTHER, "{uri} must redirect unauth");
        }
    }
}
