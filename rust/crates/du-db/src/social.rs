//! Social layer: team↔tester support threads + the announcement/community feed.
//!
//! Reuses the mig-0009 `social.*` tables (activated by mig 0041) for the alpha/beta
//! communication layer. Testers are bridged into `ident.users` by DID
//! ([`crate::auth::upsert_user_by_did`]); the **team** is a ROLE (Curator/Admin), not a
//! conversation participant — so a `SUPPORT` conversation has exactly one participant
//! (the user) and any curator may reply with `from_team = true`. Bodies are central
//! plaintext: this is operator communication, not citizen↔citizen P2P (which routes
//! over the D1 encrypted relay instead).

use crate::{DbError, Page};
use chrono::{DateTime, Utc};
use serde::Serialize;
use sqlx::PgPool;
use uuid::Uuid;

/// Canonical signed messages for the Edge (Navigator) API — a cross-repo contract with
/// the client. Keep these byte-stable. The free-text body/content is NOT signed (the
/// signature binds the caller's identity + the target ids, mirroring `research::messages`).
pub mod messages {
    /// Open a new support thread (conversation_id empty) or reply to an existing one.
    pub fn thread(did: &str, conversation_id: Option<&str>) -> String {
        format!("social-thread\n{did}\n{}", conversation_id.unwrap_or(""))
    }
    /// Replay-guarded read poll (list my threads / read the feed): caller proves it is
    /// `did` at unix-seconds `ts`.
    pub fn poll(did: &str, ts: i64) -> String {
        format!("social-poll\n{did}\n{ts}")
    }
    /// Replay-guarded read of one thread's messages.
    pub fn thread_read(did: &str, conversation_id: &str, ts: i64) -> String {
        format!("social-thread-read\n{did}\n{conversation_id}\n{ts}")
    }
    /// Create a community feed post (parent empty) or a reply (parent set).
    pub fn post(did: &str, parent: Option<&str>) -> String {
        format!("social-post\n{did}\n{}", parent.unwrap_or(""))
    }
}

// ── support threads ───────────────────────────────────────────────────────────

/// Which side of a thread is reading (whose read-cutoff to advance).
#[derive(Debug, Clone, Copy)]
pub enum ReadSide {
    Team,
    User,
}

/// A support-thread summary for an inbox list (team or user side).
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct Thread {
    pub id: Uuid,
    pub subject: Option<String>,
    pub status: String,
    pub kind: String,
    pub requester_id: Option<Uuid>,
    pub requester_name: Option<String>,
    pub created_at: DateTime<Utc>,
    pub last_message_at: Option<DateTime<Utc>>,
    /// Unread on the *team* side (a non-team message after `team_last_read_at`).
    pub team_unread: bool,
    /// Unread on the *user* side (a team message after `user_last_read_at`).
    pub user_unread: bool,
}

/// One message in a thread.
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct ThreadMessage {
    pub id: Uuid,
    pub sender_id: Uuid,
    pub sender_name: Option<String>,
    pub from_team: bool,
    pub body: String,
    pub created_at: DateTime<Utc>,
    pub read_at: Option<DateTime<Utc>>,
}

/// Columns + the computed unread flags shared by both inbox queries. `$1` is the
/// reading viewpoint's bind position is supplied by the caller's query, not here —
/// these flags are derived purely from the row's own read-cutoff columns.
const THREAD_COLS: &str = "c.id, c.subject, c.status, c.kind, \
     c.participant_ids[1] AS requester_id, u.display_name AS requester_name, \
     c.created_at, c.last_message_at, \
     EXISTS (SELECT 1 FROM social.message m WHERE m.conversation_id = c.id \
             AND m.from_team = false \
             AND (c.team_last_read_at IS NULL OR m.created_at > c.team_last_read_at)) AS team_unread, \
     EXISTS (SELECT 1 FROM social.message m WHERE m.conversation_id = c.id \
             AND m.from_team = true \
             AND (c.user_last_read_at IS NULL OR m.created_at > c.user_last_read_at)) AS user_unread";

/// Open a new `SUPPORT` thread for `user_id` and post their first message. Returns the
/// new conversation id. The team is not a participant (it's a role); the lone
/// participant is the requester.
pub async fn open_support_thread(
    pool: &PgPool,
    user_id: Uuid,
    subject: Option<&str>,
    first_message: &str,
) -> Result<Uuid, DbError> {
    let mut tx = pool.begin().await?;
    let conv_id: Uuid = sqlx::query_scalar(
        "INSERT INTO social.conversation (participant_ids, subject, kind, status, last_message_at) \
         VALUES (ARRAY[$1]::uuid[], $2, 'SUPPORT', 'open', now()) RETURNING id",
    )
    .bind(user_id)
    .bind(subject)
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query(
        "INSERT INTO social.message (conversation_id, sender_id, body, from_team) \
         VALUES ($1, $2, $3, false)",
    )
    .bind(conv_id)
    .bind(user_id)
    .bind(first_message)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(conv_id)
}

