//! Tree-versioning management API (`/api/v1/manage/change-sets/*`). Curator-
//! gated (session + Curator role), JSON in/out. Backs the change-set review and
//! apply workflow over `du_db::change_set`.
//!
//! Auth note: the legacy app gated these with an X-API-Key; here they use the
//! same session/Curator guard as the rest of the curator surface. They are not
//! part of the public OpenAPI document (which describes the unauthenticated read
//! API only).

use crate::auth::Curator;
use crate::error::AppError;
use crate::state::AppState;
use axum::extract::{Path, Query, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/v1/manage/change-sets", get(list).post(create))
        .route("/api/v1/manage/change-sets/:id", get(detail))
        .route("/api/v1/manage/change-sets/:id/changes", post(add_change))
        .route("/api/v1/manage/change-sets/:id/start-review", post(start_review))
        .route("/api/v1/manage/change-sets/:id/apply", post(apply))
        .route("/api/v1/manage/change-sets/:id/discard", post(discard))
        .route("/api/v1/manage/change-sets/:id/comments", get(list_comments).post(add_comment))
        .route("/api/v1/manage/change-sets/:id/approve-all", post(approve_all))
        .route("/api/v1/manage/change-sets/:id/changes/:change_id/review", post(review_change))
        .route("/api/v1/manage/change-sets/:id/diff", get(diff))
}

#[derive(Deserialize)]
struct ListQuery {
    haplogroup_type: Option<String>,
    status: Option<String>,
    page: Option<i64>,
    page_size: Option<i64>,
}

async fn list(
    _cur: Curator,
    State(st): State<AppState>,
    Query(q): Query<ListQuery>,
) -> Result<Json<Value>, AppError> {
    let page = du_db::change_set::list(
        &st.pool,
        q.haplogroup_type.as_deref(),
        q.status.as_deref(),
        q.page.unwrap_or(1),
        q.page_size.unwrap_or(20),
    )
    .await?;
    let total_pages = page.total_pages();
    Ok(Json(json!({
        "items": page.items, "total": page.total, "page": page.page,
        "page_size": page.page_size, "total_pages": total_pages
    })))
}

#[derive(Deserialize)]
struct CreateBody {
    source: String,
    haplogroup_type: Option<String>,
    description: Option<String>,
}

async fn create(
    cur: Curator,
    State(st): State<AppState>,
    Json(b): Json<CreateBody>,
) -> Result<Json<Value>, AppError> {
    let id = du_db::change_set::create(
        &st.pool,
        &b.source,
        b.haplogroup_type.as_deref(),
        b.description.as_deref(),
        &cur.0.display_name,
    )
    .await?;
    Ok(Json(json!({ "id": id })))
}

async fn detail(_cur: Curator, State(st): State<AppState>, Path(id): Path<i64>) -> Result<Json<Value>, AppError> {
    let d = du_db::change_set::get(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("change set {id}")))?;
    Ok(Json(json!({ "summary": d.summary, "changes": d.changes, "comments": d.comments })))
}

#[derive(Deserialize)]
struct AddChangeBody {
    change_type: String,
    haplogroup_id: Option<i64>,
    old_values: Option<Value>,
    new_values: Option<Value>,
}

async fn add_change(
    _cur: Curator,
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Json(b): Json<AddChangeBody>,
) -> Result<Json<Value>, AppError> {
    let change_id = du_db::change_set::add_change(
        &st.pool,
        id,
        &b.change_type,
        b.haplogroup_id,
        b.old_values.as_ref(),
        b.new_values.as_ref(),
    )
    .await?;
    Ok(Json(json!({ "id": change_id })))
}

async fn start_review(_cur: Curator, State(st): State<AppState>, Path(id): Path<i64>) -> Result<Json<Value>, AppError> {
    if du_db::change_set::start_review(&st.pool, id).await? {
        Ok(Json(json!({ "status": "UNDER_REVIEW" })))
    } else {
        Err(AppError::BadRequest("change set is not in a reviewable state".into()))
    }
}

async fn apply(cur: Curator, State(st): State<AppState>, Path(id): Path<i64>) -> Result<Json<Value>, AppError> {
    let result = du_db::change_set::apply(&st.pool, id, &cur.0.display_name).await?;
    Ok(Json(json!({ "status": "APPLIED", "result": result })))
}

async fn discard(cur: Curator, State(st): State<AppState>, Path(id): Path<i64>) -> Result<Json<Value>, AppError> {
    if du_db::change_set::discard(&st.pool, id, &cur.0.display_name).await? {
        Ok(Json(json!({ "status": "DISCARDED" })))
    } else {
        Err(AppError::BadRequest("change set cannot be discarded (already applied?)".into()))
    }
}

async fn list_comments(_cur: Curator, State(st): State<AppState>, Path(id): Path<i64>) -> Result<Json<Value>, AppError> {
    let d = du_db::change_set::get(&st.pool, id)
        .await?
        .ok_or_else(|| AppError::NotFound(format!("change set {id}")))?;
    Ok(Json(json!(d.comments)))
}

#[derive(Deserialize)]
struct CommentBody {
    comment: String,
}

async fn add_comment(
    cur: Curator,
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Json(b): Json<CommentBody>,
) -> Result<Json<Value>, AppError> {
    let cid = du_db::change_set::add_comment(&st.pool, id, &cur.0.display_name, &b.comment).await?;
    Ok(Json(json!({ "id": cid })))
}

async fn approve_all(_cur: Curator, State(st): State<AppState>, Path(id): Path<i64>) -> Result<Json<Value>, AppError> {
    let n = du_db::change_set::approve_all(&st.pool, id).await?;
    Ok(Json(json!({ "approved": n })))
}

#[derive(Deserialize)]
struct ReviewBody {
    approve: bool,
}

async fn review_change(
    _cur: Curator,
    State(st): State<AppState>,
    Path((_id, change_id)): Path<(i64, i64)>,
    Json(b): Json<ReviewBody>,
) -> Result<Json<Value>, AppError> {
    if du_db::change_set::review_change(&st.pool, change_id, b.approve).await? {
        Ok(Json(json!({ "status": if b.approve { "APPROVED" } else { "REJECTED" } })))
    } else {
        Err(AppError::BadRequest("change cannot be reviewed (set already applied/discarded?)".into()))
    }
}

async fn diff(_cur: Curator, State(st): State<AppState>, Path(id): Path<i64>) -> Result<Json<Value>, AppError> {
    let d = du_db::change_set::diff(&st.pool, id).await?;
    Ok(Json(json!(d)))
}
