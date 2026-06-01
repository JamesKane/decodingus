//! AT Protocol identity, crypto, and resolution for DecodingUs (plan §7).
//!
//! Federation direction (June 2026): the custom "private firehose" is replaced
//! by the protocol's permissions/OAuth + notify-then-fetch model. This crate is
//! the foundation needed under either model — DID/handle identity, `did:key`
//! Ed25519 signature verification, and DID-document/PDS resolution. The OAuth
//! client (permission sets, PAR, DPoP) builds on top of this next.

pub mod did;
pub mod error;
pub mod oauth;
pub mod resolve;
pub mod signature;

pub use did::{AtUri, Did};
pub use error::AtprotoError;
pub use resolve::{DidDocument, Resolver};
pub use signature::verify_did_key;
