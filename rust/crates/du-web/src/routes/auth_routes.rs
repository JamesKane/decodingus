//! Local credential login/logout with a signed-cookie session.

use crate::auth::{verify_password, Session, SESSION_COOKIE};
use crate::error::AppError;
use crate::i18n::{Locale, T};
use crate::render::html;
use crate::state::AppState;
use axum::extract::State;
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::{get, post};
use axum::{Form, Router};
use serde::Deserialize;
use tower_cookies::{Cookie, Cookies};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/login", get(login_form).post(authenticate))
        .route("/logout", post(logout))
}

#[derive(askama::Template)]
#[template(path = "auth/login.html")]
struct LoginTemplate {
    t: T,
    next: String,
    user: Option<crate::auth::NavUser>,
    handle: String,
    error: bool,
}

#[derive(Deserialize)]
struct LoginForm {
    handle: String,
    password: String,
}

async fn login_form(locale: Locale, user: crate::auth::MaybeUser) -> Response {
    html(&LoginTemplate { t: locale.t, next: locale.next, user: user.nav(), handle: String::new(), error: false })
}

async fn authenticate(
    State(st): State<AppState>,
    cookies: Cookies,
    locale: Locale,
    Form(form): Form<LoginForm>,
) -> Result<Response, AppError> {
    let credential = du_db::auth::find_credential(&st.pool, form.handle.trim()).await?;
    let ok = match &credential {
        Some(c) => c.password_hash.as_deref().is_some_and(|h| verify_password(&form.password, h)),
        None => false,
    };
    if !ok {
        // Re-render with a generic error (don't reveal which field failed).
        return Ok(html(&LoginTemplate {
            t: locale.t,
            next: locale.next,
            user: None,
            handle: form.handle,
            error: true,
        }));
    }

    let user_id = credential.unwrap().user_id;
    // One-time welcome bonus (idempotent) so the reputation gate has a floor to work with.
    du_db::reputation::record_once(&st.pool, user_id.0, du_db::reputation::events::NEW_USER_BONUS).await?;
    let (display_name, roles) = du_db::auth::session_info(&st.pool, user_id).await?;
    let session = Session {
        user_id: user_id.0,
        display_name: display_name.unwrap_or_else(|| form.handle.clone()),
        roles,
    };
    let value = serde_json::to_string(&session).map_err(|e| AppError::BadRequest(e.to_string()))?;

    let mut cookie = Cookie::new(SESSION_COOKIE, value);
    cookie.set_path("/");
    cookie.set_http_only(true);
    cookie.set_same_site(tower_cookies::cookie::SameSite::Lax);
    cookies.signed(&st.key).add(cookie);

    let dest = if session_is_curator(&session) { "/curator" } else { "/" };
    Ok(Redirect::to(dest).into_response())
}

fn session_is_curator(s: &Session) -> bool {
    s.roles.iter().any(|r| r == "Admin" || r == "TreeCurator" || r == "Curator")
}

async fn logout(State(st): State<AppState>, cookies: Cookies) -> Response {
    let mut cookie = Cookie::new(SESSION_COOKIE, "");
    cookie.set_path("/");
    cookies.signed(&st.key).remove(cookie);
    Redirect::to("/login").into_response()
}
