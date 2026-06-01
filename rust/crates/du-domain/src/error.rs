use thiserror::Error;

/// Errors raised by pure domain logic (validation, invariant violations,
/// algorithm preconditions). IO/database errors live in their own crates.
#[derive(Debug, Error)]
pub enum DomainError {
    #[error("validation failed: {0}")]
    Validation(String),

    #[error("invalid reference build: {0}")]
    InvalidBuild(String),

    #[error("invariant violated: {0}")]
    Invariant(String),
}
