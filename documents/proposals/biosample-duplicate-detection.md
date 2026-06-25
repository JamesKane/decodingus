# Biosample duplicate detection

**Status:** proposed (2026-06-25). Greenfield subsystem. Builds on the
`fed.*` mirror, the D1 exchange substrate (`d1-encrypted-edge-exchange.md`,
`exchange-broker`), the IBD candidate engine (`d3-ibd-matching-impl.md`,
`du-db::ibd`), and the discovery variant-pooling engine (`du-db::discovery`).
**Does not** introduce central autosomal-genotype storage for private samples —
that invariant is load-bearing and preserved here.

## Why

Multiple researchers contribute overlapping datasets, so the **same physical
individual lands as multiple `core.biosample` rows**. The two dominant sources:

- **Public academic WGS (IGSR, HGDP, SGDP, 1000G, etc.)** — re-released and
  re-derived across publications; the *most common* overlap. These carry clean
  ENA/SRA accessions, so the trivial collisions already dedup (see below), but
  the same individual recurs under *different* accessions across re-analyses.
- **FTDNA / project-admin uploads** — a project admin submitting members'
  BigY+STR results; the same tester may be submitted by two admins, or
  re-uploaded, under different aliases and with no shared accession.

Today the **only** dedup is exact equality on `accession`
(`denovo.rs:399`, `ON CONFLICT (accession) ... DO NOTHING`) plus federation's
`(did, rkey)` idempotency. Nothing catches the same person submitted twice under
different identifiers. See `biosample-consolidation` for the core/fed seam this
lands on.

### The sibling trap (why the obvious heuristics fail)

The intuitive signals — *same terminal Y + same mt haplogroup*, or *identical
111 Y-STR markers* — are **candidate generators, not confirmers**. Full
siblings share a terminal Y haplogroup, an mt haplogroup, and nearly all STR
values; an extended patriline shares the Y haplotype across *many* father-son
steps. The forensic-genetics literature is unambiguous that **uniparental
markers cannot individuate**: a matching Y profile "can never suffice to
establish convincingly that Q is a source," and matching males "are all
paternal-line relatives... but the relationship may extend over many father-son
steps, well beyond the known relatives" (Buckleton et al., PMC5669422). Y+mtDNA
together still cannot separate siblings, MZ twins, or a patriline from a true
duplicate.

So any honest design must treat the uniparental + STR signals as a *blocking
filter* and resolve duplicate-vs-relative with a **different class of
evidence**.

## Literature review: what actually separates a duplicate from a sibling

A background research pass (multi-source, adversarially verified; sources in
§References) gives a clear, citeable answer.

**The single discriminator is IBS0 / IBD2 from dense autosomal genotypes.**
A true duplicate (or MZ twin) and a parent-child pair share at least one allele
at essentially *every* autosomal site (IBS0 = 0). A full sibling has **IBS0 > 0**
— at sites where both parents are heterozygous, sibs can inherit opposite
alleles — and shares two alleles across only ~25% of the genome (**IBD2 ≈
0.25**). Duplicates sit at IBD2 ≈ 1.0, parent-child at IBD2 ≈ 0; that is what
tells them apart even though both non-duplicate first-degree relatives sit near
kinship 0.5. This mechanism is what KING, somalier, and the alignment-native
tools all rely on (Pedersen et al. 2020, *Genome Medicine*; Manichaikul et al.
KING, PMC3178600).

**Standard KING-robust kinship bands** (Manichaikul et al.; the de-facto biobank
convention — cited here as the standard table, not independently re-verified by
the research pass): duplicate/MZ twin **φ > 0.354**; 1st-degree
**0.177–0.354**; 2nd-degree **0.0884–0.177**; 3rd-degree **0.0442–0.0884**.
Duplicate confirmation = high kinship **and** IBS0 ≈ 0 (kinship alone puts a
duplicate and a parent-child pair in overlapping territory; IBS0 splits them).

### Method landscape and data requirements

