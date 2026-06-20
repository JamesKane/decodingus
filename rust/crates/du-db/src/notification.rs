//! In-app notifications (Tier 2a): one-way alerts to a user, plus [`notify_system`] —
//! the rail the collaboration flows (IBD/match-consent, D4 assertions) call to reach a
//! member. Distinct from the two-way support threads in [`crate::social`]. The unread
//! bell reuses the lazy-badge pattern; producers live in `social` (reply notifications)
//! and, later, the IBD/research modules (SYSTEM notifications).

use crate::DbError;
use chrono::{DateTime, Utc};
use serde::Serialize;
use sqlx::PgPool;
use uuid::Uuid;

/// Canonical signed message for the Edge mark-read call (cross-repo; keep byte-stable).
pub mod messages {
    /// Mark one notification (or all, when `id` is empty) read; replay-guarded by `ts`.
    pub fn read(did: &str, id: Option<&str>, ts: i64) -> String {
        format!("social-notif-read\n{did}\n{}\n{ts}", id.unwrap_or(""))
    }
}

/// Notification kinds. THREAD_REPLY/FEED_REPLY are wired now; MATCH/ASSERTION/SYSTEM are
/// the collaboration rail entry points.
pub mod kinds {
    pub const THREAD_REPLY: &str = "THREAD_REPLY";
    pub const FEED_REPLY: &str = "FEED_REPLY";
    pub const MATCH: &str = "MATCH";
    pub const ASSERTION: &str = "ASSERTION";
    pub const SYSTEM: &str = "SYSTEM";
    /// A recruitment invitation. The Navigator routes these straight to its invitations
    /// section; the campaign id is the last segment of `link` (`/recruitment/{cid}`) — the
    /// campaign id is a BIGINT and can't live in the UUID `related_entity_id`.
    pub const RECRUITMENT_INVITE: &str = "RECRUITMENT_INVITE";
}

/// A notification as shown in the list (actor name joined for display).
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct Notification {
    pub id: Uuid,
    pub kind: String,
    pub title: String,
    pub body: Option<String>,
    pub link: Option<String>,
    pub actor_name: Option<String>,
    pub created_at: DateTime<Utc>,
    pub read_at: Option<DateTime<Utc>>,
}

/// What a notification points at, for dedup/navigation context.
pub struct Related<'a> {
    pub entity_type: &'a str,
    pub entity_id: Uuid,
}

/// Create a notification. A self-notification (`recipient == actor`) is skipped (returns
/// `None`) — you don't get pinged for your own reply. Returns the new id otherwise.
#[allow(clippy::too_many_arguments)]
pub async fn notify(
    pool: &PgPool,
    recipient: Uuid,
    kind: &str,
    title: &str,
    body: Option<&str>,
    link: Option<&str>,
    actor: Option<Uuid>,
    related: Option<Related<'_>>,
) -> Result<Option<Uuid>, DbError> {
    if actor == Some(recipient) {
        return Ok(None);
    }
    Ok(Some(
        sqlx::query_scalar(
            "INSERT INTO social.notification \
               (recipient_id, kind, title, body, link, actor_id, related_entity_type, related_entity_id) \
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING id",
        )
        .bind(recipient)
        .bind(kind)
        .bind(title)
        .bind(body)
        .bind(link)
        .bind(actor)
        .bind(related.as_ref().map(|r| r.entity_type))
        .bind(related.as_ref().map(|r| r.entity_id))
        .fetch_one(pool)
        .await?,
    ))
}

/// The SYSTEM rail: a one-way platform alert with no actor (e.g. "a possible match wants
/// to connect"). Convenience over [`notify`] for the collaboration flows.
pub async fn notify_system(
    pool: &PgPool,
    recipient: Uuid,
    title: &str,
    link: Option<&str>,
    related: Option<Related<'_>>,
) -> Result<Option<Uuid>, DbError> {
    notify(pool, recipient, kinds::SYSTEM, title, None, link, None, related).await
}

/// A recipient's notifications, newest first (capped).
pub async fn list(pool: &PgPool, recipient: Uuid, limit: i64) -> Result<Vec<Notification>, DbError> {
    Ok(sqlx::query_as(
        "SELECT n.id, n.kind, n.title, n.body, n.link, u.display_name AS actor_name, \
                n.created_at, n.read_at \
         FROM social.notification n LEFT JOIN ident.users u ON u.id = n.actor_id \
         WHERE n.recipient_id = $1 ORDER BY n.created_at DESC LIMIT $2",
    )
    .bind(recipient)
    .bind(limit.clamp(1, 200))
    .fetch_all(pool)
    .await?)
}

/// Unread count (the bell badge).
pub async fn unread_count(pool: &PgPool, recipient: Uuid) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar(
        "SELECT count(*) FROM social.notification WHERE recipient_id = $1 AND read_at IS NULL",
    )
    .bind(recipient)
    .fetch_one(pool)
    .await?)
}

/// Mark one notification read (scoped to its recipient for authorization). Returns
/// whether a row changed.
pub async fn mark_read(pool: &PgPool, id: Uuid, recipient: Uuid) -> Result<bool, DbError> {
    let n = sqlx::query(
        "UPDATE social.notification SET read_at = now() \
         WHERE id = $1 AND recipient_id = $2 AND read_at IS NULL",
    )
    .bind(id)
    .bind(recipient)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n > 0)
}

/// Mark all of a recipient's notifications read.
pub async fn mark_all_read(pool: &PgPool, recipient: Uuid) -> Result<u64, DbError> {
    Ok(sqlx::query("UPDATE social.notification SET read_at = now() WHERE recipient_id = $1 AND read_at IS NULL")
        .bind(recipient)
        .execute(pool)
        .await?
        .rows_affected())
}
