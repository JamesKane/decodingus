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

/// WHERE-guard gating which `tree.haplogroup` rows the recompute may refresh. The
/// denormalized `formed_ybp`/`tmrca_ybp` are a cache of the computed age, so every
/// recompute overwrites them — EXCEPT a value a curator deliberately pinned, marked
/// with the `age_curated` provenance flag. (The prior `… IS NULL` gap-fill froze the
/// first run's value, so re-runs after new data/seeds never updated the display.) A
/// curator/UI that pins an age MUST set `provenance.age_curated = true`.
const AGE_REFRESH_GUARD: &str = "NOT COALESCE((provenance->>'age_curated')::boolean, false)";

/// Minimum callable Y bp for a sample to be used as an age tester. The Poisson age
/// is `m / (b·µ)`, so a sample covering only a sliver of the MSY (the table mins at
/// ~0.09 Mbp vs a healthy ~14 Mbp) divides a handful of private SNPs by a tiny `b`
/// and computes an age in the hundreds of ky — which then floors its whole backbone.
/// Such samples can't reliably date a branch; they're excluded from aging (and worth
/// flagging for coverage QC). All legitimate WGS testers here clear ~13 Mbp, so a
/// 5 Mbp (~35% of callable MSY) floor drops only the broken ones.
pub const MIN_TESTER_CALLABLE_BP: f64 = 5_000_000.0;

/// Reject a child whose age sits more than [`CHILD_OUTLIER_K`] MADs above its
/// siblings as bad evidence for the parent's age — but only where there are ≥
/// [`MIN_CHILDREN_FOR_OUTLIER_REJECTION`] siblings to judge against, so a genuinely
/// deep branch corroborated by its peers is kept and a lone false-deep one is not.
/// (A per-tester private-SNP cap was tried and reverted: high private counts are
/// dominated by genuine deep/rare singleton lineages, not caller over-calls, so a
/// clade-blind cap youthens real biology. See `branch_snp_qc_report.md`.)
pub const CHILD_OUTLIER_K: f64 = 5.0;
pub const MIN_CHILDREN_FOR_OUTLIER_REJECTION: usize = 4;

/// Median of a slice (sorts in place). NaN-free callers only; empty → 0.
fn median_in_place(v: &mut [f64]) -> f64 {
    if v.is_empty() {
        return 0.0;
    }
    v.sort_by(f64::total_cmp);
    let n = v.len();
    if n % 2 == 1 { v[n / 2] } else { (v[n / 2 - 1] + v[n / 2]) / 2.0 }
}

/// Scaled median absolute deviation (×1.4826 → ≈σ for normal data).
fn mad_scaled(v: &[f64], med: f64) -> f64 {
    let mut dev: Vec<f64> = v.iter().map(|x| (x - med).abs()).collect();
    1.4826 * median_in_place(&mut dev)
}

/// Upper outlier threshold `median + k·MAD` over `v` (MAD floored to 1 so a
/// zero-spread set still admits its own values). Returns +∞ when `v` is too small
/// to judge, i.e. no capping.
fn upper_threshold(v: &[f64], k: f64) -> f64 {
    if v.len() < 4 {
        return f64::INFINITY;
    }
    let mut tmp = v.to_vec();
    let med = median_in_place(&mut tmp);
    med + k * mad_scaled(v, med).max(1.0)
}

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
// Headroom above the human Y-MRCA (~250–300 ky): real deep backbone branches carry
// hundreds–thousands of SNPs (validated; ~80 yr/SNP), so the oldest nodes legitimately
// approach the MRCA. The grid must clear that plus Poisson tail, or those nodes peg at
// the ceiling. (Genuinely unresolved mega-branches are flagged, not clamped — see below.)
pub const TREE_MAX_AGE_YEARS: f64 = 500_000.0;

/// Branch-time PDF for clade `x`: `P(t | m_branch)` over its callable bp.
fn branch_time(clades: &[Clade], x: usize, mu: f64, res: f64, max_age: f64) -> Pdf {
    Pdf::poisson_on(clades[x].branch_snps, clades[x].callable_bp, mu, res, max_age)
}

