//! Combined branch-age estimation (McDonald 2021 — see
//! `documents/proposals/branch-age-estimation.md`). Independent evidence terms
//! (STR variance, SNP counting, genealogical/aDNA anchors) are each stored as a
//! method-labeled row in `tree.haplogroup_age_estimate`; this module computes the
//! SNP and genealogical terms and **combines all available terms** as a product
//! of Gaussians (inverse-variance weighting — the practical realization of
//! `P(t|e)=k·∏P(t|eᵢ)`), writing a `COMBINED` estimate and gap-filling
//! `tree.haplogroup.tmrca_ybp` (a curated value is never overwritten).
//!
//! The STR term is produced by [`crate::ystr`]. SNP/genealogical terms are
//! data-gated: they only emit where private-variant/callable-loci or anchor data
//! exists (sparse until ETL cutover / curation), but the framework is correct and
//! extends to the full combined age as that data lands.

use crate::DbError;
use sqlx::PgPool;
use std::collections::BTreeMap;

/// MSY combined SNP mutation rate (SNPs/bp/year, Helgason 2015).
pub const SNP_RATE: f64 = 8.33e-10;
/// "Before present" reference year (radiocarbon convention) for calendar anchors.
pub const PRESENT_YEAR: i32 = 1950;

/// Combine independent Gaussian age estimates `(mean_ybp, sigma_ybp)` by
/// inverse-variance weighting: `µ = Σ(wᵢµᵢ)/Σwᵢ`, `σ² = 1/Σwᵢ`, `wᵢ = 1/σᵢ²`.
/// A non-positive sigma falls back to 25% of the mean (min 1) so a point estimate
/// without a usable CI still contributes (weakly). Returns `(mean, sigma)`.
pub fn combine(estimates: &[(f64, f64)]) -> Option<(f64, f64)> {
    let mut wsum = 0.0;
    let mut wxsum = 0.0;
    for &(mean, sigma) in estimates {
        let s = if sigma > 0.0 { sigma } else { (mean * 0.25).max(1.0) };
        let w = 1.0 / (s * s);
        wsum += w;
        wxsum += w * mean;
    }
    if wsum <= 0.0 {
        return None;
    }
    Some((wxsum / wsum, (1.0 / wsum).sqrt()))
}

/// `sigma` from a stored 95% CI (`(hi-lo)/(2·1.96)`); `None` if unusable.
fn sigma_from_ci(lo: Option<i32>, hi: Option<i32>) -> Option<f64> {
    match (lo, hi) {
        (Some(l), Some(h)) if h > l => Some((h - l) as f64 / (2.0 * 1.96)),
        _ => None,
    }
}

#[derive(sqlx::FromRow)]
struct AnchorRow {
    haplogroup_id: i64,
    date_ce: Option<i32>,
    carbon_date_bp: Option<i32>,
    uncertainty_years: Option<String>,
}

#[derive(Debug, Default)]
pub struct CombineStats {
    pub snp: usize,
    pub genealogical: usize,
    pub combined: usize,
}

