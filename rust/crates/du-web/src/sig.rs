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

/// Reject a signed request whose timestamp is outside the replay window (a `400`). Every
/// signed Edge endpoint calls this before [`verify_signed`] as its replay guard.
pub fn ensure_fresh_ts(ts: i64) -> Result<(), AppError> {
    if (chrono::Utc::now().timestamp() - ts).abs() > SIGNED_TS_SKEW_SECS {
        return Err(AppError::BadRequest("stale timestamp".into()));
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
    use super::verify_signed;
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
