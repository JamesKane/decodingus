//! External service clients (plan §7). OpenAlex (publication enrichment +
//! discovery) and ENA (study metadata). HTTP via reqwest; JSON→domain parsing is
//! pure and unit-tested. AWS SES/Secrets + reCAPTCHA land here later.

pub mod ena;
pub mod error;
pub mod openalex;

pub use error::ExternalError;
