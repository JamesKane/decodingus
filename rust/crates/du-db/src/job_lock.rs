//! A single Postgres advisory lock that serializes the automated (cron-driven)
//! jobs so they **never overlap** — the in-process interval scheduler that used to
//! fire them concurrently (and all at once on startup) is retired in favour of
//! one-job-at-a-time `run-once` invocations from systemd timers.
//!
//! Every serialized invocation acquires the same session-level advisory lock and
//! holds it for the job's duration. A nightly batch waits for it (`block = true`,
//! so its jobs queue behind a running one); a frequent poller tries it
//! (`block = false`, skipping its tick if a job is already running).

use crate::{DbError, PgPool};
use sqlx::pool::PoolConnection;
use sqlx::Postgres;

/// Arbitrary constant key for `pg_advisory_lock` — the bytes of "dujobs". One key
/// ⇒ one job at a time across the whole deployment.
const JOB_LOCK_KEY: i64 = 0x64_75_6a_6f_62_73; // "dujobs"

/// Guard holding the locked session. The advisory lock is released when this drops
/// **and the pooled connection's session ends** — which for a short-lived `run-once`
/// process is at process exit. Hold it for the whole job; don't stash it in the pool.
pub struct JobLock(#[allow(dead_code)] PoolConnection<Postgres>);

/// Acquire the global job lock. `block = true` waits until it's free (batch jobs:
/// always run, just queued); `block = false` returns `Ok(None)` immediately if another
/// job holds it (frequent pollers: skip this tick). Hold the returned guard for the
/// job's duration.
pub async fn acquire(pool: &PgPool, block: bool) -> Result<Option<JobLock>, DbError> {
    let mut conn = pool.acquire().await?;
    let got: bool = if block {
        sqlx::query("SELECT pg_advisory_lock($1)").bind(JOB_LOCK_KEY).execute(&mut *conn).await?;
        true
    } else {
        sqlx::query_scalar("SELECT pg_try_advisory_lock($1)").bind(JOB_LOCK_KEY).fetch_one(&mut *conn).await?
    };
    Ok(got.then_some(JobLock(conn)))
}
