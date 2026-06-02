//! Secondary public surfaces: static informational pages (about, FAQ, terms,
//! privacy, app-password help), SEO endpoints (sitemap.xml, robots.txt), and the
//! GDPR cookie-consent record. All read-only/public; no curator gating.

use crate::auth::{MaybeUser, NavUser};
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::State;
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use serde::Deserialize;

/// Bump when the cookie-consent text materially changes (re-prompts users).
const POLICY_VERSION: &str = "2026-06-01";
const CONSENT_COOKIE: &str = "du_consent";
/// ~180 days.
const CONSENT_MAX_AGE: i64 = 15_552_000;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/about", get(|l, u| page("about", l, u)))
        .route("/faq", get(|l, u| page("faq", l, u)))
        .route("/terms", get(|l, u| page("terms", l, u)))
        .route("/privacy", get(|l, u| page("privacy", l, u)))
        .route("/help/app-password", get(|l, u| page("apppw", l, u)))
        .route("/sitemap.xml", get(sitemap))
        .route("/robots.txt", get(robots))
        .route("/cookie-consent", post(cookie_consent))
}

// ── static content pages ──────────────────────────────────────────────────────

#[derive(askama::Template)]
#[template(path = "static/page.html")]
struct PageTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    /// Selects which content block renders + the page heading.
    page: &'static str,
    title: String,
}

async fn page(page: &'static str, locale: Locale, user: MaybeUser) -> Response {
    let title = locale.t.get(&format!("page.{page}.title")).to_string();
    html(&PageTemplate { t: locale.t, next: locale.next, user: user.nav(), page, title })
}

// ── SEO ─────────────────────────────────────────────────────────────────────

/// Canonical site origin for absolute URLs; overridable for deploys.
fn base_url() -> String {
    std::env::var("DU_BASE_URL").unwrap_or_else(|_| "https://decoding-us.com".to_string())
}

/// The public, indexable pages (curator/auth surfaces are intentionally omitted).
const PUBLIC_PATHS: &[&str] =
    &["/", "/ytree", "/mtree", "/variants", "/references", "/coverage-benchmarks", "/about", "/faq", "/terms", "/privacy"];

async fn sitemap() -> Response {
    let base = base_url();
    let mut xml = String::from("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.push_str("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    for p in PUBLIC_PATHS {
        xml.push_str(&format!("  <url><loc>{base}{p}</loc></url>\n"));
    }
    xml.push_str("</urlset>\n");
    ([(header::CONTENT_TYPE, "application/xml; charset=utf-8")], xml).into_response()
}

async fn robots() -> Response {
    let body = format!(
        "User-agent: *\nAllow: /\nDisallow: /curator\nDisallow: /api\nSitemap: {}/sitemap.xml\n",
        base_url()
    );
    ([(header::CONTENT_TYPE, "text/plain; charset=utf-8")], body).into_response()
}

// ── cookie consent ────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct ConsentForm {
    /// "true" to accept non-essential cookies, anything else to decline.
    consent: Option<String>,
}

/// Record a consent decision (attributed to the signed-in user if any) and set
/// the client-side consent cookie so the banner stays dismissed.
async fn cookie_consent(
    State(st): State<AppState>,
    user: MaybeUser,
    headers: HeaderMap,
    Form(f): Form<ConsentForm>,
) -> Result<Response, crate::error::AppError> {
    let accepted = f.consent.as_deref() == Some("true");
    let user_id = user.0.as_ref().map(|s| s.user_id);
    let ua = headers.get(header::USER_AGENT).and_then(|v| v.to_str().ok());
    du_db::consent::record(&st.pool, user_id, accepted, POLICY_VERSION, ua).await?;

    let cookie = format!(
        "{}={}; Path=/; Max-Age={}; SameSite=Lax",
        CONSENT_COOKIE,
        if accepted { "yes" } else { "no" },
        CONSENT_MAX_AGE
    );
    Ok((StatusCode::NO_CONTENT, [(header::SET_COOKIE, cookie)]).into_response())
}
