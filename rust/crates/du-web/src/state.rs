//! Shared application state injected into handlers via `State<AppState>`.

use du_db::PgPool;

#[derive(Clone)]
pub struct AppState {
    pub pool: PgPool,
}
