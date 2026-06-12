//! Sequencer instrument→lab consensus review API (`/manage/instrument-proposals/*`).
//! Curator-gated (session + Curator role), JSON in/out. Backs the proposal queue
//! over `du_db::sequencer` — accepting a proposal sets `sequencer_instrument.lab_id`
//! (what the public `/api/v1/sequencer/lab` lookup resolves). Accept/reject are
//! audited to `ident.audit_log`. Not part of the public OpenAPI document.

use crate::auth::Curator;
use crate::error::AppError;
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use du_db::sequencer::{ObservationView, ProposalView};
use serde::Deserialize;
use serde_json::{json, Value};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/manage/instrument-proposals", get(list))
        .route("/manage/instrument-proposals/:id", get(detail))
        .route("/manage/instrument-proposals/:id/accept", post(accept))
        .route("/manage/instrument-proposals/:id/reject", post(reject))
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
        lab_name,
        b.manufacturer.as_deref(),
        b.model.as_deref(),
        b.is_d2c.unwrap_or(false),
    )
    .await?;
    let new = json!({ "instrument_id": hit.instrument_id, "lab_name": hit.lab_name, "is_d2c": hit.is_d2c });
    du_db::audit::log(&st.pool, cur.0.user_id, "instrument_proposal", id, "ACCEPT", None, Some(&new), None).await?;
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
    let (instrument, lab) = du_db::sequencer::reject_proposal(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("reviewable proposal {id}")))?;
    let new = json!({ "instrument_id": instrument, "rejected_lab": lab });
    du_db::audit::log(&st.pool, cur.0.user_id, "instrument_proposal", id, "REJECT", None, Some(&new), reason.as_deref()).await?;
    Ok(Json(json!({ "id": id, "status": "REJECTED", "instrument_id": instrument })))
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
        let app = crate::routes::app(state);
        let r = app
            .oneshot(Request::builder().uri("/manage/instrument-proposals").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::SEE_OTHER);
    }
}
