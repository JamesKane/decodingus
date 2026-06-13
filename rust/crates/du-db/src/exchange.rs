//! D1 encrypted-exchange **broker** (AppView side) — PII-free. The AppView never
//! sees plaintext or session keys: it records consent (the handler verifies the
//! Ed25519 DID signatures), mirrors published X25519 keys, gates **dual consent**
//! into a session, and blind-relays ciphertext envelopes. This module is pure
//! storage + state transitions; the signature verification lives in the `du-web`
//! handler (it needs async DID resolution).
//!
//! **Canonical signed messages** ([`messages`]) are a cross-repo contract: the
//! Navigator Edge must sign byte-identical strings. Keep them stable.

use crate::DbError;
use serde_json::Value;
use sqlx::PgPool;
use uuid::Uuid;

/// The exact bytes each record's Ed25519 signature is computed over. A cross-repo
/// contract with the Navigator Edge — do not reorder or reformat.
pub mod messages {
    pub fn request(request_uri: &str, initiator_did: &str, partner_did: &str, purpose: &str, scope: Option<&str>) -> String {
        format!("exchange-request\n{request_uri}\n{initiator_did}\n{partner_did}\n{purpose}\n{}", scope.unwrap_or(""))
    }
    pub fn consent(request_uri: &str, consenting_did: &str, given: bool) -> String {
        format!("exchange-consent\n{request_uri}\n{consenting_did}\n{given}")
    }
    pub fn publickey(did: &str, x25519_pub_b64: &str, key_uri: Option<&str>) -> String {
        format!("exchange-publickey\n{did}\n{x25519_pub_b64}\n{}", key_uri.unwrap_or(""))
    }
    /// Replay-guarded poll: caller proves it is `did` at `ts` (unix seconds).
    pub fn poll(did: &str, ts: i64) -> String {
        format!("exchange-poll\n{did}\n{ts}")
    }
    pub fn relay(session_id: &str, from_did: &str, to_did: &str, seq: i32, blob_sha256_b64: &str) -> String {
        format!("exchange-relay\n{session_id}\n{from_did}\n{to_did}\n{seq}\n{blob_sha256_b64}")
    }
    pub fn ack(did: &str, envelope_id: i64) -> String {
        format!("exchange-ack\n{did}\n{envelope_id}")
    }
}

// ── published X25519 keys ─────────────────────────────────────────────────────

/// Upsert a DID's published X25519 key (the handler has verified the signature).
pub async fn publish_key(pool: &PgPool, did: &str, x25519_pub: &[u8], key_uri: Option<&str>) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO exchange.exchange_publickey (did, x25519_pub, key_uri, sig_verified_at) \
         VALUES ($1, $2, $3, now()) \
         ON CONFLICT (did) DO UPDATE SET x25519_pub = EXCLUDED.x25519_pub, key_uri = EXCLUDED.key_uri, \
            sig_verified_at = now()",
    )
    .bind(did)
    .bind(x25519_pub)
    .bind(key_uri)
    .execute(pool)
    .await?;
    Ok(())
}

/// A peer's published exchange key.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct PublicKey {
    pub did: String,
    pub x25519_pub: Vec<u8>,
    pub key_uri: Option<String>,
}

pub async fn key_for(pool: &PgPool, did: &str) -> Result<Option<PublicKey>, DbError> {
    Ok(sqlx::query_as("SELECT did, x25519_pub, key_uri FROM exchange.exchange_publickey WHERE did = $1")
        .bind(did)
        .fetch_optional(pool)
        .await?)
}

// ── requests + dual-consent ───────────────────────────────────────────────────

/// A new (verified) exchange request.
pub struct NewRequest<'a> {
    pub request_uri: &'a str,
    pub initiator_did: &'a str,
    pub partner_did: &'a str,
    pub purpose: &'a str,
    pub scope: Option<&'a str>,
    pub details: Value,
}

/// Record a signed exchange request (idempotent on `request_uri`).
pub async fn create_request(pool: &PgPool, r: &NewRequest<'_>) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO exchange.exchange_request \
            (request_uri, initiator_did, partner_did, purpose, scope, details) \
         VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT (request_uri) DO NOTHING",
    )
    .bind(r.request_uri)
    .bind(r.initiator_did)
    .bind(r.partner_did)
    .bind(r.purpose)
    .bind(r.scope)
    .bind(&r.details)
    .execute(pool)
    .await?;
    Ok(())
}