/// Append a message to a thread, bump `last_message_at`, and move status: a team reply
/// → `replied`; a user reply re-opens it → `open` (back in the team's queue). Returns
/// the new message id. A `closed` thread is reopened by either side replying.
pub async fn post_message(
    pool: &PgPool,
    conversation_id: Uuid,
    sender_id: Uuid,
    body: &str,
    from_team: bool,
) -> Result<Uuid, DbError> {
    let mut tx = pool.begin().await?;
    let msg_id: Uuid = sqlx::query_scalar(
        "INSERT INTO social.message (conversation_id, sender_id, body, from_team) \
         VALUES ($1, $2, $3, $4) RETURNING id",
    )
    .bind(conversation_id)
    .bind(sender_id)
    .bind(body)
    .bind(from_team)
    .fetch_one(&mut *tx)
    .await?;
    let new_status = if from_team { "replied" } else { "open" };
    sqlx::query(
        "UPDATE social.conversation SET last_message_at = now(), status = $2 WHERE id = $1",
    )
    .bind(conversation_id)
    .bind(new_status)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(msg_id)
}

/// The team's triage inbox: every live thread, newest activity first. `status` filters
/// to one of open/replied/closed when set.
pub async fn team_inbox(
    pool: &PgPool,
    status: Option<&str>,
    page: i64,
    page_size: i64,
) -> Result<Page<Thread>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let total: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM social.conversation c \
         WHERE c.deleted_at IS NULL AND ($1::text IS NULL OR c.status = $1)",
    )
    .bind(status)
    .fetch_one(pool)
    .await?;
    let items: Vec<Thread> = sqlx::query_as(&format!(
        "SELECT {THREAD_COLS} FROM social.conversation c \
         LEFT JOIN ident.users u ON u.id = c.participant_ids[1] \
         WHERE c.deleted_at IS NULL AND ($1::text IS NULL OR c.status = $1) \
         ORDER BY c.last_message_at DESC NULLS LAST LIMIT $2 OFFSET $3"
    ))
    .bind(status)
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    Ok(Page { items, total, page: page.max(1), page_size: limit })
}

/// A single user's own threads (their inbox).
pub async fn user_threads(
    pool: &PgPool,
    user_id: Uuid,
    page: i64,
    page_size: i64,
) -> Result<Page<Thread>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let total: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM social.conversation c \
         WHERE c.deleted_at IS NULL AND $1 = ANY(c.participant_ids)",
    )
    .bind(user_id)
    .fetch_one(pool)
    .await?;
    let items: Vec<Thread> = sqlx::query_as(&format!(
        "SELECT {THREAD_COLS} FROM social.conversation c \
         LEFT JOIN ident.users u ON u.id = c.participant_ids[1] \
         WHERE c.deleted_at IS NULL AND $1 = ANY(c.participant_ids) \
         ORDER BY c.last_message_at DESC NULLS LAST LIMIT $2 OFFSET $3"
    ))
    .bind(user_id)
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    Ok(Page { items, total, page: page.max(1), page_size: limit })
}

/// One thread by id (with requester + unread flags), or `None` if missing/deleted.
pub async fn get_thread(pool: &PgPool, conversation_id: Uuid) -> Result<Option<Thread>, DbError> {
    Ok(sqlx::query_as(&format!(
        "SELECT {THREAD_COLS} FROM social.conversation c \
         LEFT JOIN ident.users u ON u.id = c.participant_ids[1] \
         WHERE c.id = $1 AND c.deleted_at IS NULL"
    ))
    .bind(conversation_id)
    .fetch_optional(pool)
    .await?)
}

/// The participant (requester) of a thread, for authorization (a user may only read
/// their own thread). `None` if the thread doesn't exist or has no participant.
pub async fn thread_requester(pool: &PgPool, conversation_id: Uuid) -> Result<Option<Uuid>, DbError> {
    Ok(sqlx::query_scalar(
        "SELECT participant_ids[1] FROM social.conversation WHERE id = $1 AND deleted_at IS NULL",
    )
    .bind(conversation_id)
    .fetch_optional(pool)
    .await?
    .flatten())
}

