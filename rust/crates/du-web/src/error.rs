//! Handler error type. Maps data-layer failures to HTTP responses.

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};

pub enum AppError {
    Db(du_db::DbError),
    NotFound(String),
    Forbidden,
    /// A user-facing validation/conflict message rendered as 422.
    BadRequest(String),
}

impl From<du_db::DbError> for AppError {
    fn from(e: du_db::DbError) -> Self {
        AppError::Db(e)
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
        }
    }
}
