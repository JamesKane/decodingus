//! Combined branch-age estimation (McDonald 2021 — see
//! `documents/proposals/branch-age-estimation.md`). Independent evidence terms
//! (STR variance, SNP counting, genealogical/aDNA anchors) are each stored as a
//! method-labeled row in `tree.haplogroup_age_estimate`; this module computes the
//! SNP and genealogical terms and **combines all available terms** by the direct
//! product of their PDFs (McDonald Eq 1, `P(t|e)=k·∏P(t|eᵢ)`) — preserving each
//! term's non-Gaussian shape (Poisson skew, STR convergent-mutation tails) rather
//! than inverse-variance-averaging medians. It writes a `COMBINED` estimate and
//! gap-fills `tree.haplogroup.tmrca_ybp` (a curated value is never overwritten).
//! Disjoint terms (no overlapping support) fall back to the inverse-variance
//! Gaussian combine, which can't annihilate.
//!
//! The STR term is produced by [`crate::ystr`]. SNP/genealogical terms are
//! data-gated: they only emit where private-variant/callable-loci or anchor data
//! exists (sparse until ETL cutover / curation), but the framework is correct and
//! extends to the full combined age as that data lands.

use crate::pdf::Pdf;
use crate::DbError;
use sqlx::PgPool;
use std::collections::{BTreeMap, BTreeSet, HashMap};

/// MSY combined SNP mutation rate (SNPs/bp/year, Helgason 2015). This is the rate
/// the model applies — see `documents/proposals/branch-age-estimation.md`.
pub const SNP_RATE: f64 = 8.33e-10;

/// Independent cross-check clock from Hallast et al. 2026 (142 population-scale Y
/// assemblies, BEAST v1.10.4 strict molecular clock on the X-degenerate mask):
/// **0.76 × 10⁻⁹ sub/site/yr (95% CI 0.67–0.86 × 10⁻⁹)** — ~9% slower than
/// Helgason. Recorded for provenance/comparison **only**; `recompute_combined_ages`
/// does *not* swap to it (a slower clock makes every TMRCA ~9% older). Use it to
/// sanity-check our SNP ages or to bound the rate-uncertainty band, not as the
/// default. CI bounds: [`HALLAST_RATE_LO`], [`HALLAST_RATE_HI`].
pub const HALLAST_RATE: f64 = 0.76e-9;
pub const HALLAST_RATE_LO: f64 = 0.67e-9;
pub const HALLAST_RATE_HI: f64 = 0.86e-9;

/// "Before present" reference year (radiocarbon convention) for calendar anchors.
pub const PRESENT_YEAR: i32 = 1950;

// ── PDF-based tree propagation (McDonald 2021 §2.2, Eq 5–8) ───────────────────
//
// The SNP age of a clade is built bottom-up: a node's TMRCA is the product over
// its children of (the child's own TMRCA convolved with the parent→child branch
// time), per Eq 8. Each factor is a Poisson age PDF (Eq 3) over the branch's SNP
// count and callable bp. A node's "formed" age is its TMRCA convolved with its
// own branch time — i.e. when its lineage split from its parent. This is the pure
// algorithm; `recompute_combined_ages` (below) supplies the DB-derived inputs.
//
// Not yet modelled here (documented follow-ups): the exact b̄ coverage
// *intersection* across sub-clades (Eq 4 — needs per-sample callable intervals,
// not just totals), and the Eq 9/10 causality back-correction (the bottom-up
// convolution already keeps a parent older than its children in the common case).

/// One clade (haplogroup node) of the propagation input.
#[derive(Debug, Clone, Default)]
pub struct Clade {
    /// SNPs on the edge from this node's parent down to it (`m_{parent→node}`):
    /// the branch time when this node feeds its parent, and its own "formed" age.
    /// 0 for a root.
    pub branch_snps: i64,
    /// Effective callable bp (`b̄`) over which this clade's SNPs are counted.
    pub callable_bp: f64,
    /// Child clade indices.
    pub children: Vec<usize>,
    /// Private-SNP counts of testers sitting directly on this node (terminal tips);
    /// each contributes a Poisson age factor (tester birth ≈ present is omitted as
    /// a negligible offset).
    pub tester_snps: Vec<i64>,
}

