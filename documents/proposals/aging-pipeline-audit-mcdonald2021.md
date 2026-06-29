# Aging pipeline audit vs McDonald 2021

Audit of the DecodingUs Y-DNA branch-age pipeline against **McDonald, I. "Improved
Models of Coalescence Ages of Y-DNA Haplogroups." *Genes* 2021, 12, 862**
(doi:10.3390/genes12060862). The pipeline is intended to be a faithful
implementation of that paper; this document records where it is faithful, where it
diverges or approximates, what is missing, and candidate refinements (several of
which are under discussion by email with the author).

Code under audit:
- `crates/du-db/src/pdf.rs` — discrete PDF machinery (Poisson/Gaussian/convolve/multiply/mixture/quantiles)
- `crates/du-db/src/age.rs` — SNP-Poisson propagation, genealogical anchors, Eq-1 combine, causality
- `crates/du-db/src/ystr.rs` — Y-STR `P(g|m)`, marker age PDFs, ancestral-motif reconstruction, STR propagation
- migrations `0013_str.sql`, `0014_str_age.sql`, `0051_y_callable_mask.sql`

Paper reference frame: rates ~8×10⁻¹⁰ SNP·bp⁻¹·yr⁻¹; STR rates per-marker per-generation;
generation 33 yr; "present day" = tester/MRCA birth year.

---

## 1. Verdict at a glance

The **core probabilistic engine is faithful**: Eq 1 (product of evidence PDFs),
Eq 3 (Poisson SNP clock), Eq 11–14 + Table 1 (STR `P(g|m)` with multi-step ω),
ancestral-motif reconstruction (§2.5.2), and region-consistent SNP excision
(Appendix A.2/A.3) are all implemented and match the paper's numbers.

The **largest gaps are in the uncertainty budget and the second-order priors**,
which the paper itself flags as dominant for real-world clades:

1. **Mutation-rate uncertainty (σ_µ) is not propagated** (Appendix A.2.2/A.4). The
   paper states this *dominates the error budget* for most real cases (~±67 yr
   floor even at the well-tested R-S781). Our CIs are Poisson-only and therefore
   too narrow for larger/older clades. **[highest priority]**
2. **STR saturation has no max-time taper** (Appendix A.5.2). The paper explicitly
   recommends a Gaussian tapering of `P(g|m)` (or an exponential correction / revised
   µ) to stop STRs underestimating deep ages via convergent mutations. We use a
   crude tester-count gate instead.
3. **No NRR population-size prior** (Eq 25), **no shared-surname/autosomal historical
   terms** (Eq 24, §2.6.3), **tester-birth offset omitted** (Appendix A.1),
   **generational scatter `8√N` omitted** (Appendix A.5.1).

There is also one **methodological divergence worth a decision**: our SNP TMRCA is a
*robust depth-quantile* estimator, **not** the paper's Eq 7/8 PDF-convolution build
(§3). The STR path *does* use Eq 8 convolution — so the two clocks are built by
different machinery (see §6).

---

## 2. Section-by-section fidelity

### §2.1 Driving principle — Eq 1 `P(t|e) = k ∏ P(t|eᵢ)`
**FAITHFUL.** `Pdf::multiply` (pointwise product + renormalise) is used per node to
combine SNP × STR × genealogical PDFs (`age.rs` combine loop). The `k`
normalisation is the renormalise step. Disjoint-product underflow falls back to an
inverse-variance Gaussian combine (`combine()`), which can't annihilate — a
reasonable engineering guard not in the paper.

### §2.2 SNP PDFs from NGS — Eq 2–8
- **Eq 3 `P(t|m)=Poisson(m,tbµ)`** — **FAITHFUL.** `Pdf::poisson_on` builds the
  Gamma-shaped density `∝(tbµ)^m e^{-tbµ}` via log-sum-exp (overflow-safe for
  deep backbone counts). Mode `m/bµ`, mean `(m+1)/bµ` verified by tests.
- **µ_SNP = 8.33×10⁻¹⁰** (`age.rs SNP_RATE`) — **FAITHFUL** to Helgason/[21]
  (Icelandic, 33.5 yr/gen, MSY 21.3 Mbp). Single combined rate, matching the
  paper's choice (§2.2.1/Appendix A.4); X-deg/ampliconic vs palindromic rate split
  is not modelled (paper also uses the combined rate by default). ✓
