//! Y-STR per-branch modal signatures: aggregate the mirrored STR profiles
//! (`fed.str_profile`) by SNP-defined branch (haplogroup) into a modal haplotype
//! stored in `tree.haplogroup_ancestral_str`. This is the catalog-side half of
//! the STR feature (the mirror is `fed::str_profile`); prediction over these
//! signatures is Phase 2.
//!
//! Marker values follow the lexicon union — simple (`DYS393=13`), multi-copy
//! (`DYS385a/b=[11,14]`), complex/palindromic. Modal + distance score simple +
//! multi-copy; complex is preserved on the profile but not scored (v1).

use crate::pdf::Pdf;
use crate::DbError;
use serde_json::{json, Value};
use sqlx::PgPool;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::{Arc, OnceLock};

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
/// Hidden mutation counts summed in a marker's `P(t|g)=Σ_m P(t|m)·P(g|m)` term
/// (McDonald Eq 14 inner sum). Past ~30, the Poisson age weight at realistic STR
/// TMRCAs is negligible.
const STR_M_MAX: i64 = 30;

// ── Multi-step P(g|m): McDonald 2021 §2.5.3–2.5.4 (Eq 14–23, Table 1) ──────────
//
// A Y-STR's observed genetic distance g (|obs − ancestral|) underdetermines the
// number of mutation events m: multi-step mutations, back-mutations and parallel
// mutations all hide events. McDonald gives P(g|m) — the chance m events yield
// distance g — as Table 1, computed from the step-size frequencies ω±n. We embed
// Table 1 verbatim (the paper's authoritative values) over its published range
// (g,m ≤ 10) and fall back to the signed-step convolution model beyond it (those
// terms are deep-time and low-weight). NB the paper's own text formula
// P(g=0|m=2)=Σω±n²/2 ≈ 0.463 differs slightly from Table 1's 0.479 — we follow
// the published Table.

/// McDonald 2021 Table 1 — `P(g|m)`, rows `g` and columns `m`, both 0..=10.
#[rustfmt::skip]
const TABLE1: [[f64; 11]; 11] = [
    // m: 0      1      2      3      4      5      6      7      8      9      10
    [1.000, 0.000, 0.479, 0.024, 0.364, 0.003, 0.302, 0.006, 0.261, 0.009, 0.230], // g=0
    [0.000, 0.962, 0.032, 0.725, 0.020, 0.607, 0.009, 0.526, 0.014, 0.465, 0.020], // g=1
    [0.000, 0.032, 0.483, 0.005, 0.485, 0.017, 0.454, 0.012, 0.418, 0.017, 0.384], // g=2
    [0.000, 0.004, 0.004, 0.242, 0.006, 0.303, 0.015, 0.316, 0.013, 0.311, 0.018], // g=3
    [0.000, 0.001, 0.001, 0.003, 0.122, 0.006, 0.182, 0.013, 0.210, 0.013, 0.221], // g=4
    [0.000, 0.000, 0.000, 0.001, 0.003, 0.061, 0.005, 0.106, 0.011, 0.135, 0.012], // g=5
    [0.000, 0.000, 0.000, 0.000, 0.000, 0.002, 0.031, 0.005, 0.061, 0.008, 0.084], // g=6
    [0.000, 0.000, 0.000, 0.000, 0.000, 0.000, 0.001, 0.016, 0.003, 0.034, 0.007], // g=7
    [0.000, 0.000, 0.000, 0.000, 0.000, 0.000, 0.000, 0.001, 0.008, 0.003, 0.019], // g=8
    [0.000, 0.000, 0.000, 0.000, 0.000, 0.000, 0.000, 0.000, 0.001, 0.004, 0.002], // g=9
    [0.000, 0.000, 0.000, 0.000, 0.000, 0.000, 0.000, 0.000, 0.000, 0.000, 0.002], // g=10
];

const PGM_M_MAX: usize = 40;
const PGM_G_MAX: usize = 40;

/// Global step-size frequencies ω±n (index = n; `[0]` unused), McDonald §2.5.3:
/// ω±1,2,3 = 0.96217, 0.032, 0.004, then ÷√10 per further repeat. Normalized so
/// Σ ω±n = 1 (⇒ ω+n = ω−n = ω±n/2 under the symmetric w+ = w− = 0.5 assumption).
fn omega_global() -> Vec<f64> {
    let mut w = vec![0.0_f64, 0.96217, 0.032, 0.004];
    let mut v = 0.004;
    let root10 = 10.0_f64.sqrt();
    for _ in 4..=10 {
        v /= root10;
        w.push(v);
    }
    normalize_omega(w)
}

fn normalize_omega(mut w: Vec<f64>) -> Vec<f64> {
    let s: f64 = w.iter().sum();
    if s > 0.0 {
        for x in &mut w {
            *x /= s;
        }
    }
    w
}

