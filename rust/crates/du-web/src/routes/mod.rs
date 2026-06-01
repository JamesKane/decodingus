//! Router assembly + top-level pages.

use crate::render::html;
use crate::state::AppState;
use axum::http::StatusCode;
use axum::response::Response;
use axum::routing::get;
use axum::Router;

pub mod tree;
pub mod variants;

/// Full application router (requires a DB-backed AppState).
pub fn app(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/", get(index))
        .merge(variants::router())
        .merge(tree::router())
        .with_state(state)
}

/// Health-only router for environments without a database (and for tests).
pub fn health_only() -> Router {
    Router::new().route("/health", get(health))
}

async fn health() -> (StatusCode, &'static str) {
    (StatusCode::OK, "ok")
}

#[derive(askama::Template)]
#[template(path = "index.html")]
struct IndexTemplate;

async fn index() -> Response {
    html(&IndexTemplate)
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt;

    #[tokio::test]
    async fn health_returns_ok() {
        let resp = health_only()
            .oneshot(Request::builder().uri("/health").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
    }
}
