//! Shared application state injected into handlers via `State<AppState>`.

use du_db::PgPool;
use tower_cookies::Key;

#[derive(Clone)]
pub struct AppState {
    pub pool: PgPool,
    /// Signing key for session cookies (derived from APP_SECRET).
    pub key: Key,
}

// Lets tower_cookies' SignedCookies pull the Key straight from AppState.
impl axum::extract::FromRef<AppState> for Key {
    fn from_ref(state: &AppState) -> Self {
        state.key.clone()
    }
}
