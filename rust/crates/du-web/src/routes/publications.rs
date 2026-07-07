//! Curator **publication-candidate review** UI. The publication-discovery job
//! (OpenAlex) writes candidates into `pubs.publication_candidate`; curators
//! triage them here. Two-panel HTMX screen mirroring the proposals UI: a status-
//! filtered queue (left) and a review panel (right) with Accept (promote to a
//! real `pubs.publication`) / Reject / Defer.

use crate::auth::{Curator, NavUser};
use crate::error::AppError;
use crate::htmx::HxHeaders;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use crate::extract::Query;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Json, Router};
use serde::Deserialize;
use serde_json::json;

const CHANGED: &str = "candidate-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator/publications", get(page))
        .route("/curator/publications/fragment", get(list))
        .route("/curator/publications/:id/panel", get(panel))
        .route("/curator/publications/:id/review", post(review))
        // Curator (session): attach ENA/NCBI project(s) to an accepted candidate's paper.
        .route("/curator/publications/:id/projects", post(attach_projects_curator))
        // Machine (X-API-Key): attach project(s) to a publication by id (ops/scripts).
        .route("/manage/publications/:id/projects", post(attach_projects_api))
}

/// Require the curation/ops API key (X-API-Key == DU_CURATION_API_KEY).
fn check_api_key(headers: &HeaderMap) -> Result<(), AppError> {
    match std::env::var("DU_CURATION_API_KEY").ok().filter(|s| !s.is_empty()) {
        None => Err(AppError::Upstream("curation API not configured".into())),
        Some(expected) => {
            let provided = headers.get("x-api-key").and_then(|v| v.to_str().ok()).unwrap_or("");
            if provided == expected {
                Ok(())
            } else {
                Err(AppError::Forbidden)
            }
        }
    }
}

/// Parse a free-form list of project accessions (comma/whitespace separated),
/// upsert each study, link it to `publication_id`, and queue it for crawling.
/// Returns the number of projects attached.
async fn attach_projects(st: &AppState, publication_id: i64, raw: &str) -> Result<usize, AppError> {
    let accs: Vec<String> = raw
        .split(|c: char| c == ',' || c.is_whitespace())
        .map(|s| s.trim().to_ascii_uppercase())
        .filter(|s| !s.is_empty())
        .collect();
    if accs.is_empty() {
        return Err(AppError::BadRequest("no project accessions supplied".into()));
    }
    // Fail loudly if the paper doesn't exist rather than FK-erroring mid-loop.
    if du_db::publication::get_by_id(&st.pool, du_domain::ids::PublicationId(publication_id)).await?.is_none() {
        return Err(AppError::NotFound(format!("publication {publication_id}")));
    }
    for acc in &accs {
        let source = du_db::study::source_for_accession(acc);
        let study_id = du_db::study::upsert_by_accession(&st.pool, acc, source).await?;
        du_db::study::link_publication(&st.pool, publication_id, study_id).await?;
        du_db::study::request_crawl(&st.pool, study_id).await?;
    }
    Ok(accs.len())
}

#[derive(Deserialize)]
struct AttachProjectsApi {
    /// Project accessions (ENA `PRJEB…`/`ERP…` or NCBI BioProject `PRJNA…`).
    projects: Vec<String>,
}

/// `POST /manage/publications/:id/projects` — attach project(s) to a publication
/// and queue the crawl. 403 without the key, 404 for an unknown publication.
async fn attach_projects_api(
    State(st): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<i64>,
    Json(body): Json<AttachProjectsApi>,
) -> Result<Response, AppError> {
    check_api_key(&headers)?;
    let attached = attach_projects(&st, id, &body.projects.join(" ")).await?;
    Ok((
        StatusCode::ACCEPTED,
        Json(json!({ "publication_id": id, "attached": attached, "crawl": "pending" })),
    )
        .into_response())
}

#[derive(Deserialize)]
struct AttachForm {
    projects: String,
}

/// Curator panel action: attach project(s) to the paper this (accepted) candidate
/// was promoted to. Re-renders the panel with a notice.
async fn attach_projects_curator(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(cand_id): Path<i64>,
    Form(f): Form<AttachForm>,
) -> Result<Response, AppError> {
    let pub_id = du_db::publication::publication_for_candidate(&st.pool, cand_id)
        .await?
        .ok_or_else(|| AppError::BadRequest("accept the paper before attaching projects".into()))?;
    let n = attach_projects(&st, pub_id.0, &f.projects).await?;
    let notice = format!("{} ({n})", locale.t.get("pc.projects.queued"));
    changed_response(&st, locale.t, cand_id, Some(notice)).await
}