/// Outcome of recording a consent.
#[derive(Debug, PartialEq, Eq)]
pub enum ConsentOutcome {
    /// Recorded; still waiting on the other party.
    Recorded,
    /// Both parties consented — a session was created.
    Consented(Uuid),
    /// This party declined — the request is dead.
    Declined,
    /// No such request.
    Unknown,
}

/// Record a signed consent and apply the **dual-consent gate**: when both the
/// initiator and partner have a `consent_given = true` consent, flip the request to
/// `CONSENTED` and open an `exchange_session`. A `false` consent declines the request.
pub async fn record_consent(
    pool: &PgPool,
    request_uri: &str,
    consenting_did: &str,
    given: bool,
    consent_uri: Option<&str>,
    signature: &str,
) -> Result<ConsentOutcome, DbError> {
    let mut tx = pool.begin().await?;
    let req: Option<(String, String, String)> = sqlx::query_as(
        "SELECT initiator_did, partner_did, status FROM exchange.exchange_request WHERE request_uri = $1 FOR UPDATE",
    )
    .bind(request_uri)
    .fetch_optional(&mut *tx)
    .await?;
    let Some((initiator, partner, status)) = req else {
        return Ok(ConsentOutcome::Unknown);
    };

    sqlx::query(
        "INSERT INTO exchange.exchange_consent (request_uri, consenting_did, consent_given, consent_uri, signature) \
         VALUES ($1, $2, $3, $4, $5) \
         ON CONFLICT (request_uri, consenting_did) DO UPDATE SET \
            consent_given = EXCLUDED.consent_given, consent_uri = EXCLUDED.consent_uri, signature = EXCLUDED.signature",
    )
    .bind(request_uri)
    .bind(consenting_did)
    .bind(given)
    .bind(consent_uri)
    .bind(signature)
    .execute(&mut *tx)
    .await?;

    if !given {
        sqlx::query("UPDATE exchange.exchange_request SET status = 'DECLINED', updated_at = now() WHERE request_uri = $1")
            .bind(request_uri)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        return Ok(ConsentOutcome::Declined);
    }

    // Both participants must have an affirmative consent on record.
    let yes_count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM exchange.exchange_consent \
         WHERE request_uri = $1 AND consent_given = true AND consenting_did IN ($2, $3)",
    )
    .bind(request_uri)
    .bind(&initiator)
    .bind(&partner)
    .fetch_one(&mut *tx)
    .await?;

    if yes_count >= 2 && status == "PENDING" {
        sqlx::query("UPDATE exchange.exchange_request SET status = 'CONSENTED', updated_at = now() WHERE request_uri = $1")
            .bind(request_uri)
            .execute(&mut *tx)
            .await?;
        let session_id: Uuid = sqlx::query_scalar(
            "INSERT INTO exchange.exchange_session (request_uri, status, expires_at) \
             VALUES ($1, 'ESTABLISHING', now() + interval '7 days') RETURNING session_id",
        )
        .bind(request_uri)
        .fetch_one(&mut *tx)
        .await?;
        tx.commit().await?;
        return Ok(ConsentOutcome::Consented(session_id));
    }
    tx.commit().await?;
    Ok(ConsentOutcome::Recorded)
}

/// An exchange-ready session for a participant (the partner DID + its key pointer).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct ExchangeReady {
    pub session_id: Uuid,
    pub request_uri: String,
    pub purpose: String,
    pub partner_did: String,
    pub partner_key_uri: Option<String>,
}

// ── blind relay (ciphertext store-and-forward) ────────────────────────────────

