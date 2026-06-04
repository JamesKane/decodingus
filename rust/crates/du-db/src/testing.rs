//! Test-only support: ephemeral, freshly-migrated databases.
//!
//! Destructive integration tests (full-mirror YBrowse reconcile, source-scoped
//! deletes) must never run against the shared `decodingus` dev database. This
//! module creates a uniquely-named database on the SAME server as a given
//! `DATABASE_URL`, migrates it, and drops it (`WITH (FORCE)`) when the guard is
//! dropped — so each such test gets a private, throwaway catalog.
//!
//! Public (rather than `#[cfg(test)]`) so integration tests in sibling crates
//! (e.g. du-jobs) can share it; it pulls in no extra dependencies and is unused
//! in production builds.

use crate::{connect, run_migrations, DbError, PgPool};
use sqlx::Connection;

/// An isolated database that drops itself on `Drop`.
pub struct EphemeralDb {
    pool: PgPool,
    /// Maintenance-db DSN (`.../postgres`) used to create and later drop us.
    admin_url: String,
    db_name: String,
}

impl EphemeralDb {
    /// A pool connected to the isolated database.
    pub fn pool(&self) -> &PgPool {
        &self.pool
    }
}

impl Drop for EphemeralDb {
    fn drop(&mut self) {
        // Drop can't be async, and our own pool may still hold connections, so
        // tear down on a throwaway thread+runtime with `WITH (FORCE)` (PG13+),
        // which terminates lingering backends. Runs even on test panic.
        let admin_url = self.admin_url.clone();
        let db_name = self.db_name.clone();
        let _ = std::thread::spawn(move || {
            let Ok(rt) = tokio::runtime::Builder::new_current_thread().enable_all().build() else {
                return;
            };
            rt.block_on(async move {
                if let Ok(mut conn) = sqlx::postgres::PgConnection::connect(&admin_url).await {
                    let _ = sqlx::query(&format!("DROP DATABASE IF EXISTS \"{db_name}\" WITH (FORCE)"))
                        .execute(&mut conn)
                        .await;
                    let _ = conn.close().await;
                }
            });
        })
        .join();
    }
}

/// Split a `postgres://…/db?query` DSN into (everything up to the db name, db,
/// optional query) so we can swap the database segment.
fn split_dsn(dsn: &str) -> Option<(String, String)> {
    let (base, query) = match dsn.split_once('?') {
        Some((b, q)) => (b, Some(q)),
        None => (dsn, None),
    };
    let (host_part, _db) = base.rsplit_once('/')?;
    let suffix = query.map(|q| format!("?{q}")).unwrap_or_default();
    Some((host_part.to_string(), suffix))
}

/// Create + migrate a private database on the same server as `base_url`
/// (a normal `DATABASE_URL`). The returned guard drops the database when it
/// goes out of scope.
pub async fn ephemeral_db(base_url: &str) -> Result<EphemeralDb, DbError> {
    let (host_part, query_suffix) = split_dsn(base_url)
        .ok_or_else(|| DbError::Decode(format!("DATABASE_URL missing db path: {base_url:?}")))?;

    // Unique name without an RNG dep: pid + nanos + a process-wide counter, so
    // tests running concurrently in one binary can't collide on a coarse clock.
    use std::sync::atomic::{AtomicU64, Ordering};
    static SEQ: AtomicU64 = AtomicU64::new(0);
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let seq = SEQ.fetch_add(1, Ordering::Relaxed);
    let db_name = format!("du_test_{}_{}_{}", std::process::id(), nanos, seq);

    let admin_url = format!("{host_part}/postgres{query_suffix}");
    let new_url = format!("{host_part}/{db_name}{query_suffix}");

    // CREATE DATABASE can't run inside a transaction — use a single connection.
    let mut admin = sqlx::postgres::PgConnection::connect(&admin_url).await?;
    sqlx::query(&format!("CREATE DATABASE \"{db_name}\""))
        .execute(&mut admin)
        .await?;
    admin.close().await?;

    // Small pool — these tests issue sequential queries; keeps the total
    // connection count low when many test binaries run in parallel.
    let pool = connect(&new_url, 2).await?;
    run_migrations(&pool).await?;

    Ok(EphemeralDb { pool, admin_url, db_name })
}
