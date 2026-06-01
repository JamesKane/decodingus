use thiserror::Error;

#[derive(Debug, Error)]
pub enum ExternalError {
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("parse error: {0}")]
    Parse(String),
    #[error("aws error: {0}")]
    Aws(String),
}
