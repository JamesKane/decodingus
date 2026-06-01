//! Authentication/authorization queries against the `ident` schema.

use crate::DbError;
use du_domain::ids::UserId;
use sqlx::PgPool;
use uuid::Uuid;

/// A login credential: the owning user and the stored password hash (None for
/// OAuth-only logins).
pub struct Credential {
    pub user_id: UserId,
    pub password_hash: Option<String>,
}

/// Look up a credential by provider key (handle/email) for the `credentials`
/// provider. Returns None if no such active user/login exists.
pub async fn find_credential(pool: &PgPool, provider_key: &str) -> Result<Option<Credential>, DbError> {
    #[derive(sqlx::FromRow)]
    struct Row {
        user_id: Uuid,
        password_hash: Option<String>,
    }
    let row: Option<Row> = sqlx::query_as(
        "SELECT li.user_id, li.password_hash \
         FROM ident.user_login_info li \
         JOIN ident.users u ON u.id = li.user_id \
         WHERE li.provider_id = 'credentials' AND li.provider_key = $1 AND u.is_active = true",
    )
    .bind(provider_key)
    .fetch_optional(pool)
    .await?;
    Ok(row.map(|r| Credential {
        user_id: UserId(r.user_id),
        password_hash: r.password_hash,
    }))
}

/// The display name + role names for a user (for the session).
pub async fn session_info(pool: &PgPool, user_id: UserId) -> Result<(Option<String>, Vec<String>), DbError> {
    let display_name: Option<String> =
        sqlx::query_scalar("SELECT display_name FROM ident.users WHERE id = $1")
            .bind(user_id.0)
            .fetch_optional(pool)
            .await?
            .flatten();
    let roles: Vec<String> = sqlx::query_scalar(
        "SELECT r.name FROM ident.user_roles ur \
         JOIN ident.roles r ON r.id = ur.role_id WHERE ur.user_id = $1 ORDER BY r.name",
    )
    .bind(user_id.0)
    .fetch_all(pool)
    .await?;
    Ok((display_name, roles))
}
