# TODO: Incorporate Hallast et al. 2026 (population-scale Y assemblies)

Created 2026-06-11. Repo: decodingus (AppView), branch `rust-rewrite-foundation`.

**Source:** Hallast, Rhie, Loftus, et al. *"Population-scale Y chromosome assemblies
reveal recurrent remodeling within constrained architectures."* bioRxiv
2026.06.03.729890v1, posted 2026-06-06. CC-BY-NC-ND 4.0.
DOI: https://doi.org/10.64898/2026.06.03.729890
Local PDF: `~/Downloads/2026.06.03.729890v1.full.pdf`

142 near-T2T de novo Y assemblies, 17 major haplogroups, dated phylogeny, full
T2T-CHM13v2Y annotation set, and a three-way callable-mask comparison. It's a
**resource paper**, and the resource lines up with three threads we already have
open. This note scopes the two actionable threads + records the out-of-scope bulk.

Related docs:
- `documents/proposals/branch-age-estimation.md` (age framework — thread 2 lands here)
- `documents/planning/y-tree-hs1-coordinate-enrichment.md` (the hs1/CHM13 native-coord issue)
- memory: `yregions-ingest`, `y-tree-coords-recurrence`, `etl-cutover-verified`

---

## Thread 1 — Region-flag + callable-mask refinement (PRIORITY: high)

Lands on the in-flight `yregions` ingest. Our ingest already pulls from the **exact
bucket this paper is built on** (`human-pangenomics/T2T/CHM13/assemblies/annotation`,
the `chm13v2.0Y_*_v1.bed` files). The paper's Methods reference **v2** versions of two
of them and add features we don't yet load.

- **Where:** `rust/crates/du-jobs/src/yregions.rs` (`SOURCES`, `classify_*`),
  `rust/crates/du-db/src/genome_region.rs`, `du_db::variant::refresh_region_overlaps`.

### Tasks
- [x] **v2 BEDs wired** (commit `d39b314`). Both v2 files exist; bumped the two
      `SOURCES` entries. v2 changes: inverted-repeats adds `IR2` (10→12 inverted_repeat
      rows); amplicon coords refined. The `(region_type, name)` orphan risk is handled —
      `run` now has full-snapshot sync (fetch-all-first → upsert → `prune_source_orphans`).
      Live v2 reload pruned 9 orphaned v1 rows, 0 leftovers. P9/Rep1 NOT literally in v2
      (still a separate hunt, below).
- [x] **AZFc color-blocks — already loaded** (no action needed). The v2 amplicons BED
      (`chm13v2.0Y_amplicons_v2.bed`, wired in d39b314) already carries the full Teitz
      colorblock set: blue1-4, gray1-2, green1-3, red1-4, teal1-2, yellow1-2, plus
      P1/P3/P5-AZFb/c blocks — all classified `ampliconic` (a flag type), so AZFc variants
      are already low-confidence-for-placement. Paper Fig 2a confirms these ARE the AZFc
      amplicon repeat blocks (b/g/r/t/y, Teitz ref 6).
- [ ] **Add palindrome P9 — BLOCKED on coords.** Confirmed: v2 inverted-repeats has P1-P8
      only, not P9. Paper main text (lines 519-523) gives only median length 15.8 kb + the
      hg38 "Rep1" (12 kb) lineage; **exact CHM13v2.0 arm coordinates are in Suppl. Tables
      28-29** (not in the main PDF) — harvest from there or the T2T-chrY repo, then add as a
      `palindromic` source (or wait for a v3 inverted-repeats BED that includes it). Low
      urgency: one ~15.8 kb region.
- [x] **Callable-mask justification — recorded** in `branch-age-estimation.md` (SNP rate
      section) and `yregions.rs` module doc. Fig 5h-i numbers: phylogeny mask ~10.4 Mb =
      XDR+AMPL+OTHER (excl. XTR/SAT/HET/DYZ19/CEN); XDR retained 8.111/8.341/7.437 Mb @ QV
      50.2/55.2/60.9; AMPL kept but QV ~46; SAT/HET/DYZ19 QV 35-44 or uncallable; no mask
      calls centromere. de novo: 49/53 DNMs in Yq12, ~1 in euchromatin, 6/40 Yq12 SNVs are
      gene conversion. → empirically validates the X-DEG denominator + HET_MASK.
