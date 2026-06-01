//! DID and AT-URI parsing, plus `did:key` <-> Ed25519 public key conversion.

use crate::error::AtprotoError;
use ed25519_dalek::VerifyingKey;

/// Multicodec prefix for an Ed25519 public key (`0xed`, varint-encoded as 0xed 0x01).
const ED25519_PUB_MULTICODEC: [u8; 2] = [0xed, 0x01];

/// A decentralized identifier. Supports the `did:plc:` and `did:web:` methods
/// used in AT Protocol, plus `did:key:` for self-certifying signing identities.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Did(pub String);

impl Did {
    pub fn parse(s: &str) -> Result<Did, AtprotoError> {
        let s = s.trim();
        if !s.starts_with("did:") || s.splitn(3, ':').count() < 3 {
            return Err(AtprotoError::Parse(format!("not a DID: {s:?}")));
        }
        Ok(Did(s.to_string()))
    }

    pub fn method(&self) -> &str {
        // did:<method>:<id>
        self.0.split(':').nth(1).unwrap_or("")
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for Did {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// A parsed AT-URI: `at://<authority>/<collection>/<rkey>` (collection and rkey
/// optional). The authority is a DID or handle.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AtUri {
    pub authority: String,
    pub collection: Option<String>,
    pub rkey: Option<String>,
}

impl AtUri {
    pub fn parse(s: &str) -> Result<AtUri, AtprotoError> {
        let rest = s
            .strip_prefix("at://")
            .ok_or_else(|| AtprotoError::Parse(format!("not an at:// URI: {s:?}")))?;
        let mut parts = rest.splitn(3, '/');
        let authority = parts
            .next()
            .filter(|a| !a.is_empty())
            .ok_or_else(|| AtprotoError::Parse("at-uri missing authority".into()))?
            .to_string();
        Ok(AtUri {
            authority,
            collection: parts.next().map(str::to_string).filter(|s| !s.is_empty()),
            rkey: parts.next().map(str::to_string).filter(|s| !s.is_empty()),
        })
    }
}

/// Decode a `did:key:z...` Ed25519 identity into a verifying key.
pub fn ed25519_from_did_key(did_key: &str) -> Result<VerifyingKey, AtprotoError> {
    let mb = did_key
        .strip_prefix("did:key:")
        .ok_or_else(|| AtprotoError::Parse("not a did:key".into()))?;
    let (_base, data) = multibase::decode(mb).map_err(|e| AtprotoError::Parse(e.to_string()))?;
    let key = data
        .strip_prefix(&ED25519_PUB_MULTICODEC[..])
        .ok_or_else(|| AtprotoError::Unsupported("did:key is not Ed25519".into()))?;
    let arr: [u8; 32] = key
        .try_into()
        .map_err(|_| AtprotoError::Parse("Ed25519 key not 32 bytes".into()))?;
    VerifyingKey::from_bytes(&arr).map_err(|e| AtprotoError::Crypto(e.to_string()))
}

/// Encode an Ed25519 public key as a `did:key:z...` string (multibase base58btc).
pub fn did_key_from_ed25519(vk: &VerifyingKey) -> String {
    let mut bytes = ED25519_PUB_MULTICODEC.to_vec();
    bytes.extend_from_slice(vk.as_bytes());
    format!("did:key:{}", multibase::encode(multibase::Base::Base58Btc, bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_dids_and_methods() {
        assert_eq!(Did::parse("did:plc:abc123").unwrap().method(), "plc");
        assert_eq!(Did::parse("did:web:example.com").unwrap().method(), "web");
        assert!(Did::parse("nope").is_err());
    }

    #[test]
    fn parses_at_uris() {
        let u = AtUri::parse("at://did:plc:abc/app.decodingus.biosample/3k2l").unwrap();
        assert_eq!(u.authority, "did:plc:abc");
        assert_eq!(u.collection.as_deref(), Some("app.decodingus.biosample"));
        assert_eq!(u.rkey.as_deref(), Some("3k2l"));

        let bare = AtUri::parse("at://did:plc:abc").unwrap();
        assert_eq!(bare.authority, "did:plc:abc");
        assert!(bare.collection.is_none());

        assert!(AtUri::parse("https://x").is_err());
    }
}