// ── list ──────────────────────────────────────────────────────────────────────

struct Row {
    id: i64,
    title: String,
    journal: String,
    date: String,
    status: String,
    relevance: String,
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

fn fmt_date(d: Option<chrono::NaiveDate>) -> String {
    d.map(|d| d.to_string()).unwrap_or_else(|| "—".into())
}

fn to_row(c: du_db::publication::Candidate) -> Row {
    Row {
        id: c.id,
        title: c.title.unwrap_or_else(|| "(untitled)".into()),
        journal: c.journal_name.unwrap_or_else(|| "—".into()),
        date: fmt_date(c.publication_date),
        status: c.status,
        relevance: c.relevance_score.map(|r| format!("{r:.2}")).unwrap_or_else(|| "—".into()),
    }
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<ListView, AppError> {
    // Default the queue to the pending items (the actionable ones).
    let status = q.status.clone().unwrap_or_else(|| "pending".into());
    let filter = if status.is_empty() { None } else { Some(status.as_str()) };
    let result = du_db::publication::list_candidates(&st.pool, filter, q.page.unwrap_or(1), 20).await?;
    let (page, total, total_pages) = (result.page, result.total, result.total_pages());
    Ok(ListView {
        status,
        rows: result.items.into_iter().map(to_row).collect(),
        page,
        total,
        total_pages,
    })
}

#[derive(askama::Template)]
#[template(path = "curator/publications/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: ListView,
}
#[derive(askama::Template)]
#[template(path = "curator/publications/list.html")]
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

struct DetailView {
    id: i64,
    title: String,
    journal: String,
    date: String,
    doi: Option<String>,
    doi_url: Option<String>,
    openalex_id: String,
    relevance: String,
    status: String,
    abstract_text: Option<String>,
    /// Not yet accepted → the action buttons are live.
    can_act: bool,
    notice: Option<String>,
}

#[derive(askama::Template)]
#[template(path = "curator/publications/detail.html")]
struct DetailTemplate {
    t: T,
    c: DetailView,
}

async fn build_detail(st: &AppState, id: i64, notice: Option<String>) -> Result<DetailView, AppError> {
    let c = du_db::publication::get_candidate(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("candidate {id}")))?;
    let doi = c.doi.filter(|d| !d.trim().is_empty());
    let doi_url = doi.as_ref().map(|d| format!("https://doi.org/{d}"));
    Ok(DetailView {
        id: c.id,
        title: c.title.unwrap_or_else(|| "(untitled)".into()),
        journal: c.journal_name.unwrap_or_else(|| "—".into()),
        date: fmt_date(c.publication_date),
        doi,
        doi_url,
        openalex_id: c.openalex_id,
        relevance: c.relevance_score.map(|r| format!("{r:.2}")).unwrap_or_else(|| "—".into()),
        status: c.status.clone(),
        abstract_text: c.abstract_text.filter(|a| !a.trim().is_empty()),
        can_act: c.status != "accepted",
        notice,
    })
}

async fn detail_response(st: &AppState, t: T, id: i64, notice: Option<String>) -> Result<Response, AppError> {
    let c = build_detail(st, id, notice).await?;
    Ok(html(&DetailTemplate { t, c }))
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
struct ReviewForm {
    /// accept | reject | defer
    action: String,
}

async fn review(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<ReviewForm>,
) -> Result<Response, AppError> {
    let notice = match f.action.as_str() {
        "accept" => match du_db::publication::promote_candidate(&st.pool, id, s.user_id).await {
            Ok(pid) => format!("{} (#{})", locale.t.get("pc.notice.accepted"), pid.0),
            Err(du_db::DbError::Conflict(msg)) => msg,
            Err(e) => return Err(e.into()),
        },
        "reject" => {
            du_db::publication::review_candidate(&st.pool, id, "rejected", s.user_id).await?;
            locale.t.get("pc.notice.rejected").to_string()
        }
        _ => {
            du_db::publication::review_candidate(&st.pool, id, "deferred", s.user_id).await?;
            locale.t.get("pc.notice.deferred").to_string()
        }
    };
    changed_response(&st, locale.t, id, Some(notice)).await
}
