//! Replay guard for signed Edge requests. Each *mutating* signed request frames its
//! canonical message as `{ts}\n{base}` and is single-use: its Ed25519 signature is
//! recorded on first acceptance, so a replay of the identical signed bytes within the
//! freshness window collides here and is rejected. The signed `ts` (±5 min) bounds how
//! long an entry must be retained; [`purge_expired`] drops the rest. See
//! `du_web::sig::verify_signed_fresh` for the caller.

use crate::DbError;
use sqlx::PgPool;

/// Record `signature` as seen (single-use). Returns `true` when newly reserved (the
/// request may proceed) and `false` when it was already present (a replay). `ttl_secs`
/// is how long the entry is retained — at least the freshness window plus clock skew.
pub async fn reserve(pool: &PgPool, signature: &str, did: &str, ttl_secs: i64) -> Result<bool, DbError> {
    let affected = sqlx::query(
        "INSERT INTO fed.signed_request_seen (signature, did, expires_at) \
         VALUES ($1, $2, now() + make_interval(secs => $3)) \
         ON CONFLICT (signature) DO NOTHING",
    )
    .bind(signature)
    .bind(did)
    .bind(ttl_secs as f64)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(affected > 0)
}

/// Drop replay-guard rows past their freshness window. Returns the count removed.
pub async fn purge_expired(pool: &PgPool) -> Result<u64, DbError> {
    Ok(sqlx::query("DELETE FROM fed.signed_request_seen WHERE expires_at < now()")
        .execute(pool)
        .await?
        .rows_affected())
}
