//! Router assembly + top-level pages.

use crate::auth::{MaybeUser, NavUser};
use crate::i18n::{Lang, Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Path, Query};
use axum::http::header::{LOCATION, SET_COOKIE};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use serde::Deserialize;
use tower_cookies::CookieManagerLayer;
use tower_http::services::ServeDir;

pub mod auth_routes;
pub mod coverage;
pub mod curation;
pub mod curator;
pub mod curator_regions;
pub mod curator_variants;
pub mod maps;
pub mod references;
pub mod tree;
pub mod variants;
pub mod versioning;

/// Directory holding vendored static assets. Settable for deployment
/// (Dockerfile sets DU_ASSETS_DIR=/app/assets); falls back to the crate's
/// assets dir for local `cargo run`.
fn assets_dir() -> String {
    std::env::var("DU_ASSETS_DIR")
        .unwrap_or_else(|_| concat!(env!("CARGO_MANIFEST_DIR"), "/assets").to_string())
}

/// Full application router (requires a DB-backed AppState).
pub fn app(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/", get(index))
        .route("/language/:lang", get(switch_language))
        .merge(variants::router())
        .merge(tree::router())
        .merge(references::router())
        .merge(maps::router())
        .merge(coverage::router())
        .merge(auth_routes::router())
        .merge(curator::router())
        .merge(curator_variants::router())
        .merge(curator_regions::router())
        .merge(curation::router())
        .merge(versioning::router())
        .merge(crate::oauth::router())
        .merge(crate::api::router())
        .nest_service("/assets", ServeDir::new(assets_dir()))
        .layer(CookieManagerLayer::new())
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
struct IndexTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
}

async fn index(locale: Locale, user: MaybeUser) -> Response {
    html(&IndexTemplate { t: locale.t, next: locale.next, user: user.nav() })
}

#[derive(Deserialize)]
struct NextQuery {
    next: Option<String>,
}

/// Set the `lang` cookie and redirect back. Only same-site relative paths are
/// honored as the redirect target (open-redirect guard, like the legacy app).
async fn switch_language(Path(lang): Path<String>, Query(q): Query<NextQuery>) -> Response {
    let chosen = Lang::parse(&lang).unwrap_or(Lang::En);
    let next = q
        .next
        .filter(|n| n.starts_with('/') && !n.starts_with("//"))
        .unwrap_or_else(|| "/".to_string());
    let cookie = format!(
        "lang={}; Path=/; Max-Age=31536000; SameSite=Lax",
        chosen.code()
    );
    (
        StatusCode::SEE_OTHER,
        [(SET_COOKIE, cookie), (LOCATION, next)],
    )
        .into_response()
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
