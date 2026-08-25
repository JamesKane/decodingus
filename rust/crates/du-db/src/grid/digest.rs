//! Result-digest comparison — the agreement test that gates canonicalization.
//!
//! Realignment is not byte-deterministic across thread counts and builds, so validation never
//! hashes the BAM. It compares a small canonical digest of **discrete calls** plus **bucketed**
//! continuous metrics. Design §5.2.
//!
//! # The node signs raw values; the AppView buckets
//!
//! This is an amendment to §5.2, which had the node submit values already bucketed. Bucketing on
//! the client would make the bucket function a **cross-repo contract**: every Navigator release
//! would have to round coverage exactly as the AppView expects, and a divergence between the two
//! would surface as unexplained `DIVERGENT` verdicts against honest nodes. Bucketing here instead
//! means the rule exists once, and it can be re-tuned without redeploying a single desktop client.
//!
//! The signature still covers what the node actually computed, because the node signs the raw
//! digest it sends. Boundary sensitivity — two honest nodes landing either side of a bucket edge —
//! is inherent to bucketing and is unchanged by where it happens.
//!
//! # What is compared
//!
//! Exact match, after trimming: `sex`, `y_terminal`, `ancestry_superpop_argmax`. Two digests that
//! both omit a field agree on it; one that has it and one that does not, disagree. That is the
//! wanted behaviour — a sample with no Y call and a sample called `R-FGC29071` are not the same
//! result.
//!
//! Bucketed: `coverage_mean` to the nearest 2×, `callable_fraction` to two decimals.
//!
//! **`mt_terminal` is deliberately absent.** `App::analyze_biosample` declines to assign mtDNA
//! because that value "is not final on CHM13", and the Grid realigns to CHM13 — so the digest
//! cannot require a value the analysis path does not produce. mt is still published in the full
//! records; it simply does not gate canonicalization. Design §12.3.

use serde_json::Value;

/// Coverage agrees within this bucket width, in fold-coverage.
const COVERAGE_BUCKET: f64 = 2.0;
/// Callable fraction agrees to this many decimal places.
const CALLABLE_DECIMALS: f64 = 100.0;

/// The comparable projection of one submitted digest.
///
/// Anything not listed here is carried in the full records but does not gate canonicalization.
#[derive(Debug, Clone, PartialEq)]
pub struct Comparable {
    pub sex: Option<String>,
    pub y_terminal: Option<String>,
    pub ancestry_superpop_argmax: Option<String>,
    /// `coverage_mean` snapped to [`COVERAGE_BUCKET`]; `None` when the digest omitted it.
    pub coverage_bucket: Option<i64>,
    /// `callable_fraction` snapped to two decimals.
    pub callable_bucket: Option<i64>,
}