- **Eq 4 `b̄` (effective callable bp)** — **PARTIAL.** Paper defines `b̄` as the
  intersection of coverage callable in **≥2 sub-clades** (≈ second-highest
  coverage; §3.4.2 "second-highest coverage among constituent sub-clades"). Our
  preferred path is a **uniform joint-call mask** (`genomics.y_callable_interval`,
  mig 0051) used region-consistently for numerator *and* denominator — a cleaner
  equivalent. **But** the fallback (no mask) uses the **per-node mean** tester
  callable bp, not the paper's second-highest. Divergent only when the mask is
  absent; flag for the fallback path.
- **§2.2.2 µ uncertainty (σ_µ)** — **MISSING.** `poisson_on` takes a point `mu`.
  The paper applies σ_µ as a multiplicative tree-wide scaling (or per-node
  broadening) at the time-conversion step. `HALLAST_RATE{,_LO,_HI}` constants exist
  but are not folded into the CI. **This is the dominant missing error term.**
- **Eq 5–8 (clade build by convolution)** — **DIVERGENT (deliberate).** See §6.
  We replace the Eq-6 YFull child-averaging (which the paper rejects) with a
  *robust depth-quantile* estimator (q90 of descendant root-to-tip SNP depths, with
  a q97≥150-SNP deep-ladder gate and a 2-children corroboration floor), then emit a
  single Poisson PDF from that count and convolve the branch time for `formed`
  (`propagate()`). This fixes deep-clade over-aging but is not the paper's Eq 7/8
  PDF product.

### §2.3 Causality — Eq 9/10
- **IMPLEMENTED (simplified).** Two layers now enforce parent ≥ child:
  (a) the SNP path clamps child counts to parents top-down (`propagate`); (b) the
  COMBINED path projects medians bottom-up, raising each parent to its oldest child
  with a CI shift (`recompute_combined_ages`, added 2026-06). This *guarantees* the
  constraint (verified: 0 inversions tree-wide).
- **DIVERGENT from the exact method.** The paper's Eq 9/10 *derive the child from
  the parent* by reverse-convolution `P(t_c)=P(t_p) ⊛ P(t_{c→p}|−m)` and take a
  **√(kits)-weighted average of Eq 9 and Eq 10** with `P(t<0)=0`. We instead raise
  the parent to the child (a median projection), because our inversions came from
  *under-aged parents* (a young STR term), not over-aged children. Note
  `Pdf::convolve_sub` (the Eq 9/10 reverse convolution) already exists but is
  currently unused — the building block for a faithful Eq 9/10 is in place.

### §2.4 Ancient DNA
- **PARTIAL.** aDNA can be entered as a `tree.genealogical_anchor` with
  `carbon_date_bp`. The paper models aDNA as a **lower limit** — the *cumulative*
  calibrated ¹⁴C distribution (novel variants often uncallable) — and notes σ_µ
  must be applied before mixing aDNA calendar dates with SNP ages. We model anchors
  as symmetric Gaussians, not cumulative/one-sided, and don't apply σ_µ. Adequate
  for proven-genealogy anchors; not yet a faithful aDNA constraint.

### §2.5 Y-STR PDFs — Eq 11–23, Table 1
- **Eq 11–12 `m_s=tµ_s`, `P(t|m_s)=Poisson⊗P(µ_s)`** — **PARTIAL.** `marker_age_pdf`
  builds `Poisson(m, t·µ_year)` with `µ_year = mutation_rate/33`. The **⊗P(µ_s)
  convolution over STR rate uncertainty is omitted** (we have
  `mutation_rate_lower/upper` columns but use the point rate) — same σ_µ gap as SNPs.
- **Eq 13–14, Table 1 `P(g|m)`** — **FAITHFUL.** McDonald's Table 1 is embedded
  verbatim (`ystr.rs TABLE1`, g,m≤10) and extended beyond its range by an
  all-orders signed-step ω convolution; `marker_age_pdf` forms the mixture
  `P(t|g)=Σ_m P(g|m)P(t|m)` (`Pdf::mixture`). Row/column sums ≈1 verified.
- **§2.5.3 multi-step ω** — **FAITHFUL.** `ω±1=0.96217, ω±2=0.032, ω±3=0.004`, ÷√10
  per further repeat, `w₊=w₋=0.5` default; per-marker `omega_plus/minus` drive
  asymmetric tables when present. Exact match to Eq 15.
- **§2.5.1 "twice the TMRCA" for two modern tests** — **HANDLED CORRECTLY.** We
  measure tester→reconstructed-ancestral-motif distance (1× TMRCA), not pairwise
  (2×), so no double-count.
