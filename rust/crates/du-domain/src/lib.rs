//! Pure domain layer for DecodingUs: types and algorithms with no IO.
//!
//! This crate intentionally has no database, web, or async dependencies. JSONB
//! payload shapes (the redesigned "document columns") live here as `serde`
//! structs so both `du-db` (persistence) and `du-web` (presentation) share one
//! source of truth.

pub mod enums;
pub mod error;
pub mod ids;
pub mod variant;

pub use enums::*;
pub use error::DomainError;
pub use ids::*;
