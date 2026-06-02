//! Y-STR per-branch modal signatures: aggregate the mirrored STR profiles
//! (`fed.str_profile`) by SNP-defined branch (haplogroup) into a modal haplotype
//! stored in `tree.haplogroup_ancestral_str`. This is the catalog-side half of
//! the STR feature (the mirror is `fed::str_profile`); prediction over these
//! signatures is Phase 2.
//!
//! Marker values follow the lexicon union — simple (`DYS393=13`), multi-copy
//! (`DYS385a/b=[11,14]`), complex/palindromic. Modal + distance score simple +
//! multi-copy; complex is preserved on the profile but not scored (v1).

use crate::DbError;
use serde_json::{json, Value};
use sqlx::PgPool;
use std::collections::{BTreeMap, HashMap};

/// A parsed STR marker value.
#[derive(Debug, Clone, PartialEq)]
pub enum StrValue {
    Simple(i32),
    MultiCopy(Vec<i32>),
    /// Palindromic/complex — preserved but excluded from scoring in v1.
    Complex(Value),
}

/// Hashable/orderable key for the scoreable values (simple + multi-copy).
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
enum ScoreKey {
    Simple(i32),
    Multi(Vec<i32>),
}

impl StrValue {
    fn score_key(&self) -> Option<ScoreKey> {
        match self {
            StrValue::Simple(n) => Some(ScoreKey::Simple(*n)),
            StrValue::MultiCopy(v) => {
                let mut s = v.clone();
                s.sort_unstable(); // normalize (lexicon convention is ascending)
                Some(ScoreKey::Multi(s))
            }
            StrValue::Complex(_) => None,
        }
    }
}

impl ScoreKey {
    fn to_json(&self) -> Value {
        match self {
            ScoreKey::Simple(n) => json!({ "type": "simple", "repeats": n }),
            ScoreKey::Multi(v) => json!({ "type": "multiCopy", "copies": v }),
        }
    }
    fn simple_int(&self) -> Option<i32> {
        match self {
            ScoreKey::Simple(n) => Some(*n),
            ScoreKey::Multi(_) => None,
        }
    }
}

/// Parse one `strMarkerValue` JSON object into `(marker, value)`.
fn parse_marker(m: &Value) -> Option<(String, StrValue)> {
    let marker = m.get("marker")?.as_str()?.to_string();
    let v = m.get("value")?;
    let value = match v.get("type").and_then(Value::as_str)? {
        "simple" => StrValue::Simple(v.get("repeats")?.as_i64()? as i32),
        "multiCopy" => {
            let copies = v.get("copies")?.as_array()?.iter().filter_map(|x| x.as_i64().map(|n| n as i32)).collect();
            StrValue::MultiCopy(copies)
        }
        "complex" => StrValue::Complex(v.clone()),
        _ => return None,
    };
    Some((marker, value))
}

/// Parse a profile's `markers` JSONB array into typed `(marker, value)` pairs.
pub fn parse_markers(markers: &Value) -> Vec<(String, StrValue)> {
    markers.as_array().map(|a| a.iter().filter_map(parse_marker).collect()).unwrap_or_default()
}

/// One marker of a branch's modal haplotype.
#[derive(Debug, Clone, PartialEq)]
pub struct ModalMarker {
    pub marker: String,
    /// Simple repeat count (None for multi-copy — see `ancestral_json`).
    pub ancestral_value: Option<i32>,
    /// The modal value as a lexicon `strValue`.
    pub ancestral_json: Value,
    /// modal_count / observations for this marker (0.0–1.0).
    pub confidence: f64,
    /// Number of scored observations backing this marker.
    pub supporting_samples: i32,
}

/// Compute the modal haplotype across a set of profiles. For each marker, the
/// most common scoreable value wins (ties → smallest, deterministic); confidence
/// is its share of observations. Complex markers are ignored.
pub fn compute_modal(profiles: &[Vec<(String, StrValue)>]) -> Vec<ModalMarker> {
    let mut counts: BTreeMap<String, HashMap<ScoreKey, usize>> = BTreeMap::new();
    for profile in profiles {
        for (marker, value) in profile {
            if let Some(key) = value.score_key() {
                *counts.entry(marker.clone()).or_default().entry(key).or_insert(0) += 1;
            }
        }
    }
    counts
        .into_iter()
        .filter_map(|(marker, hist)| {
            let total: usize = hist.values().sum();
            if total == 0 {
                return None;
            }
            // max count, tie-break on smallest key (Ord) for determinism.
            let (key, count) = hist
                .into_iter()
                .max_by(|(ka, ca), (kb, cb)| ca.cmp(cb).then_with(|| kb.cmp(ka)))?;
            Some(ModalMarker {
                marker,
                ancestral_value: key.simple_int(),
                ancestral_json: key.to_json(),
                confidence: count as f64 / total as f64,
                supporting_samples: total as i32,
            })
        })
        .collect()
}

