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
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use serde::Deserialize;

const CHANGED: &str = "candidate-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator/publications", get(page))
        .route("/curator/publications/fragment", get(list))
        .route("/curator/publications/:id/panel", get(panel))
        .route("/curator/publications/:id/review", post(review))
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
