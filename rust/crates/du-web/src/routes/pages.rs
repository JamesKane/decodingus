//! Secondary surfaces: static informational pages (about, FAQ, terms, privacy,
//! cookies, reputation), SEO endpoints (sitemap.xml, robots.txt), the GDPR
//! cookie-consent record, the signed-in user's profile, and the public contact
//! form (reCAPTCHA-protected when configured).

use crate::auth::{MaybeUser, NavUser};
use crate::error::AppError;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::State;
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use du_domain::ids::UserId;
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
        .route("/cookies", get(|l, u| page("cookies", l, u)))
        .route("/reputation", get(|l, u| page("reputation", l, u)))
        .route("/sitemap.xml", get(sitemap))
        .route("/robots.txt", get(robots))
        .route("/cookie-consent", post(cookie_consent))
        .route("/profile", get(profile_page).post(profile_update))
        .route("/contact", get(contact_form).post(contact_submit))
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
    &["/", "/ytree", "/mtree", "/variants", "/references", "/coverage-benchmarks", "/about", "/contact",
      "/reputation", "/terms", "/privacy", "/cookies", "/faq"];

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

// ── profile (signed-in user's own account) ─────────────────────────────────────

#[derive(askama::Template)]
#[template(path = "account/profile.html")]
struct ProfileTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    display_name: String,
    roles: String,
    email: Option<String>,
    did: Option<String>,
    handle: Option<String>,
    member_since: String,
    /// Shows a "saved" confirmation after an update.
    saved: bool,
}

/// Render the profile page for the signed-in user (or redirect to login).
async fn render_profile(
    st: &AppState,
    user: &MaybeUser,
    locale: &Locale,
    saved: bool,
) -> Result<Response, AppError> {
    let Some(session) = user.0.clone() else {
        return Ok(Redirect::to("/login").into_response());
    };
    let p = du_db::auth::profile(&st.pool, UserId(session.user_id))
        .await?
        .ok_or_else(|| AppError::NotFound("user".into()))?;
    let roles = if session.roles.is_empty() { "—".to_string() } else { session.roles.join(", ") };
    Ok(html(&ProfileTemplate {
        t: locale.t,
        next: locale.next.clone(),
        user: user.nav(),
        display_name: p.display_name.unwrap_or_else(|| session.display_name.clone()),
        roles,
        email: p.email,
        did: p.did,
        handle: p.handle,
        member_since: p.created_at.format("%Y-%m-%d").to_string(),
        saved,
    }))
}

async fn profile_page(State(st): State<AppState>, user: MaybeUser, locale: Locale) -> Result<Response, AppError> {
    render_profile(&st, &user, &locale, false).await
}

#[derive(Deserialize)]
struct ProfileForm {
    display_name: String,
}

async fn profile_update(
    State(st): State<AppState>,
    user: MaybeUser,
    locale: Locale,
    Form(f): Form<ProfileForm>,
) -> Result<Response, AppError> {
    let Some(session) = user.0.clone() else {
        return Ok(Redirect::to("/login").into_response());
    };
    let name = f.display_name.trim();
    if !name.is_empty() {
        du_db::auth::update_display_name(&st.pool, UserId(session.user_id), name).await?;
    }
    render_profile(&st, &user, &locale, true).await
}

// ── contact / support form ─────────────────────────────────────────────────────

#[derive(askama::Template)]
#[template(path = "static/contact.html")]
struct ContactTemplate {
    t: T,
    next: String,
    user: Option<NavUser>,
    /// reCAPTCHA site key — renders the widget when present.
    site_key: Option<String>,
    sent: bool,
    error: Option<String>,
}

#[derive(Deserialize)]
struct ContactForm {
    name: Option<String>,
    email: Option<String>,
    subject: Option<String>,
    message: String,
    #[serde(rename = "g-recaptcha-response")]
    recaptcha: Option<String>,
}

/// reCAPTCHA secret for server-side verification; when unset, verification is
/// skipped (dev) and the widget is not rendered.
pub(crate) fn recaptcha_secret() -> Option<String> {
    std::env::var("RECAPTCHA_SECRET").ok().filter(|s| !s.is_empty())
}
pub(crate) fn recaptcha_site_key() -> Option<String> {
    std::env::var("RECAPTCHA_SITE_KEY").ok().filter(|s| !s.is_empty())
}

/// Verify a reCAPTCHA token against Google's siteverify endpoint.
pub(crate) async fn verify_recaptcha(secret: &str, token: &str) -> bool {
    let resp = reqwest::Client::new()
        .post("https://www.google.com/recaptcha/api/siteverify")
        .form(&[("secret", secret), ("response", token)])
        .send()
        .await;
    match resp {
        Ok(r) => r
            .json::<serde_json::Value>()
            .await
            .ok()
            .and_then(|v| v["success"].as_bool())
            .unwrap_or(false),
        Err(_) => false,
    }
}

async fn contact_form(user: MaybeUser, locale: Locale) -> Response {
    html(&ContactTemplate {
        t: locale.t,
        next: locale.next,
        user: user.nav(),
        site_key: recaptcha_site_key(),
        sent: false,
        error: None,
    })
}

async fn contact_submit(
    State(st): State<AppState>,
    user: MaybeUser,
    locale: Locale,
    Form(f): Form<ContactForm>,
) -> Result<Response, AppError> {
    let render = |sent: bool, error: Option<String>| {
        html(&ContactTemplate {
            t: locale.t,
            next: locale.next,
            user: user.nav(),
            site_key: recaptcha_site_key(),
            sent,
            error,
        })
    };

    if f.message.trim().is_empty() {
        return Ok(render(false, Some(locale.t.get("contact.error.empty").to_string())));
    }
    // reCAPTCHA: enforced only when a secret is configured (so dev works).
    if let Some(secret) = recaptcha_secret() {
        let token = f.recaptcha.as_deref().unwrap_or("");
        if token.is_empty() || !verify_recaptcha(&secret, token).await {
            return Ok(render(false, Some(locale.t.get("contact.error.captcha").to_string())));
        }
    }

    let trim = |o: &Option<String>| o.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
    let (name, email, subject) = (trim(&f.name), trim(&f.email), trim(&f.subject));
    du_db::support::create_message(
        &st.pool,
        &du_db::support::NewContactMessage {
            user_id: user.0.as_ref().map(|s| s.user_id),
            sender_name: name.as_deref(),
            sender_email: email.as_deref(),
            subject: subject.as_deref(),
            message: f.message.trim(),
            ip_address_hash: None,
        },
    )
    .await?;
    Ok(render(true, None))
}
