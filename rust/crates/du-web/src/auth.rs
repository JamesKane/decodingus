//! Session auth: password hashing/verification, the signed-cookie session, and
//! request extractors. Federated (AT Protocol) login lands later in du-atproto;
//! this covers local credential login + RBAC.

use crate::state::AppState;
use argon2::password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
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

/// Verify a password against a stored hash. Supports Argon2 (new) and bcrypt
/// (legacy) hashes, dispatching on the hash prefix.
pub fn verify_password(password: &str, hash: &str) -> bool {
    if hash.starts_with("$argon2") {
        PasswordHash::new(hash)
            .is_ok_and(|parsed| Argon2::default().verify_password(password.as_bytes(), &parsed).is_ok())
    } else if hash.starts_with("$2") {
        bcrypt::verify(password, hash).unwrap_or(false)
    } else {
        false
    }
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
    fn argon2_hash_roundtrips_and_rejects_wrong() {
        let h = hash_password("correct horse").unwrap();
        assert!(h.starts_with("$argon2"));
        assert!(verify_password("correct horse", &h));
        assert!(!verify_password("Tr0ub4dor", &h));
    }

    #[test]
    fn unknown_hash_format_is_rejected() {
        assert!(!verify_password("x", "plaintext"));
    }
}