- **§2.5.2 ancestral-motif reconstruction** — **FAITHFUL.** `reconstruct_motifs`
  does the up-pass (modal of sub-clades + direct testers) then down-pass (parent
  fill), simple markers only. The paper warns this **underestimates** when mutations
  are missed (null/ambiguous ancestral alleles bias young) — which is exactly the
  collapse we observed and mitigated with the ≥2-tester combine gate
  (`MIN_STR_TESTERS_FOR_COMBINE`). See §3 / §5.
- **Eq 23 / §2.5.4 multi-copy markers** — **DIVERGENT.** Paper recommends including
  multi-copy markers (DYS464/CDY/YCAII/DYS385…) as **binary** "any mutation?"
  (`P(g=0)` vs `Σ P(g>0)`). We **exclude multi-copy entirely** from scoring and
  propagation (simple markers only). This drops signal from the fastest markers,
  most useful for recent clades. Candidate refinement.
- **Eq 22 mixed multi-step (`f₂`+)** — acceptably neglected (paper says terms
  `f₃`+ and mixed `[+²,−²]` are <1/900th weight).

### §2.6 Historical / ancillary
- **§2.6.1 paper genealogies** — **PARTIAL.** Point/Gaussian anchors supported
  (`genealogical_anchor` + 10%-or-25yr σ default). δ-function, boxcar, log-normal
  shapes from the paper are not distinctly modelled (all collapse to Gaussian).
- **§2.6.2 shared surnames (Eq 24)** — **MISSING** (`ψ₂ + ½(1−ψ₂)[1+erf(...)]`).
- **§2.6.3 autosomal DNA constraints** — **MISSING** (log-normal / cumulative
  Gaussian on close relatedness).
- **§2.6.4 NRR population-size prior (Eq 25 `P(t|NRR)=NRR^{t/G}`)** — **MISSING.**
  The paper notes this skews TMRCAs older (many more distant than close cousins;
  ~30% for NRR=1.3) and can dominate the budget for very-well-tested recent
  families. Notable omission for the genealogical (recent) timescale.

### §2.7 / Appendix A — calibration constants
| Item | Paper | Code | Verdict |
|---|---|---|---|
| Present day (A.1) | tester/MRCA birth; add ~64 yr (95% CI 35–91) to TMRCA | `PRESENT_YEAR=1950` for ¹⁴C; **tester-birth offset omitted** ("≈present, negligible") | **MINOR GAP** — ages ~1 generation too young; lose that CI term |
| SNP rate (A.4) | ~8×10⁻¹⁰, σ≈±8–10% | `SNP_RATE=8.33e-10`; σ_µ not used | rate ✓, **σ_µ MISSING** |
| Recurrent/errant SNPs (A.2/A.3) | excise PAR1/PAR2/DYZ19/Yq12/centromere (~940 kb), retain palindromes; mask SNP clusters <~100 bp (MNPs) | `recurrent_region_mask_sql` masks heterochromatin + inverted_repeat; palindromes retained; joint-call mask excludes non-callable | **MOSTLY FAITHFUL**; palindrome-retention ✓; **MNP <100 bp clustering not removed** |
| Generation length (A.5.1) | 33 yr (95% CI 29–37) + per-gen scatter `8√N` yr | `GENERATION_YEARS=33` | mean ✓; **scatter & CI MISSING** |
| STR rates (A.5.2) | per-marker, homogeneous study (Willems 2016 [47]) | `genomics.str_mutation_rate` per-marker, `DEFAULT_STR_RATE=0.0025` fallback; 137 rows seeded | **FAITHFUL** (ensure full ~700-marker coverage) |
| STR max-time taper (A.5.2) | Gaussian taper on `P(g|m)` / exp correction / revised µ at large t | none (only ≥2-tester gate) | **MISSING** |
| Pop-growth bias (A.4.1) | larger clades gain ~2.3 extra SNPs; remedy = causality | causality projection | acceptable |

---

## 3. What the worked examples confirm

- **Example 3 (Fig 3, ~4 ky):** STR-only underestimates badly (2560 vs 4000 yr)
  from convergent/hidden mutations; combined SNP+STR (3667) beats SNP-only (3873).
  → validates both that STR *adds* precision **and** that it needs a deep-time
  taper (our §1.2 gap). Our reconstruction-collapse is the same phenomenon.
