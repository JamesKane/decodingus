//! Data-access layer. Owns the `PgPool` and exposes per-aggregate query modules.
//!
//! Status: scaffold. The pool + error type are wired; query modules
//! (`biosample`, `variant`, `haplogroup`, …) land as each subsystem is ported.

use sqlx::postgres::{PgPool, PgPoolOptions};
use std::time::Duration;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum DbError {
    #[error("database error: {0}")]
    Sqlx(#[from] sqlx::Error),
    #[error("migration error: {0}")]
    Migrate(#[from] sqlx::migrate::MigrateError),
}

/// Connect and return a pool. `database_url` is the standard `postgres://` DSN
/// (driven by `DATABASE_URL`; see `scripts/test-db.sh` and plan §9).
pub async fn connect(database_url: &str, max_connections: u32) -> Result<PgPool, DbError> {
    let pool = PgPoolOptions::new()
        .max_connections(max_connections)
        .acquire_timeout(Duration::from_secs(10))
        .connect(database_url)
        .await?;
    Ok(pool)
}

/// Apply the workspace migrations (the redesigned schema) to the given pool.
pub async fn run_migrations(pool: &PgPool) -> Result<(), DbError> {
    sqlx::migrate!("../../migrations").run(pool).await?;
    Ok(())
}