/// A clade's computed age PDFs.
#[derive(Debug, Clone)]
pub struct CladeAge {
    /// TMRCA of the node's sampled descendants.
    pub tmrca: Pdf,
    /// When the node's lineage split from its parent (`TMRCA ⊛ branch time`).
    pub formed: Pdf,
}

/// Grid for the whole-tree propagation. Coarser/wider than the PDF default: Y
/// TMRCAs run from recent surname clades to ~300 ky (A00), so 50-yr bins over
/// 350 ky keep convolution affordable while spanning the deepest nodes.
pub const TREE_RESOLUTION_YEARS: f64 = 50.0;
pub const TREE_MAX_AGE_YEARS: f64 = 350_000.0;

/// Branch-time PDF for clade `x`: `P(t | m_branch)` over its callable bp.
fn branch_time(clades: &[Clade], x: usize, mu: f64, res: f64, max_age: f64) -> Pdf {
    Pdf::poisson_on(clades[x].branch_snps, clades[x].callable_bp, mu, res, max_age)
}

fn compute_tmrca(
    i: usize,
    clades: &[Clade],
    mu: f64,
    res: f64,
    max_age: f64,
    memo: &mut [Option<Option<Pdf>>],
) {
    if memo[i].is_some() {
        return;
    }
    memo[i] = Some(None); // guard against accidental cycles
    let mut factors: Vec<Pdf> = Vec::new();
    for &ch in &clades[i].children {
        compute_tmrca(ch, clades, mu, res, max_age, memo);
        if let Some(Some(ct)) = &memo[ch] {
            factors.push(ct.convolve(&branch_time(clades, ch, mu, res, max_age)));
        }
    }
    for &s in &clades[i].tester_snps {
        factors.push(Pdf::poisson_on(s, clades[i].callable_bp, mu, res, max_age));
    }
    let result = factors.split_first().map(|(first, rest)| {
        rest.iter().fold(first.clone(), |acc, f| acc.multiply(f))
    });
    memo[i] = Some(result);
}

/// Compute every clade's TMRCA + formed-age PDFs bottom-up (Eq 8) on a
/// `res`-year grid spanning `[0, max_age]`. A clade with no evidence (no children
/// with ages, no testers) yields `None`.
pub fn propagate(clades: &[Clade], mu: f64, res: f64, max_age: f64) -> Vec<Option<CladeAge>> {
    let mut memo: Vec<Option<Option<Pdf>>> = vec![None; clades.len()];
    for i in 0..clades.len() {
        compute_tmrca(i, clades, mu, res, max_age, &mut memo);
    }
    (0..clades.len())
        .map(|i| {
            let Some(Some(tmrca)) = memo[i].take() else { return None };
            let formed = tmrca.convolve(&branch_time(clades, i, mu, res, max_age));
            Some(CladeAge { tmrca, formed })
        })
        .collect()
}

/// SNPs in heterochromatic sequence are masked from age counting — they sit
/// outside the callable denominator (`y_xdegen+y_ampliconic+y_palindromic`) and
/// the paper excises recurrent regions self-consistently (Appendix A.2/A.3).
/// Ampliconic and palindromic SNPs are kept (same rate as X-degenerate). This is
/// a SQL fragment testing `core.variant v` for any `heterochromatin:` overlap.
const HET_MASK: &str = "NOT EXISTS (SELECT 1 FROM \
    jsonb_array_elements_text(COALESCE(v.annotations->'region_overlaps','[]'::jsonb)) e \
    WHERE e LIKE 'heterochromatin:%')";

