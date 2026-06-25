//! Curator review of **biosample duplicate candidates** — pairs the Tier-1 engine
//! flagged (same terminal Y + mt, or strong private-variant overlap). A candidate
//! is only a *suspicion*: uniparental markers can't separate a duplicate from a
//! sibling/patriline, so the curator confirms with autosomal Tier-2 evidence and
//! then either **merges** (provenance-preserving), records a **relative** finding,
//! or **dismisses**. Two-panel HTMX screen: the candidate queue (left, highest
//! score first) + a resolve panel (right).

use crate::auth::{Curator, NavUser};
use crate::error::AppError;
use crate::htmx::HxHeaders;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Path, State};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use serde::Deserialize;
use serde_json::Value;
use uuid::Uuid;

const CHANGED: &str = "dedup-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/curator/dedup", get(page))
        .route("/curator/dedup/fragment", get(list))
        .route("/curator/dedup/:id/panel", get(panel))
        .route("/curator/dedup/:id/merge", post(merge))
        .route("/curator/dedup/:id/relative", post(relative))
        .route("/curator/dedup/:id/dismiss", post(dismiss))
}

// ── list ──────────────────────────────────────────────────────────────────────

struct Row {
    id: i64,
    pair: String,
    block_key: String,
    score: i64, // percent, for display
}

struct ListView {
    rows: Vec<Row>,
}

/// `signals.<key>` as a string ("" if absent).
fn sig_str(signals: &Value, key: &str) -> String {
    signals.get(key).and_then(Value::as_str).unwrap_or("").to_string()
}

fn pair_label(signals: &Value, a: Uuid, b: Uuid) -> String {
    let la = sig_str(signals, "accession_a");
    let lb = sig_str(signals, "accession_b");
    let la = if la.is_empty() { a.to_string()[..8].to_string() } else { la };
    let lb = if lb.is_empty() { b.to_string()[..8].to_string() } else { lb };
    format!("{la} / {lb}")
}

async fn load_list(st: &AppState) -> Result<ListView, AppError> {
    let cands = du_db::dedup::list_candidates(&st.pool, Some("CANDIDATE"), 200).await?;
    let rows = cands
        .into_iter()
        .map(|c| Row {
            pair: pair_label(&c.signals, c.sample_a, c.sample_b),
            block_key: c.block_key.unwrap_or_default(),
            score: (c.score * 100.0).round() as i64,
            id: c.id,
        })
        .collect();
    Ok(ListView { rows })
}

#[derive(askama::Template)]
#[template(path = "curator/dedup/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: ListView,
}
#[derive(askama::Template)]
#[template(path = "curator/dedup/list.html")]
struct ListTemplate {
    t: T,
    list: ListView,
}

async fn page(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
) -> Result<Response, AppError> {
    let list = load_list(&st).await?;
    Ok(html(&PageTemplate {
        t: locale.t,
        next: locale.next,
        user: Some(NavUser { display_name: s.display_name, is_curator: true }),
        list,
    }))
}

async fn list(_c: Curator, State(st): State<AppState>, locale: Locale) -> Result<Response, AppError> {
    let list = load_list(&st).await?;
    Ok(html(&ListTemplate { t: locale.t, list }))
}

// ── detail panel ────────────────────────────────────────────────────────────────

struct DetailView {
    id: i64,
    sample_a: String,
    sample_b: String,
    label_a: String,
    label_b: String,
    block_key: String,
    score: i64,
    mt_match: bool,
    shared_private: i64,
    both_public: bool,
    /// Tier-2 routing hint copied from signals.
    route: String,
    status: String,
    resolved: bool,
    notice: Option<String>,
}

#[derive(askama::Template)]
#[template(path = "curator/dedup/detail.html")]
struct DetailTemplate {
    t: T,
    f: DetailView,
}

async fn build_detail(st: &AppState, id: i64, notice: Option<String>) -> Result<DetailView, AppError> {
    let c = du_db::dedup::candidate(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("candidate {id}")))?;
    let label_a = {
        let a = sig_str(&c.signals, "accession_a");
        if a.is_empty() { c.sample_a.to_string()[..8].into() } else { a }
    };
    let label_b = {
        let b = sig_str(&c.signals, "accession_b");
        if b.is_empty() { c.sample_b.to_string()[..8].into() } else { b }
    };
    Ok(DetailView {
        label_a,
        label_b,
        sample_a: c.sample_a.to_string(),
        sample_b: c.sample_b.to_string(),
        block_key: c.block_key.unwrap_or_default(),
        score: (c.score * 100.0).round() as i64,
        mt_match: c.signals.get("mt_match").and_then(Value::as_bool).unwrap_or(false),
        shared_private: c.signals.get("shared_private").and_then(Value::as_i64).unwrap_or(0),
        both_public: c.signals.get("both_public").and_then(Value::as_bool).unwrap_or(false),
        route: sig_str(&c.signals, "tier2_route"),
        resolved: c.status != "CANDIDATE",
        status: c.status,
        notice,
        id,
    })
}

async fn detail_response(st: &AppState, t: T, id: i64, notice: Option<String>) -> Result<Response, AppError> {
    let f = build_detail(st, id, notice).await?;
    Ok(html(&DetailTemplate { t, f }))
}

async fn changed_response(st: &AppState, t: T, id: i64, notice: Option<String>) -> Result<Response, AppError> {
    let body = detail_response(st, t, id, notice).await?;
    Ok((HxHeaders::new().trigger(CHANGED), body).into_response())
}

async fn panel(_c: Curator, State(st): State<AppState>, locale: Locale, Path(id): Path<i64>) -> Result<Response, AppError> {
    detail_response(&st, locale.t, id, None).await
}

// ── actions ──────────────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct MergeForm {
    /// The sample_guid to keep; the other is merged into it.
    survivor: Uuid,
}

async fn merge(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<MergeForm>,
) -> Result<Response, AppError> {
    let c = du_db::dedup::candidate(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("candidate {id}")))?;
    if f.survivor != c.sample_a && f.survivor != c.sample_b {
        return Err(AppError::BadRequest("survivor must be one of the candidate pair".into()));
    }
    let merged = if f.survivor == c.sample_a { c.sample_b } else { c.sample_a };
    let evidence = serde_json::json!({ "candidate_id": id, "signals": c.signals, "via": "curator" });
    let rep = du_db::dedup::merge_biosamples(&st.pool, f.survivor, merged, &s.display_name, Some(id), evidence).await?;
    let notice = format!("{} ({} {})", locale.t.get("dedup.notice.merged"), rep.rows_repointed, locale.t.get("dedup.notice.repointed"));
    changed_response(&st, locale.t, id, Some(notice)).await
}

async fn relative(Curator(s): Curator, State(st): State<AppState>, locale: Locale, Path(id): Path<i64>) -> Result<Response, AppError> {
    du_db::dedup::resolve_candidate(&st.pool, id, "RELATIVE", &s.display_name, None).await?;
    let notice = locale.t.get("dedup.notice.relative").to_string();
    changed_response(&st, locale.t, id, Some(notice)).await
}

async fn dismiss(Curator(s): Curator, State(st): State<AppState>, locale: Locale, Path(id): Path<i64>) -> Result<Response, AppError> {
    du_db::dedup::resolve_candidate(&st.pool, id, "DISMISSED", &s.display_name, None).await?;
    let notice = locale.t.get("dedup.notice.dismissed").to_string();
    changed_response(&st, locale.t, id, Some(notice)).await
}
