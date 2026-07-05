# 11 — Standardized Test Profiles (DTC coverage tiers)

## Motivation

Direct-to-consumer sequencing is **sold by yield (Gbases) and read configuration**, not
by depth. The vendor names are marketing-specific and ambiguous. YSEQ, for example:

| Read config     | Yield      | Product name |
|-----------------|-----------|--------------|
| 100 or 150 bp PE | 45 Gbases  | **WGS**      |
| 100 or 150 bp PE | 90 Gbases  | **WGS+**     |
| 100 or 150 bp PE | 150 Gbases | **WGS++**    |
| 500 bp SE        | (varies)   | **WGS500**   |

"WGS" doesn't say whether it's 100 bp or 150 bp; "WGS+" is meaningless across vendors.
Our `testType` field currently carries these opaque strings (`WGS`, `EXOME`, …), so the
Coverage Benchmarks report can't group or compare like-for-like across labs.

We standardize to an **explicit, vendor-neutral label** that encodes read length and
yield directly:

```
WGS150 45Gbases
```

## The standardized label

Two grammars — short-read (fixed-length Illumina-class) and long-read (variable-length
HiFi / ONT), because read length is meaningless for long reads.

**Short-read:**
```
WGS<readLen> <G>Gbases
```
- `readLen` — nominal read length in bp (100, 150, 250, 500, …), bucketed from the
  measured mean.
- `<G>` — total sequenced yield in Gbases, snapped to the nearest advertised tier.
- Single-end vs paired-end is not part of the label text (500 bp is conventionally SE),
  but is retained as a structured attribute.

