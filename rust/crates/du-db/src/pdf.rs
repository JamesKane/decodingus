//! Age probability-distribution machinery for the McDonald (2021) branch-age
//! model — the foundation the SNP, STR, and historical terms all build on.
//!
//! An age estimate is a PDF over time-before-present, `P(t|e)`. The paper combines
//! independent evidence by multiplying PDFs (Eq 1: `P(t|e)=k·∏P(t|eᵢ)`), derives a
//! parent clade's age by convolving a child's age with the parent→child branch
//! time (Eq 7), and reverses that to push a constraint down a branch (Eq 9). We
//! represent each PDF as probability **mass per fixed-width time bin** so those
//! operations are exact discrete arithmetic.
//!
//! This module is pure (no DB, no I/O) and is the replacement substrate for the
//! inverse-variance Gaussian shortcut in [`crate::age::combine`].

/// Grid resolution — years per bin (design default `pdf-resolution`).
pub const RESOLUTION_YEARS: f64 = 10.0;
/// Grid extent — oldest age modelled (design default `pdf-max-age`).
pub const MAX_AGE_YEARS: f64 = 100_000.0;

/// A discrete probability distribution over age (years before present). `mass[i]`
/// is the probability the age lies in bin `i`, i.e. around `i * res` years; the
/// masses are kept normalized to sum 1 (a degenerate all-zero PDF is allowed and
/// reports zero for every statistic).
#[derive(Debug, Clone)]
pub struct Pdf {
    res: f64,
    mass: Vec<f64>,
}

impl Pdf {
    fn zeros(res: f64, max_age: f64) -> Pdf {
        let bins = (max_age / res).round() as usize + 1;
        Pdf { res, mass: vec![0.0; bins] }
    }

    /// Years at the centre of bin `i`.
    #[inline]
    fn age(&self, i: usize) -> f64 {
        i as f64 * self.res
    }

    fn bin_of(&self, years: f64) -> usize {
        ((years / self.res).round() as usize).min(self.mass.len() - 1)
    }

    /// `P(t|m)` for `m` SNPs over `b` callable bp at rate `mu` (Eq 3:
    /// `Poisson(m, t·b·µ)` read as a function of `t`). As a density in `t` this is
    /// `∝ (t·b·µ)^m · exp(−t·b·µ)`, i.e. a Gamma with mode `m/(b·µ)` and mean
    /// `(m+1)/(b·µ)` — so `1/(b·µ)` is the per-SNP temporal resolution (~83 yr for
    /// a 15 Mbp test). Uses the default grid.
    pub fn poisson(m: i64, b: f64, mu: f64) -> Pdf {
        Pdf::poisson_on(m, b, mu, RESOLUTION_YEARS, MAX_AGE_YEARS)
    }

    pub fn poisson_on(m: i64, b: f64, mu: f64, res: f64, max_age: f64) -> Pdf {
        let mut pdf = Pdf::zeros(res, max_age);
        let m = m.max(0) as f64;
        for i in 0..pdf.mass.len() {
            let lambda = pdf.age(i) * b * mu; // t·b·µ
            // log-space: m·ln(λ) − λ (the 1/m! constant drops out in normalization).
            pdf.mass[i] = if lambda <= 0.0 {
                if m == 0.0 {
                    1.0
                } else {
                    0.0
                }
            } else {
                (m * lambda.ln() - lambda).exp()
            };
        }
        pdf.normalize();
        pdf
    }

    /// A Gaussian age PDF — for genealogical/aDNA anchors with a date ± sigma, or
    /// for applying mutation-rate uncertainty as a broadening. Uses the default grid.
    pub fn gaussian(mean_years: f64, sigma_years: f64) -> Pdf {
        Pdf::gaussian_on(mean_years, sigma_years, RESOLUTION_YEARS, MAX_AGE_YEARS)
    }

    pub fn gaussian_on(mean_years: f64, sigma_years: f64, res: f64, max_age: f64) -> Pdf {
        let mut pdf = Pdf::zeros(res, max_age);
        let s = sigma_years.max(pdf.res / 2.0);
        for i in 0..pdf.mass.len() {
            let z = (pdf.age(i) - mean_years) / s;
            pdf.mass[i] = (-0.5 * z * z).exp();
        }
        pdf.normalize();
        pdf
    }

    /// A near-delta PDF at a precisely known age (e.g. a proven MRCA birth year).
    pub fn point(years: f64) -> Pdf {
        let mut pdf = Pdf::zeros(RESOLUTION_YEARS, MAX_AGE_YEARS);
        let i = pdf.bin_of(years.max(0.0));
        pdf.mass[i] = 1.0;
        pdf
    }