/// Build the propagation input from the current Y tree: nodes, parent→child
/// edges, het-masked branch (defining) SNP counts, and per-node tester data
/// (active private-SNP counts + callable bp). Returns `(clades, haplogroup_ids)`
/// where `haplogroup_ids[i]` is the DB id of clade `i`.
async fn build_clades(pool: &PgPool) -> Result<(Vec<Clade>, Vec<i64>), DbError> {
    // Stable index over current Y nodes.
    let ids: Vec<i64> = sqlx::query_scalar(
        "SELECT id FROM tree.haplogroup \
         WHERE haplogroup_type='Y_DNA'::core.dna_type AND valid_until IS NULL ORDER BY id",
    )
    .fetch_all(pool)
    .await?;
    let idx: HashMap<i64, usize> = ids.iter().enumerate().map(|(i, &id)| (id, i)).collect();
    let mut clades = vec![Clade::default(); ids.len()];

    // Edges → children (a child carries its own branch SNPs).
    let edges: Vec<(i64, i64)> = sqlx::query_as(
        "SELECT c.id, p.id FROM tree.haplogroup_relationship r \
         JOIN tree.haplogroup c ON c.id=r.child_haplogroup_id AND c.valid_until IS NULL \
            AND c.haplogroup_type='Y_DNA'::core.dna_type \
         JOIN tree.haplogroup p ON p.id=r.parent_haplogroup_id AND p.valid_until IS NULL \
         WHERE r.valid_until IS NULL",
    )
    .fetch_all(pool)
    .await?;
    for (c, p) in edges {
        if let (Some(&ci), Some(&pi)) = (idx.get(&c), idx.get(&p)) {
            clades[pi].children.push(ci);
        }
    }

    // Branch defining-SNP counts (het-masked).
    let branch: Vec<(i64, i64)> = sqlx::query_as(&format!(
        "SELECT hv.haplogroup_id, count(*)::bigint FROM tree.haplogroup_variant hv \
         JOIN core.variant v ON v.id=hv.variant_id \
         WHERE hv.valid_until IS NULL AND {HET_MASK} GROUP BY hv.haplogroup_id"
    ))
    .fetch_all(pool)
    .await?;
    for (hg, n) in branch {
        if let Some(&i) = idx.get(&hg) {
            clades[i].branch_snps = n;
        }
    }

    // Testers: per (node, sample) active private-SNP count (het-masked) + that
    // sample's Y callable bp (xdegen+ampliconic+palindromic, else total).
    let cbp = "COALESCE(NULLIF(COALESCE(cl.y_xdegen_callable_bp,0)+COALESCE(cl.y_ampliconic_callable_bp,0)\
               +COALESCE(cl.y_palindromic_callable_bp,0),0), cl.total_callable_bp, 0)";
    let testers: Vec<(i64, i64, f64)> = sqlx::query_as(&format!(
        "SELECT pv.terminal_haplogroup_id, count(*)::bigint, max({cbp})::float8 \
         FROM tree.biosample_private_variant pv \
         JOIN core.variant v ON v.id=pv.variant_id \
         LEFT JOIN genomics.biosample_callable_loci cl \
            ON cl.sample_guid=pv.sample_guid AND cl.chromosome IN ('chrY','Y') \
         WHERE pv.status='ACTIVE' AND pv.haplogroup_type='Y_DNA'::core.dna_type \
            AND pv.terminal_haplogroup_id IS NOT NULL AND {HET_MASK} \
         GROUP BY pv.terminal_haplogroup_id, pv.sample_guid"
    ))
    .fetch_all(pool)
    .await?;
    let (mut bp_sum, mut bp_cnt) = (vec![0.0f64; ids.len()], vec![0u32; ids.len()]);
    for (hg, snps, b) in testers {
        if let (Some(&i), true) = (idx.get(&hg), b > 0.0) {
            clades[i].tester_snps.push(snps);
            bp_sum[i] += b;
            bp_cnt[i] += 1;
        }
    }

    // Representative b̄ per node: mean of its testers' callable bp, else the
    // catalog-wide mean (so SNP-less internal branches still get a branch time).
    let default_b: f64 = sqlx::query_scalar::<_, Option<f64>>(&format!(
        "SELECT avg({cbp})::float8 FROM genomics.biosample_callable_loci cl WHERE cl.chromosome IN ('chrY','Y')"
    ))
    .fetch_one(pool)
    .await?
    .filter(|b| *b > 0.0)
    .unwrap_or(15_000_000.0);
    for i in 0..ids.len() {
        clades[i].callable_bp = if bp_cnt[i] > 0 { bp_sum[i] / bp_cnt[i] as f64 } else { default_b };
    }

    Ok((clades, ids))
}

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
/// every branch with ≥1 term, gap-filling `tmrca_ybp`. COMBINED is the direct PDF
/// product (Eq 1) of the SNP TMRCA PDF (propagation), the STR TMRCA PDF
/// ([`crate::ystr::str_tmrca_pdfs`]), and the genealogical anchor PDF — all on the
/// shared TREE grid. Full refresh of the computed methods (`SNP_POISSON`,
/// `GENEALOGICAL`, `COMBINED`); `STR_VARIANCE` (from `ystr`) and curated values are
/// left intact.
pub async fn recompute_combined_ages(pool: &PgPool) -> Result<CombineStats, DbError> {
    let mut tx = pool.begin().await?;
    let mut stats = CombineStats::default();

    sqlx::query("DELETE FROM tree.haplogroup_age_estimate WHERE method IN ('SNP_POISSON','GENEALOGICAL','COMBINED')")
        .execute(&mut *tx)
        .await?;

    // ── SNP-Poisson term: tree propagation (McDonald Eq 5–8) ──────────────────
    // Build the clade tree, propagate TMRCA/formed PDFs bottom-up, then store a
    // SNP_POISSON term per scored node (median + 95% CI of its TMRCA) and gap-fill
    // `formed_ybp`. The COMBINED step below fills `tmrca_ybp`. Heterochromatic SNPs
    // are masked from both `m` and (already) the callable denominator (`HET_MASK`).
    let (clades, ids) = build_clades(pool).await?;
    let ages = propagate(&clades, SNP_RATE, TREE_RESOLUTION_YEARS, TREE_MAX_AGE_YEARS);
    // Keep each term's actual PDF (on the shared TREE grid) for the Eq-1 product below.
    let mut snp_pdf: HashMap<i64, Pdf> = HashMap::new();
    for (i, age) in ages.iter().enumerate() {
        let Some(age) = age else { continue };
        let (med, lo, hi) = age.tmrca.ci95();
        snp_pdf.insert(ids[i], age.tmrca.clone());
        upsert_estimate_ci(
            &mut tx,
            ids[i],
            "SNP_POISSON",
            med.round() as i32,
            lo.round() as i32,
            hi.round() as i32,
            clades[i].tester_snps.len() as i32,
        )
        .await?;
        // Node formation age — gap-fill only (never overwrite a curated value).
        sqlx::query("UPDATE tree.haplogroup SET formed_ybp=$2 WHERE id=$1 AND formed_ybp IS NULL")
            .bind(ids[i])
            .bind(age.formed.median().round() as i32)
            .execute(&mut *tx)
            .await?;
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
    let mut gen_pdf: HashMap<i64, Pdf> = HashMap::new();
    for (hg, ests) in &by_hg {
        if let Some((mean, sigma)) = combine(ests) {
            let rel = if mean > 0.0 { sigma / mean } else { 0.0 };
            gen_pdf.insert(*hg, Pdf::gaussian_on(mean, sigma, TREE_RESOLUTION_YEARS, TREE_MAX_AGE_YEARS));
            upsert_estimate(&mut tx, *hg, "GENEALOGICAL", mean, rel, None, None).await?;
            stats.genealogical += 1;
        }
    }

    // STR term: tree-propagated TMRCA PDFs on the same grid (the STR_VARIANCE rows
    // are written separately by `crate::ystr::recompute_signatures`, from the same
    // computation). Any stored STR_VARIANCE row with no fresh PDF — a curated value,
    // or one predating profile data — still contributes, reconstructed as a Gaussian.
    let mut str_pdf = crate::ystr::str_tmrca_pdfs(pool, TREE_RESOLUTION_YEARS, TREE_MAX_AGE_YEARS).await?;
    let str_rows: Vec<(i64, i32, Option<i32>, Option<i32>)> = sqlx::query_as(
        "SELECT haplogroup_id, estimate_ybp, ci_low_ybp, ci_high_ybp \
         FROM tree.haplogroup_age_estimate WHERE method='STR_VARIANCE' AND estimate_ybp IS NOT NULL",
    )
    .fetch_all(&mut *tx)
    .await?;
    for (hg, est, lo, hi) in str_rows {
        if str_pdf.contains_key(&hg) {
            continue;
        }
        let mean = est as f64;
        let sigma = match (lo, hi) {
            (Some(l), Some(h)) if h > l => (h - l) as f64 / (2.0 * 1.96),
            _ => (mean * 0.25).max(1.0),
        };
        str_pdf.insert(hg, Pdf::gaussian_on(mean, sigma, TREE_RESOLUTION_YEARS, TREE_MAX_AGE_YEARS));
    }

    // ── Combine all method terms per branch (McDonald Eq 1: P(t|all)=k·∏P(t|eᵢ)) ──
    // Multiply the actual term PDFs rather than inverse-variance-averaging their
    // medians, so non-Gaussian shape (Poisson skew, STR convergent-mutation tails)
    // is preserved. If the terms are disjoint (product underflows to zero mass) the
    // node falls back to the inverse-variance Gaussian combine, which can't annihilate.
    let mut nodes: BTreeSet<i64> = BTreeSet::new();
    nodes.extend(snp_pdf.keys().chain(gen_pdf.keys()).chain(str_pdf.keys()).copied());
    for hg in nodes {
        let factors: Vec<&Pdf> =
            [snp_pdf.get(&hg), gen_pdf.get(&hg), str_pdf.get(&hg)].into_iter().flatten().collect();
        let Some((first, rest)) = factors.split_first() else { continue };
        let product = rest.iter().fold((*first).clone(), |acc, f| acc.multiply(f));
        let combined = if product.total() > 0.0 {
            product
        } else {
            let params: Vec<(f64, f64)> = factors.iter().map(|p| pdf_gaussian_params(p)).collect();
            match combine(&params) {
                Some((mean, sigma)) => Pdf::gaussian_on(mean, sigma, TREE_RESOLUTION_YEARS, TREE_MAX_AGE_YEARS),
                None => (*first).clone(),
            }
        };
        let (med, lo, hi) = combined.ci95();
        upsert_estimate_ci(
            &mut tx,
            hg,
            "COMBINED",
            med.round() as i32,
            lo.round() as i32,
            hi.round() as i32,
            factors.len() as i32,
        )
        .await?;
        // Gap-fill the authoritative tmrca_ybp (never overwrite a curated value).
        sqlx::query("UPDATE tree.haplogroup SET tmrca_ybp = $2 WHERE id = $1 AND tmrca_ybp IS NULL")
            .bind(hg)
            .bind(med.round() as i32)
            .execute(&mut *tx)
            .await?;
        stats.combined += 1;
    }

    tx.commit().await?;
    Ok(stats)
}

/// `(median, sigma)` Gaussian approximation of a PDF (sigma from its 95% CI) — used
/// only for the disjoint-terms fallback in the combine.
fn pdf_gaussian_params(p: &Pdf) -> (f64, f64) {
    let (med, lo, hi) = p.ci95();
    (med, ((hi - lo) / (2.0 * 1.96)).max(1.0))
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

    // Propagation tests use b·µ = 0.01 (b = 1.25e7, µ = 8e-10) so a Poisson age has
    // a clean mode of m/(b·µ) = 100·m years.
    const B: f64 = 1.25e7;
    const MU: f64 = 8e-10;
    // Small ages here → use the fine default PDF grid.
    const RES: f64 = crate::pdf::RESOLUTION_YEARS;
    const MAXA: f64 = crate::pdf::MAX_AGE_YEARS;

    #[test]
    fn tmrca_of_single_tester_is_poisson_mode() {
        let clades = vec![Clade { branch_snps: 0, callable_bp: B, children: vec![], tester_snps: vec![3] }];
        let ages = propagate(&clades, MU, RES, MAXA);
        let tmrca = &ages[0].as_ref().unwrap().tmrca;
        assert!((tmrca.mode() - 300.0).abs() <= 10.0, "mode {}", tmrca.mode());
    }

    #[test]
    fn parent_is_older_than_child_and_formed_exceeds_tmrca() {
        // parent(0) → child(1); child has 2 private SNPs and is 1 SNP below parent.
        let clades = vec![
            Clade { branch_snps: 0, callable_bp: B, children: vec![1], tester_snps: vec![] },
            Clade { branch_snps: 1, callable_bp: B, children: vec![], tester_snps: vec![2] },
        ];
        let ages = propagate(&clades, MU, RES, MAXA);
        let parent = ages[0].as_ref().unwrap();
        let child = ages[1].as_ref().unwrap();
        // Parent TMRCA = child TMRCA convolved with the branch → strictly older.
        assert!(parent.tmrca.median() > child.tmrca.median(), "causality");
        // A node's formed age (split from parent) is older than its own TMRCA.
        assert!(child.formed.median() > child.tmrca.median(), "formed > tmrca");
    }

    #[test]
    fn more_children_tighten_the_parent_ci() {
        let leaf = |b| Clade { branch_snps: 1, callable_bp: b, children: vec![], tester_snps: vec![2] };
        let one = vec![
            Clade { branch_snps: 0, callable_bp: B, children: vec![1], tester_snps: vec![] },
            leaf(B),
        ];
        let two = vec![
            Clade { branch_snps: 0, callable_bp: B, children: vec![1, 2], tester_snps: vec![] },
            leaf(B),
            leaf(B),
        ];
        let width = |ages: &[Option<CladeAge>]| {
            let (_, lo, hi) = ages[0].as_ref().unwrap().tmrca.ci95();
            hi - lo
        };
        assert!(
            width(&propagate(&two, MU, RES, MAXA)) < width(&propagate(&one, MU, RES, MAXA)),
            "two independent sub-clades give a tighter parent TMRCA than one"
        );
    }

    // ── DB-gated: full path over a seeded root→mid→leaf tree ──────────────────
    async fn ins_hg(pool: &PgPool, name: &str) -> i64 {
        sqlx::query_scalar(
            "INSERT INTO tree.haplogroup (name, haplogroup_type) \
             VALUES ($1, 'Y_DNA'::core.dna_type) RETURNING id",
        )
        .bind(name)
        .fetch_one(pool)
        .await
        .unwrap()
    }
    async fn ins_var(pool: &PgPool, name: &str, het: bool) -> i64 {
        let ann = if het {
            serde_json::json!({ "region_overlaps": ["heterochromatin:DYZ1"] })
        } else {
            serde_json::json!({})
        };
        sqlx::query_scalar(
            "INSERT INTO core.variant (canonical_name, mutation_type, naming_status, annotations) \
             VALUES ($1, 'SNP'::core.mutation_type, 'NAMED'::core.naming_status, $2) RETURNING id",
        )
        .bind(name)
        .bind(ann)
        .fetch_one(pool)
        .await
        .unwrap()
    }

    /// Seed a 3-node chain with one tester, run the whole pipeline, and check the
    /// het-mask, causality (parent older), and formed > tmrca — against real PG.
    #[tokio::test]
    async fn recompute_over_seeded_tree() {
        let Ok(url) = std::env::var("DATABASE_URL") else {
            eprintln!("DATABASE_URL unset — skipping seeded age test");
            return;
        };
        if url.is_empty() {
            return;
        }
        let db = crate::testing::ephemeral_db(&url).await.expect("ephemeral db");
        let pool = db.pool().clone();
        const GUID: &str = "00000000-0000-0000-0000-0000000000aa";

        let (root, mid, leaf) =
            (ins_hg(&pool, "Y-ROOT").await, ins_hg(&pool, "Y-MID").await, ins_hg(&pool, "Y-LEAF").await);
        for (p, c) in [(root, mid), (mid, leaf)] {
            sqlx::query("INSERT INTO tree.haplogroup_relationship (parent_haplogroup_id, child_haplogroup_id) VALUES ($1,$2)")
                .bind(p).bind(c).execute(&pool).await.unwrap();
        }
        // Defining (branch) SNPs: mid 4, leaf 3 — plus one heterochromatic defining
        // SNP on leaf that must be masked out.
        for i in 0..4 {
            let v = ins_var(&pool, &format!("MIDDEF{i}"), false).await;
            sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1,$2)").bind(mid).bind(v).execute(&pool).await.unwrap();
        }
        for i in 0..3 {
            let v = ins_var(&pool, &format!("LEAFDEF{i}"), false).await;
            sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1,$2)").bind(leaf).bind(v).execute(&pool).await.unwrap();
        }
        let hetdef = ins_var(&pool, "LEAFDEFHET", true).await;
        sqlx::query("INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id) VALUES ($1,$2)").bind(leaf).bind(hetdef).execute(&pool).await.unwrap();

        // One tester under leaf: 12.5 Mbp callable, 5 private SNPs + 1 het (masked).
        sqlx::query("INSERT INTO core.biosample (sample_guid, source) VALUES ($1::uuid, 'CITIZEN')").bind(GUID).execute(&pool).await.unwrap();
        sqlx::query("INSERT INTO genomics.biosample_callable_loci (sample_guid, chromosome, y_xdegen_callable_bp) VALUES ($1::uuid, 'chrY', 12500000)").bind(GUID).execute(&pool).await.unwrap();
        for i in 0..5 {
            let v = ins_var(&pool, &format!("PRIV{i}"), false).await;
            sqlx::query("INSERT INTO tree.biosample_private_variant (sample_guid, variant_id, haplogroup_type, terminal_haplogroup_id) VALUES ($1::uuid,$2,'Y_DNA'::core.dna_type,$3)").bind(GUID).bind(v).bind(leaf).execute(&pool).await.unwrap();
        }
        let hv = ins_var(&pool, "PRIVHET", true).await;
        sqlx::query("INSERT INTO tree.biosample_private_variant (sample_guid, variant_id, haplogroup_type, terminal_haplogroup_id) VALUES ($1::uuid,$2,'Y_DNA'::core.dna_type,$3)").bind(GUID).bind(hv).bind(leaf).execute(&pool).await.unwrap();

        // (a) build_clades: het-masking + structure.
        let (clades, ids) = build_clades(&pool).await.unwrap();
        let at = |id: i64| ids.iter().position(|&x| x == id).unwrap();
        assert_eq!(clades[at(leaf)].tester_snps, vec![5], "het private SNP masked → 5 counted");
        assert_eq!(clades[at(leaf)].branch_snps, 3, "het defining SNP masked → 3");
        assert!(clades[at(mid)].children.contains(&at(leaf)));
        assert!(clades[at(root)].children.contains(&at(mid)));
        assert!((clades[at(leaf)].callable_bp - 12_500_000.0).abs() < 1.0);

        // (b) full recompute: ages written, causality, formed > tmrca.
        let stats = recompute_combined_ages(&pool).await.unwrap();
        assert!(stats.snp >= 3, "root/mid/leaf all scored, got {}", stats.snp);
        let rows: Vec<(i64, Option<i32>, Option<i32>)> = sqlx::query_as(
            "SELECT id, tmrca_ybp, formed_ybp FROM tree.haplogroup WHERE id = ANY($1)",
        )
        .bind(vec![root, mid, leaf])
        .fetch_all(&pool)
        .await
        .unwrap();
        let tmrca = |id: i64| rows.iter().find(|r| r.0 == id).unwrap().1.unwrap();
        let formed = |id: i64| rows.iter().find(|r| r.0 == id).unwrap().2.unwrap();
        assert!(tmrca(leaf) > 0, "leaf has a positive TMRCA");
        assert!(tmrca(root) > tmrca(mid) && tmrca(mid) > tmrca(leaf), "causality: root>mid>leaf");
        assert!(formed(leaf) >= tmrca(leaf), "leaf formed age ≥ its TMRCA");
    }
}
