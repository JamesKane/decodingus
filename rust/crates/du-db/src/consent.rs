//! GDPR cookie-consent records. The banner POSTs an accept/decline; we persist
//! an audit row in `ident.cookie_consents` (attributed to the signed-in user when
//! there is one, else anonymous) alongside the client-side consent cookie.

use crate::DbError;
use sqlx::PgPool;
use uuid::Uuid;

/// Record a consent decision. `user_id` is the signed-in user, if any.
pub async fn record(
    pool: &PgPool,
    user_id: Option<Uuid>,
    consent_given: bool,
    policy_version: &str,
    user_agent: Option<&str>,
) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO ident.cookie_consents \
           (user_id, consent_given, policy_version, user_agent) \
         VALUES ($1, $2, $3, $4)",
    )
    .bind(user_id)
    .bind(consent_given)
    .bind(policy_version)
    .bind(user_agent)
    .execute(pool)
    .await?;
    Ok(())
}
