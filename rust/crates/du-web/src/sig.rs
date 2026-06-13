//! Ed25519 DID-signature authentication for the D1/D2 broker + registry endpoints.
//! Every Edge submission signs a canonical message with its DID identity key; the
//! broker verifies it (no OAuth/cookie). `did:key` is self-certifying (verified
//! directly); `did:plc`/`did:web` resolve to their signing key first.

use crate::error::AppError;

/// Verify that `signature` (standard base64 Ed25519) over `message` was produced by
/// `did`'s identity key. A bad signature → 403; an unresolvable did → 4xx/5xx.
pub async fn verify_signed(did: &str, message: &str, signature: &str) -> Result<(), AppError> {
    let did_key = if did.starts_with("did:key:") {
        did.to_string()
    } else {
        let parsed = du_atproto::did::Did::parse(did).map_err(|_| AppError::BadRequest("invalid did".into()))?;
        du_atproto::Resolver::new()
            .resolve_did(&parsed)
            .await
            .map_err(|e| AppError::Upstream(format!("did resolution: {e}")))?
            .signing_did_key()
            .ok_or_else(|| AppError::BadRequest("no signing key in did document".into()))?
    };
    du_atproto::verify_did_key(&did_key, message.as_bytes(), signature).map_err(|_| AppError::Forbidden)
}