**Long-read (special case):**
```
<ReadType> <G>Gbases
```
- `ReadType` ∈ { `HiFi`, `ONT` } (optionally `CLR` for legacy PacBio continuous-long-read;
  `PacBio` when the platform is PacBio but the generation can't be resolved).
- No read-length component — long reads are inherently variable-length.

**Targeted Y (special case):**
```
<Product>
```
- `Product` ∈ { `BigY-700`, `BigY-500`, `Y-Elite`, `Y-Prime`, `TargetedY` }.
- **No yield component.** Targeted Y is a hybrid-capture enrichment panel, not a depth/yield-sold
  WGS product — the vendor product *is* the cohort dimension, so it stands alone as the type token.
  (`mtFull` full-mtDNA sequencing collapses to `mtDNA`, likewise product-only.)
- Chips, SNP packs, and Sanger panels are not yield-based sequencing and get **no** standardized
  label — the raw `test_type` / display name is kept.

### Reference mapping (YSEQ + long-read)

| Vendor product | Read config    | Yield  | **Standard label**            |
|----------------|----------------|--------|-------------------------------|
| WGS            | 150 bp PE      | 45 Gb  | `WGS150 45Gbases`             |
| WGS            | 100 bp PE      | 45 Gb  | `WGS100 45Gbases`             |
| WGS+           | 150 bp PE      | 90 Gb  | `WGS150 90Gbases`             |
| WGS++          | 150 bp PE      | 150 Gb | `WGS150 150Gbases`            |
| WGS500         | 500 bp SE      | *G*    | `WGS500 <G>Gbases`            |
| (PacBio HiFi)  | HiFi           | *G*    | `HiFi <G>Gbases`              |
| (ONT)          | ONT            | *G*    | `ONT <G>Gbases`               |
| FTDNA Big Y-700 | 150 bp PE (Y capture) | — | `BigY-700`                 |
| FTDNA Big Y-500 | 100 bp PE (Y capture) | — | `BigY-500`                 |
| Full Genomes Y Elite | Y capture        | — | `Y-Elite`                  |
| YSEQ Y Prime   | Y capture      | —      | `Y-Prime`                     |
| (targeted Y, vendor unknown) | Y capture | — | `TargetedY`               |

The canonical label is a **superset** of the marketing name: `WGS150 45Gbases`
disambiguates `WGS` (which YSEQ leaves as 100-or-150), and it is directly comparable
across vendors.

## Derivation algorithm

Inputs (all per **sequencing run**, see the gap analysis for provenance):

| Input        | Meaning                                    |
|--------------|--------------------------------------------|
| `platform`   | ILLUMINA / PACBIO / NANOPORE / …           |
| `readType`   | SHORT / HIFI / CLR / ONT  *(new)*          |
| `layout`     | PAIRED / SINGLE                            |
| `meanReadLen`| mean read length (bp)                      |
| `totalBases` | total sequenced yield (bp)  *(new)*        |

Steps:

0. **Targeted / non-sequencing short-circuit (by `test_type` code).** A targeted-Y product
   emits its product token with **no yield** (`BIG_Y_700 → BigY-700`, `BIG_Y_500 → BigY-500`,
   `Y_ELITE → Y-Elite`, `Y_PRIME → Y-Prime`, `TARGETED_Y → TargetedY`); full-mtDNA products
   collapse to `mtDNA`. Chips / SNP packs / panels (`ARRAY_*`, `YDNA_SNP*`, `YDNA_PANEL*`)
   return **no** standardized label (caller keeps the raw `test_type`). Everything else falls
   through to the yield-based WGS/long-read grammar below.
1. **Classify short vs long.** Long-read if `readType ∈ {HIFI, CLR}` or the `test_type`
   code / `platform` says PacBio/Nanopore, or `meanReadLen > 1000`; else short-read.
   (`readType` wins when present — it's the only way to tell HiFi from CLR; an explicit
   `SHORT` pins short-read.)
2. **Bucket the yield.** `G = totalBases / 1e9`.
   - **Short-read WGS** snaps to the **nearest marketed depth tier**
     `[15, 30, 45, 90, 150, 300]` Gbases — at ~3 Gb/1× these are 5× / 10× / 15× / 30× /
     50× / 100×, the depths the labs actually sell. Always the nearest (no "in between"
     bucket), so one product is one cohort: Dante's 30× lands on `90Gbases` whether it
     measured 92 or 101 Gb, instead of fragmenting across 90/100. Non-marketed measured
     values (60/100/200) are deliberately **not** tiers.
   - **Exome and long-read** aren't sold in those tiers, so they show the measured yield
     rounded to the nearest 5 Gbases.
   - Yield absent → the label simply omits it.
3. **Short-read:** bucket `meanReadLen` to the nearest of `[100, 150, 250, 300, 500]`
   within **±15 bp**, else round to the nearest 10 → `readLen`. Emit
   `WGS{readLen} {G}Gbases` (`WES{readLen} …` for exome; read length absent → just `WGS`).
4. **Long-read:** map `readType`/code/`platform` → `HiFi` (PacBio CCS), `CLR` (PacBio
   continuous), `ONT` (Nanopore), or `PacBio` (platform PacBio, generation unknown). Emit
   `{ReadType} {G}Gbases`.

The ladders and tolerances are the only tunables; everything else is mechanical. The
algorithm lives in **one shared pure function** — `du_domain::testprofile::standardized_label`
(shared crate) — so the AppView (display/grouping) and Navigator (pre-labelling) never diverge.
It classifies off the stable `test_type` **code strings** both sides publish/consume, so it needs
no dependency on the desktop test-type catalog.

## What we have vs. what's missing

Per-run inputs and their current status on the published records:

| Input        | Source (published today)                              | Status |
|--------------|--------------------------------------------------------|--------|
| `platform`   | `sequencerun.platformName`                             | ✅ present |
| `layout`     | `sequencerun.libraryLayout`                            | ✅ present |
| `meanReadLen`| `sequencerun.readLength` (mean)                        | ✅ present |
| **`totalBases`** | — (only `totalReads` is published)                 | ❌ **missing** |
| **`readType`**   | — (`platformName` can't tell HiFi from CLR)        | ❌ **missing** |

Two genuine gaps. Both are **derivable at the edge from data Navigator already
computes** — it just isn't published.

### Gap 1 — yield (`totalBases`)

The label's Gbases figure is the whole point, and it is not published. `totalReads ×
readLength` is a rough proxy but drifts (dedup, adapter trimming, variable read length
for long reads), so it should not be reconstructed downstream.

Navigator already has the exact figure: `navigator-analysis` `ReadMetrics` holds the
full `read_length_histogram` (`BTreeMap<u32,u64>`), so the exact yield is
`Σ length × count`. (`total_reads × mean_read_length` is an acceptable fallback for
fixed-length short reads.)

### Gap 2 — read type (`readType`)

`platformName` distinguishes Illumina / PacBio / Nanopore but **not HiFi from CLR**,
which the long-read label requires. Navigator already sees the signal:
`navigator-analysis/src/library_stats.rs::detect_platform_from_qname` recognizes PacBio
and matches the `…/ccs` qname suffix (its own tests assert
`m84005_230101_000000/1234/ccs → PacBio`) — a `ccs` suffix **is** HiFi. ONT is implied
by the Nanopore platform; simplex/duplex (if wanted) comes from the basecaller/qname.

## The ask — lexicon additions for Navigator

Add two fields to **`com.decodingus.atmosphere.sequencerun`** (read-level stats belong
on the run record; the alignment record joins to it). Both are `Option`al and
`skip_serializing_if` empty, so older records still validate.

```jsonc
// com.decodingus.atmosphere.sequencerun › record.properties
"totalBases": {
  "type": "integer",
  "description": "Total sequenced yield in base pairs (Σ read_length_histogram). The DTC 'Gbases' figure = totalBases / 1e9."
},
"readType": {
  "type": "string",
  "description": "Read chemistry/mode, distinguishing HiFi from CLR and short from long reads.",
  "knownValues": ["SHORT", "HIFI", "CLR", "ONT_SIMPLEX", "ONT_DUPLEX"]
}
```

Shared-type changes (`du-domain::fed::SequenceRunRecord`, the single source of truth for
publisher + consumer):

- add `pub total_bases: Option<i64>` and `pub read_type: Option<String>`
  (`#[serde(rename_all = "camelCase")]`, `skip_serializing_if = "Option::is_none"`);
- extend `SequenceRunRecord::new` (or add a `with_read_profile(total_bases, read_type)`
  builder) so Navigator can populate them.

How Navigator sources each:

| Field        | Navigator source |
|--------------|------------------|
| `totalBases` | `ReadMetrics.read_length_histogram` → `Σ len × count` (exact); fallback `total_reads × mean_read_length` |
| `readType`   | `library_stats::detect_platform_from_qname` + `ccs` suffix → HIFI; PacBio non-CCS → CLR; NANOPORE → ONT_*; else SHORT |

## AppView side — ✅ implemented (migration `0057`)

Depends on `decodingus-shared` `67091c8`, which finalized the `testprofile` module — `372f0e4`
had declared `pub mod testprofile;` but never staged the file, so it didn't compile; `67091c8`
adds it. The `rust/Cargo.toml` shared-crate rev is bumped to `67091c8`.

1. **Mirror** — migration `0057` adds `total_bases BIGINT`, `read_type TEXT`, and the derived
   `test_profile_label TEXT` (+ index) to `fed.sequencerun`; ingest stores all three
   (`du-jobs/jetstream.rs` `build_sequencerun`, `du-db/fed/core.rs` struct + upsert).
2. **Classify at ingest, not read** — `standardized_label(&RunProfile{…})` is called once in
   `build_sequencerun` and the result **persisted to the column**. (The design first said "read
   time", but the coverage benchmark aggregates in SQL and can't call a Rust fn, so we store it —
   the classification *logic* still lives only in the shared crate.) A ladder/tolerance tweak
   needs a re-publish or a backfill run-once.
3. **Surface** —
   - the per-sample report shows the label in the test-type column, `COALESCE`-ing back to the
     raw code (`du-db/biosample.rs` run + coverage queries, `du-web/samples.rs`);
   - the **Coverage Benchmarks** report groups/filters/lists on
     `COALESCE(sr.test_profile_label, sr.test_type)` (`du-db/coverage.rs`), so WGS150 45Gbases
     vs WGS100 45Gbases are distinct, comparable cohorts.

Tests: `du-jobs` `sequencerun_classifies_standardized_test_profile_label`;
`du-db/tests/contig_benchmarks.rs::coverage_groups_by_standardized_profile_label`.

No new coverage math is needed — this is a labelling layer over the existing run/coverage records.
Additive + NULL-safe: labels stay `NULL` (readers fall back to raw `test_type`) until Navigator
publishes `totalBases`/`readType` and re-publishes runs.

## Backward compatibility

Records published before these fields exist have no `totalBases`/`readType`. The AppView
degrades gracefully:

- **Short-read, no yield** → fall back to `totalReads × readLength`; if `totalReads` is
  also absent, show the raw `testType`.
- **Long-read, no `readType`** → HiFi is indistinguishable from CLR, so show
  `PacBio <G>Gbases` / `ONT <G>Gbases` from `platformName` until the run is re-published.

Re-publishing a run (Navigator recomputes cheaply from the cached `ReadMetrics`)
upgrades it to the exact label.

## Status

**Shared + Navigator: implemented.**

- **Shared (`decodingus-shared`)** — `du_domain::testprofile::standardized_label` (the pure
  function above, with unit tests); `SequenceRunRecord` gains `total_bases: Option<i64>` +
  `read_type: Option<String>` with a `with_read_profile(..)` builder (both
  `skip_serializing_if` empty, so older records still validate).
- **Navigator (`DUNavigator`)** —
  - `totalBases`: `ReadMetrics::total_bases()` (`Σ len × count` over `read_length_histogram`,
    falling back to `total_reads × mean_read_length`), persisted via `set_read_stats`.
  - `readType`: inferred per-read in `library_stats` (`/ccs` suffix → HIFI, PacBio non-CCS →
    CLR, Nanopore → ONT_SIMPLEX/DUPLEX, else SHORT), persisted via `set_library_stats`.
  - `sequence_run` migration `0032` adds both columns; `SequenceRun` carries them and exposes
    `standardized_label()`; the publish path attaches them via `with_read_profile`; older runs
    backfill `total_bases` from a cached `read_metrics` artifact on next load.
  - The Data Sources run card shows the standardized label under the run title.
  - **Batch backfill for existing workspaces** — `navigator backfill-profiles [--rescan]
    [--project NAME] [--json]` populates both fields across every run in the DB without a
    re-analysis: `total_bases` from cached read-metrics, `read_type` from platform/test-type
    inference (and, with `--rescan`, a bounded read-name scan to tell HiFi from CLR on
    generic-`WGS` PacBio runs).

**AppView: remaining follow-up** (the "AppView side" section above) — mirror the two columns
onto `fed.sequencerun`, parse `totalBases`/`readType` in the Jetstream ingest, and group the
Coverage Benchmarks report by the standardized label. Depends on a published shared-crate rev.
