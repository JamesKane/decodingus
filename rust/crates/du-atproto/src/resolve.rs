//! Handle/DID resolution and DID-document parsing.
//!
//! - handle -> DID via the HTTPS well-known method (`/.well-known/atproto-did`).
//!   (The DNS `_atproto` TXT method is a future addition; it needs a DNS dep.)
//! - DID -> DID document via the PLC directory (`did:plc`) or `did:web`.
//! - From the document: the PDS service endpoint and the signing `did:key`.
//!
//! Document parsing is pure and unit-tested; the HTTP fetch is isolated in
//! `Resolver`.

use crate::did::Did;
use crate::error::AtprotoError;
use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
pub struct DidDocument {
    pub id: String,
    #[serde(default, rename = "alsoKnownAs")]
    pub also_known_as: Vec<String>,
    #[serde(default, rename = "verificationMethod")]
    pub verification_method: Vec<VerificationMethod>,
    #[serde(default)]
    pub service: Vec<Service>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct VerificationMethod {
    pub id: String,
    #[serde(rename = "type")]
    pub typ: String,
    #[serde(rename = "publicKeyMultibase")]
    pub public_key_multibase: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Service {
    pub id: String,
    #[serde(rename = "type")]
    pub typ: String,
    #[serde(rename = "serviceEndpoint")]
    pub service_endpoint: String,
}

impl DidDocument {
    /// The PDS service endpoint (`#atproto_pds` / `AtprotoPersonalDataServer`).
    pub fn pds_endpoint(&self) -> Option<&str> {
        self.service
            .iter()
            .find(|s| s.id.ends_with("#atproto_pds") || s.typ == "AtprotoPersonalDataServer")
            .map(|s| s.service_endpoint.as_str())
    }

    /// The primary handle (`alsoKnownAs` `at://<handle>`), if any.
    pub fn handle(&self) -> Option<String> {
        self.also_known_as
            .iter()
            .find_map(|a| a.strip_prefix("at://").map(str::to_string))
    }

    /// The signing key as a `did:key` (the Multikey `publicKeyMultibase` is the
    /// did:key suffix).
    pub fn signing_did_key(&self) -> Option<String> {
        self.verification_method
            .iter()
            .find_map(|vm| vm.public_key_multibase.as_ref().map(|m| format!("did:key:{m}")))
    }
}

/// Resolves handles and DIDs over HTTPS.
pub struct Resolver {
    client: reqwest::Client,
    plc_directory: String,
}

impl Default for Resolver {
    fn default() -> Self {
        Self::new()
    }
}

impl Resolver {
    pub fn new() -> Self {
        Resolver {
            client: reqwest::Client::new(),
            plc_directory: "https://plc.directory".to_string(),
        }
    }

    /// handle -> DID via `https://<handle>/.well-known/atproto-did`.
    pub async fn resolve_handle(&self, handle: &str) -> Result<Did, AtprotoError> {
        let url = format!("https://{handle}/.well-known/atproto-did");
        let body = self.client.get(url).send().await?.error_for_status()?.text().await?;
        Did::parse(body.trim())
    }

    /// DID -> DID document (`did:plc` via PLC directory, `did:web` via well-known).
    pub async fn resolve_did(&self, did: &Did) -> Result<DidDocument, AtprotoError> {
        let url = match did.method() {
            "plc" => format!("{}/{}", self.plc_directory, did.as_str()),
            "web" => did_web_doc_url(did.as_str())?,
            m => return Err(AtprotoError::Unsupported(format!("did method: {m}"))),
        };
        let doc = self
            .client
            .get(url)
            .send()
            .await?
            .error_for_status()?
            .json::<DidDocument>()
            .await?;
        Ok(doc)
    }

    /// Convenience: resolve a DID straight to its PDS endpoint.
    pub async fn resolve_pds(&self, did: &Did) -> Result<String, AtprotoError> {
        self.resolve_did(did)
            .await?
            .pds_endpoint()
            .map(str::to_string)
            .ok_or_else(|| AtprotoError::Resolve("no PDS endpoint in DID document".into()))
    }
}

/// `did:web:example.com` -> `https://example.com/.well-known/did.json`;
/// `did:web:example.com:u:alice` -> `https://example.com/u/alice/did.json`.
fn did_web_doc_url(did: &str) -> Result<String, AtprotoError> {
    let rest = did
        .strip_prefix("did:web:")
        .ok_or_else(|| AtprotoError::Parse("not did:web".into()))?;
    let mut parts = rest.split(':');
    let host = parts.next().unwrap_or("").replace("%3A", ":");
    let path: Vec<&str> = parts.collect();
    if path.is_empty() {
        Ok(format!("https://{host}/.well-known/did.json"))
    } else {
        Ok(format!("https://{host}/{}/did.json", path.join("/")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // r##"…"## because the JSON contains the sequence `"#` (in "#atproto_pds").
    const FIXTURE: &str = r##"{
        "id": "did:plc:abc123",
        "alsoKnownAs": ["at://alice.example.com"],
        "verificationMethod": [{
            "id": "did:plc:abc123#atproto",
            "type": "Multikey",
            "controller": "did:plc:abc123",
            "publicKeyMultibase": "zQ3shXjHeiBuRCKmM36cuYnm7YEMzhGnCmCyW92sRJ9pribSF"
        }],
        "service": [{
            "id": "#atproto_pds",
            "type": "AtprotoPersonalDataServer",
            "serviceEndpoint": "https://pds.example.com"
        }]
    }"##;

    #[test]
    fn parses_did_document_pds_handle_and_key() {
        let doc: DidDocument = serde_json::from_str(FIXTURE).unwrap();
        assert_eq!(doc.pds_endpoint(), Some("https://pds.example.com"));
        assert_eq!(doc.handle().as_deref(), Some("alice.example.com"));
        assert_eq!(
            doc.signing_did_key().as_deref(),
            Some("did:key:zQ3shXjHeiBuRCKmM36cuYnm7YEMzhGnCmCyW92sRJ9pribSF")
        );
    }

    #[test]
    fn did_web_url_host_and_path() {
        assert_eq!(did_web_doc_url("did:web:example.com").unwrap(), "https://example.com/.well-known/did.json");
        assert_eq!(did_web_doc_url("did:web:example.com:u:alice").unwrap(), "https://example.com/u/alice/did.json");
    }
}