    /// Weighted mixture `Σ wᵢ·pdfᵢ`, renormalized — used for the STR marker age
    /// `P(t|g) = Σ_m P(g|m)·P(t|m)` (McDonald Eq 14, inner sum over the hidden
    /// mutation count `m`). Components must share a grid; non-positive weights are
    /// skipped. `None` if nothing contributes.
    pub fn mixture(components: &[(f64, Pdf)]) -> Option<Pdf> {
        let res = components.iter().find(|(w, _)| *w > 0.0).map(|(_, p)| p.res)?;
        let len = components.iter().map(|(_, p)| p.mass.len()).max().unwrap_or(0);
        let mut out = Pdf { res, mass: vec![0.0; len] };
        for (w, p) in components {
            if *w <= 0.0 {
                continue;
            }
            debug_assert_eq!(p.res, res, "mixture PDFs must share a grid resolution");
            for (i, &m) in p.mass.iter().enumerate() {
                out.mass[i] += w * m;
            }
        }
        (out.total() > 0.0).then(|| {
            out.normalize();
            out
        })
    }

    /// Combine independent evidence (Eq 1): pointwise product, renormalized.
    pub fn multiply(&self, other: &Pdf) -> Pdf {
        debug_assert_eq!(self.res, other.res, "PDFs must share a grid resolution");
        let n = self.mass.len().min(other.mass.len());
        let mut out = Pdf { res: self.res, mass: vec![0.0; self.mass.len()] };
        for i in 0..n {
            out.mass[i] = self.mass[i] * other.mass[i];
        }
        out.normalize();
        out
    }

    /// Distribution of the sum of two ages (Eq 7: parent age = child age ⊛
    /// parent→child branch time). Mass that would land beyond the grid is dropped.
    pub fn convolve(&self, other: &Pdf) -> Pdf {
        debug_assert_eq!(self.res, other.res, "PDFs must share a grid resolution");
        let mut out = Pdf { res: self.res, mass: vec![0.0; self.mass.len()] };
        let last = out.mass.len() - 1;
        let (alo, ahi) = self.support();
        let (blo, bhi) = other.support();
        for i in alo..=ahi {
            let a = self.mass[i];
            if a == 0.0 {
                continue;
            }
            for j in blo..=bhi {
                let k = i + j;
                if k > last {
                    break; // j only increases → rest also overflow
                }
                out.mass[k] += a * other.mass[j];
            }
        }
        out.normalize();
        out
    }

    /// Distribution of the difference of two ages, `self − other`, with negative
    /// outcomes dropped (Eq 9/10: derive a child's age from its parent's, given the
    /// branch time — the parent must be older, so `P(t<0)=0`).
    pub fn convolve_sub(&self, other: &Pdf) -> Pdf {
        debug_assert_eq!(self.res, other.res, "PDFs must share a grid resolution");
        let mut out = Pdf { res: self.res, mass: vec![0.0; self.mass.len()] };
        let (alo, ahi) = self.support();
        let (blo, bhi) = other.support();
        for i in alo..=ahi {
            let a = self.mass[i];
            if a == 0.0 {
                continue;
            }
            for j in blo..=bhi {
                if j > i {
                    break; // i−j < 0 → dropped; j only grows
                }
                out.mass[i - j] += a * other.mass[j];
            }
        }
        out.normalize();
        out
    }

    /// First and last bin carrying non-negligible mass (keeps convolution near the
    /// support rather than O(n²) over the whole grid).
    fn support(&self) -> (usize, usize) {
        let lo = self.mass.iter().position(|&m| m > 0.0).unwrap_or(0);
        let hi = self.mass.iter().rposition(|&m| m > 0.0).unwrap_or(0);
        (lo, hi)
    }

    fn normalize(&mut self) {
        let total: f64 = self.mass.iter().sum();
        if total > 0.0 {
            for m in &mut self.mass {
                *m /= total;
            }
        }
    }

    /// Total mass — 1.0 for a valid PDF, 0.0 for a degenerate (empty) one.
    pub fn total(&self) -> f64 {
        self.mass.iter().sum()
    }

    /// Expected age (years).
    pub fn mean(&self) -> f64 {
        self.mass.iter().enumerate().map(|(i, &m)| self.age(i) * m).sum()
    }

    /// Age (years) at the most probable bin.
    pub fn mode(&self) -> f64 {
        let mut best = (0usize, 0.0f64);
        for (i, &m) in self.mass.iter().enumerate() {
            if m > best.1 {
                best = (i, m);
            }
        }
        self.age(best.0)
    }

    /// The `p`-quantile (0–1) by linear interpolation across the crossing bin.
    pub fn percentile(&self, p: f64) -> f64 {
        if self.total() <= 0.0 {
            return 0.0;
        }
        let target = p.clamp(0.0, 1.0);
        let mut cum = 0.0;
        for (i, &m) in self.mass.iter().enumerate() {
            let next = cum + m;
            if next >= target {
                // Interpolate within bin i for a smoother quantile.
                let frac = if m > 0.0 { (target - cum) / m } else { 0.0 };
                return (i as f64 - 0.5 + frac).max(0.0) * self.res;
            }
            cum = next;
        }
        self.age(self.mass.len() - 1)
    }