| Method | Statistic | Min. data | Separates dup vs sibling? |
|---|---|---|---|
| **KING / KING-robust** | kinship φ + IBS0 | dense autosomal genotypes | **Yes** (φ + IBS0) |
| **somalier** | relatedness = (shared_hets − 2·IBS0)/min(hets) | BAM/CRAM/VCF/GVCF at ~17.7k common sites | **Yes** (relatedness ~1.0 + IBS0=0) |
| **NGSCheckMate** | Pearson corr. of VAF vectors | NGS reads at ~21k SNPs (works at low depth) | No — same-individual only |
| **Picard CrosscheckFingerprints** | base-10 LOD (same vs random) | BAM/CRAM/VCF at fingerprint sites | No — identity only |
| **Small fingerprint panels** (24/50/96/110 SNP) | allele-count / match prob. | a few dozen common high-MAF SNPs | **No** — individual ID only |
| **Y-STR / Y-DNA / mtDNA haplotype** | haplotype match | uniparental markers | **No** — patriline/maternal line only |

Key facts the design leans on:

- **somalier** works *directly on alignments* without joint variant calling,
  using a fixed panel of **17,766 sites (17,384 autosomal)** drawn from gnomAD
  coding SNPs with **AF ≈ 0.5** (maximally informative), unlinked, segdup/low-
  complexity excluded. A **relatedness cutoff of 0.5** classified every
  same-individual vs not-same pair in its GTEx cross-assay test (Pedersen 2020).
  This is the model to copy.
- **Sparse common-SNP panels are enough to *identify* an individual** with a
  vanishingly small random-match probability: the Pengelly 24-SNP panel
  (MAF 0.2–0.8, LD-independent) had a profile collision in <2.5% of simulated
  10k-person sets; a 110-SNP panel reaches cumulative RMP ~10⁻⁴⁶; ~20 informative
  SNPs already give unique ID. **Cross-platform/cross-assay matching is
  tractable from sparse overlap** — same-individual matching across array/WGS/
  exome/RNA-seq runs near 100% (NGSCheckMate; Gjorgjieva & Rosenberg 2025 match
  SNP profiles to orthogonal CODIS STR profiles with ~1,800 informed SNPs).
  **But** — repeatedly flagged by the verifiers — these panels detect
  *duplicates and individuals*; **~20–110 SNPs cannot separate a duplicate from
  a full sibling.** That still needs IBS0/IBD2 from dense genotypes.

**Net:** the field has a solved answer (autosomal IBS0/IBD2), and a graceful
degradation path (sparse common-SNP fingerprint → individual identity, cross-
platform), but **no method resolves duplicate-vs-sibling from uniparental data
alone.** Our design is therefore necessarily *tiered by what data a sample
has*, and must be willing to terminate at "suspected, unconfirmable" rather than
fabricate certainty.

## How this maps onto our data

What we hold **centrally** (`fed.*`, PII-free aggregates) and what we don't:

| Signal | Central table | Use |
|---|---|---|
| Terminal Y + mt haplogroup | `fed.haplogroup_reconciliation` | Tier-1 blocking key |
| Y private variants ("extra SNPs") | `fed.private_variant`, `tree.biosample_private_variant` | Tier-1 refine (Jaccard) |
| Y-STR profile (up to 111+) | `fed.str_profile` | Tier-1 refine (marker agreement) |
| PCA + ADMIXTURE | `fed.population_breakdown` | Tier-1 ancestry block (reuse `ibd.rs`) |
| Callable loci / coverage | `genomics.biosample_callable_loci` | Tier-1 overlap gate; Tier-2 site availability |
| Genotype *summary* counts | `fed.genotype` | metadata only (no alleles) |
| **Autosomal genotypes** | **— none —** | **must come from ingest pipeline (public) or Edge (private)** |

The decisive asymmetry:

- **Public academic WGS** flows through our *own* ingestion pipeline
  (`~/Genomics/ytree`, `du-jobs`) and is **not privacy-gated** — the sequence is
  already public. We can compute a somalier-style autosomal fingerprint **at
  ingest** and store it. This delivers full Tier-2 confirmation for the *most
  common* overlap source.
