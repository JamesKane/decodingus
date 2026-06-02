//! External service clients (plan §7). OpenAlex (publication enrichment +
//! discovery), ENA (study metadata), NCBI/PubMed (publication enrichment by
//! PMID). HTTP via reqwest; JSON→domain parsing is pure and unit-tested. AWS
//! SES/Secrets + reCAPTCHA land here later.

pub mod email;
pub mod ena;
pub mod error;
pub mod ncbi;
pub mod openalex;
pub mod secrets;

pub use error::ExternalError;
