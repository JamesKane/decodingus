//! Curation proposals. Navigator submits variant/branch proposals to a
//! machine-authenticated intake endpoint; curators review/promote them in the
//! web UI. (Intake auth is an X-API-Key for now; it becomes the OAuth bearer
//! once the handshake with Edge is live.)

use crate::auth::{Curator, NavUser};
use crate::error::AppError;
use crate::htmx::HxHeaders;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Json, Router};
use du_db::proposal::SubmitProposal;
use du_domain::enums::DnaType;
use serde::Deserialize;
use serde_json::Value;
use uuid::Uuid;

const CHANGED: &str = "proposal-changed";

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/manage/curation/proposals", post(intake))
        .route("/curator/proposals", get(page))
        .route("/curator/proposals/fragment", get(list))
        .route("/curator/proposals/:id/panel", get(panel))
        .route("/curator/proposals/:id/review", post(review))
        .route("/curator/proposals/:id/promote", post(promote))
}

// ── intake (Navigator → AppView) ─────────────────────────────────────────────
#[derive(Deserialize)]
struct ProposalIn {
    proposed_name: String,
    parent_haplogroup: Option<String>,
    dna_type: Option<String>,
    sample_guid: Option<Uuid>,
    proposed_by: Option<String>,
    evidence: Option<Value>,
}

fn parse_dna(s: Option<&str>) -> DnaType {
    match s {
        Some("MT_DNA") => DnaType::MtDna,
        _ => DnaType::YDna,
    }
}

/// Require the curation API key (X-API-Key == DU_CURATION_API_KEY).
fn check_api_key(headers: &HeaderMap) -> Result<(), AppError> {
    match std::env::var("DU_CURATION_API_KEY").ok().filter(|s| !s.is_empty()) {
        None => Err(AppError::Upstream("curation intake not configured".into())),
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

async fn intake(
    State(st): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<ProposalIn>,
) -> Result<Response, AppError> {
    check_api_key(&headers)?;
    if body.proposed_name.trim().is_empty() {
        return Err(AppError::BadRequest("proposed_name is required".into()));
    }
    let submit = SubmitProposal {
        proposed_name: body.proposed_name.trim().to_string(),
        parent_haplogroup: body.parent_haplogroup,
        dna_type: parse_dna(body.dna_type.as_deref()),
        sample_guid: body.sample_guid,
        proposed_by: body.proposed_by,
        evidence: body.evidence.unwrap_or_else(|| serde_json::json!({})),
    };
    let (id, created) = du_db::proposal::submit(&st.pool, &submit).await?;
    Ok((
        StatusCode::CREATED,
        Json(serde_json::json!({ "id": id, "pooled": !created })),
    )
        .into_response())
}

// ── curator review queue ─────────────────────────────────────────────────────
struct Row {
    id: i64,
    name: String,
    parent: String,
    dna: String,
    status: String,
    evidence_count: i32,
    submitter_count: i32,
    confidence: String,
}

/// A defining variant of a proposal (name + cross-submitter support).
struct VarRow {
    name: String,
    support: i32,
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

fn to_row(s: du_db::proposal::ProposalSummary) -> Row {
    Row {
        id: s.id,
        name: s.proposed_name.filter(|n| !n.is_empty()).unwrap_or_else(|| "(unnamed)".into()),
        parent: s.parent_name.unwrap_or_else(|| "—".into()),
        dna: s.dna_type.unwrap_or_default(),
        status: s.status,
        evidence_count: s.evidence_count,
        submitter_count: s.submitter_count,
        confidence: s.confidence.map(|c| format!("{c:.2}")).unwrap_or_else(|| "—".into()),
    }
}

async fn load_list(st: &AppState, q: &ListQuery) -> Result<ListView, AppError> {
    let status = q.status.as_deref().filter(|s| !s.is_empty());
    let filter = du_db::proposal::ProposalFilter { status, ..Default::default() };
    let result = du_db::proposal::list(&st.pool, &filter, q.page.unwrap_or(1), 20).await?;
    let (page, total, total_pages) = (result.page, result.total, result.total_pages());
    Ok(ListView {
        status: q.status.clone().unwrap_or_default(),
        rows: result.items.into_iter().map(to_row).collect(),
        page,
        total,
        total_pages,
    })
}

#[derive(askama::Template)]
#[template(path = "curator/proposals/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    list: ListView,
}
#[derive(askama::Template)]
#[template(path = "curator/proposals/list.html")]
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

#[derive(askama::Template)]
#[template(path = "curator/proposals/detail.html")]
struct DetailTemplate {
    t: T,
    row: Row,
    variants: Vec<VarRow>,
    evidence: Vec<String>,
    /// True while still open to a decision (PROPOSED / UNDER_REVIEW /
    /// READY_FOR_REVIEW / SPLIT_CANDIDATE — the engine's actionable states).
    open: bool,
    /// True when ACCEPTED and not yet promoted (show the Promote button).
    accepted: bool,
    /// The engine flagged a diverging submitter — show the split banner.
    split: bool,
}

async fn detail_view(st: &AppState, t: T, id: i64) -> Result<Response, AppError> {
    let d = du_db::proposal::get(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("proposal {id}")))?;
    let open = matches!(
        d.summary.status.as_str(),
        "PROPOSED" | "UNDER_REVIEW" | "READY_FOR_REVIEW" | "SPLIT_CANDIDATE"
    );
    let accepted = d.summary.status == "ACCEPTED";
    let split = d.summary.status == "SPLIT_CANDIDATE";
    let row = to_row(d.summary);
    let variants = d
        .variants
        .into_iter()
        .map(|v| VarRow { name: v.name.unwrap_or_else(|| "(unnamed)".into()), support: v.supporting_sample_count })
        .collect();
    let evidence = d
        .evidence
        .iter()
        .map(|v| serde_json::to_string_pretty(v).unwrap_or_default())
        .collect();
    Ok(html(&DetailTemplate { t, row, variants, evidence, open, accepted, split }))
}

async fn panel(
    _c: Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    detail_view(&st, locale.t, id).await
}

#[derive(Deserialize)]
struct ReviewForm {
    action: String,
    notes: Option<String>,
}

async fn review(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
    Form(f): Form<ReviewForm>,
) -> Result<Response, AppError> {
    let notes = f.notes.as_deref().filter(|n| !n.trim().is_empty());
    let ok = du_db::proposal::review(&st.pool, id, &f.action, &s.display_name, notes).await?;
    if !ok {
        return Err(AppError::NotFound(format!("proposal {id}")));
    }
    let body = detail_view(&st, locale.t, id).await?;
    Ok((HxHeaders::new().trigger(CHANGED), body).into_response())
}

/// Promote an accepted proposal into the named catalog (new haplogroup branch +
/// relationship + variant links). Conflicts (wrong status, name taken, no parent)
/// surface as a 422 message.
async fn promote(
    Curator(s): Curator,
    State(st): State<AppState>,
    locale: Locale,
    Path(id): Path<i64>,
) -> Result<Response, AppError> {
    match du_db::proposal::promote(&st.pool, id, &s.display_name).await {
        Ok(_) => {
            let body = detail_view(&st, locale.t, id).await?;
            Ok((HxHeaders::new().trigger(CHANGED), body).into_response())
        }
        Err(du_db::DbError::Conflict(msg)) => Err(AppError::BadRequest(msg)),
        Err(e) => Err(e.into()),
    }
}