/// Recompute the SNP and genealogical age terms, then the COMBINED estimate for
/// every branch with ≥1 term, gap-filling `tmrca_ybp`. Full refresh of the
/// computed methods (`SNP_POISSON`, `GENEALOGICAL`, `COMBINED`); `STR_VARIANCE`
/// (from `ystr`) and any curated values are left intact.
pub async fn recompute_combined_ages(pool: &PgPool) -> Result<CombineStats, DbError> {
    let mut tx = pool.begin().await?;
    let mut stats = CombineStats::default();

    sqlx::query("DELETE FROM tree.haplogroup_age_estimate WHERE method IN ('SNP_POISSON','GENEALOGICAL','COMBINED')")
        .execute(&mut *tx)
        .await?;

    // ── SNP-Poisson term ──────────────────────────────────────────────────────
    // Pooled MLE per branch: t = Σm / (µ · Σb), m = ACTIVE private Y-SNPs whose
    // terminal haplogroup is the branch, b = those samples' Y callable bp.
    let snp: Vec<(i64, i64, i64)> = sqlx::query_as(
        "WITH s AS ( \
            SELECT DISTINCT terminal_haplogroup_id AS hg, sample_guid \
            FROM tree.biosample_private_variant \
            WHERE status='ACTIVE' AND haplogroup_type='Y_DNA'::core.dna_type AND terminal_haplogroup_id IS NOT NULL \
         ), m AS ( \
            SELECT terminal_haplogroup_id AS hg, count(*) AS m \
            FROM tree.biosample_private_variant \
            WHERE status='ACTIVE' AND haplogroup_type='Y_DNA'::core.dna_type AND terminal_haplogroup_id IS NOT NULL \
            GROUP BY terminal_haplogroup_id \
         ), b AS ( \
            SELECT s.hg, sum(COALESCE(NULLIF(COALESCE(cl.y_xdegen_callable_bp,0)+COALESCE(cl.y_ampliconic_callable_bp,0)+COALESCE(cl.y_palindromic_callable_bp,0),0), cl.total_callable_bp)) AS b \
            FROM s JOIN genomics.biosample_callable_loci cl ON cl.sample_guid = s.sample_guid AND cl.chromosome IN ('chrY','Y') \
            GROUP BY s.hg \
         ) \
         SELECT m.hg, m.m, b.b FROM m JOIN b ON b.hg = m.hg WHERE b.b > 0",
    )
    .fetch_all(&mut *tx)
    .await?;
    for (hg, m, b) in snp {
        let years = m as f64 / (SNP_RATE * b as f64);
        let rel = ((1.0 / (m.max(1) as f64)) + 0.08f64.powi(2)).sqrt();
        upsert_estimate(&mut tx, hg, "SNP_POISSON", years, rel, Some(m as i32), None).await?;
        stats.snp += 1;
    }

    // ── Genealogical / aDNA anchors ───────────────────────────────────────────
    // Per branch, combine its anchors into one GENEALOGICAL term.
    let anchors: Vec<AnchorRow> = sqlx::query_as(
        "SELECT haplogroup_id, date_ce, carbon_date_bp, \
                details->>'uncertainty_years' AS uncertainty_years \
         FROM tree.genealogical_anchor",
    )
    .fetch_all(&mut *tx)
    .await?;
    let mut by_hg: BTreeMap<i64, Vec<(f64, f64)>> = BTreeMap::new();
    for a in anchors {
        let ybp = match (a.carbon_date_bp, a.date_ce) {
            (Some(c), _) => c as f64,
            (None, Some(d)) => (PRESENT_YEAR - d) as f64,
            _ => continue,
        };
        if ybp < 0.0 {
            continue;
        }
        // Sigma: explicit uncertainty_years, else 10% of the age (min 25 yr).
        let sigma = a
            .uncertainty_years
            .and_then(|u| u.parse::<f64>().ok())
            .filter(|s| *s > 0.0)
            .unwrap_or((ybp * 0.10).max(25.0));
        by_hg.entry(a.haplogroup_id).or_default().push((ybp, sigma));
    }
    for (hg, ests) in &by_hg {
        if let Some((mean, sigma)) = combine(ests) {
            let rel = if mean > 0.0 { sigma / mean } else { 0.0 };
            upsert_estimate(&mut tx, *hg, "GENEALOGICAL", mean, rel, None, None).await?;
            stats.genealogical += 1;
        }
    }

    // ── Combine all method terms per branch ───────────────────────────────────
    let rows: Vec<(i64, i32, Option<i32>, Option<i32>)> = sqlx::query_as(
        "SELECT haplogroup_id, estimate_ybp, ci_low_ybp, ci_high_ybp \
         FROM tree.haplogroup_age_estimate \
         WHERE method IN ('STR_VARIANCE','SNP_POISSON','GENEALOGICAL') AND estimate_ybp IS NOT NULL",
    )
    .fetch_all(&mut *tx)
    .await?;
    let mut terms: BTreeMap<i64, Vec<(f64, f64)>> = BTreeMap::new();
    for (hg, est, lo, hi) in rows {
        let mean = est as f64;
        let sigma = sigma_from_ci(lo, hi).unwrap_or((mean * 0.25).max(1.0));
        terms.entry(hg).or_default().push((mean, sigma));
    }
    for (hg, ests) in &terms {
        let Some((mean, sigma)) = combine(ests) else { continue };
        let (lo, hi) = ((mean - 1.96 * sigma).max(0.0).round() as i32, (mean + 1.96 * sigma).round() as i32);
        upsert_estimate_ci(&mut tx, *hg, "COMBINED", mean.round() as i32, lo, hi, ests.len() as i32).await?;
        // Gap-fill the authoritative tmrca_ybp (never overwrite a curated value).
        sqlx::query("UPDATE tree.haplogroup SET tmrca_ybp = $2 WHERE id = $1 AND tmrca_ybp IS NULL")
            .bind(hg)
            .bind(mean.round() as i32)
            .execute(&mut *tx)
            .await?;
        stats.combined += 1;
    }

    tx.commit().await?;
    Ok(stats)
}

