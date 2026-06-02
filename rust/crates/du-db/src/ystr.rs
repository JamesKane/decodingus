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

/// Parse a lexicon `strValue` object (`{type, repeats|copies|alleles}`).
fn parse_value(v: &Value) -> Option<StrValue> {
    Some(match v.get("type").and_then(Value::as_str)? {
        "simple" => StrValue::Simple(v.get("repeats")?.as_i64()? as i32),
        "multiCopy" => {
            let copies = v.get("copies")?.as_array()?.iter().filter_map(|x| x.as_i64().map(|n| n as i32)).collect();
            StrValue::MultiCopy(copies)
        }
        "complex" => StrValue::Complex(v.clone()),
        _ => return None,
    })
}

/// Parse one `strMarkerValue` JSON object into `(marker, value)`.
fn parse_marker(m: &Value) -> Option<(String, StrValue)> {
    let marker = m.get("marker")?.as_str()?.to_string();
    Some((marker, parse_value(m.get("value")?)?))
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

// ── STR-based age (contributing factor) ──────────────────────────────────────

/// Average Y-STR mutation rate (mutations/marker/generation) used when a marker
/// has no specific rate in `genomics.str_mutation_rate`. Replace per-marker via
/// that table as real rates (Ballantyne 2010 / Willems 2016) are imported.
pub const DEFAULT_STR_RATE: f64 = 0.0025;
/// Years per generation (pre-industrial average; McDonald 2021).
pub const GENERATION_YEARS: f64 = 33.0;

/// An STR-variance branch-age estimate (one contributing factor to the combined age).
#[derive(Debug, Clone, PartialEq)]
pub struct StrAge {
    pub estimate_ybp: i32,
    pub ci_low_ybp: i32,
    pub ci_high_ybp: i32,
    pub sample_count: i32,
    pub marker_count: i32,
    pub total_distance: i32,
}

/// STR TMRCA via the stepwise model (McDonald 2021, mirroring the legacy
/// `StrAgeService`): generations = Σ|obs − modal| / Σµ over the descendant
/// samples' simple markers (µ = per-marker rate or [`DEFAULT_STR_RATE`]); years =
/// generations × [`GENERATION_YEARS`]. CI combines Poisson sampling on the total
/// distance with ~8% rate uncertainty. `None` if nothing comparable.
pub fn compute_str_age(
    modal: &[ModalMarker],
    profiles: &[Vec<(String, StrValue)>],
    rates: &HashMap<String, f64>,
) -> Option<StrAge> {
    let modal_simple: HashMap<&str, i32> =
        modal.iter().filter_map(|m| m.ancestral_value.map(|v| (m.marker.as_str(), v))).collect();
    if modal_simple.is_empty() {
        return None;
    }
    let (mut total_dist, mut total_mu, mut samples_used) = (0i32, 0f64, 0i32);
    let mut markers_used: std::collections::BTreeSet<&str> = Default::default();
    for profile in profiles {
        let mut used = false;
        for (marker, value) in profile {
            if let StrValue::Simple(obs) = value {
                if let Some(&anc) = modal_simple.get(marker.as_str()) {
                    total_dist += (obs - anc).abs();
                    total_mu += rates.get(marker).copied().unwrap_or(DEFAULT_STR_RATE);
                    markers_used.insert(marker.as_str());
                    used = true;
                }
            }
        }
        if used {
            samples_used += 1;
        }
    }
    if total_mu <= 0.0 || samples_used == 0 {
        return None;
    }
    let years = (total_dist as f64 / total_mu) * GENERATION_YEARS;
    let rel = ((1.0 / total_dist.max(1) as f64) + 0.08f64.powi(2)).sqrt();
    Some(StrAge {
        estimate_ybp: years.round() as i32,
        ci_low_ybp: (years * (1.0 - 1.96 * rel)).max(0.0).round() as i32,
        ci_high_ybp: (years * (1.0 + 1.96 * rel)).round() as i32,
        sample_count: samples_used,
        marker_count: markers_used.len() as i32,
        total_distance: total_dist,
    })
}

// ── queries ───────────────────────────────────────────────────────────────────

/// Per-marker mutation rates (marker → per-generation rate) for age estimation.
pub async fn load_rates(pool: &PgPool) -> Result<HashMap<String, f64>, DbError> {
    let rows: Vec<(String, f64)> =
        sqlx::query_as("SELECT marker_name, mutation_rate::float8 FROM genomics.str_mutation_rate")
            .fetch_all(pool)
            .await?;
    Ok(rows.into_iter().collect())
}

#[derive(Debug, Default)]
pub struct RecomputeStats {
    pub haplogroups: usize,
    pub markers: usize,
    pub age_estimates: usize,
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

    let rates = load_rates(pool).await?;

    let mut tx = pool.begin().await?;
    // Full refresh of computed signatures + STR ages (manual overrides survive).
    sqlx::query("DELETE FROM tree.haplogroup_ancestral_str WHERE method = 'MODAL'")
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM tree.haplogroup_age_estimate WHERE method = 'STR_VARIANCE'")
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
        // STR-variance age — a contributing factor (not the authoritative tmrca_ybp).
        if let Some(age) = compute_str_age(&modal, profiles, &rates) {
            sqlx::query(
                "INSERT INTO tree.haplogroup_age_estimate \
                   (haplogroup_id, method, estimate_ybp, ci_low_ybp, ci_high_ybp, \
                    sample_count, marker_count, generation_years, computed_at) \
                 VALUES ($1, 'STR_VARIANCE', $2, $3, $4, $5, $6, $7, now()) \
                 ON CONFLICT (haplogroup_id, method) DO UPDATE SET \
                   estimate_ybp = EXCLUDED.estimate_ybp, ci_low_ybp = EXCLUDED.ci_low_ybp, \
                   ci_high_ybp = EXCLUDED.ci_high_ybp, sample_count = EXCLUDED.sample_count, \
                   marker_count = EXCLUDED.marker_count, generation_years = EXCLUDED.generation_years, \
                   computed_at = now()",
            )
            .bind(hg_id)
            .bind(age.estimate_ybp)
            .bind(age.ci_low_ybp)
            .bind(age.ci_high_ybp)
            .bind(age.sample_count)
            .bind(age.marker_count)
            .bind(GENERATION_YEARS)
            .execute(&mut *tx)
            .await?;
            stats.age_estimates += 1;
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

/// A per-branch age estimate (one contributing factor; method-labeled).
#[derive(Debug, sqlx::FromRow)]
pub struct AgeEstimate {
    pub method: String,
    pub estimate_ybp: Option<i32>,
    pub ci_low_ybp: Option<i32>,
    pub ci_high_ybp: Option<i32>,
    pub sample_count: Option<i32>,
    pub marker_count: Option<i32>,
    pub generation_years: Option<f64>,
}

/// Contributing age estimates for a Y haplogroup (e.g. STR_VARIANCE).
pub async fn branch_age_estimates(pool: &PgPool, haplogroup: &str) -> Result<Vec<AgeEstimate>, DbError> {
    let rows = sqlx::query_as::<_, AgeEstimate>(
        "SELECT e.method, e.estimate_ybp, e.ci_low_ybp, e.ci_high_ybp, e.sample_count, \
                e.marker_count, e.generation_years::float8 AS generation_years \
         FROM tree.haplogroup_age_estimate e \
         JOIN tree.haplogroup h ON h.id = e.haplogroup_id \
         WHERE h.name = $1 AND h.haplogroup_type::text = 'Y_DNA' AND h.valid_until IS NULL \
         ORDER BY e.method",
    )
    .bind(haplogroup)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

/// A ranked STR→branch prediction.
#[derive(Debug, Clone, PartialEq)]
pub struct Prediction {
    pub haplogroup: String,
    /// Total stepwise genetic distance to the branch's modal signature.
    pub distance: i32,
    /// Markers compared (present + scoreable in both query and signature).
    pub compared_markers: usize,
    /// Markers in the branch's stored signature (coverage context).
    pub signature_markers: usize,
}

/// Predict the most likely Y branches for a query STR profile by genetic distance
/// to each branch's modal signature. Branches sharing fewer than `min_compared`
/// scoreable markers with the query are excluded (avoids confident-looking calls
/// off one or two markers). Ranked: distance asc, then more-compared, then name.
pub async fn predict(
    pool: &PgPool,
    query: &[(String, StrValue)],
    top_n: i64,
    min_compared: usize,
) -> Result<Vec<Prediction>, DbError> {
    let rows: Vec<(String, String, Option<i32>, Option<Value>)> = sqlx::query_as(
        "SELECT h.name, s.marker_name, s.ancestral_value, s.ancestral_json \
         FROM tree.haplogroup_ancestral_str s \
         JOIN tree.haplogroup h ON h.id = s.haplogroup_id \
              AND h.haplogroup_type::text = 'Y_DNA' AND h.valid_until IS NULL",
    )
    .fetch_all(pool)
    .await?;

    // Reconstruct each branch's modal profile (Simple via the int column,
    // multi-copy/complex via the JSON column).
    let mut sigs: BTreeMap<String, Vec<(String, StrValue)>> = BTreeMap::new();
    for (hg, marker, av, aj) in rows {
        let value = match (av, aj) {
            (Some(n), _) => StrValue::Simple(n),
            (None, Some(j)) => match parse_value(&j) {
                Some(v) => v,
                None => continue,
            },
            _ => continue,
        };
        sigs.entry(hg).or_default().push((marker, value));
    }

    let mut preds: Vec<Prediction> = sigs
        .into_iter()
        .filter_map(|(hg, markers)| {
            let (distance, compared) = distance(query, &markers)?;
            (compared >= min_compared).then_some(Prediction {
                haplogroup: hg,
                distance,
                compared_markers: compared,
                signature_markers: markers.len(),
            })
        })
        .collect();
    preds.sort_by(|a, b| {
        a.distance
            .cmp(&b.distance)
            .then(b.compared_markers.cmp(&a.compared_markers))
            .then(a.haplogroup.cmp(&b.haplogroup))
    });
    preds.truncate(top_n.max(0) as usize);
    Ok(preds)
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
    fn str_age_from_distance_and_rate() {
        let modal = compute_modal(&[vec![simple("DYS393", 13)]]); // modal DYS393 = 13
        let profiles = vec![vec![simple("DYS393", 14)], vec![simple("DYS393", 13)]]; // dist 1 + 0
        let rates: std::collections::HashMap<String, f64> = [("DYS393".to_string(), 0.005)].into();
        let age = compute_str_age(&modal, &profiles, &rates).unwrap();
        // t_gen = total_dist(1) / total_mu(2*0.005=0.01) = 100 → 100*33 = 3300 ybp.
        assert_eq!(age.estimate_ybp, 3300);
        assert_eq!(age.total_distance, 1);
        assert_eq!(age.sample_count, 2);
        assert_eq!(age.marker_count, 1);
        assert!(age.ci_low_ybp < age.estimate_ybp && age.ci_high_ybp > age.estimate_ybp);
        // No simple modal markers → no estimate.
        assert!(compute_str_age(&[], &profiles, &rates).is_none());
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
