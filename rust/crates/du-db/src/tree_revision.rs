//! Tree revision marker — the cache-revalidation token (ETag source) for the
//! haplogroup-tree API endpoints. A single persisted, monotonic counter
//! (`tree.tree_revision`, migration 0024) bumped explicitly by tree-mutating
//! operations (change-set apply, coordinate enrichment, YBrowse reconcile,
//! tree-init build) — not by a per-row trigger, to keep the hot per-variant
//! write path free.

use crate::DbError;
use sqlx::types::chrono::{DateTime, Utc};
use sqlx::PgPool;

/// The current tree revision and when it last changed.
pub async fn current(pool: &PgPool) -> Result<(i64, DateTime<Utc>), DbError> {
    let row: (i64, DateTime<Utc>) =
        sqlx::query_as("SELECT revision, updated_at FROM tree.tree_revision WHERE id = 1")
            .fetch_one(pool)
            .await?;
    Ok(row)
}

/// Bump the tree revision (+1) and return the new value. Generic over the
/// executor so it runs standalone (`&PgPool`) or inside the mutating transaction
/// it marks (`&mut *tx`) — bumping in-txn keeps the marker atomic with the change.
pub async fn bump<'e, E>(executor: E) -> Result<i64, DbError>
where
    E: sqlx::PgExecutor<'e>,
{
    let rev: i64 = sqlx::query_scalar(
        "UPDATE tree.tree_revision SET revision = revision + 1, updated_at = now() \
         WHERE id = 1 RETURNING revision",
    )
    .fetch_one(executor)
    .await?;
    Ok(rev)
}