/// Upsert a point estimate with a relative-error CI.
async fn upsert_estimate(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    hg: i64,
    method: &str,
    years: f64,
    rel: f64,
    marker_or_snp_count: Option<i32>,
    sample_count: Option<i32>,
) -> Result<(), DbError> {
    let lo = (years * (1.0 - 1.96 * rel)).max(0.0).round() as i32;
    let hi = (years * (1.0 + 1.96 * rel)).round() as i32;
    sqlx::query(
        "INSERT INTO tree.haplogroup_age_estimate \
           (haplogroup_id, method, estimate_ybp, ci_low_ybp, ci_high_ybp, sample_count, marker_count, computed_at) \
         VALUES ($1,$2,$3,$4,$5,$6,$7, now()) \
         ON CONFLICT (haplogroup_id, method) DO UPDATE SET \
           estimate_ybp=EXCLUDED.estimate_ybp, ci_low_ybp=EXCLUDED.ci_low_ybp, ci_high_ybp=EXCLUDED.ci_high_ybp, \
           sample_count=EXCLUDED.sample_count, marker_count=EXCLUDED.marker_count, computed_at=now()",
    )
    .bind(hg)
    .bind(method)
    .bind(years.round() as i32)
    .bind(lo)
    .bind(hi)
    .bind(sample_count)
    .bind(marker_or_snp_count)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

/// Upsert with explicit CI bounds (the COMBINED term).
async fn upsert_estimate_ci(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    hg: i64,
    method: &str,
    est: i32,
    lo: i32,
    hi: i32,
    term_count: i32,
) -> Result<(), DbError> {
    sqlx::query(
        "INSERT INTO tree.haplogroup_age_estimate \
           (haplogroup_id, method, estimate_ybp, ci_low_ybp, ci_high_ybp, sample_count, computed_at) \
         VALUES ($1,$2,$3,$4,$5,$6, now()) \
         ON CONFLICT (haplogroup_id, method) DO UPDATE SET \
           estimate_ybp=EXCLUDED.estimate_ybp, ci_low_ybp=EXCLUDED.ci_low_ybp, ci_high_ybp=EXCLUDED.ci_high_ybp, \
           sample_count=EXCLUDED.sample_count, computed_at=now()",
    )
    .bind(hg)
    .bind(method)
    .bind(est)
    .bind(lo)
    .bind(hi)
    .bind(term_count)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inverse_variance_combine() {
        // Two equally-precise estimates → mean between them, sigma tighter than either.
        let (mean, sigma) = combine(&[(3000.0, 300.0), (3300.0, 300.0)]).unwrap();
        assert!((mean - 3150.0).abs() < 1.0);
        assert!(sigma < 300.0 && sigma > 200.0);
        // A tighter estimate pulls the mean toward it.
        let (mean2, _) = combine(&[(3000.0, 50.0), (5000.0, 1000.0)]).unwrap();
        assert!(mean2 < 3100.0, "tight 3000±50 dominates, got {mean2}");
        assert!(combine(&[]).is_none());
    }
}