/// Build `P(g|m)` for m=0..=`m_max`, g=0..=`g_max` by m-fold convolution of the
/// signed single-step distribution (P(step=+n)=`plus`·ω±n, P(−n)=(1−`plus`)·ω±n),
/// folded to the magnitude |g|. Returns a `[m][g]` table.
fn build_pgm(omega: &[f64], plus: f64, m_max: usize, g_max: usize) -> Vec<Vec<f64>> {
    let nmax = omega.len().saturating_sub(1);
    let span = nmax * m_max; // widest reachable |sum|
    let width = 2 * span + 1;
    let zero = span;
    let mut step = vec![0.0_f64; width];
    for n in 1..=nmax {
        step[zero + n] += plus * omega[n];
        step[zero - n] += (1.0 - plus) * omega[n];
    }
    let fold = |dist: &[f64]| -> Vec<f64> {
        (0..=g_max)
            .map(|g| {
                if g == 0 {
                    dist[zero]
                } else {
                    dist.get(zero + g).copied().unwrap_or(0.0)
                        + if zero >= g { dist[zero - g] } else { 0.0 }
                }
            })
            .collect()
    };
    let mut rows = Vec::with_capacity(m_max + 1);
    let mut cur = vec![0.0_f64; width];
    cur[zero] = 1.0;
    rows.push(fold(&cur)); // m = 0
    let (mut lo, mut hi) = (zero, zero); // support of `cur`
    for _ in 1..=m_max {
        let mut nxt = vec![0.0_f64; width];
        for (a, &pa) in cur.iter().enumerate().take(hi + 1).skip(lo) {
            if pa == 0.0 {
                continue;
            }
            for (d, &ps) in step.iter().enumerate() {
                if ps != 0.0 {
                    nxt[a + d - zero] += pa * ps;
                }
            }
        }
        lo = lo.saturating_sub(nmax);
        hi = (hi + nmax).min(width - 1);
        cur = nxt;
        rows.push(fold(&cur));
    }
    rows
}

static GLOBAL_PGM: OnceLock<Vec<Vec<f64>>> = OnceLock::new();

fn global_pgm() -> &'static [Vec<f64>] {
    GLOBAL_PGM.get_or_init(|| build_pgm(&omega_global(), 0.5, PGM_M_MAX, PGM_G_MAX))
}

/// `P(genetic distance g | m mutations)` — McDonald Table 1 in its published
/// range (g,m ≤ 10), the global-ω convolution model beyond it.
fn p_g_given_m(g: i64, m: i64) -> f64 {
    if g < 0 || m < 0 {
        return 0.0;
    }
    if g <= 10 && m <= 10 {
        return TABLE1[g as usize][m as usize];
    }
    global_pgm().get(m as usize).and_then(|r| r.get(g as usize)).copied().unwrap_or(0.0)
}

/// Per-marker mutation model. `omega_pgm` is a marker-specific `P(g|m)` table
/// derived from the marker's own ω (str_mutation_rate.multi_step_rate +
/// omega_plus/omega_minus asymmetry); `None` ⇒ the global model (Table 1).
#[derive(Clone)]
pub struct MarkerModel {
    pub mu_per_gen: f64,
    omega_pgm: Option<Arc<Vec<Vec<f64>>>>,
}

impl MarkerModel {
    fn p_g_given_m(&self, g: i64, m: i64) -> f64 {
        match &self.omega_pgm {
            None => p_g_given_m(g, m),
            Some(t) if g >= 0 && m >= 0 => {
                t.get(m as usize).and_then(|r| r.get(g as usize)).copied().unwrap_or(0.0)
            }
            Some(_) => 0.0,
        }
    }
}

/// Distribute a total multi-step fraction (`Σ_{n≥2} ω±n`) over n≥2 by the global
/// shape, with ω±1 taking the remainder; `None` if no usable custom fraction.
fn custom_omega(multi_step_rate: Option<f64>) -> Option<Vec<f64>> {
    let ms = multi_step_rate.filter(|m| (0.0..1.0).contains(m))?;
    let g = omega_global();
    let tail: f64 = g[2..].iter().sum();
    if tail <= 0.0 {
        return None;
    }
    let mut w = vec![0.0, 1.0 - ms];
    w.extend(g[2..].iter().map(|gn| ms * gn / tail));
    Some(normalize_omega(w))
}

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

