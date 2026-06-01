//! Strongly-typed identifiers. Prevents mixing up the many integer/UUID keys
//! that flow through the genomics domain (a frequent source of bugs in the
//! legacy code where everything was a bare `Int`/`UUID`).

use serde::{Deserialize, Serialize};
use uuid::Uuid;

macro_rules! int_id {
    ($(#[$m:meta])* $name:ident) => {
        $(#[$m])*
        #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
        #[serde(transparent)]
        pub struct $name(pub i64);

        impl std::fmt::Display for $name {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                write!(f, "{}", self.0)
            }
        }
        impl From<i64> for $name {
            fn from(v: i64) -> Self { $name(v) }
        }
    };
}

int_id!(/// Primary key of `core.variant`.
    VariantId);
int_id!(/// Primary key of `tree.haplogroup`.
    HaplogroupId);
int_id!(/// Primary key of `pub.publication`.
    PublicationId);

/// A biosample's stable cross-system identity (UUID), shared by all sources
/// (standard/citizen/pgp/external/ancient) in the unified `core.biosample`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct SampleGuid(pub Uuid);

impl std::fmt::Display for SampleGuid {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

/// A user's stable identity (UUID).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct UserId(pub Uuid);

impl std::fmt::Display for UserId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}