/// All messages in a thread, oldest first.
pub async fn thread_messages(pool: &PgPool, conversation_id: Uuid) -> Result<Vec<ThreadMessage>, DbError> {
    Ok(sqlx::query_as(
        "SELECT m.id, m.sender_id, u.display_name AS sender_name, m.from_team, m.body, \
                m.created_at, m.read_at \
         FROM social.message m LEFT JOIN ident.users u ON u.id = m.sender_id \
         WHERE m.conversation_id = $1 ORDER BY m.created_at ASC, m.id ASC",
    )
    .bind(conversation_id)
    .fetch_all(pool)
    .await?)
}

/// Advance a side's read-cutoff to now (clears its unread badge for this thread).
pub async fn mark_read(pool: &PgPool, conversation_id: Uuid, side: ReadSide) -> Result<(), DbError> {
    let col = match side {
        ReadSide::Team => "team_last_read_at",
        ReadSide::User => "user_last_read_at",
    };
    sqlx::query(&format!(
        "UPDATE social.conversation SET {col} = now() WHERE id = $1"
    ))
    .bind(conversation_id)
    .execute(pool)
    .await?;
    Ok(())
}

/// Set a thread's triage status (open/replied/closed).
pub async fn set_status(pool: &PgPool, conversation_id: Uuid, status: &str) -> Result<(), DbError> {
    sqlx::query("UPDATE social.conversation SET status = $2 WHERE id = $1")
        .bind(conversation_id)
        .bind(status)
        .execute(pool)
        .await?;
    Ok(())
}

/// Count threads needing team attention (the inbox badge): live + status='open'.
pub async fn team_open_count(pool: &PgPool) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar(
        "SELECT count(*) FROM social.conversation WHERE deleted_at IS NULL AND status = 'open'",
    )
    .fetch_one(pool)
    .await?)
}

/// Count a user's threads with an unread team reply (the member's navbar badge).
pub async fn user_unread_count(pool: &PgPool, user_id: Uuid) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar(
        "SELECT count(*) FROM social.conversation c \
         WHERE c.deleted_at IS NULL AND $1 = ANY(c.participant_ids) \
           AND EXISTS (SELECT 1 FROM social.message m WHERE m.conversation_id = c.id \
               AND m.from_team = true \
               AND (c.user_last_read_at IS NULL OR m.created_at > c.user_last_read_at))",
    )
    .bind(user_id)
    .fetch_one(pool)
    .await?)
}

// ── feed: announcements + community ───────────────────────────────────────────

/// A feed post (announcement or community), with the author's display name joined.
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct FeedPost {
    pub id: Uuid,
    pub author_id: Uuid,
    pub author_name: Option<String>,
    pub kind: String,
    pub topic: Option<String>,
    pub content: String,
    pub parent_post_id: Option<Uuid>,
    pub pinned: bool,
    pub created_at: DateTime<Utc>,
    pub reply_count: i64,
}

const FEED_COLS: &str = "p.id, p.author_id, u.display_name AS author_name, p.kind, p.topic, \
     p.content, p.parent_post_id, p.pinned, p.created_at, \
     (SELECT count(*) FROM social.feed_post r \
      WHERE r.parent_post_id = p.id AND r.deleted_at IS NULL) AS reply_count";

/// Create a feed post (top-level when `parent` is `None`, else a reply). `kind` is
/// `ANNOUNCEMENT` (team only — gated by the caller) or `COMMUNITY`.
pub async fn create_post(
    pool: &PgPool,
    author_id: Uuid,
    kind: &str,
    topic: Option<&str>,
    content: &str,
    parent: Option<Uuid>,
) -> Result<Uuid, DbError> {
    Ok(sqlx::query_scalar(
        "INSERT INTO social.feed_post (author_id, kind, topic, content, parent_post_id) \
         VALUES ($1, $2, $3, $4, $5) RETURNING id",
    )
    .bind(author_id)
    .bind(kind)
    .bind(topic)
    .bind(content)
    .bind(parent)
    .fetch_one(pool)
    .await?)
}

