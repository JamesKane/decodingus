use thiserror::Error;

#[derive(Debug, Error)]
pub enum AtprotoError {
    #[error("parse error: {0}")]
    Parse(String),
    #[error("unsupported: {0}")]
    Unsupported(String),
    #[error("signature verification failed")]
    BadSignature,
    #[error("crypto error: {0}")]
    Crypto(String),
    #[error("resolution failed: {0}")]
    Resolve(String),
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
}
