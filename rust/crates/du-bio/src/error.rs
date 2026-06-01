use thiserror::Error;

#[derive(Debug, Error)]
pub enum BioError {
    #[error("parse error: {0}")]
    Parse(String),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
}