- **Example 4 (R-S781):** σ_µ sets a ~±67 yr error floor even with good data;
  YFull's narrower CI is called out as *not* carrying full Poisson + rate
  uncertainty. → our Poisson-only CIs share YFull's under-coverage; §1.1 gap.
- **`b̄` = second-highest sub-clade coverage; palindromes retained; ~940 kb of
  PAR/DYZ19/Yq12/centromere excised** — confirms our mask design intent.

---

## 4. Faithful — keep as is
- Eq 1 product combine (`multiply`) ✓
- Eq 3 Poisson clock, log-safe (`poisson_on`) ✓
- Table 1 `P(g|m)` verbatim + ω convolution extension ✓
- Multi-step ω (0.96217/0.032/0.004, ÷√10) ✓
- Per-marker STR rates from `str_mutation_rate` ✓
- Ancestral-motif up/down reconstruction (§2.5.2) ✓
- Region-consistent SNP excision; palindromes retained (A.2/A.3) ✓
- 33 yr/gen, 1950 ¹⁴C present ✓
- Causality guarantee (parent ≥ child) ✓ (mechanism simplified — see §6)

---

## 5. Recently fixed (2026-06, this work)
- **Causality on COMBINED** (§2.3): bottom-up parent≥child projection — was wholly
  absent from the combined term; caused the R-A804/R-A802 inversion.
- **STR collapse gate** (§2.5.2): `MIN_STR_TESTERS_FOR_COMBINE=2` keeps
  reconstruction-only nodes (≈0 genetic distance ⇒ spuriously young+narrow PDF)
  from dominating the SNP clock. A stop-gap for the missing §A.5.2 taper.
- Undatable nodes no longer retain stale denormalised `tmrca_ybp`.

---

## 6. The central methodological question: SNP propagation

The paper builds a node's age as the **normalised product of its children's PDFs,
each convolved with its branch-time PDF** (Eq 7/8), with `P(t_p<0)=0` guaranteeing
causality *by construction*. Our **STR** path does exactly this (`str_tmrca`:
`child ⊛ branch`, then product). Our **SNP** path does **not** — it computes a
robust **q90 depth-quantile** of descendant SNP depths and emits one Poisson PDF
(`propagate`). This was a deliberate fix for deep-clade over-aging (a single
over-called deep tip was inflating ancestors under naive max/averaging), but it
means:

- the two clocks are built by **different estimators** (inconsistent uncertainty
  semantics), and
- the SNP side departs from the paper's probabilistic Eq 8.

**Decision needed:** either (a) return the SNP side to the Eq 7/8 convolution build
and layer the robustness fixes (outlier-robust child weighting, deep-ladder gate)
on top of it — unifying with the STR path and recovering true PDF shape; or
(b) keep the quantile estimator and document it as an intentional, validated
divergence. This is a good email topic with the author.

---

## 7. Prioritised refinements

**P1 — Propagate mutation-rate uncertainty σ_µ (A.2.2/A.4, Eq 23).**
The paper's dominant error term. Convolve/scale the final age PDFs by the rate
uncertainty (SNP ±~8–10% via `HALLAST_RATE` CI already present; STR via
`mutation_rate_lower/upper`). Widens CIs to honest coverage; biggest accuracy win.

**P2 — STR deep-time taper (A.5.2).**
Add a Gaussian taper on `P(g|m)` (mathematically preferred) or a max-time/exp
correction so STR stops underestimating old clades via convergent mutations.
Replaces the blunt ≥2-tester gate with a principled saturation model; lets STR
inform internal nodes again.

**P3 — Faithful Eq 9/10 causality** (reverse-convolution `convolve_sub`, √kits
weighted Eq9/Eq10 average) replacing the median projection — redistributes PDF mass
instead of shifting medians.

**P4 — Tester-birth offset + generational scatter** (A.1, A.5.1): add ~64 yr (95%
CI 35–91) at the tip and `8√N`-yr generational scatter to STR ages. Small, cheap,
improves both central value and CI.

**P5 — NRR population-size prior (Eq 25)** for the recent/genealogical timescale;
**multi-copy STR binary inclusion (§2.5.4)** for fast-marker signal; **shared-surname
(Eq 24) / autosomal** historical terms; **MNP <100 bp cluster removal (A.3)**.

**P6 — Unify SNP propagation with Eq 8** (see §6) — larger, do after P1–P2.

---

*Generated 2026-06-29. Pipeline state at audit: 10,614 COMBINED estimates, 2,013
STR_VARIANCE, 0 parent<child inversions.*
