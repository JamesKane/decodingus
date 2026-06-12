//! Curator action audit trail (`ident.audit_log`). The first runtime writer; the
//! column set mirrors the legacy backfill in `du-migrate`. Used to record curator
//! decisions (accept/reject) on consensus proposals.

use crate::DbError;
use sqlx::PgPool;
use uuid::Uuid;

/// Append a curator action to the audit log. `entity_id` is the catalog row id;
/// `action` is a short verb (`ACCEPT`/`REJECT`/`CREATE`/`UPDATE`/`DELETE`). `id`
/// and `created_at` use DB defaults.
#[allow(clippy::too_many_arguments)]
pub async fn log(
    pool: &PgPool,
    user_id: Uuid,
    entity_type: &str,
    entity_id: i64,
    action: &str,
    old_value: Option<&serde_json::Value>,
    new_value: Option<&serde_json::Value>,
    comment: Option<&str>,
) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO ident.audit_log (user_id, entity_type, entity_id, action, old_value, new_value, comment) \
         VALUES ($1, $2, $3, $4, $5, $6, $7)",
    )
    .bind(user_id)
    .bind(entity_type)
    .bind(entity_id)
    .bind(action)
    .bind(old_value)
    .bind(new_value)
    .bind(comment)
    .execute(pool)
    .await?;
    Ok(())
}
