# CNCB / GSA sequence-file linking — design

**Status:** proposal · **Date:** 2026-07-10 · **Author:** recon by Claude w/ James

Adds a second external archive alongside ENA/NCBI for enumerating and linking raw
sequencing files to academic biosamples. The China National Center for
Bioinformation (CNCB) National Genomics Data Center (NGDC) is now the deposition
target for a growing body of East/Central-Asian popgen papers (the trigger here:
`GVM000900`, West China Hospital, 166 Central Asians, BioProject `PRJCA032194`).

This doc scopes what we can link, how it maps onto our existing
`crawl-project` / `sequence_library` machinery, and the one structural change the
CNCB source forces (source is no longer implicitly "ENA").

---

## 1. Background: how CNCB is shaped

CNCB/NGDC is a family of sub-archives bridged by a single **BioProject**
accession (`PRJCA…`, their `PRJEB/PRJNA` analog). The tiers that matter to us:

| CNCB resource | Accession | Holds | Our analog | Linkable? |
|---|---|---|---|---|
| BioProject | `PRJCA…` | project umbrella | PRJEB/PRJNA | bridge only |
| **GSA** | `CRA`(study)/`CRX`(exp)/**`CRR`(run)**/`SAMC`(sample) | **open raw reads** (FASTQ/BAM) | ENA/SRA | **yes, anonymous** |
| GSA-Human | `HRA…` | controlled human raw reads | dbGaP/EGA | no — DAR + aspera key |
| GVM | `GVM…` | merged variant VCF/FASTA | dbSNP / joint VCF | open ones only; not reads |
| OMIX / GWH / OBIA | — | processed / assemblies | — | out of scope |

**Two-tier reality.** Only the **open GSA (`CRA`)** tier is automatically
linkable. GSA-Human (`HRA`) and controlled-access GVM are DAR-gated (like
dbGaP/EGA): we can record the accession and a "controlled, DAR required" status,
but cannot fetch files. The linker must handle both — the happy path *and* the
metadata-only case — not assume every CNCB record yields files.

### The `PRJCA032194` probe (why the two-tier handling is mandatory)

Probed 2026-07-09. As of now this specific project exposes **nothing linkable**:

- `getRunInfoByCra?searchTerm=PRJCA032194` and `getRunInfo` → **header-only CSV,
  zero run rows** (no released GSA runs).
- GSA-Human "matches" (`HRA000087/150`, `CRA000112`) were **template-boilerplate
  example accessions** — a nonsense control search returns the same ones. No real
  `HRA` study is tied to the project.
- Only artifact on file is the controlled, embargoed **GVM VCF `GVM000900`**
  (genotypes, not reads; release **2026-11-11**).

So `PRJCA032194` is a controlled + embargoed + VCF-only record — the exact class
where the linker must degrade to "record accession + status," not file ingest.
Worth a re-probe after 2026-11-11.

---

## 2. The programmatic surface (GSA open tier)

