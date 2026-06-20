//! Mirrored community feed posts (`com.decodingus.atmosphere.feed.post`). Members publish
//! short public posts to their own PDS; the AppView indexes them here (via the Jetstream
//! consumer) and merges them into the community feed alongside the central
//! `social.feed_post` path. PII-free — a DID + public text + thread pointers. See
//! [`super`] for the shared cursor/delete routing.

use super::Common;
use crate::DbError;
use chrono::{DateTime, Utc};
use serde::Serialize;
use sqlx::PgPool;

/// A feed post to mirror (the extracted lexicon fields + provenance).
pub struct FeedPost {
    pub common: Common,
    pub text: String,
    pub topic: Option<String>,
    /// `reply.parent.uri` / `reply.root.uri` — present on replies.
    pub parent_uri: Option<String>,
    pub root_uri: Option<String>,
}

/// Upsert a mirrored post, last-writer-wins by `time_us` (a late-arriving stale event
/// can't clobber a newer one — same guard as the other mirrors).
pub async fn upsert(pool: &PgPool, p: &FeedPost) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO fed.feed_post \
           (did, rkey, at_uri, cid, text, topic, parent_uri, root_uri, record_created_at, time_us) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) \
         ON CONFLICT (did, rkey) DO UPDATE SET \
           at_uri = EXCLUDED.at_uri, cid = EXCLUDED.cid, text = EXCLUDED.text, topic = EXCLUDED.topic, \
           parent_uri = EXCLUDED.parent_uri, root_uri = EXCLUDED.root_uri, \
           record_created_at = EXCLUDED.record_created_at, time_us = EXCLUDED.time_us, indexed_at = now() \
         WHERE EXCLUDED.time_us >= fed.feed_post.time_us",
    )
    .bind(&p.common.did)
    .bind(&p.common.rkey)
    .bind(&p.common.at_uri)
    .bind(&p.common.cid)
    .bind(&p.text)
    .bind(&p.topic)
    .bind(&p.parent_uri)
    .bind(&p.root_uri)
    .bind(p.common.record_created_at)
    .bind(p.common.time_us)
    .execute(pool)
    .await?;
    Ok(())
}

/// A federated post as the feed renders it; `author_name` is resolved when the DID is
/// bridged into `ident.users`, else `None` (caller shows the DID).
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct FedPost {
    pub did: String,
    pub author_name: Option<String>,
    pub text: String,
    pub topic: Option<String>,
    pub at_uri: String,
    pub created_at: Option<DateTime<Utc>>,
}

/// Recent top-level federated posts (no parent), newest first, optionally by topic.
pub async fn recent(pool: &PgPool, topic: Option<&str>, limit: i64) -> Result<Vec<FedPost>, DbError> {
    Ok(sqlx::query_as(
        "SELECT f.did, u.display_name AS author_name, f.text, f.topic, f.at_uri, f.record_created_at AS created_at \
         FROM fed.feed_post f LEFT JOIN ident.users u ON u.did = f.did \
         WHERE f.parent_uri IS NULL AND ($1::text IS NULL OR f.topic = $1) \
         ORDER BY f.record_created_at DESC NULLS LAST LIMIT $2",
    )
    .bind(topic)
    .bind(limit.clamp(1, 200))
    .fetch_all(pool)
    .await?)
}
