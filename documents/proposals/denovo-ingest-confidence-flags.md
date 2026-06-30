# De-novo ingest confidence flags — issues for the tree team

**Status:** findings from age-pipeline work (2026-06-30, `feat/faithful-mcdonald-age`).
**Audience:** ytree pipeline maintainers (`~/Genomics/ytree/bin`).
**TL;DR:** The regenerated FTDNA ingest artifacts carry `jointConfirmed` / `monophyletic`
per-variant confidence flags. The AppView loader excludes flagged SNPs from branch-age
counting. One of those flags (`monophyletic`) is computed in a way that is **invalid for
reverse-polarity SNPs**, which silently zeroes the **entire deep backbone** (BT/CT/F) and
collapses every TMRCA below CT. A loader-side workaround is in place; the real fix belongs
upstream in the ASR / flagging stages.

## Where this bites

The loader (`rust/crates/du-db/src/denovo.rs::DenovoVariant::low_confidence`) maps:

```
low_confidence = !jointConfirmed || !monophyletic     (missing flag → confident)
```

and the age engine (`du-db/src/age.rs::build_clades`) excludes `low_confidence` defining
SNPs from a node's branch count. Combined with the age engine's *zero-age-countable-SNP
nodes are age-transparent* rule (a node with 0 countable SNPs is treated as a zero-length,
pass-through edge), any node whose defining SNPs are **all** flagged collapses onto its
parent. When that happens to the deep backbone, the whole tree below it inherits the
collapse via the parent≥child causality constraint.

## Issue 1 — `monophyletic=false` on reverse-polarity backbone SNPs (the CHM13-is-J bug)

The reference is **CHM13 / HG002, whose Y is haplogroup J** — which sits deep *inside* CT.
So every backbone SNP **above J** (CF, F, CT, BT, and most of the deep A spine) carries the
**derived** allele *as the reference* → `polarity: "reverse"`.

For a reverse-polarity SNP the derived carriers are reference-matching and therefore
**invisible to variant calling**, while only the ancestral outgroup (A/B lineages) shows a
variant call. A monophyly test computed from variant calls then sees the *ancestral*
carriers — a paraphyletic set — and flags the SNP `monophyletic=false`. This is a systematic
artifact of the inversion, not real homoplasy.

Observed in `chrY.ftdna.refined.ingest.json` and `chrY.ftdna.refined.indel.ingest.json`:

| node    | defining SNPs | polarity   | monophyletic=false | would-count (pre-fix) |
|---------|---------------|------------|--------------------|-----------------------|
| CT-M168 | 289           | all reverse| 286                | **0**                 |
| F-M89   | 163           | 162 reverse| 163                | **0**                 |
| BT-M42  | 8             | all reverse| 8                  | **0**                 |
| A-V168  | 205           | all reverse| 197                | 8                     |

These are foundational markers (M168, M89, M42 themselves). Zeroing them drove the whole
tree to a single collapsed depth (every TMRCA pinned at CT's collapsed ~16.8 kya).

**Loader workaround (shipped):** exempt reverse-polarity SNPs from the `monophyletic` gate.
`jointConfirmed` still applies to all; `monophyletic` still excludes genuine *forward*
homoplasy.

**Upstream fix wanted:** the monophyly computation (Fitch / ASR, `bin/82b_asr.py` +
`bin/86_flag_confidence.py`) should be **polarity-aware** — evaluate clade-membership on the
*derived* state in phylogenetic (ancestral/derived) space, not on raw reference-relative
variant calls. Then reverse-polarity backbone SNPs come out `monophyletic=true` and no
loader exemption is needed.

## Issue 2 — `jointConfirmed=false` excludes ~27% of forward SNPs

~80,400 forward-polarity defining SNPs (27% of all defining calls) are `jointConfirmed=false`
and excluded from counting. This is **kept** for now — it plausibly reflects real
"AEngine positive-only / not confirmed in joint genotyping" calls and may legitimately tame
the deep-backbone over-counting seen earlier. Flagging here only so the tree team is aware of
the magnitude; if ages come out systematically young, this gate is the first suspect.

## Issue 3 — the INDEL artifact is unverified (extraction has placement-collapsing issues)

`chrY.ftdna.refined.indel.ingest.json` (ASR + indels + flags) has **not** been confirmed to
be generated correctly. The **indel extraction itself has a known problem**, observed
tree-side as **placement collapsing** — samples/branches collapsing in the topology (the
indel path is currently used to debug sample placements visually in the local web view, and
that is where the collapsing shows up).

Important: this is an **upstream extraction** problem, **not** an AppView loader or age-engine
problem. Mechanically the indel artifact loads cleanly now — with the reverse-polarity
monophyletic fix (Issue 1) the deep backbone counts (CT 286/293, F 162/163, BT 8/8) and the
+10,560 indel variant-links load without error. So once the extraction is corrected the indel
tree should drop in without further loader work.

Until the extraction is validated we are **staying on the SNP artifact**
(`chrY.ftdna.refined.ingest.json`). The SNP artifact tracks the latest export format (same
flag schema). Workflow when iterating on the indel build: load the indel artifact only to
eyeball placements in the local web view; revert to the SNP artifact (reload + branch-age
recompute) before trusting any ages.

## Provenance

- Deep-backbone SNP reality was spot-checked in the joint VCF: e.g. A1a (A-M31) stem SNPs
  are GQ=99, normal DP, ~95% clean AC=5 synapomorphies — i.e. the data is real; the *flag*
  was wrong, not the calls.
- A-M31 ages to formed ~119 kya / TMRCA ~12 kya, matching YFull — confirming the deep
  spine is correctly calibrated once the backbone SNPs are allowed to count.