/// Drop children whose age (median) sits more than `CHILD_OUTLIER_K` MADs above the
/// sibling consensus — an over-called / mis-placed subtree is bad evidence for its
/// parent's age and must not floor it up. Acts only with enough siblings to judge
/// ([`MIN_CHILDREN_FOR_OUTLIER_REJECTION`]); never returns empty.
fn reject_age_outliers(children: Vec<Pdf>) -> Vec<Pdf> {
    if children.len() < MIN_CHILDREN_FOR_OUTLIER_REJECTION {
        return children;
    }
    let meds: Vec<f64> = children.iter().map(Pdf::median).filter(|m| m.is_finite()).collect();
    let thr = upper_threshold(&meds, CHILD_OUTLIER_K);
    let kept: Vec<Pdf> = children.iter().filter(|c| c.median() <= thr).cloned().collect();
    if kept.is_empty() {
        children
    } else {
        kept
    }
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

    // Each scored input is an estimate of THIS node's age. Sub-clades: the node is
    // (child TMRCA ⊛ parent→child branch). Direct testers: each tester's private SNPs
    // since the node are an independent Poisson measurement of the node's own age.
    // These all estimate the same quantity, so combine by product (Eq 1 / Eq 5–8) —
    // which sharpens as evidence accrues and gives well-calibrated magnitudes.
    let child_factors: Vec<Pdf> = clades[i]
        .children
        .iter()
        .filter_map(|&ch| {
            compute_tmrca(ch, clades, mu, res, max_age, memo);
            match &memo[ch] {
                Some(Some(ct)) => Some(ct.convolve(&branch_time(clades, ch, mu, res, max_age))),
                _ => None,
            }
        })
        .collect();
    // Damp the AEngine over-call: a lone wildly-old sibling (false-deep subtree) is
    // dropped so it can't drag this node up via the product/floor/fallback below.
    let child_factors = reject_age_outliers(child_factors);
    let mut factors: Vec<Pdf> = child_factors.clone();
    let tester_pdfs: Vec<Pdf> = clades[i]
        .tester_snps
        .iter()
        .map(|&s| Pdf::poisson_on(s, clades[i].callable_bp, mu, res, max_age))
        .collect();
    if let Some((first, rest)) = tester_pdfs.split_first() {
        factors.push(rest.iter().fold(first.clone(), |acc, f| acc.multiply(f)));
    }

    let result = factors.split_first().map(|(first, rest)| {
        let product = rest.iter().fold(first.clone(), |acc, f| acc.multiply(f));
        // Coalescent causality floor: an ancestor is older than its oldest sub-clade.
        // For consistent sub-clades the product is well-calibrated and already above
        // this floor (no-op). When an under-sampled young sibling collapses the product
        // below it, truncate to the floor; if nothing survives, take the oldest
        // sub-clade's estimate. (Deep backbone nodes whose sub-clades peg at the grid
        // ceiling can still go degenerate — those are flagged as a data issue, not
        // fixable in the combiner.)
        let floor = child_factors.iter().map(Pdf::median).fold(f64::NEG_INFINITY, f64::max);
        if product.median().is_finite() && product.median() >= floor {
            product
        } else {
            product.truncate_below(floor).unwrap_or_else(|| {
                child_factors
                    .iter()
                    .max_by(|a, b| a.median().total_cmp(&b.median()))
                    .cloned()
                    .unwrap_or(product)
            })
        }
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

/// SNPs in recurrent / FP-prone sequence are masked from age counting — they sit
/// outside the callable denominator (`y_xdegen+y_ampliconic+y_palindromic`) and the
/// paper excises recurrent regions self-consistently (Appendix A.2/A.3). Ampliconic
/// and palindromic SNPs are kept (same rate as X-degenerate). The masked set
/// ([`crate::variant::RECURRENT_REGION_KINDS`]: heterochromatin + inverted_repeat) is
/// shared with the discovery consensus engine so age and branch-formation excise the
/// same FP-prone sequence. SQL fragment testing `core.variant v`'s `region_overlaps`.
fn recurrent_mask() -> String {
    crate::variant::recurrent_region_mask_sql("v")
}

/// Replace the per-build Y callable-mask intervals (`genomics.y_callable_interval`)
/// with `intervals` — half-open `[start, end)` hs1 (CHM13v2.0) spans parsed from the
/// pipeline's `chrY.callable_mask.chm13v2.bed`. Loaded by `decodingus-tree-init`
/// alongside the de-novo tree; consumed by [`build_clades`]. Returns the row count.
pub async fn load_callable_mask(pool: &PgPool, intervals: &[(i64, i64)]) -> Result<u64, DbError> {
    let los: Vec<i64> = intervals.iter().map(|&(a, _)| a).collect();
    let his: Vec<i64> = intervals.iter().map(|&(_, b)| b).collect();
    let mut tx = pool.begin().await?;
    sqlx::query("TRUNCATE genomics.y_callable_interval").execute(&mut *tx).await?;
    let n = sqlx::query(
        "INSERT INTO genomics.y_callable_interval (span) \
         SELECT int8range(lo, hi, '[)') FROM unnest($1::bigint[], $2::bigint[]) AS t(lo, hi) \
         WHERE hi > lo",
    )
    .bind(&los)
    .bind(&his)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    tx.commit().await?;
    Ok(n)
}

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

    // Per-build joint-call callable mask (genomics.y_callable_interval), if loaded.
    // When present it makes the numerator and denominator region-consistent:
    //   • denominator — the UNIFORM mask size (the region ASR branch counts were
    //     ascertained over), replacing the per-sample coverage average; and
    //   • numerator — a POSITIVE filter so only SNPs INSIDE the mask are counted
    //     (the recurrent mask is negative-only and lets non-callable SNPs through).
    // Absent (empty table) → falls back to the prior per-sample behaviour.
    let mask_bp: Option<f64> = sqlx::query_scalar(
        "SELECT sum(upper(span) - lower(span))::float8 FROM genomics.y_callable_interval",
    )
    .fetch_one(pool)
    .await?;
    let callable_filter = if mask_bp.is_some() {
        "AND EXISTS (SELECT 1 FROM genomics.y_callable_interval ci \
            WHERE ci.span @> (v.coordinates->'hs1'->>'position')::bigint)"
    } else {
        ""
    };

    // Branch defining-SNP counts (recurrent-region-masked, callable-mask-intersected).
    let mask = recurrent_mask();
    let branch: Vec<(i64, i64)> = sqlx::query_as(&format!(
        "SELECT hv.haplogroup_id, count(*)::bigint FROM tree.haplogroup_variant hv \
         JOIN core.variant v ON v.id=hv.variant_id \
         WHERE hv.valid_until IS NULL AND NOT hv.low_confidence AND {mask} {callable_filter} GROUP BY hv.haplogroup_id"
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
            AND pv.terminal_haplogroup_id IS NOT NULL AND {mask} {callable_filter} \
         GROUP BY pv.terminal_haplogroup_id, pv.sample_guid"
    ))
    .fetch_all(pool)
    .await?;
    let (mut bp_sum, mut bp_cnt) = (vec![0.0f64; ids.len()], vec![0u32; ids.len()]);
    for (hg, snps, b) in testers {
        // Skip sliver-coverage samples: they divide their SNPs by a tiny callable
        // denominator and produce impossible (hundreds-of-ky) ages that floor the spine.
        // (No outlier cap on the private count: high counts are dominated by genuine
        // deep/rare singleton lineages — A/B/Q/N — not caller over-calls, so a
        // clade-blind cap would wrongly youthen them. See branch_snp_qc_report.md.)
        if let (Some(&i), true) = (idx.get(&hg), b >= MIN_TESTER_CALLABLE_BP) {
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
        // Uniform joint-call mask size when loaded (ASR branch counts are ascertained
        // over that fixed region); else the prior per-sample mean (or catalog default).
        clades[i].callable_bp = match mask_bp {
            Some(b) if b > 0.0 => b,
            _ if bp_cnt[i] > 0 => bp_sum[i] / bp_cnt[i] as f64,
            _ => default_b,
        };
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

/// One persisted age-estimate row for a node, for surfacing the inputs/uncertainty
/// behind a branch's age in the UI. `sample_count` is per-method: for `SNP_POISSON`
/// it is the number of tester samples whose private SNPs measured this node (the
/// "population size" behind the age); for `COMBINED` it is the number of method
/// terms folded together (see [`recompute_combined_ages`]).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct AgeEstimate {
    pub method: String,
    pub estimate_ybp: Option<i32>,
    pub ci_low_ybp: Option<i32>,
    pub ci_high_ybp: Option<i32>,
    pub sample_count: Option<i32>,
}

/// All persisted age-estimate rows for a node (any method), most-confident first
/// (COMBINED, then SNP_POISSON, then the rest) for convenient display.
pub async fn estimates_for(pool: &PgPool, haplogroup_id: i64) -> Result<Vec<AgeEstimate>, DbError> {
    Ok(sqlx::query_as(
        "SELECT method, estimate_ybp, ci_low_ybp, ci_high_ybp, sample_count \
         FROM tree.haplogroup_age_estimate WHERE haplogroup_id = $1 \
         ORDER BY CASE method WHEN 'COMBINED' THEN 0 WHEN 'SNP_POISSON' THEN 1 \
                              WHEN 'GENEALOGICAL' THEN 2 ELSE 3 END",
    )
    .bind(haplogroup_id)
    .fetch_all(pool)
    .await?)
}

/// Count of distinct tester samples (ACTIVE Y private-variant carriers) at or below
/// `haplogroup_id` — the "population size" behind a node's TMRCA. A node's age is
/// measured by its own direct testers *and*, via propagation, every tester in its
/// subtree, so this whole-subtree count (not just the node's direct testers, which
/// are 0 for most internal backbone nodes) is what actually determined the estimate.
pub async fn subtree_tester_count(pool: &PgPool, haplogroup_id: i64) -> Result<i64, DbError> {
    Ok(sqlx::query_scalar(
        "WITH RECURSIVE sub AS ( \
            SELECT $1::bigint AS id \
          UNION ALL \
            SELECT c.id FROM tree.haplogroup c \
            JOIN tree.haplogroup_relationship r \
              ON r.child_haplogroup_id = c.id AND r.valid_until IS NULL \
            JOIN sub ON sub.id = r.parent_haplogroup_id \
            WHERE c.valid_until IS NULL \
         ) \
         SELECT count(DISTINCT pv.sample_guid) \
         FROM tree.biosample_private_variant pv \
         WHERE pv.terminal_haplogroup_id IN (SELECT id FROM sub) \
           AND pv.status = 'ACTIVE' \
           AND pv.haplogroup_type = 'Y_DNA'::core.dna_type",
    )
    .bind(haplogroup_id)
    .fetch_one(pool)
    .await?)
}

/// Recompute the SNP and genealogical age terms, then the COMBINED estimate for
/// every branch with ≥1 term, gap-filling `tmrca_ybp`. COMBINED is the direct PDF
/// product (Eq 1) of the SNP TMRCA PDF (propagation), the STR TMRCA PDF
/// ([`crate::ystr::str_tmrca_pdfs`]), and the genealogical anchor PDF — all on the
/// shared TREE grid. Full refresh of the computed methods (`SNP_POISSON`,
/// `GENEALOGICAL`, `COMBINED`); `STR_VARIANCE` (from `ystr`) and curated values are
/// left intact.
pub async fn recompute_combined_ages(pool: &PgPool) -> Result<CombineStats, DbError> {
    let mut stats = CombineStats::default();

    // ── PHASE 1: read inputs + run all CPU-bound PDF math holding NO transaction.
    // The dev proxy severs any connection idle > ~5s; the convolutions below take
    // seconds, so a tx held across them would be reaped mid-compute. Pool reads here
    // are each short, and a reaped idle pool connection is transparently replaced on
    // the next acquire (test_before_acquire). All writes are deferred to phase 2.

    // SNP-Poisson term: build the clade tree, propagate TMRCA/formed PDFs bottom-up
    // (McDonald Eq 5–8). Heterochromatic SNPs are masked from both `m` and the
    // callable denominator (`HET_MASK`).
    let (clades, ids) = build_clades(pool).await?;
    let ages = propagate(&clades, SNP_RATE, TREE_RESOLUTION_YEARS, TREE_MAX_AGE_YEARS);
    // Keep each term's actual PDF (on the shared TREE grid) for the Eq-1 product below.
    let mut snp_pdf: HashMap<i64, Pdf> = HashMap::new();
    // (id, med, lo, hi, tester_count, formed) — written in phase 2.
    let mut snp_writes: Vec<(i64, i32, i32, i32, i32, i32)> = Vec::new();
    for (i, age) in ages.iter().enumerate() {
        let Some(age) = age else { continue };
        let (med, lo, hi) = age.tmrca.ci95();
        snp_pdf.insert(ids[i], age.tmrca.clone());
        snp_writes.push((
            ids[i],
            med.round() as i32,
            lo.round() as i32,
            hi.round() as i32,
            clades[i].tester_snps.len() as i32,
            age.formed.median().round() as i32,
        ));
    }

    // Genealogical / aDNA anchors: per branch, combine its anchors into one term.
    let anchors: Vec<AnchorRow> = sqlx::query_as(
        "SELECT haplogroup_id, date_ce, carbon_date_bp, \
                details->>'uncertainty_years' AS uncertainty_years \
         FROM tree.genealogical_anchor",
    )
    .fetch_all(pool)
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
    let mut gen_writes: Vec<(i64, f64, f64)> = Vec::new(); // (hg, mean, rel)
    for (hg, ests) in &by_hg {
        if let Some((mean, sigma)) = combine(ests) {
            let rel = if mean > 0.0 { sigma / mean } else { 0.0 };
            gen_pdf.insert(*hg, Pdf::gaussian_on(mean, sigma, TREE_RESOLUTION_YEARS, TREE_MAX_AGE_YEARS));
            gen_writes.push((*hg, mean, rel));
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
    .fetch_all(pool)
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

    // Combine all method terms per branch (McDonald Eq 1: P(t|all)=k·∏P(t|eᵢ)).
    // Multiply the actual term PDFs rather than inverse-variance-averaging their
    // medians, so non-Gaussian shape (Poisson skew, STR convergent-mutation tails)
    // is preserved. If the terms are disjoint (product underflows to zero mass) the
    // node falls back to the inverse-variance Gaussian combine, which can't annihilate.
    let mut node_set: BTreeSet<i64> = BTreeSet::new();
    node_set.extend(snp_pdf.keys().chain(gen_pdf.keys()).chain(str_pdf.keys()).copied());
    let mut combined_writes: Vec<(i64, i32, i32, i32, i32)> = Vec::new(); // (hg, med, lo, hi, n_terms)
    for hg in node_set {
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
        combined_writes.push((hg, med.round() as i32, lo.round() as i32, hi.round() as i32, factors.len() as i32));
    }

    // ── PHASE 2: write everything in one short transaction. The writes are tiny and
    // back-to-back, so the connection never idles long enough to be reaped (no CPU
    // work happens between them).
    let mut tx = pool.begin().await?;
    sqlx::query("DELETE FROM tree.haplogroup_age_estimate WHERE method IN ('SNP_POISSON','GENEALOGICAL','COMBINED')")
        .execute(&mut *tx)
        .await?;
    for (id, med, lo, hi, testers, formed) in &snp_writes {
        upsert_estimate_ci(&mut tx, *id, "SNP_POISSON", *med, *lo, *hi, *testers).await?;
        // Refresh the denormalized node formation age so re-runs reflect the latest
        // computation — but never clobber a value a curator pinned (`age_curated`).
        // (Gap-fill-on-NULL would freeze the first run's value forever; see AGE_REFRESH_GUARD.)
        sqlx::query(&format!("UPDATE tree.haplogroup SET formed_ybp=$2 WHERE id=$1 AND {AGE_REFRESH_GUARD}"))
            .bind(id)
            .bind(formed)
            .execute(&mut *tx)
            .await?;
        stats.snp += 1;
    }
    for (hg, mean, rel) in &gen_writes {
        upsert_estimate(&mut tx, *hg, "GENEALOGICAL", *mean, *rel, None, None).await?;
        stats.genealogical += 1;
    }
    for (hg, med, lo, hi, n_terms) in &combined_writes {
        upsert_estimate_ci(&mut tx, *hg, "COMBINED", *med, *lo, *hi, *n_terms).await?;
        // Refresh the authoritative tmrca_ybp (unless curator-pinned via age_curated).
        sqlx::query(&format!("UPDATE tree.haplogroup SET tmrca_ybp = $2 WHERE id = $1 AND {AGE_REFRESH_GUARD}"))
            .bind(hg)
            .bind(med)
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

    #[test]
    fn parent_older_than_all_children_when_inconsistent() {
        // Parent with an OLD sub-clade (50 private SNPs ≈ 5000 yr) and a YOUNG one
        // (1 private SNP ≈ 100 yr) — the real-tree case of an unevenly-sampled split.
        // A common ancestor must be at least as old as its oldest descendant.
        let clades = vec![
            Clade { branch_snps: 0, callable_bp: B, children: vec![1, 2], tester_snps: vec![] },
            Clade { branch_snps: 1, callable_bp: B, children: vec![], tester_snps: vec![50] },
            Clade { branch_snps: 1, callable_bp: B, children: vec![], tester_snps: vec![1] },
        ];
        let ages = propagate(&clades, MU, RES, MAXA);
        let parent = ages[0].as_ref().unwrap().tmrca.median();
        let c1 = ages[1].as_ref().unwrap().tmrca.median();
        let c2 = ages[2].as_ref().unwrap().tmrca.median();
        eprintln!("parent={parent} old_child={c1} young_child={c2}");
        assert!(parent >= c1, "parent {parent} must be >= oldest child {c1}");
        assert!(parent >= c2, "parent {parent} must be >= youngest child {c2}");
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