/// Stepwise genetic distance over markers shared+scoreable in both profiles:
/// Σ|a−b| (multi-copy compared element-wise on sorted copies of equal length).
/// Returns `(distance, compared_marker_count)`; `None` if nothing comparable.
/// (Phase-2 prediction helper.)
pub fn distance(a: &[(String, StrValue)], b: &[(String, StrValue)]) -> Option<(i32, usize)> {
    let bm: HashMap<&str, &StrValue> = b.iter().map(|(m, v)| (m.as_str(), v)).collect();
    let mut sum = 0i32;
    let mut compared = 0usize;
    for (marker, av) in a {
        let Some(bv) = bm.get(marker.as_str()) else { continue };
        match (av, bv) {
            (StrValue::Simple(x), StrValue::Simple(y)) => {
                sum += (x - y).abs();
                compared += 1;
            }
            (StrValue::MultiCopy(x), StrValue::MultiCopy(y)) if x.len() == y.len() => {
                let (mut xs, mut ys) = (x.clone(), y.clone());
                xs.sort_unstable();
                ys.sort_unstable();
                sum += xs.iter().zip(&ys).map(|(p, q)| (p - q).abs()).sum::<i32>();
                compared += 1;
            }
            _ => {} // type mismatch / complex → skip
        }
    }
    (compared > 0).then_some((sum, compared))
}

// ── queries ───────────────────────────────────────────────────────────────────

#[derive(Debug, Default)]
pub struct RecomputeStats {
    pub haplogroups: usize,
    pub markers: usize,
}

/// Recompute every Y branch's modal signature from the mirrored profiles.
/// Profiles reach a branch via their biosample's Y-haplogroup assignment
/// (`fed.str_profile` → `fed.biosample.y_haplogroup` → `tree.haplogroup`).
/// Full refresh of `method='MODAL'` rows; `MANUAL` overrides are preserved.
pub async fn recompute_signatures(pool: &PgPool) -> Result<RecomputeStats, DbError> {
    let rows: Vec<(i64, Value)> = sqlx::query_as(
        "SELECT h.id, sp.markers \
         FROM fed.str_profile sp \
         JOIN fed.biosample b ON b.at_uri = sp.biosample_ref \
         JOIN tree.haplogroup h ON h.name = b.y_haplogroup \
              AND h.haplogroup_type::text = 'Y_DNA' AND h.valid_until IS NULL \
         WHERE b.y_haplogroup IS NOT NULL",
    )
    .fetch_all(pool)
    .await?;

    // Group profiles by haplogroup.
    let mut by_hg: BTreeMap<i64, Vec<Vec<(String, StrValue)>>> = BTreeMap::new();
    for (hg_id, markers) in &rows {
        by_hg.entry(*hg_id).or_default().push(parse_markers(markers));
    }

    let mut tx = pool.begin().await?;
    // Full refresh of computed signatures (manual overrides survive the upsert).
    sqlx::query("DELETE FROM tree.haplogroup_ancestral_str WHERE method = 'MODAL'")
        .execute(&mut *tx)
        .await?;

    let mut stats = RecomputeStats::default();
    for (hg_id, profiles) in &by_hg {
        let modal = compute_modal(profiles);
        if modal.is_empty() {
            continue;
        }
        stats.haplogroups += 1;
        for m in &modal {
            // DO NOTHING preserves a MANUAL row for the same (haplogroup, marker).
            let n = sqlx::query(
                "INSERT INTO tree.haplogroup_ancestral_str \
                   (haplogroup_id, marker_name, ancestral_value, ancestral_json, confidence, \
                    supporting_samples, method, recomputed_at) \
                 VALUES ($1,$2,$3,$4, ROUND($5::numeric, 3), $6, 'MODAL', now()) \
                 ON CONFLICT (haplogroup_id, marker_name) DO NOTHING",
            )
            .bind(hg_id)
            .bind(&m.marker)
            .bind(m.ancestral_value)
            .bind(&m.ancestral_json)
            .bind(m.confidence)
            .bind(m.supporting_samples)
            .execute(&mut *tx)
            .await?
            .rows_affected();
            stats.markers += n as usize;
        }
    }
    tx.commit().await?;
    Ok(stats)
}

