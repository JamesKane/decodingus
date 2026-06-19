//! Reputation engine (Tier 1a): the append-only event ledger + the cached score it
//! aggregates, plus the gates the social layer checks. Schema is mig 0009
//! (`social.reputation_event_type`/`reputation_event`/`user_reputation_score`), seeded
//! by mig 0042. Every score change is one ledger row written transactionally with the
//! cached total, so `sum(actual_points_change) == user_reputation_score.score`.

use crate::DbError;
use sqlx::PgPool;
use uuid::Uuid;

/// Event-type names (the mig-0042 seed set). Use these constants, not raw strings.
pub mod events {
    pub const ACCOUNT_VERIFIED: &str = "ACCOUNT_VERIFIED";
    pub const NEW_USER_BONUS: &str = "NEW_USER_BONUS";
    pub const LAB_OBSERVATION_ACCEPTED: &str = "LAB_OBSERVATION_ACCEPTED";
    pub const FEED_POST_UPVOTED: &str = "FEED_POST_UPVOTED";
    pub const FEED_POST_DOWNVOTED: &str = "FEED_POST_DOWNVOTED";
    pub const SPAM_REPORT_VALIDATED: &str = "SPAM_REPORT_VALIDATED";
    pub const RECRUITMENT_ACCEPTED: &str = "RECRUITMENT_ACCEPTED";
}

/// Score gates (`ReputationGuard`). `FEED_POST_MIN` is 0 for alpha/beta (open posting);
/// raise once awards are flowing and the cohort grows. DM/group gates per the design doc.
pub const FEED_POST_MIN: i64 = 0;
pub const DM_MIN: i64 = 20;
pub const GROUP_MIN: i64 = 50;

/// The thing a reputation event is about (e.g. the upvoted post), for the ledger.
pub struct Related<'a> {
    pub entity_type: &'a str,
    pub entity_id: Uuid,
}

/// Resolve an event type to `(id, default_points_change)`; `Conflict` if unseeded.
async fn event_type(pool: &PgPool, name: &str) -> Result<(Uuid, i32), DbError> {
    sqlx::query_as("SELECT id, default_points_change FROM social.reputation_event_type WHERE name = $1")
        .bind(name)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| DbError::Conflict(format!("unknown reputation event type {name}")))
}

/// Apply a delta to the cached score (insert-or-add), returning the new total.
async fn bump_score(tx: &mut sqlx::PgConnection, user_id: Uuid, delta: i32) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar(
        "INSERT INTO social.user_reputation_score (user_id, score, last_calculated_at) \
         VALUES ($1, $2, now()) \
         ON CONFLICT (user_id) DO UPDATE SET \
           score = social.user_reputation_score.score + EXCLUDED.score, last_calculated_at = now() \
         RETURNING score",
    )
    .bind(user_id)
    .bind(delta as i64)
    .fetch_one(&mut *tx)
    .await?)
}

/// Record a reputation event for `user_id` and atomically update the cached score.
/// `points` overrides the event type's default (used for vote toggles, e.g. -2). Returns
/// the new score.
pub async fn record_event(
    pool: &PgPool,
    user_id: Uuid,
    event_type_name: &str,
    points: Option<i32>,
    related: Option<Related<'_>>,
    source_user: Option<Uuid>,
) -> Result<i64, DbError> {
    let (type_id, default_points) = event_type(pool, event_type_name).await?;
    let applied = points.unwrap_or(default_points);
    let mut tx = pool.begin().await?;
    sqlx::query(
        "INSERT INTO social.reputation_event \
           (user_id, event_type_id, actual_points_change, source_user_id, related_entity_type, related_entity_id) \
         VALUES ($1, $2, $3, $4, $5, $6)",
    )
    .bind(user_id)
    .bind(type_id)
    .bind(applied)
    .bind(source_user)
    .bind(related.as_ref().map(|r| r.entity_type))
    .bind(related.as_ref().map(|r| r.entity_id))
    .execute(&mut *tx)
    .await?;
    let score = bump_score(&mut tx, user_id, applied).await?;
    tx.commit().await?;
    Ok(score)
}

/// Award a one-time system bonus (no related entity, no source) at most once per user —
/// idempotent via the mig-0042 partial unique index. Returns the current score (whether
/// or not this call awarded it), so callers can fire it on every login cheaply.
pub async fn record_once(pool: &PgPool, user_id: Uuid, event_type_name: &str) -> Result<i64, DbError> {
    let (type_id, default_points) = event_type(pool, event_type_name).await?;
    let mut tx = pool.begin().await?;
    let inserted: Option<Uuid> = sqlx::query_scalar(
        "INSERT INTO social.reputation_event (user_id, event_type_id, actual_points_change) \
         VALUES ($1, $2, $3) ON CONFLICT DO NOTHING RETURNING id",
    )
    .bind(user_id)
    .bind(type_id)
    .bind(default_points)
    .fetch_optional(&mut *tx)
    .await?;
    let score = if inserted.is_some() {
        bump_score(&mut tx, user_id, default_points).await?
    } else {
        sqlx::query_scalar(
            "SELECT COALESCE((SELECT score FROM social.user_reputation_score WHERE user_id = $1), 0)",
        )
        .bind(user_id)
        .fetch_one(&mut *tx)
        .await?
    };
    tx.commit().await?;
    Ok(score)
}

/// A user's current cached score (0 if none yet).
pub async fn score_of(pool: &PgPool, user_id: Uuid) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar(
        "SELECT COALESCE((SELECT score FROM social.user_reputation_score WHERE user_id = $1), 0)",
    )
    .bind(user_id)
    .fetch_one(pool)
    .await?)
}

/// Whether the user clears a score gate (the `ReputationGuard` primitive).
pub async fn at_least(pool: &PgPool, user_id: Uuid, threshold: i64) -> Result<bool, DbError> {
    Ok(score_of(pool, user_id).await? >= threshold)
}

/// Gate: may post to the community feed.
pub async fn can_post_to_feed(pool: &PgPool, user_id: Uuid) -> Result<bool, DbError> {
    at_least(pool, user_id, FEED_POST_MIN).await
}
