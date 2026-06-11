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
- [ ] **Verify the v2 BED filenames exist in the bucket before changing anything.**
      Paper Methods (Assembly evaluation / QC, ~line 817-818) cite:
      - `chm13v2.0Y_inverted_repeats_v2.bed` (we load `..._v1.bed`)
      - `chm13v2.0Y_amplicons_v2.bed` (we load `..._v1.bed`)
      Check `https://s3-us-west-2.amazonaws.com/human-pangenomics/T2T/CHM13/assemblies/annotation/<file>`
      (the `YREGIONS_BASE`-overridable `DEFAULT_BASE` already points here). If v2 exists,
      bump those two `SOURCES` entries and diff the parsed output against v1 (coords may
      have shifted; names may have changed → watch the `(region_type, name)` upsert key).
- [ ] **Add the AZFc color-blocks.** Paper uses GRCh38 AZFc colorblock coords from
      Teitz et al. (ref 23) projected onto CHM13. These are the blue/teal/red/green/
      yellow/grey repeat blocks that define AZFc structural haplotypes — high-recurrence,
      so they belong in the *fine-grained flag* layer (low-confidence-for-placement),
      not the sequence-class partition. Find the source coords (T2T-chrY repo or Suppl.).
- [ ] **Add palindrome P9.** Newly identified ninth palindrome (a hg38 "Rep1" direct
      repeat that is consistently *inverted* across all 142 samples; median length 15.8 kb).
      Classify as `palindromic`. Confirm whether the v2 inverted-repeats BED already
      carries it before adding a separate source.
- [ ] **Record the callable-mask justification.** The paper validates our X-DEG-only
      age denominator empirically (Fig 5h-i; Methods "Y-chromosomal phylogeny"):
      - Phylogeny ran on **~10.4 Mb** short-read-accessible Y: **10,400,778 callable
        positions, 25,426 polymorphic sites** (≥85% allele support, MQ/BQ ≥20, <5% missing).
      - Callable-mask comparison totals: GRCh37 **10.419 Mb** / T2T **14.364 Mb** /
        pangenome **9.739 Mb**; mean QV **48.99 / 49.17 / 61.90**. Per-class breakdown
        (XDR ~8.1–8.3 Mb across masks) in Fig 5h.
      - Takeaway the paper states: callability on chrY is a breadth-vs-accuracy trade-off;
        none of the masks call centromeric sequence; complex/satellite/ampliconic regions
        diverge most. → cite this where the age denominator `b` and Navigator's callable
        intersection are documented (proposal + `yregions.rs` module doc).
- [ ] After source changes, re-run `decodingus-jobs run-once yregions` and confirm
      `refresh_region_overlaps` re-flags cleanly (idempotent; upsert on `(region_type, name)`).

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