- [ ] After P9 (if added), re-run `decodingus-jobs run-once yregions` and confirm
      `refresh_region_overlaps` re-flags cleanly (idempotent; full-snapshot sync since d39b314).

### Validation note
The paper empirically confirms variants in AMPL/SAT/CEN/DYZ17/DYZ19/HET are unreliable
and recurrent — exactly the classes `classify_sequence_class` already folds to flagged
types. This *validates* the existing `region_overlaps` design; cite it rather than
re-architecting.

---

## Thread 2 — Branch-age calibration (PRIORITY: medium; follows thread 1)

Lands in the age framework (`du_db::age`, `documents/proposals/branch-age-estimation.md`).
Our model uses µ = 8.33×10⁻¹⁰ (Helgason). The paper provides an independent recent
calibration + ready-made anchor nodes.

### Tasks
- [ ] **Record the paper's clock rate as an alternative/cross-check.** BEAST v1.10.4
      strict molecular clock, **0.76×10⁻⁹ sub/site/yr (95% CI 0.67–0.86×10⁻⁹)** — ~9%
      slower than our 0.833×10⁻⁹. (RAxML GTR+gamma start tree; constant-size coalescent;
      150M MCMC, 10% burn-in; TreeAnnotator MCC tree.) Do **not** silently swap rates;
      surface both with provenance.
- [ ] **Seed `tree.genealogical_anchor` from dated nodes.** Paper reports node TMRCAs
      with 95% HPD on ISOGG v15.73-labeled clades — usable as calibration anchors:
      - D1 clade TMRCA **19,450 ybp** (HPD 16,360–22,880)
      - HG00609 (used as phylo-close reference) TMRCA **10,350 ybp** (HPD 8,540–12,330)
      - more in Fig 1b / Fig 4c and Suppl. — harvest the major-branch nodes that map to
        our haplogroup names. `anchor_type = ANCIENT_DNA`-style calibration (these are
        model-dated, not C14 — set a `method`/`source` that records that).
- [ ] **Note the de novo per-generation rate.** CEPH pedigree DNMs (R1b lineages,
      Methods "de novo mutation analysis") are a direct empirical anchor for the
      per-generation mutation input — worth referencing alongside the per-year clock.

---

## Out of scope (record, don't build)

The bulk of the paper is deep sequence biology a haplogroup/genealogy platform won't
model: DAZ/RBMY/TSPY multicopy copy-number evolution, 5mC methylation profiles,
centromere DYZ3 α-satellite HOR / CDR, Yq12 (DYZ1/DYZ2) structural genomics, gene
conversion / G4 motifs, AZFc structural-haplotype cataloguing. The recurrence *principle*
("repeat-mediated variants arise independently → distrust for placement") is already
captured by our `defining_haplogroup_id` recurrence model + `region_overlaps` flag; the
paper is supporting evidence, not new schema.

---

## Data sources (all public)

- HPRC Data Release 2: https://humanpangenome.org/hprc-data-release-2/
- T2T-chrY analysis repo (annotation/scaffolding code): https://github.com/arangrhie/T2T-chrY
- Annotation BEDs (what `yregions` already reads):
  `s3://human-pangenomics/T2T/CHM13/assemblies/annotation/`
- GQC suspect-region BEDs: `s3://human-pangenomics/T2T/scratch/chrY/GQC/`
- Samples: 1kGP Diversity Panel cell lines (132/144) + GIAB + CEPH1463 — public, except
  two CEPH samples (NA12883/NA12884) which are dbGaP-restricted.

## Suggested first step
Verify the two v2 BED URLs resolve, diff v2-vs-v1 parsed output, then wire the v2
sources + AZFc color-blocks + P9 into `SOURCES`. That's the smallest contained change
and it's on code already uncommitted on this branch.