/// `P(t|g)` for one marker (McDonald Eq 12+14 inner sum): a mixture over the
/// hidden mutation count m of the Poisson age PDF `P(t|m)` weighted by `P(g|m)`.
/// Rate is per generation; ages are years via [`GENERATION_YEARS`]. `None` for a
/// non-positive rate.
fn marker_age_pdf(g: i64, model: &MarkerModel) -> Option<Pdf> {
    let mu_year = model.mu_per_gen / GENERATION_YEARS;
    if mu_year <= 0.0 {
        return None;
    }
    let comps: Vec<(f64, Pdf)> = (0..=STR_M_MAX)
        .filter_map(|m| {
            let w = model.p_g_given_m(g, m);
            (w > 1e-9).then(|| (w, Pdf::poisson(m, 1.0, mu_year)))
        })
        .collect();
    Pdf::mixture(&comps)
}

/// STR TMRCA via McDonald's multi-step PDF model (Eq 11–14, Table 1). Each scored
/// tester contributes, per simple marker, `P(t|g)=Σ_m P(t|m)·P(g|m)` — a Poisson
/// age PDF mixed over the hidden mutation count. These independent per-(tester,
/// marker) PDFs are multiplied (Eq 1) into the clade's STR age PDF, from which the
/// estimate and 95% CI are read. `None` if nothing comparable.
///
/// This is the **star-phylogeny** approximation: every tester is treated as an
/// independent lineage descending straight from the clade node. Propagating STR
/// ages through the tree's internal structure (as the SNP term does in
/// [`crate::age`]) is a documented refinement — for deep, well-substructured
/// clades the star model overstates precision.
pub fn compute_str_age(
    modal: &[ModalMarker],
    profiles: &[Vec<(String, StrValue)>],
    models: &HashMap<String, MarkerModel>,
) -> Option<StrAge> {
    let modal_simple: HashMap<&str, i32> =
        modal.iter().filter_map(|m| m.ancestral_value.map(|v| (m.marker.as_str(), v))).collect();
    if modal_simple.is_empty() {
        return None;
    }
    let default = MarkerModel { mu_per_gen: DEFAULT_STR_RATE, omega_pgm: None };
    // Per-marker age PDFs depend only on (marker, g) — memoize across testers.
    let mut cache: HashMap<(&str, i64), Option<Pdf>> = HashMap::new();
    let mut acc: Option<Pdf> = None;
    let (mut total_dist, mut samples_used) = (0i32, 0i32);
    let mut markers_used: BTreeSet<&str> = Default::default();
    for profile in profiles {
        let mut used = false;
        for (marker, value) in profile {
            let StrValue::Simple(obs) = value else { continue };
            let Some(&anc) = modal_simple.get(marker.as_str()) else { continue };
            let g = (*obs - anc).unsigned_abs() as i64;
            let model = models.get(marker).unwrap_or(&default);
            let pdf = cache
                .entry((marker.as_str(), g))
                .or_insert_with(|| marker_age_pdf(g, model))
                .clone();
            let Some(pdf) = pdf else { continue };
            acc = Some(match acc.take() {
                Some(a) => a.multiply(&pdf),
                None => pdf,
            });
            total_dist += (*obs - anc).abs();
            markers_used.insert(marker.as_str());
            used = true;
        }
        if used {
            samples_used += 1;
        }
    }
    let pdf = acc?;
    if samples_used == 0 {
        return None;
    }
    let (med, lo, hi) = pdf.ci95();
    Some(StrAge {
        estimate_ybp: med.round() as i32,
        ci_low_ybp: lo.round() as i32,
        ci_high_ybp: hi.round() as i32,
        sample_count: samples_used,
        marker_count: markers_used.len() as i32,
        total_distance: total_dist,
    })
}

// ── queries ───────────────────────────────────────────────────────────────────

/// Per-marker mutation models (rate + any custom multi-step ω) for age estimation.
/// `omega_plus`/`omega_minus`/`multi_step_rate` build a marker-specific `P(g|m)`
/// table when they depart from the global symmetric single-step-dominated model.
pub async fn load_marker_models(pool: &PgPool) -> Result<HashMap<String, MarkerModel>, DbError> {
    let rows: Vec<MarkerRateRow> = sqlx::query_as(
        "SELECT marker_name, mutation_rate::float8 AS mutation_rate, \
                omega_plus::float8 AS omega_plus, omega_minus::float8 AS omega_minus, \
                multi_step_rate::float8 AS multi_step_rate FROM genomics.str_mutation_rate",
    )
    .fetch_all(pool)
    .await?;
    Ok(rows
        .into_iter()
        .map(|r| {
            let plus = match (r.omega_plus, r.omega_minus) {
                (Some(p), Some(m)) if p + m > 0.0 => p / (p + m),
                _ => 0.5,
            };
            let asymmetric = (plus - 0.5).abs() > 1e-6;
            let omega_pgm = match custom_omega(r.multi_step_rate) {
                Some(w) => Some(Arc::new(build_pgm(&w, plus, PGM_M_MAX, PGM_G_MAX))),
                None if asymmetric => {
                    Some(Arc::new(build_pgm(&omega_global(), plus, PGM_M_MAX, PGM_G_MAX)))
                }
                None => None,
            };
            (r.marker_name, MarkerModel { mu_per_gen: r.mutation_rate, omega_pgm })
        })
        .collect())
}

