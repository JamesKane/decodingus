//! Ed25519 DID-signature authentication for the Edge endpoints (D1 exchange, D2/D5
//! research, D4 assertions, IBD). Every Edge submission signs a canonical message with a
//! key it controls; the AppView verifies it (no OAuth/cookie per call).
//!
//! `did:key` is self-certifying (verified directly). For `did:plc`/`did:web`, the AppView
//! verifies against the caller's **registered device key(s)** — Ed25519 public keys the
//! client published as `com.decodingus.atmosphere.deviceKey` records in its own repo and
//! the AppView ingested (`du_db::fed::device_key`). The DID doc's `#atproto` signing key is
//! NOT used: it's PDS-custodied, so a desktop client can't sign with it and can't add its
//! own verificationMethod. Registering a device key (writing to your own repo = proof of
//! control over the DID) is the bootstrap; revocation is deleting that record.

use crate::error::AppError;
use du_db::PgPool;

/// Replay window for signed Edge requests: the signed `ts` must be within ±5 min of now.
const SIGNED_TS_SKEW_SECS: i64 = 300;

/// How long an accepted signature is kept in the replay guard: the freshness window plus
/// headroom for clock skew. Inside this window a replayed signature collides and is
/// rejected; past it, [`ensure_fresh_ts`] alone rejects the stale `ts`, so the recorded
/// signature is no longer needed.
const REPLAY_TTL_SECS: i64 = 2 * SIGNED_TS_SKEW_SECS;

/// Reject a signed request whose timestamp is outside the replay window (a `400`). Every
/// signed Edge read endpoint calls this before [`verify_signed`]; mutating endpoints go
/// through [`verify_signed_fresh`], which calls it.
pub fn ensure_fresh_ts(ts: i64) -> Result<(), AppError> {
    if (chrono::Utc::now().timestamp() - ts).abs() > SIGNED_TS_SKEW_SECS {
        return Err(AppError::BadRequest("stale timestamp".into()));
    }
    Ok(())
}

/// Canonical replay-guarded framing for a **mutating** signed request: the caller's
/// timestamp prefixed to the base message (`{ts}\n{base}`). The Navigator signer mirrors
/// this byte-for-byte, so one signature binds both the timestamp (freshness) and the
/// operation (the base message). Keep this format stable — it is a cross-repo contract.
pub fn fresh_message(ts: i64, base_message: &str) -> String {
    format!("{ts}\n{base_message}")
}

/// Verify a **mutating** signed Edge request with replay protection. The signature must
/// cover [`fresh_message(ts, base_message)`](fresh_message), `ts` must be within the
/// freshness window, and the exact signature must not have been accepted before
/// (single-use within the window — a replay of the identical signed bytes is rejected).
/// A stale `ts` → 400; a bad signature or a replay → 403.
///
/// A legitimate client that retries re-signs with a fresh `ts` (a new signature), so
/// retries are not mistaken for replays; the mutating handlers are also idempotent.
pub async fn verify_signed_fresh(
    pool: &PgPool,
    did: &str,
    ts: i64,
    base_message: &str,
    signature: &str,
) -> Result<(), AppError> {
    ensure_fresh_ts(ts)?;
    verify_signed(pool, did, &fresh_message(ts, base_message), signature).await?;
    // Authenticated → burn the signature so an identical replay within the window collides.
    if !du_db::fed::signed_request::reserve(pool, signature, did, REPLAY_TTL_SECS).await? {
        return Err(AppError::Forbidden);
    }
    Ok(())
}

/// Verify that `signature` (standard base64 Ed25519) over `message` was produced by a key
/// `did` controls. `did:key` self-certifies; otherwise the signature must match one of the
/// DID's registered device keys. A bad/absent key → 403.
pub async fn verify_signed(pool: &PgPool, did: &str, message: &str, signature: &str) -> Result<(), AppError> {
    if did.starts_with("did:key:") {
        return du_atproto::verify_did_key(did, message.as_bytes(), signature).map_err(|_| AppError::Forbidden);
    }
    // did:plc / did:web → match any registered device key (none yet ⇒ not yet bootstrapped).
    let keys = du_db::fed::device_key::keys_for(pool, did).await?;
    if keys.is_empty() {
        return Err(AppError::Forbidden);
    }
    if keys.iter().any(|k| du_atproto::verify_did_key(k, message.as_bytes(), signature).is_ok()) {
        Ok(())
    } else {
        Err(AppError::Forbidden)
    }
}

#[cfg(test)]
mod tests {
    use super::{fresh_message, verify_signed, REPLAY_TTL_SECS, SIGNED_TS_SKEW_SECS};

    /// The replay-guarded framing is exactly `{ts}\n{base}` — the cross-repo contract the
    /// Navigator `DeviceKey::sign_fresh` mirrors byte-for-byte.
    #[test]
    fn fresh_message_prefixes_ts() {
        assert_eq!(
            fresh_message(1_700_000_000, "exchange-consent\nurn:x\ndid:key:zA\ntrue"),
            "1700000000\nexchange-consent\nurn:x\ndid:key:zA\ntrue"
        );
    }

    /// The replay guard must retain an accepted signature at least as long as the freshness
    /// window, or a replay could outlive its guard entry while its `ts` is still fresh.
    #[test]
    fn replay_ttl_covers_the_freshness_window() {
        assert!(REPLAY_TTL_SECS >= 2 * SIGNED_TS_SKEW_SECS);
    }

    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;
    use ed25519_dalek::{Signer, SigningKey};

    /// A did:plc caller is verified against its registered device key; an unregistered DID
    /// and a wrong-key signature are both rejected; did:key still self-certifies (offline).
    #[tokio::test]
    async fn device_key_registration_gates_did_plc() {
        let Some(url) = std::env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()) else {
            eprintln!("DATABASE_URL unset — skipping device-key sig test");
            return;
        };
        let db = du_db::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();

        let device = SigningKey::from_bytes(&[51u8; 32]);
        let device_did_key = du_atproto::did::did_key_from_ed25519(&device.verifying_key());
        let alice = "did:plc:alice";
        let msg = "ibd-poll\ndid:plc:alice\n1700000000";
        let sig = STANDARD.encode(device.sign(msg.as_bytes()).to_bytes());

        // Before registration: a perfectly valid signature is still rejected (no key on file).
        assert!(verify_signed(&pool, alice, msg, &sig).await.is_err(), "unregistered DID → 403");

        // Register the device key as the ingest would (a deviceKey record in alice's repo).
        sqlx::query(
            "INSERT INTO fed.device_key (did, rkey, at_uri, public_key, time_us) VALUES ($1,$2,$3,$4,$5)",
        )
        .bind(alice)
        .bind("dk1")
        .bind("at://did:plc:alice/com.decodingus.atmosphere.deviceKey/dk1")
        .bind(&device_did_key)
        .bind(1_i64)
        .execute(&pool)
        .await
        .unwrap();

        // Now the registered key verifies the signature.
        assert!(verify_signed(&pool, alice, msg, &sig).await.is_ok(), "registered key verifies");

        // A signature from a DIFFERENT key (not registered) is rejected.
        let other = SigningKey::from_bytes(&[52u8; 32]);
        let other_sig = STANDARD.encode(other.sign(msg.as_bytes()).to_bytes());
        assert!(verify_signed(&pool, alice, msg, &other_sig).await.is_err(), "wrong key → 403");

        // did:key remains self-certifying (no DB lookup needed).
        assert!(verify_signed(&pool, &device_did_key, msg, &sig).await.is_ok(), "did:key self-certifies");
    }
}
