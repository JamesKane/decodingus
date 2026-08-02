//! External service clients (plan §7). OpenAlex (publication enrichment +
//! discovery), ENA (study metadata), NCBI/PubMed (publication enrichment by
//! PMID), GitHub Releases (Navigator installer downloads). HTTP via reqwest;
//! JSON→domain parsing is pure and unit-tested. AWS SES/Secrets + reCAPTCHA land
//! here later.

pub mod email;
pub mod ena;
pub mod error;
pub mod github;
pub mod ncbi;
pub mod openalex;
pub mod secrets;

pub use error::ExternalError;