/// A marker of a branch's stored signature (for the read endpoint).
#[derive(Debug, sqlx::FromRow)]
pub struct SignatureMarker {
    pub marker_name: String,
    pub ancestral_value: Option<i32>,
    pub ancestral_json: Option<Value>,
    pub confidence: Option<f64>,
    pub supporting_samples: Option<i32>,
    pub method: Option<String>,
}

/// The modal/ancestral STR signature for a Y haplogroup (by name), markers sorted.
pub async fn branch_signature(pool: &PgPool, haplogroup: &str) -> Result<Vec<SignatureMarker>, DbError> {
    let rows = sqlx::query_as::<_, SignatureMarker>(
        "SELECT s.marker_name, s.ancestral_value, s.ancestral_json, \
                s.confidence::float8 AS confidence, s.supporting_samples, s.method \
         FROM tree.haplogroup_ancestral_str s \
         JOIN tree.haplogroup h ON h.id = s.haplogroup_id \
         WHERE h.name = $1 AND h.haplogroup_type::text = 'Y_DNA' AND h.valid_until IS NULL \
         ORDER BY s.marker_name",
    )
    .bind(haplogroup)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn simple(name: &str, n: i32) -> (String, StrValue) {
        (name.into(), StrValue::Simple(n))
    }

    #[test]
    fn parses_marker_union() {
        let markers = json!([
            { "marker": "DYS393", "value": { "type": "simple", "repeats": 13 } },
            { "marker": "DYS385", "value": { "type": "multiCopy", "copies": [11, 14] } },
            { "marker": "DYF399X", "value": { "type": "complex", "alleles": [], "rawNotation": "22-25" } }
        ]);
        let parsed = parse_markers(&markers);
        assert_eq!(parsed.len(), 3);
        assert_eq!(parsed[0], simple("DYS393", 13));
        assert_eq!(parsed[1].1, StrValue::MultiCopy(vec![11, 14]));
        assert!(matches!(parsed[2].1, StrValue::Complex(_)));
    }

    #[test]
    fn modal_picks_majority_and_skips_complex() {
        let profiles = vec![
            vec![simple("DYS393", 13), ("DYS385".into(), StrValue::MultiCopy(vec![11, 14])), ("DYF399X".into(), StrValue::Complex(json!({})))],
            vec![simple("DYS393", 13), ("DYS385".into(), StrValue::MultiCopy(vec![11, 14]))],
            vec![simple("DYS393", 14), ("DYS385".into(), StrValue::MultiCopy(vec![11, 15]))],
        ];
        let modal = compute_modal(&profiles);
        // complex marker (DYF399X) is not scored.
        assert!(modal.iter().all(|m| m.marker != "DYF399X"));
        let d393 = modal.iter().find(|m| m.marker == "DYS393").unwrap();
        assert_eq!(d393.ancestral_value, Some(13)); // 2 of 3
        assert!((d393.confidence - 2.0 / 3.0).abs() < 1e-9);
        assert_eq!(d393.supporting_samples, 3);
        let d385 = modal.iter().find(|m| m.marker == "DYS385").unwrap();
        assert_eq!(d385.ancestral_value, None); // multi-copy → no simple int
        assert_eq!(d385.ancestral_json, json!({ "type": "multiCopy", "copies": [11, 14] }));
    }

    #[test]
    fn stepwise_distance() {
        let a = vec![simple("DYS393", 13), simple("DYS390", 24), ("DYS385".into(), StrValue::MultiCopy(vec![11, 14]))];
        let b = vec![simple("DYS393", 14), simple("DYS390", 24), ("DYS385".into(), StrValue::MultiCopy(vec![11, 15]))];
        // |13-14| + |24-24| + (|11-11|+|14-15|) = 1 + 0 + 1 = 2 over 3 markers.
        assert_eq!(distance(&a, &b), Some((2, 3)));
        assert_eq!(distance(&a, &[]), None);
    }
}