No clean REST/JSON like ENA's `filereport`; it's POST-form → CSV, plus a
deterministic file host. Reference implementation:
[`BioOmics/iSeq`](https://github.com/BioOmics/iSeq) (`bin/iseq`).

**Metadata (the `filereport` analog).** POST form-encoded, returns a ~25-column
CSV, **one row per file** (not per run — runs span multiple rows, grouped by the
`Run` column):

```
POST https://ngdc.cncb.ac.cn/gsa/search/getRunInfoByCra
     searchTerm=<CRA>&totalDatas=9999&downLoadCount=9999
GET  https://ngdc.cncb.ac.cn/gsa/search?searchTerm=<CRR|CRX>   # resolve run/exp → parent CRA
```

CSV columns (header verified live):

```
Run, Center, ReleaseDate, FileType, FileName, FileSize, Download_path,
Experiment, Title, LibraryName, LibraryStrategy, LibrarySelection,
LibrarySource, LibraryLayout, InsertSize, InsertDev, Platform,
BioProject, BioSample, SampleType, TaxID, ScientificName, SampleName,
Submission, Organization
```

Everything we need is in-band: `Download_path` gives the file URL directly (no
HTML scrape of `browse/<CRA>/<CRR>` needed, unlike iSeq), plus `BioSample`
(`SAMC…`), `Platform`, `LibraryLayout`, `FileType`, `FileSize`.

**File URLs** — deterministic, multi-mirror:

```
https://download.cncb.ac.cn/gsa<N>/<CRA>/<CRR>/<CRR>_f1.fq.gz    # HTTPS (store this)
ftp://download.big.ac.cn/gsa<N>/<CRA>/<CRR>/<CRR>_f1.fq.gz       # FTP mirror
+ HuaweiCloud / Qtrans accelerator mirrors when present
```

`<N>` is a volume segment (`gsa`, `gsa2`, `gsa3`, …) — **do not hardcode**; take
it from the CSV `Download_path`.

**Checksums** — no per-row MD5. One study-level manifest:
`https://download.cncb.ac.cn/gsa<N>/<CRA>/md5sum.txt` (filename→md5). One fetch
per study, index by `FileName`.

### Caveats vs ENA

- POST-form + CSV + a separate md5 manifest — no single JSON call.
- Endpoint already renamed once (`getRunInfo` → `getRunInfoByCra`, Sep 2024) —
  pin endpoint paths in config.
- One row **per file**; paired FASTQ = two rows sharing a `Run`. (ENA gives one
  row per run with `;`-joined files.) Grouping is `Run → Sample`.
- Download hosts are CN-resident; expect slow HTTPS. We only *store* URLs, we
  don't fetch bytes, so this is a curator/end-user concern, not ours.
- No credentials for open GSA. GSA-Human needs an aspera key + approved DAR.

---

## 3. How it maps onto our existing machinery

We already do exactly this for ENA in `crawl_project.rs`
(`du_external::ena` → group runs by sample → `biosample::upsert_by_accession`
→ `publication::link_biosample` → `sequence::ingest_libraries`). CNCB reuses the
whole write path; only the *fetch + parse* and the *source label* change.

### 3.1 New: `du-external/src/cncb.rs`

Mirror `ena.rs`. A `GsaClient` with:

- `study_runs(cra: &str) -> Vec<GsaRunRow>` — POST `getRunInfoByCra`, parse CSV
  by header (robust to column reorder, same as `ena::parse_run_report`).
- `resolve_cra(run_or_exp: &str) -> Vec<String>` — for `CRR`/`CRX`/`PRJCA` inputs.
- `md5_manifest(cra, volume) -> HashMap<FileName, Md5>` — fetch + parse
  `md5sum.txt` once per study.
- `GsaRunRow` fields mirror the CSV columns above. Parsing pure + unit-tested
  against a captured CSV fixture; HTTP is a thin wrapper. (One row per file →
  the client can pre-group into runs, or leave grouping to the job as ENA does.)

### 3.2 Changed: `sequence::ingest_libraries` — parametrize the source

Today the JSONB provenance is **hardcoded `"source": "ENA"`**
(`sequence.rs`, the `atproto` slot):

```rust
.bind(json!({ "source": "ENA", "run_accession": lib.external_run_ref }))
```

Add a `source: &str` (or a small `enum SeqSource { Ena, Gsa }`) to
`NewSeqLibrary` (or the `ingest_libraries` call) so GSA runs record
`{"source": "GSA", "run_accession": "CRR…"}`. This is the one required change to
shared code; everything downstream (`biosample::report` read path, the sidecar
`http_locations`/`checksums` JSONB shapes) is source-agnostic already.

### 3.3 Changed: `crawl_project.rs` — dispatch by accession shape

`crawl_one_accession` / `crawl_pending` currently assume ENA. Options:

- **(A, preferred) Dispatch inside the existing job.** Detect archive by
  accession prefix — `PRJEB/PRJNA/ERP/SRP` → ENA path; `PRJCA/CRA` → GSA path —
  and route to the matching client. Keeps one `crawl-project` command and one
  pending-study drain; both clients feed the same `group-by-sample → upsert →
  link → ingest` core. Factor that core to take a
  `Vec<(sample_acc, Vec<NewSeqLibrary>)>` so ENA and GSA just produce it
  differently.
- (B) A parallel `crawl-project-cncb` run-once command. Less code churn now,
  duplicates the drain loop and the study-table plumbing. Reject unless (A)
  proves awkward.

### 3.4 Required migration: extend `pubs.study_source`

`study::upsert_by_accession` casts its source `::pubs.study_source`, and that
enum is `('ENA', 'NCBI_BIOPROJECT', 'NCBI_GENBANK')` (mig `0006_pubs.sql`) — **no
GSA value**. A `CRA`/`PRJCA` study insert would fail the cast. Add a migration:

```sql
ALTER TYPE pubs.study_source ADD VALUE IF NOT EXISTS 'CNCB_GSA';
```

(`ADD VALUE` can't run inside the same txn that uses it, and can't be dropped —
standard enum-extension caveats; add it in its own migration.) The accession
*string* itself is fine — `genomic_study.accession` is verbatim + unique, no
prefix logic. This plus the `sequence` source param (§3.2) are the two required
data-layer changes; `sequence_library` provenance is free JSONB (no migration).

### 3.5 Biosample source — verified prefix-agnostic ✓

Crawl-created samples stay `SAMPLE_SOURCE = "EXTERNAL"` (public/academic), same
as ENA. The CNCB `SAMC…` BioSample accession goes in via
`upsert_by_accession(pool, samc_acc, "EXTERNAL", None)`. **Verified**
(`biosample.rs`): the upsert stores `accession.trim()` verbatim, unique on
`accession`, with no `SAM[END]`/prefix assumption anywhere in the write or report
read path; `core.biosample_source` already includes `EXTERNAL`. `SAMC…` needs no
change. (Open question #3 — resolved.)

---

## 4. Controlled tier (GSA-Human / GVM-controlled)

Out of scope for automated file ingest. Minimum useful handling:

- When a crawl resolves a BioProject to only an `HRA` study or a controlled
  `GVM`, **record the accession + a `controlled: true, dar_required: true`
  marker** on the study/publication so a curator knows a manual DAR is the gate
  (analogous to how we'd treat dbGaP/EGA).
- Do **not** attempt aspera/credentialed fetch. If we ever pursue it, it's a
  separate manual-curation workflow (register → DAR to the study's DAC → aspera
  key), not a job.

---

## 5. Scope / cut list

**In scope (first slice):**
- `du-external::cncb::GsaClient` (getRunInfoByCra CSV + md5 manifest), unit-tested
  against a captured fixture.
- Migration: `ALTER TYPE pubs.study_source ADD VALUE 'CNCB_GSA'` (§3.4).
- `sequence::ingest_libraries` source parametrization (`ENA` | `GSA`).
- `crawl_project` dispatch-by-prefix, GSA open path reaching `ingest_libraries`.
- Controlled-tier: record accession + DAR marker, no fetch.

**Deferred / out:**
- GSA-Human aspera/DAR fetch (manual curation, not a job).
- GVM variant-VCF ingestion (variant tier — separate from read linking; overlaps
  ybrowse/reconcile territory, not this doc).
- OMIX/GWH/OBIA.
- A standalone ops python driver — the Rust `crawl-project` job is the driver;
  no `link-gsa-sequence-files.py` needed (the ENA python script predated the job).

---

## 6. Open questions / verify before building

1. **Re-probe `PRJCA032194` after 2026-11-11** — does an open `CRA` study appear,
   or does it stay HRA/GVM-controlled? Decides whether the trigger paper is ever
   auto-linkable or is purely a controlled-tier example.
2. **Paired-FASTQ grouping** — confirm on a real multi-file `CRA` that rows share
   the `Run` value and that `_f1/_r2` (or `_1/_2`) is the reliable mate signal.
3. ~~**`biosample` accession namespace** — verify `SAMC…` stored verbatim.~~
   **Resolved (2026-07-10):** prefix-agnostic, `EXTERNAL` source exists, no change
   (§3.5). Verifying this also surfaced the required `study_source` migration (§3.4).
4. **Rate/politeness** — reuse the ENA `REQUEST_GAP` (150ms) + bounded batch;
   confirm CNCB tolerates it (CN latency may want a longer gap).
5. **Endpoint stability** — the `getRunInfo*` rename history argues for
   config-pinned endpoint paths + a fixture-refresh check in CI.
