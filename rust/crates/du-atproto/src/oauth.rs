//! AT Protocol OAuth client wiring (plan §7; federation pivot to permissions/
//! OAuth). The spec-defined, testable pieces: PKCE, ES256 JOSE (client assertion
//! + DPoP proofs), client + authorization-server metadata, and request builders.
//!
//! The interactive handshake (PAR -> redirect -> token) is orchestrated by
//! du-web on top of these; it requires a live PDS / authorization server, so the
//! end-to-end flow is exercised with the Edge team (see docs/atproto-oauth-findings.md).

use crate::error::AtprotoError;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use p256::ecdsa::{signature::Signer, Signature, SigningKey, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

fn b64u(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

// ── EC (P-256 / ES256) client key ────────────────────────────────────────────

/// The client's P-256 signing key (used for `private_key_jwt` client assertions
/// and DPoP proofs). Persisted as the base64url-encoded 32-byte scalar.
pub struct EcKey {
    signing: SigningKey,
}

impl EcKey {
    pub fn generate() -> Self {
        EcKey { signing: SigningKey::random(&mut rand_core::OsRng) }
    }

    pub fn from_base64(s: &str) -> Result<Self, AtprotoError> {
        let bytes = URL_SAFE_NO_PAD
            .decode(s.trim())
            .map_err(|e| AtprotoError::Parse(format!("ec key base64: {e}")))?;
        let signing = SigningKey::from_slice(&bytes).map_err(|e| AtprotoError::Crypto(e.to_string()))?;
        Ok(EcKey { signing })
    }

    pub fn to_base64(&self) -> String {
        b64u(&self.signing.to_bytes())
    }

    pub fn verifying_key(&self) -> VerifyingKey {
        *self.signing.verifying_key()
    }

    /// Public key as a JWK (`{kty,crv,x,y}`), plus `kid` set to the thumbprint.
    pub fn public_jwk(&self) -> serde_json::Value {
        let (x, y) = self.xy();
        serde_json::json!({
            "kty": "EC", "crv": "P-256", "x": x, "y": y,
            "use": "sig", "alg": "ES256", "kid": self.thumbprint(),
        })
    }

    /// Bare public JWK used inside a DPoP header (no kid/use/alg).
    pub fn dpop_jwk(&self) -> serde_json::Value {
        let (x, y) = self.xy();
        serde_json::json!({ "kty": "EC", "crv": "P-256", "x": x, "y": y })
    }

    /// RFC 7638 JWK thumbprint (base64url SHA-256 of the canonical JWK).
    pub fn thumbprint(&self) -> String {
        let (x, y) = self.xy();
        // Canonical member ordering: crv, kty, x, y.
        let canonical = format!(r#"{{"crv":"P-256","kty":"EC","x":"{x}","y":"{y}"}}"#);
        b64u(&Sha256::digest(canonical.as_bytes()))
    }

    fn xy(&self) -> (String, String) {
        let ep = self.verifying_key().to_encoded_point(false);
        (b64u(ep.x().unwrap()), b64u(ep.y().unwrap()))
    }

    /// Sign a compact JWS (`b64u(header).b64u(payload).b64u(sig)`) with ES256.
    pub fn sign_jws(&self, header: &serde_json::Value, payload: &serde_json::Value) -> String {
        let signing_input = format!(
            "{}.{}",
            b64u(header.to_string().as_bytes()),
            b64u(payload.to_string().as_bytes())
        );
        let sig: Signature = self.signing.sign(signing_input.as_bytes());
        format!("{}.{}", signing_input, b64u(&sig.to_bytes()))
    }
}

// ── PKCE ─────────────────────────────────────────────────────────────────────

pub struct Pkce {
    pub verifier: String,
    pub challenge: String,
}

impl Pkce {
    pub fn generate() -> Self {
        let mut bytes = [0u8; 32];
        rand_core::RngCore::fill_bytes(&mut rand_core::OsRng, &mut bytes);
        Self::from_verifier(b64u(&bytes))
    }

    pub fn from_verifier(verifier: String) -> Self {
        let challenge = b64u(&Sha256::digest(verifier.as_bytes()));
        Pkce { verifier, challenge }
    }
}

/// A random URL-safe token (PKCE-style), for `state` / `jti`.
pub fn random_token() -> String {
    let mut bytes = [0u8; 24];
    rand_core::RngCore::fill_bytes(&mut rand_core::OsRng, &mut bytes);
    b64u(&bytes)
}

// ── JWTs: client assertion + DPoP proof ──────────────────────────────────────

/// `private_key_jwt` client assertion for authenticating at PAR/token endpoints.
pub fn client_assertion(key: &EcKey, client_id: &str, audience: &str, iat: i64) -> String {
    let header = serde_json::json!({ "alg": "ES256", "typ": "JWT", "kid": key.thumbprint() });
    let payload = serde_json::json!({
        "iss": client_id, "sub": client_id, "aud": audience,
        "jti": random_token(), "iat": iat, "exp": iat + 300,
    });
    key.sign_jws(&header, &payload)
}

/// A DPoP proof JWT binding a request (`htm` method, `htu` URL) to the key.
/// `nonce` is set when the server has supplied one; `ath` is the base64url
/// SHA-256 of the access token (required on token-bound requests).
pub fn dpop_proof(
    key: &EcKey,
    htm: &str,
    htu: &str,
    iat: i64,
    nonce: Option<&str>,
    access_token: Option<&str>,
) -> String {
    let header = serde_json::json!({ "typ": "dpop+jwt", "alg": "ES256", "jwk": key.dpop_jwk() });
    let mut payload = serde_json::json!({
        "jti": random_token(), "htm": htm, "htu": htu, "iat": iat,
    });
    if let Some(n) = nonce {
        payload["nonce"] = serde_json::Value::String(n.to_string());
    }
    if let Some(tok) = access_token {
        payload["ath"] = serde_json::Value::String(b64u(&Sha256::digest(tok.as_bytes())));
    }
    key.sign_jws(&header, &payload)
}

// ── Metadata documents ───────────────────────────────────────────────────────

/// The client metadata document served at `client_id` (a stable HTTPS URL).
#[derive(Debug, Clone, Serialize)]
pub struct ClientMetadata {
    pub client_id: String,
    pub client_name: String,
    pub client_uri: String,
    pub redirect_uris: Vec<String>,
    pub grant_types: Vec<String>,
    pub response_types: Vec<String>,
    pub scope: String,
    pub token_endpoint_auth_method: String,
    pub token_endpoint_auth_signing_alg: String,
    pub application_type: String,
    pub dpop_bound_access_tokens: bool,
    pub jwks_uri: String,
}

impl ClientMetadata {
    /// Confidential web client using `private_key_jwt` + DPoP (the atproto
    /// recommendation for server-side apps).
    pub fn confidential_web(base_url: &str, scope: &str) -> Self {
        ClientMetadata {
            client_id: format!("{base_url}/oauth/client-metadata.json"),
            client_name: "Decoding Us".to_string(),
            client_uri: base_url.to_string(),
            redirect_uris: vec![format!("{base_url}/oauth/callback")],
            grant_types: vec!["authorization_code".into(), "refresh_token".into()],
            response_types: vec!["code".into()],
            scope: scope.to_string(),
            token_endpoint_auth_method: "private_key_jwt".into(),
            token_endpoint_auth_signing_alg: "ES256".into(),
            application_type: "web".into(),
            dpop_bound_access_tokens: true,
            jwks_uri: format!("{base_url}/oauth/jwks.json"),
        }
    }
}

/// Authorization-server metadata (`/.well-known/oauth-authorization-server`).
#[derive(Debug, Clone, Deserialize)]
pub struct AuthServerMetadata {
    pub issuer: String,
    pub authorization_endpoint: String,
    pub token_endpoint: String,
    pub pushed_authorization_request_endpoint: Option<String>,
}

/// Protected-resource metadata (`/.well-known/oauth-protected-resource`) — names
/// the authorization server(s) for a PDS.
#[derive(Debug, Clone, Deserialize)]
pub struct ProtectedResourceMetadata {
    #[serde(default)]
    pub authorization_servers: Vec<String>,
}

/// Discover the authorization server for a PDS, then its metadata.
pub async fn discover_auth_server(
    client: &reqwest::Client,
    pds_url: &str,
) -> Result<AuthServerMetadata, AtprotoError> {
    let prm: ProtectedResourceMetadata = client
        .get(format!("{pds_url}/.well-known/oauth-protected-resource"))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    let issuer = prm
        .authorization_servers
        .into_iter()
        .next()
        .ok_or_else(|| AtprotoError::Resolve("no authorization_servers for PDS".into()))?;
    let meta: AuthServerMetadata = client
        .get(format!("{issuer}/.well-known/oauth-authorization-server"))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    Ok(meta)
}

// ── Request builders (pure) ──────────────────────────────────────────────────

/// PAR (pushed authorization request) form body.
#[allow(clippy::too_many_arguments)]
pub fn par_form(
    client_id: &str,
    redirect_uri: &str,
    scope: &str,
    state: &str,
    code_challenge: &str,
    login_hint: Option<&str>,
    client_assertion_jwt: &str,
) -> Vec<(String, String)> {
    let mut form = vec![
        ("response_type".into(), "code".into()),
        ("client_id".into(), client_id.into()),
        ("redirect_uri".into(), redirect_uri.into()),
        ("scope".into(), scope.into()),
        ("state".into(), state.into()),
        ("code_challenge".into(), code_challenge.into()),
        ("code_challenge_method".into(), "S256".into()),
        (
            "client_assertion_type".into(),
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer".into(),
        ),
        ("client_assertion".into(), client_assertion_jwt.into()),
    ];
    if let Some(hint) = login_hint {
        form.push(("login_hint".into(), hint.into()));
    }
    form
}

/// PAR form for a **public client** (PKCE only, no `client_assertion`) — e.g.
/// the Navigator desktop app. The auth method is established by PKCE + DPoP.
pub fn par_form_public(
    client_id: &str,
    redirect_uri: &str,
    scope: &str,
    state: &str,
    code_challenge: &str,
    login_hint: Option<&str>,
) -> Vec<(String, String)> {
    let mut form = vec![
        ("response_type".into(), "code".into()),
        ("client_id".into(), client_id.into()),
        ("redirect_uri".into(), redirect_uri.into()),
        ("scope".into(), scope.into()),
        ("state".into(), state.into()),
        ("code_challenge".into(), code_challenge.into()),
        ("code_challenge_method".into(), "S256".into()),
    ];
    if let Some(hint) = login_hint {
        form.push(("login_hint".into(), hint.into()));
    }
    form
}

/// Token-exchange form for a **public client** (PKCE only, no `client_assertion`).
pub fn token_form_public(
    client_id: &str,
    redirect_uri: &str,
    code: &str,
    code_verifier: &str,
) -> Vec<(String, String)> {
    vec![
        ("grant_type".into(), "authorization_code".into()),
        ("code".into(), code.into()),
        ("redirect_uri".into(), redirect_uri.into()),
        ("client_id".into(), client_id.into()),
        ("code_verifier".into(), code_verifier.into()),
    ]
}

/// Authorization-code token-exchange form body.
pub fn token_form(
    client_id: &str,
    redirect_uri: &str,
    code: &str,
    code_verifier: &str,
    client_assertion_jwt: &str,
) -> Vec<(String, String)> {
    vec![
        ("grant_type".into(), "authorization_code".into()),
        ("code".into(), code.into()),
        ("redirect_uri".into(), redirect_uri.into()),
        ("client_id".into(), client_id.into()),
        ("code_verifier".into(), code_verifier.into()),
        (
            "client_assertion_type".into(),
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer".into(),
        ),
        ("client_assertion".into(), client_assertion_jwt.into()),
    ]
}

/// Build the authorization redirect URL from a PAR `request_uri`.
pub fn authorize_url(authorization_endpoint: &str, client_id: &str, request_uri: &str) -> String {
    let q = format!(
        "client_id={}&request_uri={}",
        urlencode(client_id),
        urlencode(request_uri)
    );
    let sep = if authorization_endpoint.contains('?') { '&' } else { '?' };
    format!("{authorization_endpoint}{sep}{q}")
}

fn urlencode(s: &str) -> String {
    // Minimal application/x-www-form-urlencoded for query values.
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::signature::Verifier;

    fn decode_part(part: &str) -> serde_json::Value {
        serde_json::from_slice(&URL_SAFE_NO_PAD.decode(part).unwrap()).unwrap()
    }

    #[test]
    fn pkce_matches_rfc7636_vector() {
        // RFC 7636 Appendix B.
        let p = Pkce::from_verifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk".into());
        assert_eq!(p.challenge, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    #[test]
    fn es256_jws_roundtrips_and_verifies() {
        let key = EcKey::generate();
        let jwt = client_assertion(&key, "https://app.example/oauth/client-metadata.json", "https://pds.example", 1_700_000_000);
        let parts: Vec<&str> = jwt.split('.').collect();
        assert_eq!(parts.len(), 3);

        let header = decode_part(parts[0]);
        assert_eq!(header["alg"], "ES256");
        assert_eq!(header["kid"], key.thumbprint());
        let payload = decode_part(parts[1]);
        assert_eq!(payload["iss"], "https://app.example/oauth/client-metadata.json");
        assert_eq!(payload["aud"], "https://pds.example");

        // signature verifies against the public key over the signing input.
        let signing_input = format!("{}.{}", parts[0], parts[1]);
        let sig = Signature::from_slice(&URL_SAFE_NO_PAD.decode(parts[2]).unwrap()).unwrap();
        assert!(key.verifying_key().verify(signing_input.as_bytes(), &sig).is_ok());
    }

    #[test]
    fn dpop_proof_has_jwk_htm_htu_ath() {
        let key = EcKey::generate();
        let jwt = dpop_proof(&key, "POST", "https://pds.example/xrpc/com.atproto.server.getSession", 1_700_000_000, Some("srvnonce"), Some("access-tok"));
        let parts: Vec<&str> = jwt.split('.').collect();
        let header = decode_part(parts[0]);
        assert_eq!(header["typ"], "dpop+jwt");
        assert_eq!(header["jwk"]["crv"], "P-256");
        let payload = decode_part(parts[1]);
        assert_eq!(payload["htm"], "POST");
        assert_eq!(payload["nonce"], "srvnonce");
        assert!(payload["ath"].is_string());
    }

    #[test]
    fn ec_key_base64_roundtrips() {
        let key = EcKey::generate();
        let b64 = key.to_base64();
        let restored = EcKey::from_base64(&b64).unwrap();
        assert_eq!(key.thumbprint(), restored.thumbprint());
    }

    #[test]
    fn public_client_forms_omit_client_assertion() {
        let par = par_form_public("nav-client", "http://127.0.0.1:0/callback", "atproto", "st8", "chal", Some("alice.test"));
        assert!(par.iter().all(|(k, _)| k != "client_assertion"));
        assert!(par.iter().any(|(k, v)| k == "code_challenge_method" && v == "S256"));
        assert!(par.iter().any(|(k, v)| k == "login_hint" && v == "alice.test"));

        let tok = token_form_public("nav-client", "http://127.0.0.1:0/callback", "code123", "verifier");
        assert!(tok.iter().all(|(k, _)| k != "client_assertion"));
        assert!(tok.iter().any(|(k, v)| k == "grant_type" && v == "authorization_code"));
        assert!(tok.iter().any(|(k, v)| k == "code_verifier" && v == "verifier"));
    }

    #[test]
    fn client_metadata_shape() {
        let m = ClientMetadata::confidential_web("https://decoding-us.com", "atproto transition:generic");
        let v = serde_json::to_value(&m).unwrap();
        assert_eq!(v["client_id"], "https://decoding-us.com/oauth/client-metadata.json");
        assert_eq!(v["token_endpoint_auth_method"], "private_key_jwt");
        assert_eq!(v["dpop_bound_access_tokens"], true);
        assert_eq!(v["redirect_uris"][0], "https://decoding-us.com/oauth/callback");
    }
}