- **Private (FTDNA/Navigator) samples** have **no central autosomal data** by
  design. Tier-2 for these is **Edge-mediated** over the existing D1
  `IBD_AUTOSOMAL` exchange, or — absent an Edge/consent — terminates at
  *suspected*.

## Decisions (locked)

1. **Three tiers, and only Tier 2 ever *confirms* a duplicate.**
   Tier 0 = exact identifiers. Tier 1 = central candidate generation from
   uniparental + STR + private-variant + ancestry signals — produces **candidate
   pairs only**, structurally incapable of separating dup from sibling/patriline.
   Tier 2 = autosomal IBS0/IBD2 confirmation. **No path auto-merges from Tier 1.**
2. **Tier-2 autosomal fingerprint = somalier model, not bespoke.** Adopt a fixed
   common-SNP panel (AF ≈ 0.5, unlinked, ~10–20k autosomal sites; reuse
   somalier's published GRCh37/hg38 site lists) and its relatedness +
   IBS0 statistics. Don't reinvent the panel or the math.
3. **Fingerprint storage is gated by publicness.** The autosomal fingerprint
   sketch is stored centrally **only for public samples** (where the genotypes
   are already public). For private samples the sketch **never leaves the Edge**;
   confirmation runs Edge-to-Edge over D1. This preserves the AppView's
   no-private-genotype invariant.
4. **Reuse the D1 exchange substrate for private confirmation.** A suspected
   private duplicate is resolved by an `IBD_AUTOSOMAL` exchange between the two
   Edges (dual-consent, `exchange-broker`); the AppView receives only a signed
   verdict (relatedness band + IBS0≈0 flag), never genotypes. No new channel.
5. **Confirmed-duplicate band = φ > 0.354 AND IBS0 ≈ 0.** Siblings (IBS0 > 0,
   IBD2 ≈ 0.25) are explicitly **classified and recorded as relatives, never
   merged.** First/second-degree relative findings are a valuable *byproduct*
   (they feed `ibd.match_suggestion`), not an error.
6. **Merge is curator-gated and provenance-preserving**, executed at the
   core/fed seam (`biosample-consolidation`). Auto-merge is allowed *only* for
   Tier-2-confirmed public duplicates above a high-confidence threshold; private
   and uniparental-only cases always require human review.

## Architecture

```
Tier 0  exact          accession ==, atproto (did,rkey)        → auto-collapse (exists today)
Tier 1  candidate       block: sex + terminal Y + mt + ancestry → dedup.duplicate_candidate
        (central)       refine: STR agreement + private-var Jaccard   (status = CANDIDATE)
                        ⚠ cannot separate duplicate from sibling/patriline
Tier 2  confirm         public:  ingest-time autosomal fingerprint → IBS0/IBD2/kinship
        (autosomal)     private: D1 IBD_AUTOSOMAL Edge-to-Edge verdict
                        uniparental-only: no autosomal → status = SUSPECTED_UNCONFIRMABLE
        classify        φ>0.354 & IBS0≈0 → DUPLICATE   |   IBS0>0 → SIBLING/RELATIVE (not a dup)
Resolve curator review → core.biosample merge (provenance-preserving) | dismiss | relative-link
```

### Tier 0 — exact (exists)

`accession` equality and `(did, rkey)` idempotency. No change; documented here
for completeness as the floor.

### Tier 1 — central candidate generation (`du-jobs` + `du-db::dedup`)

A scheduled, incremental job (sibling to `branch-age-recompute` and
`ibd-discovery-recompute`). **Blocking, never N²** — reuse `ibd.rs`'s ancestry-
block + haplogroup logic so we only compare within (super-population × PCA cell ×
terminal-Y × mt) buckets. Within a block, score each pair:

- **Y-STR agreement** — fraction of co-tested markers identical over the shared
  marker set (handle multi-copy/complex per `0013_str.sql` scoring rules);
  near-perfect agreement on a large shared panel is necessary but not sufficient.
- **Private-variant Jaccard** — reuse `du-db::discovery`'s variant-set primitive
  over `fed.private_variant`; identical private-SNP sets are a strong same-
  patriline (possibly same-person) signal.
- **Coverage compatibility** — from `biosample_callable_loci`, to weight how much
  shared callable territory the agreement is actually based on.

Output: `dedup.duplicate_candidate` rows (`status = CANDIDATE`) carrying the
per-signal scores in a `signals` JSONB. **Every Tier-1 row is annotated
"uniparental — cannot exclude sibling/patriline."** This is the candidate set
Tier 2 consumes; it is never a merge trigger on its own.

### Tier 2 — autosomal confirmation (the only confirmer)

For each candidate, branch on data availability:

- **Both public WGS** → compare stored autosomal fingerprints (§Fingerprint).
  Compute relatedness/kinship + IBS0. Classify per Decision 5.
- **At least one private** → if both have a Navigator Edge and consent, open an
  `IBD_AUTOSOMAL` D1 exchange; the Edges compute IBS0/IBD2 and return a **signed
  verdict** (band + IBS0≈0 flag), no genotypes to the AppView. Otherwise →
  `SUSPECTED_UNCONFIRMABLE`, routed to curator.
- **Uniparental-only, no autosomal anywhere** → `SUSPECTED_UNCONFIRMABLE`. We
  *must not* call these duplicates; surface them for human judgment with the
  explicit caveat that a sibling/patriline is indistinguishable.

Classification:

| Result | Condition | Action |
|---|---|---|
| `DUPLICATE` | φ > 0.354 **and** IBS0 ≈ 0 | merge (auto if public+high-conf; else curator) |
| `RELATIVE` | IBS0 > 0, φ in relative bands | record relationship → `ibd.match_suggestion`; **not** a dup |
| `DISTINCT` | low kinship | dismiss candidate |
| `SUSPECTED_UNCONFIRMABLE` | no autosomal data | curator review, no merge |

### Fingerprint (`fed.identity_fingerprint`, public samples only)

At ingest of a **public** sample, the pipeline emits a somalier-style sketch:
genotypes at the fixed common-SNP panel (~10–20k autosomal AF≈0.5 sites). Stored
keyed by `sample_guid` with `panel_id` + `build`. Because the source genotypes
are already public, this introduces no new privacy exposure. Private samples are
**never** fingerprinted centrally (Decision 3) — their sketch stays on the Edge.

### Resolution — curator review + merge

A curator surface (analogous to the discovery proposal queue) lists candidates
with their tier, signals, and Tier-2 verdict. Actions: **merge**, **mark
relative**, **dismiss**. Merge runs a provenance-preserving
`core.biosample` consolidation at the core/fed seam: pick a surviving
`sample_guid`, repoint FKs (`tree.haplogroup_sample`,
`tree.biosample_private_variant`, `fed.*` refs, callable loci), fold
`original_haplogroups`/aliases/accessions into the survivor, and tombstone the
merged row with a `core.biosample_merge` audit record (evidence, curator, verdict,
timestamp). Mirrors the tombstone-merge pattern already used for
`research-subject-registry` D2 — though that operates on pseudonymous subjects,
not biosamples, so it's an analogy, not a reuse.

## Schema additions (sketch)

- `dedup.duplicate_candidate` — `(sample_a, sample_b, tier, signals jsonb,
  score, status, verdict jsonb, resolved_by, resolved_at)`; unordered pair
  unique.
- `fed.identity_fingerprint` — `(sample_guid, panel_id, build, sketch,
  source)`; **public samples only**; sketch = packed genotypes at panel sites.
- `core.biosample_merge` — `(surviving_guid, merged_guid, evidence jsonb,
  curator, created_at)` + tombstone marker on the merged biosample.

## Empirical validation (dev corpus, 2026-06-25)

A Phase-1 prototype run against the de-novo dev DB (12,900 `core.biosample`;
9,629 Y-DNA + 3,343 mtDNA `tree.haplogroup_sample` calls; 96,950
`biosample_private_variant`) confirms the design thesis on real data:

- **Tier 0 is clean.** Zero `accession` collisions (the partial-unique index
  holds); exact dedup already does its job. Every candidate below is a pair of
  *distinct* accessions.
- **Terminal-Y blocking alone is too coarse:** 1,765 multi-sample blocks →
  **3,193 candidate pairs** (whole shallow haplogroups colliding).
- **Adding mt tightens it to 13 pairs** (among the 1,742 dual-typed samples) —
  tractable, but **none are confirmable duplicates.** They decompose into
  exactly the three Tier-1 false-positive modes:
  - *Patriline/siblings:* 3 pairs within **PRJEB36890** (a **trio** cohort) —
    e.g. `HG04191/HG04193`, `HG04204/HG04215` — close male relatives.
  - *Cross-cohort relative:* `HG02736` (PRJEB31736) × `HG02738` (PRJEB36890) —
    the same PUR line appearing in two projects. **This is precisely the
    cross-researcher overlap the system targets, and uniparentally it is
    indistinguishable from a relative.**
  - *Within-population coincidence:* 9 pairs within PRJEB31736 where a common
    Y rides a common mt lineage (mt id `27035` recurs across the YRI `NA19xxx`
    pairs) — not even relatives.
- **Private-variant Jaccard contributes nothing here:** all 96,950 DENOVO
  private variants are per-sample singletons (max 1 sample/variant), so no pair
  shares even 3 — expected for a curated 1000G/HGDP/SGDP corpus, and a reminder
  the refinement only bites once federated citizen privates land.

**Conclusion (Tier 1):** Tier 1 generated a clean, tractable candidate set and
**zero false confidence** — no pair could be promoted to "duplicate" without
autosomal evidence, exactly as designed.

### Tier-2 ground truth (autosomal, run on the CHM13v2 CRAMs)

All colliding samples are public WGS, so the Tier-2a confirmer was run for real:
a 3,588-SNP common-autosomal panel (AF 0.40–0.60, ≥5 kb apart) was built on
chr21 **directly from the 1KGP-on-CHM13v2 recall** (`1kgp_chm13_af`, native
coordinates — no liftover), genotyped from each CRAM at 32–41× via
`bcftools mpileup/call`, and scored with somalier's verified statistic
`relatedness = (shared_hets − 2·IBS0)/min(hets)` plus the raw IBS0 rate. Result:

| Pair | relatedness | IBS0 rate | Verdict |
|---|---|---|---|
| `HG02736`/`HG02738` (cross-cohort, distinct accessions) | 0.496 | **0.11%** | **parent-child** — *not* a duplicate |
| `HG04191`/`HG04193` (PRJEB36890 trio) | 0.469 | 1.13% | full-sibling |
| `HG04204`/`HG04215` (PRJEB36890 trio) | 0.458 | 1.16% | full-sibling |
| `HGDP01028`/`HGDP01035` | 0.007 | 9.06% | unrelated (within-population coincidence) |
| 24 cross-block control pairs | ≤ 0.10 | 9–20% | unrelated baseline |

This is the design's central claim, demonstrated end-to-end on real data:

- **IBS0 separates the first-degree classes exactly as the literature predicts:**
  parent-child ≈ 0.1%, full-sib ≈ 1.1%, unrelated ≈ 9–20%. A true duplicate
  (rel ≈ 1.0, IBS0 ≈ 0) would be unambiguously distinct from all of them.
- **The most duplicate-shaped candidate is not a duplicate.** `HG02736`/`HG02738`
  — two different accessions, two different cohorts, identical terminal Y + mt:
  precisely what a re-submitted sample looks like to Tier 1 — is a **parent-child
  pair**. Only the autosomal IBS0 reveals it.
- **Tier 1 would have over-called every one of these.** Tier 2 reclassifies all
  to relative/unrelated; **zero merges** would result, which is correct.
- **Apply Tier 2 within ancestry blocks.** Cross-ancestry control pairs (HGDP vs
  the others) produced *negative* relatedness — KING/somalier statistics are not
  ancestry-corrected. Harmless for the dup-vs-sib decision (duplicates share
  ancestry by construction) but it means Tier 2 must reuse the `ibd.rs`
  super-population + PCA blocking rather than compare across populations.

(Reproduction: `/tmp/dedup_gt/` — panel build, `crams.txt`, `score.py`.)

## Phases

1. **Tier 0 + Tier 1 candidate engine** (central, no genotypes): blocking +
   STR/Jaccard scoring → `dedup.duplicate_candidate`. Ships value immediately as
   a *triage* surface ("here are likely-overlapping rows") even before any
   confirmer. Lets us measure how many real collisions the dev data has.
   **BUILT** (mig `0049_dedup.sql`; engine `du_db::dedup::recompute_candidates`
   reading the resolved `tree.*` tables, source-agnostic; job
   `du-jobs run-once dedup-candidates`, also daily). Declarative recompute mirrors
   the discovery engine: advisory-locked, idempotent upsert, prunes stale
   CANDIDATEs, never touches curator/Tier-2 statuses. On the dev corpus it writes
   exactly the 13 Y+mt pairs from the validation above, each routed
   `tier2_route=AUTOSOMAL_PUBLIC`. STR-marker agreement is a stubbed-out third
   signal (no `fed.str_profile` link to de-novo samples yet). Read surface
   `du_db::dedup::list_candidates`; curator web handlers + merge op are Phase 3.
2. **Tier 2a — public autosomal fingerprint**: ingest-time sketch +
   IBS0/IBD2 confirmation for IGSR/academic. Closes the most common source.
3. **Resolution surface + merge op**: curator review, provenance-preserving
   `core.biosample` merge, audit.
4. **Tier 2b — private Edge-mediated** confirmation over D1 `IBD_AUTOSOMAL`
   (gates on Navigator Edge + consent; depends on D1 being live).

## Not in scope / open questions

- **mtDNA full-sequence variants** are not stored today (only haplogroup);
  Tier-1's mt signal is haplogroup-only until that lands.
- **Confirmed biobank kinship thresholds in production** (UK Biobank, gnomAD,
  TOPMed exact drop cutoffs) and **DTC re-upload heuristics** (GEDmatch/FTDNA
  total-shared-cM / fully-identical-segment rules) were *not* covered by the
  verified research pass — worth a targeted follow-up before finalizing the
  exact auto-merge confidence threshold in Phase 3.
- **Contamination/swap tools beyond identity** (verifyBamID2, Conpair, peddy)
  are out of scope — they solve contamination/ancestry/pedigree QC, not
  duplicate detection.
- **Whether to expose any duplicate/relative finding publicly** — kept internal
  (curator-only) by default; surfacing relatives ties into the IBD consent model
  and is deferred.

## References

- Pedersen et al. 2020, *Genome Medicine* — **somalier**: alignment-native
  relatedness, 17,766-site panel, relatedness formula, IBS0 sibling-vs-duplicate
  separation. https://link.springer.com/article/10.1186/s13073-020-00761-2
- Manichaikul et al. — **KING** robust kinship / IBD inference. https://pmc.ncbi.nlm.nih.gov/articles/PMC3178600/
- Lee et al. 2017, *NAR* — **NGSCheckMate**: VAF-correlation identity, depth-
  dependent thresholds, cross-assay accuracy. https://academic.oup.com/nar/article/45/11/e103/3079509
- **Picard CrosscheckFingerprints** — LOD identity metric. https://gatk.broadinstitute.org/hc/en-us/articles/360037594711-CrosscheckFingerprints-Picard
- Pengelly et al. 2013 — **24-SNP** identity panel / random-match probability. https://pmc.ncbi.nlm.nih.gov/articles/PMC3978886/
- Lin/Huang et al. 2018 — **110-SNP** universal identification panel (RMP ~10⁻⁴⁶). https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5882920/
- Yousefi et al. 2018 — **50-SNP** DNA/RNA identity panel. https://pmc.ncbi.nlm.nih.gov/articles/PMC5785835/
- Gjorgjieva & Rosenberg 2025, *EJHG* — cross-assay SNP↔CODIS-STR record matching from sparse SNPs. https://www.nature.com/articles/s41431-025-01941-7
- Buckleton et al. — **Y-STR cannot individuate**; matching males are patriline
  relatives over unbounded father-son steps. https://pmc.ncbi.nlm.nih.gov/articles/PMC5669422/