/// Store an opaque ciphertext envelope for the recipient. The caller (`from_did`)
/// and `to_did` must be the session's two participants and the session open; the
/// blob is treated as opaque (the broker can't decrypt it). Flips the session to
/// `ACTIVE`. Returns the envelope id.
pub async fn post_envelope(
    pool: &PgPool,
    session_id: Uuid,
    from_did: &str,
    to_did: &str,
    seq: i32,
    blob: &[u8],
) -> Result<i64, DbError> {
    let mut tx = pool.begin().await?;
    let row: Option<(String, String, String)> = sqlx::query_as(
        "SELECT r.initiator_did, r.partner_did, s.status \
         FROM exchange.exchange_session s JOIN exchange.exchange_request r ON r.request_uri = s.request_uri \
         WHERE s.session_id = $1 FOR UPDATE OF s",
    )
    .bind(session_id)
    .fetch_optional(&mut *tx)
    .await?;
    let Some((initiator, partner, status)) = row else {
        return Err(DbError::Conflict("no such session".into()));
    };
    if !matches!(status.as_str(), "ESTABLISHING" | "ACTIVE") {
        return Err(DbError::Conflict(format!("session is {status}")));
    }
    let parties = [initiator.as_str(), partner.as_str()];
    if from_did == to_did || !parties.contains(&from_did) || !parties.contains(&to_did) {
        return Err(DbError::Conflict("not a session participant".into()));
    }
    let id: i64 = sqlx::query_scalar(
        "INSERT INTO exchange.relay_envelope (session_id, from_did, to_did, seq, size_bytes, blob, expires_at) \
         VALUES ($1, $2, $3, $4, $5, $6, (SELECT expires_at FROM exchange.exchange_session WHERE session_id = $1)) \
         RETURNING id",
    )
    .bind(session_id)
    .bind(from_did)
    .bind(to_did)
    .bind(seq)
    .bind(blob.len() as i32)
    .bind(blob)
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query("UPDATE exchange.exchange_session SET status = 'ACTIVE' WHERE session_id = $1 AND status = 'ESTABLISHING'")
        .bind(session_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(id)
}

/// An undelivered ciphertext envelope (the recipient's pull).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct Envelope {
    pub id: i64,
    pub from_did: String,
    pub seq: i32,
    pub blob: Vec<u8>,
}

/// Pull the undelivered envelopes addressed to `to_did` in a session, ordered by seq.
pub async fn pull_envelopes(pool: &PgPool, session_id: Uuid, to_did: &str) -> Result<Vec<Envelope>, DbError> {
    Ok(sqlx::query_as(
        "SELECT id, from_did, seq, blob FROM exchange.relay_envelope \
         WHERE session_id = $1 AND to_did = $2 AND delivered_at IS NULL ORDER BY seq",
    )
    .bind(session_id)
    .bind(to_did)
    .fetch_all(pool)
    .await?)
}

/// Ack (delete) a delivered envelope — only its own recipient may ack it.
pub async fn ack_envelope(pool: &PgPool, envelope_id: i64, did: &str) -> Result<bool, DbError> {
    let affected = sqlx::query("DELETE FROM exchange.relay_envelope WHERE id = $1 AND to_did = $2")
        .bind(envelope_id)
        .bind(did)
        .execute(pool)
        .await?
        .rows_affected();
    Ok(affected > 0)
}

/// TTL cleanup: drop expired envelopes and expired sessions (cascading their
/// envelopes). Returns (envelopes_dropped, sessions_dropped).
pub async fn expire(pool: &PgPool) -> Result<(u64, u64), DbError> {
    let envelopes = sqlx::query("DELETE FROM exchange.relay_envelope WHERE expires_at IS NOT NULL AND expires_at < now()")
        .execute(pool)
        .await?
        .rows_affected();
    let sessions = sqlx::query("DELETE FROM exchange.exchange_session WHERE expires_at IS NOT NULL AND expires_at < now()")
        .execute(pool)
        .await?
        .rows_affected();
    Ok((envelopes, sessions))
}

/// Sessions ready for `did` to start (CONSENTED + an open session), with the partner.
pub async fn pending_for(pool: &PgPool, did: &str) -> Result<Vec<ExchangeReady>, DbError> {
    Ok(sqlx::query_as(
        "SELECT s.session_id, r.request_uri, r.purpose, \
                CASE WHEN r.initiator_did = $1 THEN r.partner_did ELSE r.initiator_did END AS partner_did, \
                pk.key_uri AS partner_key_uri \
         FROM exchange.exchange_session s \
         JOIN exchange.exchange_request r ON r.request_uri = s.request_uri \
         LEFT JOIN exchange.exchange_publickey pk \
            ON pk.did = CASE WHEN r.initiator_did = $1 THEN r.partner_did ELSE r.initiator_did END \
         WHERE (r.initiator_did = $1 OR r.partner_did = $1) AND s.status IN ('ESTABLISHING','ACTIVE') \
         ORDER BY s.created_at DESC",
    )
    .bind(did)
    .fetch_all(pool)
    .await?)
}
