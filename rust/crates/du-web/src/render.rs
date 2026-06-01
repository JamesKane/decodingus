//! Renders an Askama template into an HTML response. Used instead of the
//! `askama_axum` IntoResponse integration to keep the rendering path explicit
//! and version-independent.

use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};

pub fn html<T: askama::Template>(t: &T) -> Response {
    match t.render() {
        Ok(body) => (
            [(header::CONTENT_TYPE, "text/html; charset=utf-8")],
            body,
        )
            .into_response(),
        Err(e) => {
            tracing::error!(error = %e, "template render failed");
            (StatusCode::INTERNAL_SERVER_ERROR, "template error").into_response()
        }
    }
}
