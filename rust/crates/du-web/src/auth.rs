//! Session auth: the signed-cookie session and request extractors, plus the
//! `hash_password` helper used by the `hash-password` CLI. Login itself is AT
//! Protocol OAuth (`crate::oauth`); there is no local credential login.

use crate::state::AppState;
use argon2::password_hash::{rand_core::OsRng, PasswordHasher, SaltString};
use argon2::Argon2;
use axum::extract::FromRequestParts;
use axum::http::request::Parts;
use axum::response::{IntoResponse, Redirect, Response};
use serde::{Deserialize, Serialize};
use tower_cookies::Cookies;
use uuid::Uuid;

pub const SESSION_COOKIE: &str = "session";

/// Hash a new password with Argon2id.
pub fn hash_password(password: &str) -> Result<String, String> {
    let salt = SaltString::generate(&mut OsRng);
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|h| h.to_string())
        .map_err(|e| e.to_string())
}

/// Authenticated session, stored in a signed cookie.
#[derive(Clone, Serialize, Deserialize)]
pub struct Session {
    pub user_id: Uuid,
    pub display_name: String,
    pub roles: Vec<String>,
}

impl Session {
    pub fn has_role(&self, role: &str) -> bool {
        self.roles.iter().any(|r| r == role)
    }
    #[allow(dead_code)] // part of the Session API; used by admin-only routes to come
    pub fn is_admin(&self) -> bool {
        self.has_role("Admin")
    }
    /// May use the curator tools.
    pub fn is_curator(&self) -> bool {
        self.has_role("Admin") || self.has_role("TreeCurator") || self.has_role("Curator")
    }
}

fn read_session(cookies: &Cookies, state: &AppState) -> Option<Session> {
    let value = cookies.signed(&state.key).get(SESSION_COOKIE)?;
    serde_json::from_str(value.value()).ok()
}

/// Minimal user info the shared navbar needs.
pub struct NavUser {
    pub display_name: String,
    pub is_curator: bool,
}

/// Optional current user — never rejects.
pub struct MaybeUser(pub Option<Session>);

impl MaybeUser {
    /// Navbar view of the current user, if signed in.
    pub fn nav(&self) -> Option<NavUser> {
        self.0.as_ref().map(|s| NavUser {
            display_name: s.display_name.clone(),
            is_curator: s.is_curator(),
        })
    }
}

#[axum::async_trait]
impl FromRequestParts<AppState> for MaybeUser {
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, Self::Rejection> {
        let cookies = Cookies::from_request_parts(parts, state).await.expect("cookie layer present");
        Ok(MaybeUser(read_session(&cookies, state)))
    }
}

/// Any authenticated session, or a redirect to /login. Use as a handler argument to
/// gate routes that require a signed-in user (member areas: messages, feed posting).
pub struct User(pub Session);

#[axum::async_trait]
impl FromRequestParts<AppState> for User {
    type Rejection = Response;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, Self::Rejection> {
        let cookies = Cookies::from_request_parts(parts, state).await.expect("cookie layer present");
        match read_session(&cookies, state) {
            Some(s) => Ok(User(s)),
            None => Err(Redirect::to("/login").into_response()),
        }
    }
}

/// A session with curator privileges, or a redirect to /login. Use as a handler
/// argument to gate curator routes.
pub struct Curator(pub Session);

#[axum::async_trait]
impl FromRequestParts<AppState> for Curator {
    type Rejection = Response;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, Self::Rejection> {
        let cookies = Cookies::from_request_parts(parts, state).await.expect("cookie layer present");
        match read_session(&cookies, state) {
            Some(s) if s.is_curator() => Ok(Curator(s)),
            Some(_) => Err(crate::error::AppError::Forbidden.into_response()),
            None => Err(Redirect::to("/login").into_response()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn argon2_hash_has_expected_prefix() {
        let h = hash_password("correct horse").unwrap();
        assert!(h.starts_with("$argon2"));
    }
}