    pub fn median(&self) -> f64 {
        self.percentile(0.5)
    }

    /// `(median, 2.5th percentile, 97.5th percentile)` — the central estimate and
    /// its 95% credible interval.
    pub fn ci95(&self) -> (f64, f64, f64) {
        (self.median(), self.percentile(0.025), self.percentile(0.975))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn approx(a: f64, b: f64, tol: f64) -> bool {
        (a - b).abs() <= tol
    }

    /// P(t|m) is Gamma(shape m+1, rate b·µ) in t: mode m/(b·µ), mean (m+1)/(b·µ).
    /// Pick b·µ = 0.01 (1/bµ = 100 yr/SNP) for round numbers.
    #[test]
    fn poisson_mode_and_mean_match_gamma() {
        let (b, mu) = (1.25e7, 8e-10); // b·µ = 0.01
        let pdf = Pdf::poisson(3, b, mu);
        assert!(approx(pdf.total(), 1.0, 1e-9));
        assert!(approx(pdf.mode(), 300.0, RESOLUTION_YEARS), "mode {}", pdf.mode());
        assert!(approx(pdf.mean(), 400.0, 2.0), "mean {}", pdf.mean());
    }

    /// 1/(b·µ) ≈ 83 yr/SNP for a ~15 Mbp test (paper's quoted resolution): m=1 mode.
    #[test]
    fn per_snp_resolution_is_about_83_years() {
        let pdf = Pdf::poisson(1, 1.5e7, 8.33e-10); // 1/bµ ≈ 80
        assert!(approx(pdf.mode(), 80.0, RESOLUTION_YEARS), "mode {}", pdf.mode());
    }

    #[test]
    fn gaussian_median_and_ci() {
        let pdf = Pdf::gaussian(3000.0, 300.0);
        let (med, lo, hi) = pdf.ci95();
        assert!(approx(med, 3000.0, RESOLUTION_YEARS));
        assert!(approx(lo, 3000.0 - 1.96 * 300.0, 15.0), "lo {lo}");
        assert!(approx(hi, 3000.0 + 1.96 * 300.0, 15.0), "hi {hi}");
    }

    /// Multiplying two Gaussians = inverse-variance combine (the old behaviour),
    /// now as a proper PDF: mean pulled between, CI tighter than either input.
    #[test]
    fn multiply_is_inverse_variance_combine() {
        let a = Pdf::gaussian(3000.0, 300.0);
        let b = Pdf::gaussian(3300.0, 300.0);
        let m = a.multiply(&b);
        assert!(approx(m.mean(), 3150.0, 5.0), "mean {}", m.mean());
        let (_, lo, hi) = m.ci95();
        assert!(hi - lo < 2.0 * 1.96 * 300.0, "combined CI should tighten");
        // A much tighter estimate dominates.
        let tight = Pdf::gaussian(3000.0, 50.0).multiply(&Pdf::gaussian(5000.0, 1000.0));
        assert!(tight.mean() < 3100.0, "tight estimate dominates, got {}", tight.mean());
    }

    #[test]
    fn multiply_disjoint_is_empty() {
        // Non-overlapping terms produce zero mass — the signal the combined-age step
        // (du_db::age) uses to fall back from the PDF product to a Gaussian combine.
        let disjoint = Pdf::gaussian(1000.0, 50.0).multiply(&Pdf::gaussian(50_000.0, 50.0));
        assert_eq!(disjoint.total(), 0.0);
    }

    #[test]
    fn convolve_adds_ages() {
        // Two point ages add.
        let s = Pdf::point(1200.0).convolve(&Pdf::point(800.0));
        assert!(approx(s.mean(), 2000.0, RESOLUTION_YEARS));
        // Two Gaussians (means kept clear of the 0 floor so neither tail is
        // truncated): means add, variances add (sigma = √(σ₁²+σ₂²)).
        let g = Pdf::gaussian(2000.0, 300.0).convolve(&Pdf::gaussian(1500.0, 400.0));
        assert!(approx(g.mean(), 3500.0, 5.0), "mean {}", g.mean());
        let (_, lo, hi) = g.ci95();
        let sigma = (hi - lo) / (2.0 * 1.96);
        assert!(approx(sigma, 500.0, 20.0), "sigma {sigma}"); // √(300²+400²)=500
    }

    #[test]
    fn convolve_sub_subtracts_and_floors_at_zero() {
        // Parent 2000 minus branch 800 → child 1200.
        let c = Pdf::point(2000.0).convolve_sub(&Pdf::point(800.0));
        assert!(approx(c.mean(), 1200.0, RESOLUTION_YEARS), "mean {}", c.mean());
        // A larger subtrahend than minuend → all mass floored away (P(t<0)=0).
        let z = Pdf::point(500.0).convolve_sub(&Pdf::point(900.0));
        assert!(approx(z.mean(), 0.0, RESOLUTION_YEARS), "mean {}", z.mean());
    }
}
