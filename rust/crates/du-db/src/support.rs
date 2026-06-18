//! Support / contact messages (`support.contact_message`). The public contact
//! form inserts here; curators/admins triage later (status new→read→replied→closed).

use crate::DbError;
use sqlx::PgPool;
use uuid::Uuid;

/// A contact-form submission. `user_id` is set when the sender is signed in.
pub struct NewContactMessage<'a> {
    pub user_id: Option<Uuid>,
    pub sender_name: Option<&'a str>,
    pub sender_email: Option<&'a str>,
    pub subject: Option<&'a str>,
    pub message: &'a str,
    pub ip_address_hash: Option<&'a str>,
}

/// Store a contact message; returns its id.
pub async fn create_message(pool: &PgPool, m: &NewContactMessage<'_>) -> Result<Uuid, DbError> {
    let id: Uuid = sqlx::query_scalar(
        "INSERT INTO support.contact_message \
           (user_id, sender_name, sender_email, subject, message, ip_address_hash) \
         VALUES ($1, $2, $3, $4, $5, $6) RETURNING id",
    )
    .bind(m.user_id)
    .bind(m.sender_name)
    .bind(m.sender_email)
    .bind(m.subject)
    .bind(m.message)
    .bind(m.ip_address_hash)
    .fetch_one(pool)
    .await?;
    Ok(id)
}
