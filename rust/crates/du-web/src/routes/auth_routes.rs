//! Sign-in landing page + session logout. Authentication itself is AT Protocol
//! OAuth (`crate::oauth`, `GET /login/atproto`) — there is no local password login.

use crate::auth::SESSION_COOKIE;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::{Query, State};
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::{get, post};
use axum::Router;
use serde::Deserialize;
use tower_cookies::{Cookie, Cookies};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/login", get(login_form))
        .route("/logout", post(logout))
}

#[derive(askama::Template)]
#[template(path = "auth/login.html")]
struct LoginTemplate {
    t: T,
    next: String,
    user: Option<crate::auth::NavUser>,
    /// Prefill the handle field (e.g. after a bounce back from a failed attempt).
    handle: String,
    /// OAuth is configured — show the Bluesky sign-in form (else an "unavailable" note).
    oauth_enabled: bool,
    /// A prior sign-in attempt failed (an OAuth error redirected here with `?error=`).
    error: bool,
}

#[derive(Deserialize, Default)]
struct LoginQuery {
    handle: Option<String>,
    error: Option<String>,
}

async fn login_form(
    State(st): State<AppState>,
    locale: Locale,
    user: crate::auth::MaybeUser,
    Query(q): Query<LoginQuery>,
) -> Response {
    html(&LoginTemplate {
        t: locale.t,
        next: locale.next,
        user: user.nav(),
        handle: q.handle.unwrap_or_default().trim().to_string(),
        oauth_enabled: st.oauth.is_some(),
        error: q.error.is_some(),
    })
}

async fn logout(State(st): State<AppState>, cookies: Cookies) -> Response {
    let mut cookie = Cookie::new(SESSION_COOKIE, "");
    cookie.set_path("/");
    cookies.signed(&st.key).remove(cookie);
    Redirect::to("/login").into_response()
}
