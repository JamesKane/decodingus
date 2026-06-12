//! Data-access layer. Owns the `PgPool` and exposes per-aggregate query modules.
//!
//! Status: scaffold. The pool + error type are wired; query modules
//! (`biosample`, `variant`, `haplogroup`, …) land as each subsystem is ported.

/// Re-exported so downstream crates can hold a pool without depending on sqlx.
pub use sqlx::postgres::PgPool;
use sqlx::postgres::PgPoolOptions;
use std::time::Duration;
use thiserror::Error;

pub mod age;
pub mod audit;
pub mod auth;
pub mod biosample;
pub mod change_set;
pub mod consent;
pub mod coverage;
pub mod discovery;
pub mod fed;
pub mod genome_region;
pub mod haplogroup;
pub mod merge;
pub mod naming;
pub mod pagination;
pub mod pdf;
pub mod proposal;
pub mod publication;
pub mod sequencer;
pub mod snp_graft;
pub mod study;
pub mod support;
pub mod testing;
pub mod tree_revision;
pub mod variant;
pub mod wip;
pub mod ybrowse;
pub mod ystr;

pub use pagination::Page;

#[derive(Debug, Error)]
pub enum DbError {
    #[error("database error: {0}")]
    Sqlx(#[from] sqlx::Error),
    #[error("migration error: {0}")]
    Migrate(#[from] sqlx::migrate::MigrateError),
    /// A row's text/JSONB column failed to decode into a domain type.
    #[error("decode error: {0}")]
    Decode(String),
    /// A precondition/uniqueness conflict surfaced to the caller (e.g. promoting
    /// a proposal whose name is already in the catalog).
    #[error("conflict: {0}")]
    Conflict(String),
}

/// Decode a Postgres enum label (fetched as `::text`) into a domain enum that
/// derives `Deserialize` with matching SCREAMING_SNAKE_CASE variants. Keeps
/// du-domain free of any sqlx dependency.
pub(crate) fn parse_pg_enum<T: serde::de::DeserializeOwned>(
    label: &str,
    what: &str,
) -> Result<T, DbError> {
    serde_json::from_value(serde_json::Value::String(label.to_string()))
        .map_err(|e| DbError::Decode(format!("{what} = {label:?}: {e}")))
}

/// Inverse of `parse_pg_enum`: a domain enum's SCREAMING_SNAKE_CASE label for
/// binding against a `::text`-cast enum column.
pub(crate) fn pg_enum_label<T: serde::Serialize>(value: &T) -> Result<String, DbError> {
    match serde_json::to_value(value).map_err(|e| DbError::Decode(e.to_string()))? {
        serde_json::Value::String(s) => Ok(s),
        other => Err(DbError::Decode(format!("expected enum string, got {other}"))),
    }
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
