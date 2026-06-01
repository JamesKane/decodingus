//! Shared application state injected into handlers via `State<AppState>`.

use crate::oauth::OauthClient;
use du_db::PgPool;
use std::sync::Arc;
use tower_cookies::Key;

#[derive(Clone)]
pub struct AppState {
    pub pool: PgPool,
    /// Signing key for session cookies (derived from APP_SECRET).
    pub key: Key,
    /// AT Protocol OAuth client (None when OAuth isn't configured).
    pub oauth: Option<Arc<OauthClient>>,
}

// Lets tower_cookies' SignedCookies pull the Key straight from AppState.
impl axum::extract::FromRef<AppState> for Key {
    fn from_ref(state: &AppState) -> Self {
        state.key.clone()
    }
}
