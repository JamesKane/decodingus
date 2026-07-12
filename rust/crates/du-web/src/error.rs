//! Handler error type. Maps data-layer failures to HTTP responses.

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};

pub enum AppError {
    Db(du_db::DbError),
    NotFound(String),
    Forbidden,
    /// A user-facing validation/conflict message rendered as 422.
    BadRequest(String),
    /// An upstream/federation call failed (DID resolution, PDS, OAuth) — 502.
    Upstream(String),
    /// An unexpected server-side failure (e.g. response serialization) — 500.
    Internal(String),
}

impl From<du_db::DbError> for AppError {
    fn from(e: du_db::DbError) -> Self {
        // A surfaced precondition/uniqueness conflict is a client error (422),
        // not a 500.
        match e {
            du_db::DbError::Conflict(msg) => AppError::BadRequest(msg),
            other => AppError::Db(other),
        }
    }
}

impl From<du_atproto::AtprotoError> for AppError {
    fn from(e: du_atproto::AtprotoError) -> Self {
        AppError::Upstream(e.to_string())
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        match self {
            AppError::Db(e) => {
                tracing::error!(error = %e, "database error");
                (StatusCode::INTERNAL_SERVER_ERROR, "internal server error").into_response()
            }
            AppError::NotFound(what) => (StatusCode::NOT_FOUND, format!("not found: {what}")).into_response(),
            AppError::Forbidden => (StatusCode::FORBIDDEN, "forbidden").into_response(),
            AppError::BadRequest(msg) => (StatusCode::UNPROCESSABLE_ENTITY, msg).into_response(),
            AppError::Upstream(msg) => {
                tracing::warn!(error = %msg, "upstream/federation error");
                (StatusCode::BAD_GATEWAY, "upstream error").into_response()
            }
            AppError::Internal(msg) => {
                tracing::error!(error = %msg, "internal error");
                (StatusCode::INTERNAL_SERVER_ERROR, "internal server error").into_response()
            }
        }
    }
}