fn text(calls: &Value, key: &str) -> Option<String> {
    calls
        .get(key)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

fn bucketed(calls: &Value, key: &str, scale: f64) -> Option<i64> {
    let v = calls.get(key).and_then(Value::as_f64)?;
    if !v.is_finite() {
        return None;
    }
    Some((v * scale).round() as i64)
}

impl Comparable {
    /// Project a submitted digest. A digest whose shape is wrong yields all-`None`, which agrees
    /// only with another equally empty digest — so a malformed submission can never canonicalize a
    /// unit on its own.
    pub fn from_digest(digest: &Value) -> Self {
        let calls = digest.get("calls").cloned().unwrap_or(Value::Null);
        Comparable {
            sex: text(&calls, "sex"),
            y_terminal: text(&calls, "y_terminal"),
            ancestry_superpop_argmax: text(&calls, "ancestry_superpop_argmax"),
            coverage_bucket: bucketed(&calls, "coverage_mean", 1.0 / COVERAGE_BUCKET),
            callable_bucket: bucketed(&calls, "callable_fraction", CALLABLE_DECIMALS),
        }
    }
}

/// The major component of a semver-ish stack version: `"1.7.0"` → `"1"`.
///
/// Only submissions from a compatible major are compared. A minor release that refactors a walker
/// should not invalidate a canonical result; a major one may legitimately change what a call means.
pub fn stack_major(version: &str) -> &str {
    version.split('.').next().unwrap_or(version).trim()
}

/// Whether two submissions are even *comparable* — same reference build and same stack major.
///
/// Results on different references are not in disagreement; they are answers to different
/// questions, and clustering them together would manufacture divergence out of nothing.
pub fn comparable(a_build: &str, a_stack: &str, b_build: &str, b_stack: &str) -> bool {
    a_build.trim().eq_ignore_ascii_case(b_build.trim())
        && stack_major(a_stack) == stack_major(b_stack)
}

/// The SHA-256 of a digest's canonical bytes, base64 (standard alphabet).
///
/// "Canonical" is `serde_json` with **sorted keys and no whitespace**, which is simply what
/// `serde_json::to_vec` produces: this workspace does not enable the `preserve_order` feature, so
/// `serde_json::Map` is a `BTreeMap` and serialization is key-sorted and deterministic. Navigator
/// uses the same crate under the same default, so both sides hash identical bytes without either
/// having to implement a canonicalization scheme.
///
/// **This is the one remaining byte-level contract between the repos**, and it is deliberately the
/// smallest one available: no field order to agree on, no float formatting rules, no separator
/// choices. The test below pins it. If `preserve_order` is ever switched on anywhere, that test
/// fails here rather than as unexplained 400s against desktop clients.
///
/// The submit handler recomputes this from the digest it received and rejects a mismatch, so a node
/// cannot sign the hash of one result and send another.
pub fn canonical_sha256_b64(digest: &Value) -> String {
    use base64::Engine as _;
    use sha2::{Digest as _, Sha256};
    let bytes = serde_json::to_vec(digest).unwrap_or_default();
    base64::engine::general_purpose::STANDARD.encode(Sha256::digest(bytes))
}

/// Whether two digests **agree**, per §5.2.
pub fn agree(a: &Value, b: &Value) -> bool {
    Comparable::from_digest(a) == Comparable::from_digest(b)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn d(sex: &str, y: &str, pop: &str, cov: f64, callable: f64) -> Value {
        json!({"calls": {
            "sex": sex, "y_terminal": y, "ancestry_superpop_argmax": pop,
            "coverage_mean": cov, "callable_fraction": callable
        }})
    }

    #[test]
    fn identical_digests_agree() {
        assert!(agree(
            &d("XY", "R-FGC29071", "EUR", 30.4, 0.9412),
            &d("XY", "R-FGC29071", "EUR", 30.4, 0.9412)
        ));
    }

    /// The point of bucketing: two honest nodes never produce the same float, and must still agree.
    #[test]
    fn continuous_metrics_agree_within_their_bucket() {
        assert!(agree(
            &d("XY", "R-A", "EUR", 30.4, 0.9412),
            &d("XY", "R-A", "EUR", 30.9, 0.9448)
        ));
    }

    #[test]
    fn a_different_discrete_call_is_a_disagreement() {
        assert!(!agree(
            &d("XY", "R-A", "EUR", 30.0, 0.94),
            &d("XY", "R-B", "EUR", 30.0, 0.94)
        ));
        assert!(!agree(
            &d("XY", "R-A", "EUR", 30.0, 0.94),
            &d("XX", "R-A", "EUR", 30.0, 0.94)
        ));
        assert!(!agree(
            &d("XY", "R-A", "EUR", 30.0, 0.94),
            &d("XY", "R-A", "AFR", 30.0, 0.94)
        ));
    }

    /// Far apart in coverage is a real disagreement — one of the two analysed different data.
    #[test]
    fn coverage_far_apart_is_a_disagreement() {
        assert!(!agree(
            &d("XY", "R-A", "EUR", 30.0, 0.94),
            &d("XY", "R-A", "EUR", 12.0, 0.94)
        ));
    }

    /// A sample with no Y call agrees with another that also has none, and never with one that does.
    #[test]
    fn a_missing_field_agrees_only_with_a_missing_field() {
        let absent = json!({"calls": {"sex": "XX", "ancestry_superpop_argmax": "EUR"}});
        let present =
            json!({"calls": {"sex": "XX", "ancestry_superpop_argmax": "EUR", "y_terminal": "R-A"}});
        assert!(agree(&absent, &absent.clone()));
        assert!(!agree(&absent, &present));
    }

    /// A malformed digest must never canonicalize anything by matching a well-formed one.
    #[test]
    fn a_shapeless_digest_agrees_with_nothing_real() {
        let junk = json!({"unexpected": true});
        assert!(!agree(&junk, &d("XY", "R-A", "EUR", 30.0, 0.94)));
        assert!(
            agree(&junk, &json!(null)),
            "two empties are consistent, and equally uninformative"
        );
    }

    /// The cross-repo byte contract: key order in the source JSON must not change the hash, or a
    /// node and the AppView would disagree about what was signed.
    #[test]
    fn the_canonical_hash_ignores_key_order() {
        let a = json!({"calls": {"sex": "XY", "y_terminal": "R-A"}, "unit": "SAMEA1"});
        let b = json!({"unit": "SAMEA1", "calls": {"y_terminal": "R-A", "sex": "XY"}});
        assert_eq!(canonical_sha256_b64(&a), canonical_sha256_b64(&b));
        assert_ne!(
            canonical_sha256_b64(&a),
            canonical_sha256_b64(&json!({"calls": {"sex": "XX"}, "unit": "SAMEA1"})),
            "a different result must hash differently, or the check is worthless"
        );
    }

    #[test]
    fn only_a_matching_build_and_stack_major_are_compared() {
        assert!(comparable("chm13v2.0", "1.7.0", "CHM13v2.0", "1.9.3"));
        assert!(!comparable("chm13v2.0", "1.7.0", "GRCh38", "1.7.0"));
        assert!(!comparable("chm13v2.0", "1.7.0", "chm13v2.0", "2.0.0"));
    }
}
