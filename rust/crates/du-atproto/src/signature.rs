//! Ed25519 signature verification against a `did:key` identity. Self-certifying:
//! the public key is encoded in the DID, so no network resolution is needed to
//! verify a payload signed by that key (used for signed PDS/edge-node requests
//! and IBD attestations).

use crate::did::ed25519_from_did_key;
use crate::error::AtprotoError;
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use ed25519_dalek::Signature;

/// Verify that `signature_b64` (standard base64 of a 64-byte Ed25519 signature)
/// over `message` was produced by the key in `did_key` (`did:key:z...`).
pub fn verify_did_key(did_key: &str, message: &[u8], signature_b64: &str) -> Result<(), AtprotoError> {
    let vk = ed25519_from_did_key(did_key)?;
    let sig_bytes = STANDARD
        .decode(signature_b64.trim())
        .map_err(|e| AtprotoError::Parse(format!("signature base64: {e}")))?;
    let sig = Signature::from_slice(&sig_bytes)
        .map_err(|e| AtprotoError::Parse(format!("signature bytes: {e}")))?;
    vk.verify_strict(message, &sig).map_err(|_| AtprotoError::BadSignature)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::did::did_key_from_ed25519;
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine;
    use ed25519_dalek::{Signer, SigningKey};

    fn keypair() -> SigningKey {
        // Deterministic seed (tests must not use RNG that breaks reproducibility).
        SigningKey::from_bytes(&[7u8; 32])
    }

    #[test]
    fn verifies_a_valid_signature() {
        let sk = keypair();
        let did = did_key_from_ed25519(&sk.verifying_key());
        assert!(did.starts_with("did:key:z"));
        let msg = b"de-identified call signature payload";
        let sig = STANDARD.encode(sk.sign(msg).to_bytes());
        assert!(verify_did_key(&did, msg, &sig).is_ok());
    }

    #[test]
    fn rejects_tampered_message_and_wrong_key() {
        let sk = keypair();
        let did = did_key_from_ed25519(&sk.verifying_key());
        let sig = STANDARD.encode(sk.sign(b"original").to_bytes());
        assert!(matches!(
            verify_did_key(&did, b"tampered", &sig),
            Err(AtprotoError::BadSignature)
        ));

        let other = SigningKey::from_bytes(&[9u8; 32]);
        let other_did = did_key_from_ed25519(&other.verifying_key());
        assert!(verify_did_key(&other_did, b"original", &sig).is_err());
    }
}