#[derive(sqlx::FromRow)]
struct MarkerRateRow {
    marker_name: String,
    mutation_rate: f64,
    omega_plus: Option<f64>,
    omega_minus: Option<f64>,
    multi_step_rate: Option<f64>,
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

    let models = load_marker_models(pool).await?;

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
        if let Some(age) = compute_str_age(&modal, profiles, &models) {
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

    fn model(mu: f64) -> MarkerModel {
        MarkerModel { mu_per_gen: mu, omega_pgm: None }
    }

    #[test]
    fn p_g_given_m_matches_table1() {
        // Spot values straight from the published Table 1.
        assert_eq!(p_g_given_m(0, 0), 1.0);
        assert_eq!(p_g_given_m(0, 1), 0.0);
        assert_eq!(p_g_given_m(1, 1), 0.962);
        assert_eq!(p_g_given_m(0, 2), 0.479);
        assert_eq!(p_g_given_m(2, 2), 0.483);
        // Each m-column is a proper distribution over g (table rounded to 3dp).
        for m in 0..=10 {
            let col: f64 = (0..=10).map(|g| p_g_given_m(g, m)).sum();
            assert!((col - 1.0).abs() < 0.02, "P(·|m={m}) sums to {col}");
        }
    }

    #[test]
    fn convolution_fallback_is_valid_and_ballpark() {
        // The global-ω convolution (used only past the embedded table) is an exact
        // all-orders sum; McDonald's Table 1 truncates the f_r series (Eq 22, f₃+
        // neglected), so the two differ by up to ~0.1 at a few cells — including the
        // documented P(0|2)=0.463-vs-0.479 gap from the paper's own text. The
        // fallback must still be a proper distribution and stay in the table's
        // ballpark (it only serves deep-time, low-weight terms).
        // Sums to 1 bar the negligible mass past g_max (only reached at high m,
        // far beyond any real observed distance) and float accumulation.
        for m in 0..=PGM_M_MAX {
            let col: f64 = global_pgm()[m].iter().sum();
            assert!((col - 1.0).abs() < 1e-4, "convolution P(·|m={m}) sums to {col}");
        }
        let mut max_gap = 0.0_f64;
        for m in 0..=10 {
            for g in 0..=10 {
                max_gap = max_gap.max((global_pgm()[m][g] - TABLE1[g][m]).abs());
            }
        }
        assert!(max_gap < 0.12, "convolution vs Table 1 max gap {max_gap}");
    }

    #[test]
    fn marker_pdf_ages_with_distance() {
        // More observed repeats from the ancestral motif ⇒ older marker age.
        let m0 = marker_age_pdf(0, &model(0.005)).unwrap();
        let m1 = marker_age_pdf(1, &model(0.005)).unwrap();
        let m2 = marker_age_pdf(2, &model(0.005)).unwrap();
        assert!(m0.median() < m1.median(), "g=0 younger than g=1");
        assert!(m1.median() < m2.median(), "g=1 younger than g=2");
    }

    #[test]
    fn str_age_pdf_basic() {
        let modal = compute_modal(&[vec![simple("DYS393", 13)]]); // modal DYS393 = 13
        let profiles = vec![vec![simple("DYS393", 14)], vec![simple("DYS393", 13)]]; // dist 1 + 0
        let models: HashMap<String, MarkerModel> = [("DYS393".to_string(), model(0.005))].into();
        let age = compute_str_age(&modal, &profiles, &models).unwrap();
        assert_eq!(age.total_distance, 1);
        assert_eq!(age.sample_count, 2);
        assert_eq!(age.marker_count, 1);
        assert!(age.estimate_ybp > 0, "positive age");
        assert!(age.ci_low_ybp < age.estimate_ybp && age.ci_high_ybp > age.estimate_ybp);
        // No simple modal markers → no estimate.
        assert!(compute_str_age(&[], &profiles, &models).is_none());
    }

    #[test]
    fn custom_multi_step_omega_builds_table() {
        // A marker with a large multi-step fraction gets its own P(g|m) table,
        // raising P(g=2|m=1) (a single 2-step mutation) well above the global ~0.03.
        let w = custom_omega(Some(0.20)).expect("custom omega");
        let pgm = build_pgm(&w, 0.5, 10, 10);
        assert!(pgm[1][2] > 0.05, "two-step single mutation likelier: {}", pgm[1][2]);
        assert!(pgm[1][1] < p_g_given_m(1, 1), "single-step share reduced");
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
