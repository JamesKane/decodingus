//! HTTP handlers. Grows into submodules (public, curator, api, federation) as
//! subsystems are ported. For now: the health check.

use axum::http::StatusCode;

/// Load-balancer / container health check. Mirrors the Play `/health` route.
pub async fn health() -> (StatusCode, &'static str) {
    (StatusCode::OK, "ok")
}

#[cfg(test)]
mod tests {
    use crate::build_router;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt; // for `oneshot`

    #[tokio::test]
    async fn health_returns_ok() {
        let app = build_router();
        let resp = app
            .oneshot(Request::builder().uri("/health").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
    }
}