/// List top-level feed posts (no parent), pinned first then newest. Filter by `kind`
/// (ANNOUNCEMENT/COMMUNITY) and/or `topic` when set.
pub async fn list_feed(
    pool: &PgPool,
    kind: Option<&str>,
    topic: Option<&str>,
    page: i64,
    page_size: i64,
) -> Result<Page<FeedPost>, DbError> {
    let offset = Page::<()>::offset(page, page_size);
    let limit = page_size.clamp(1, 200);
    let where_sql = "WHERE p.deleted_at IS NULL AND p.parent_post_id IS NULL \
                     AND ($1::text IS NULL OR p.kind = $1) \
                     AND ($2::text IS NULL OR p.topic = $2)";
    let total: i64 = sqlx::query_scalar(&format!(
        "SELECT count(*) FROM social.feed_post p {where_sql}"
    ))
    .bind(kind)
    .bind(topic)
    .fetch_one(pool)
    .await?;
    let items: Vec<FeedPost> = sqlx::query_as(&format!(
        "SELECT {FEED_COLS} FROM social.feed_post p \
         LEFT JOIN ident.users u ON u.id = p.author_id {where_sql} \
         ORDER BY p.pinned DESC, p.created_at DESC LIMIT $3 OFFSET $4"
    ))
    .bind(kind)
    .bind(topic)
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    Ok(Page { items, total, page: page.max(1), page_size: limit })
}

/// Replies to a post, oldest first.
pub async fn post_replies(pool: &PgPool, parent_id: Uuid) -> Result<Vec<FeedPost>, DbError> {
    Ok(sqlx::query_as(&format!(
        "SELECT {FEED_COLS} FROM social.feed_post p \
         LEFT JOIN ident.users u ON u.id = p.author_id \
         WHERE p.deleted_at IS NULL AND p.parent_post_id = $1 \
         ORDER BY p.created_at ASC"
    ))
    .bind(parent_id)
    .fetch_all(pool)
    .await?)
}

/// Fetch one post by id (live only).
pub async fn get_post(pool: &PgPool, id: Uuid) -> Result<Option<FeedPost>, DbError> {
    Ok(sqlx::query_as(&format!(
        "SELECT {FEED_COLS} FROM social.feed_post p \
         LEFT JOIN ident.users u ON u.id = p.author_id \
         WHERE p.id = $1 AND p.deleted_at IS NULL"
    ))
    .bind(id)
    .fetch_optional(pool)
    .await?)
}

/// Soft-delete a post. When `require_author` is set, only that author may delete (the
/// user-facing path); pass `None` for a curator/moderator delete. Returns whether a row
/// was removed.
pub async fn delete_post(pool: &PgPool, id: Uuid, require_author: Option<Uuid>) -> Result<bool, DbError> {
    let n = sqlx::query(
        "UPDATE social.feed_post SET deleted_at = now() \
         WHERE id = $1 AND deleted_at IS NULL AND ($2::uuid IS NULL OR author_id = $2)",
    )
    .bind(id)
    .bind(require_author)
    .execute(pool)
    .await?
    .rows_affected();
    Ok(n > 0)
}

/// Set/clear the pinned flag on a post (team moderation).
pub async fn set_pinned(pool: &PgPool, id: Uuid, pinned: bool) -> Result<(), DbError> {
    sqlx::query("UPDATE social.feed_post SET pinned = $2 WHERE id = $1")
        .bind(id)
        .bind(pinned)
        .execute(pool)
        .await?;
    Ok(())
}

// ── blocks + reputation ───────────────────────────────────────────────────────

/// Block `blocked` from the `blocker`'s perspective (idempotent).
pub async fn block(pool: &PgPool, blocker: Uuid, blocked: Uuid, reason: Option<&str>) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO social.user_block (blocker_id, blocked_id, reason) VALUES ($1, $2, $3) \
         ON CONFLICT (blocker_id, blocked_id) DO NOTHING",
    )
    .bind(blocker)
    .bind(blocked)
    .bind(reason)
    .execute(pool)
    .await?;
    Ok(())
}

/// Remove a block.
pub async fn unblock(pool: &PgPool, blocker: Uuid, blocked: Uuid) -> Result<(), DbError> {
    sqlx::query("DELETE FROM social.user_block WHERE blocker_id = $1 AND blocked_id = $2")
        .bind(blocker)
        .bind(blocked)
        .execute(pool)
        .await?;
    Ok(())
}

/// Whether a block exists in **either** direction between two users — used to hide feed
/// content and refuse contact regardless of who blocked whom.
pub async fn is_blocked_either(pool: &PgPool, a: Uuid, b: Uuid) -> Result<bool, DbError> {
    Ok(sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM social.user_block \
         WHERE (blocker_id = $1 AND blocked_id = $2) OR (blocker_id = $2 AND blocked_id = $1))",
    )
    .bind(a)
    .bind(b)
    .fetch_one(pool)
    .await?)
}

/// A user's reputation score (0 if they have no row yet) — the feed/DM gate reads this.
pub async fn reputation_score(pool: &PgPool, user_id: Uuid) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar(
        "SELECT COALESCE((SELECT score FROM social.user_reputation_score WHERE user_id = $1), 0)",
    )
    .bind(user_id)
    .fetch_one(pool)
    .await?)
}
