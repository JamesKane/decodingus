# Design-Doc ↔ Rust Triage Report

**Started:** 2026-06-07. **Purpose:** walk the original (pre-rewrite) planning
design docs one by one, compare each against the actual Rust implementation, and
record a triage verdict + recommended action **for later action** (nothing is
changed by this report itself).

**Scope:** the 11 original docs in `documents/planning/` (excludes the new
`d1`–`d5` + `design-roadmap-rust-rewrite.md`, which are current). The
`documents/proposals/` set (Bucket B) is out of scope here.

**Verdict legend**
- ✅ **Doc current** — matches the code; no action.
- 📝 **Update doc** — code is the source of truth; doc is stale/drifted.
- 🔧 **Make code compliant** — doc is the intended design; code should change.
- ⚖️ **Split** — some of both (note which parts).
- 🗑️ **Deprecate/supersede** — doc describes a dropped or superseded approach.

## Execution log (2026-06-07)

- **Reconciliation headers added** to the kept docs (#2, #3, #5, #6, #7, #8, #11);
  #1 already had one.
- **Removed** the three superseded docs (#4 jsonb-consolidation, #9 ibd-matching,
  #10 appview-pds-backfeed) per "if superseded, just remove it." Their inbound
  references inside `documents/planning/` were rewired (→ D1/D3 for IBD; "realized
  in mig 0002/0004" for JSONB; "dropped, member-claim carve-out under D1/D4" for
  backfeed). The PDS-backfeed **member-claim carve-out** is preserved in the
  design-roadmap gap catalog + D2/D5.
- **Still to do (later passes):** references to the removed docs remain in
  `documents/atmosphere/` (00-Overview, 04-Ancestry-Records, 06-IBD-Matching-Records,
  Executive-Summary) and `documents/proposals/` (branch-age-estimation,
  group-project-system) — clean up when those doc sets are triaged.

## Status index

| # | Doc | Verdict | Action owner |
|---|-----|---------|--------------|
| 1 | variant-naming-authority.md | ✅ Doc current | — (optional code nicety) |
| 2 | tree-versioning-system.md | 📝 Update doc | docs |
| 3 | openalex-publication-discovery.md | 📝 Update doc (light) | docs |
| 4 | jsonb-consolidation-analysis.md | 🗑️ REMOVED (realized) | done |
| 5 | multi-test-type-roadmap.md | ⚖️ Split (reconcile built; rest is forward = D7) | docs + forward |
| 6 | sequencer-lab-inference-system.md | ⚖️ Split (schema built incl. consensus; logic forward = D8) | docs + forward |
| 7 | haplogroup-discovery-system-overview.md | ✅ Doc current (minor terminology) | docs (light) |
| 8 | haplogroup-discovery-system.md | ⚖️ Split (curator/pool half built; engine forward = D6; arch evolved) | docs + forward |
| 9 | ibd-matching-system.md | 🗑️ REMOVED (→ D1 + D3) | done |
| 10 | appview-pds-backfeed-system.md | 🗑️ REMOVED (dropped; 1 carve-out) | done |
| 11 | post-mvp-roadmap.md | 📝 Update / reconcile with design-roadmap | docs |

---

## 1. variant-naming-authority.md — ✅ Doc current

**Compared against:** `du_db::naming` (`crates/du-db/src/naming.rs`),
`du_db::variant`, migration 0016, `/curator/naming`, `/api/v1/variants/export.gff`.

**Finding:** already reconciled — the doc carries an accurate `Implementation
status (2026-06, Rust)` header and the code matches it: `DU` sequence
(`core.next_du_name()`), lifecycle `UNNAMED→PENDING_REVIEW→NAMED`, mint preserves
the prior name as a `common_names` alias, local same-coordinate dedup
(`dedup_by_coordinates`, GRCh38), curator queue with modes, GFF3 propagation
export. The two "Not yet" items are genuine future work, not violations:
- **Live external (YBrowse/ISOGG/YFull) dedup lookup** — not built; "check
  external names" is a manual curator step.
- **Unnamed variants in the public API/domain** — `du-domain::Variant.canonical_name`
  is still `String`; the code sidesteps it by filtering `canonical_name IS NOT NULL`
  on every public path, so unnamed variants never flow through that type.

**Latent edge (low severity):** `du_db::variant::get_by_id` selects
`canonical_name` into a non-`Option<String>` without a NULL filter, so
`GET /api/v1/variants/{id}` on an *unnamed* variant id would 500 on row decode.
Unreachable via normal UX (unnamed ids aren't surfaced anywhere public).

**Recommended action (later, optional):** harden `get_by_id` to tolerate/404 a
NULL `canonical_name`. Full compliance (Option in `du-domain::Variant`) is a
cross-repo change the doc already defers. No doc change needed.

---

## 2. tree-versioning-system.md — 📝 Update doc

**Compared against:** `tree.change_set` / `tree.tree_change` (mig 0001 enum +
0003), `tree.wip_*` staging tables, `tree.curator_action` (mig 0010),
`du_db::change_set`, `routes/change_sets.rs` + `routes/reviews.rs` + `/manage/*`
(`routes/versioning.rs`).

**Finding:** the *design* is correct and implemented — the doc recommends **Option
B (overlay change-sets)** and that's exactly what Rust built (`tree.change_set`
+ `tree.tree_change`; **no `tree.tree_version` table** — Option A was not taken;
audit in `tree.curator_action` as described). But every concrete specific is
Scala-era and has drifted:

- **Code/types:** Scala `case class` / `Future` / `trait TreeVersioningService` /
  `*.scala.html` → Rust `du_db::change_set` + axum routes + Askama.
- **Schema:** `SERIAL` / `VARCHAR CHECK(...)` / `TIMESTAMP` / added
  `tree_version_id` columns → `BIGINT IDENTITY` / native enum
  `tree.change_set_status` / `TIMESTAMPTZ` / the existing **temporal**
  (`valid_from`/`valid_until`) model (no version-id columns).
- **API:** documented public `/api/v1/tree/change-sets` + `/api/v1/curator/changes/*`
  **do not exist**. Reality: `/curator/change-sets/*` + `/curator/reviews/*` (UI) and
  `/manage/change-sets/*` + `/manage/haplogroups/merge[/preview]` (machine). Change-sets
  are deliberately **not** in the public `/api/v1`.
- **Permissions:** granular `tree.version.*` → the single **`Curator`** role guard
  (Admin/TreeCurator/Curator).
- **Ambiguity handling (substantive evolution):** the doc describes a **file-based
  `ambiguity_report_path`** + an in-change-set review. Rust replaced this with the
  **`tree.wip_*` staging tables + a dedicated `/curator/reviews` resolution flow**
  (REPARENT/MERGE_EXISTING/DEFER), enacted by the change-set apply engine. The doc
  doesn't mention this layer at all.

**Recommended action (later):** **update the doc to match the code.** Add a
`Rust implementation status` reconciliation header (as variant-naming-authority.md
has), correct the schema/API/permissions specifics, and add a section on the
`wip_*` + `/curator/reviews` merge-review layer that superseded the file-report
approach. Keep the Option A/B rationale as historical design context. Cross-link
the refreshed user guide (`../curator-guide-tree-versioning.md`). No code changes
needed — the system is built and working.

---

## 3. openalex-publication-discovery.md — 📝 Update doc (light)

**Compared against:** `pubs.publication_candidate` + `pubs.publication_search_config`
(mig 0006), `du_db::publication` (`enabled_search_configs`, `upsert_candidate`,
`promote_candidate`, `review_candidate`), `du_jobs::publications`
(`publication-update`, `publication-discovery`), `routes/publications.rs`
(`/curator/publications`), `du_external::openalex`.

**Finding:** design is sound and **substantially implemented** — scheduled
discovery runs each enabled search config and upserts candidates; candidates
dedupe by `openalex_id`; the curator review queue (`/curator/publications`) does
**accept (promote to a reference) / reject / defer**. So the doc's Phase-1 "simple
curator review UI" (shown `[ ]`) is in fact **done**. Drift to fix:

- **Stale specifics:** Scala `OpenAlexService.scala` / `PublicationService`,
  **Pekko Quartz** cron, `public.users`, `SERIAL`, plural/unprefixed table names,
  and the `/api/private/publication-candidates/*` endpoints → Rust
  `du_external::openalex` + `du_db::publication` + the tokio scheduler +
  `pubs.publication_candidate` (singular) + the `/curator/publications` UI (no
  public candidate API).
- **Schedule:** documented weekly cron (Sun 02:00) → Rust runs **daily**
  (`Duration::from_secs(86_400)`), config-gated.
- **Phase status drift:** Phase 1 complete incl. the curator UI; **not built:**
  relevance scoring (Phase 2 — the `relevance_score` column exists but isn't
  computed), smart discovery (Phase 3), biosample-extraction hints (Phase 4), and
  the `publication_search_run` history/debug table.
- **Addition not in the doc:** the public **"suggest a paper"** on-ramp
  (`/references/submit`: DOI → OpenAlex resolve → candidate queue).

**Recommended action (later):** light doc refresh — Rust reconciliation header,
fix the schema/endpoint/scheduler specifics, correct the phase checkboxes, add the
`/references/submit` on-ramp, and keep relevance scoring + `search_run` as explicit
forward work. No code changes required.

---

## 4. jsonb-consolidation-analysis.md — 🗑️ Superseded (recommendations realized)

**Compared against:** migrations 0002 (`core.biosample.original_haplogroups`) and
0004 (`genomics` header + `sequence_file`, `alignment_metadata`).

**Finding:** this is a **pre-rewrite analysis** recommending 7 child-table → JSONB
consolidations, and the Rust redesign **implemented all of them** (mig 0004's
header explicitly enumerates the same moves):
- `sequence_file_checksum` / `_http_location` / `_atp_location` → `sequence_file`
  `checksums` / `http_locations` / `atp_location` JSONB ✓ (child tables gone)
- `alignment_coverage` / `pangenome_alignment_coverage` → `coverage` JSONB on the
  metadata tables ✓ — **with the recommended expression index**
  (`((coverage->>'meanDepth')::double precision)`)
- `biosample_original_haplogroup` / `citizen_*` → `core.biosample.original_haplogroups`
  JSONB ✓ (and the three biosample tables collapsed to one)
- bonus: scattered `at_uri`/`at_cid` → a single `atproto` JSONB ✓

Nothing to make compliant — the code already embodies (and slightly exceeds) the
analysis. Only nit: implemented coverage keys are camelCase (`meanDepth`,
`medianDepth`) alongside `percent_coverage_at_*x`, vs the doc's snake_case
proposal — cosmetic, no action.

**Recommended action (later):** treat as **historical/implemented** — add a short
"realized in the Rust redesign (mig 0002/0004)" note at the top, or archive it.
No code or design action.

---

## 5. multi-test-type-roadmap.md — ⚖️ Split (reconcile the built part; rest is forward = D7)

**Compared against:** `genomics.test_type_definition` + `genomics.coverage_expectation_profile`
(mig 0004), `core.data_generation_method` / `core.target_type` enums,
`sequence_library.test_type_id` FK, `du-domain` `DataGenerationMethod`/`TargetType`,
`fed.genotype` (mig 0012).

**Finding — built foundation (~Phase 1):**
- `genomics.test_type_definition` exists — **leaner** than the doc's spec: has
  code/display_name/category/vendor/target_type/expected_min_depth/supports_*/
  typical_file_formats/description, but **omits** `expected_target_depth`,
  `expected_marker_count`, `version`, `release_date`, `deprecated_at`,
  `successor_test_type_id`, `documentation_url`. Coverage thresholds live in a
  separate `genomics.coverage_expectation_profile` (not inline columns).
- `core.data_generation_method` (SEQUENCING/GENOTYPING) + `core.target_type`
  native enums; `du-domain` mirrors them. ✓
- `sequence_library.test_type_id` is a **native FK from the start** — the doc's
  Phase-1 "[ ] migrate the string column to an FK" is moot (no string column).
- **Seed data NOT loaded** — the table is empty in migrations (the doc shows seed
  as `[X]`; in Rust it's outstanding, per the design-roadmap "seed test_type_definition").

**Finding — not built (forward, = design-roadmap D7):** everything in Phases 2–6 —
`test_type_target_region`, `genotyping_test_summary` (local; partly shadowed by the
federated `fed.genotype` summary), `test_type_haplogroup_marker_coverage`,
`test_type_marker_intersection`; the `TestTypeService` + `/api/v1/test-types/*` and
`/api/v1/haplogroup-variants/*` APIs; chip-metadata ingest; **test-type-aware
haplogroup confidence**; cross-test-type IBD. Also tightly coupled to the (also
forward) haplogroup-discovery doc.

**Drift:** Scala throughout (Slick case classes, `Future` service traits,
`models.domain.genomics`), Pekko, removed `/api/private` endpoints — all need
restating in Rust terms when the forward parts are built.

**Recommended action (later):** **keep the doc as the forward design (D7) but
reconcile the built part** — add a Rust status header: Phase-1 schema is built
(note the leaner `test_type_definition` + separate `coverage_expectation_profile`
+ native `test_type_id` FK), seed data is still TODO, and Phases 2–6 remain
forward; restate their schema/services in Rust terms when picked up. The core
indexing principle (index Y/mt variants + summaries, never raw autosomal) is
correct and already matches the implemented federation posture. No code change
required now beyond (optionally) loading the test-type seed.

---

## 6. sequencer-lab-inference-system.md — ⚖️ Split (schema built incl. consensus; logic forward = D8)

**Compared against:** `genomics.sequencing_lab`, `genomics.sequencer_instrument`,
`genomics.instrument_observation`, `genomics.instrument_association_proposal`
(mig 0004), `fed.sequencerun.instrument_id` (mig 0012). No lab-lookup/consensus
code found (only `coverage.rs`/`fed::core` touch `instrument_*` for benchmarks).

**Finding — schema built (more than the roadmap's ~20% implies):** all four tables
exist, **including the two the doc marks as NEW/`[ ]`** — `instrument_observation`
and `instrument_association_proposal`. So the consensus data model is in place.
Schema deltas to reconcile:
- Tables are in `genomics`, native `BIGINT IDENTITY` (doc: `public.*`, `SERIAL`);
  `sequencing_lab` is leaner (no created/updated_at).
- **`sequencer_instrument` has no `lab_id` FK** and a different column set
  (`model_name`, `manufacturer`, `year_introduced`, `estimated_max_throughput`) —
  i.e. instrument↔lab is intended to resolve via observation→proposal→accept, not a
  static FK. The doc's proposed `sequencer_instrument` add-ons
  (`source`/`observation_count`/`confidence_score`/`last_observed_at`) are **not**
  present (that state lives in the proposal table instead).

**Finding — zero logic (forward = design-roadmap D8):** none of it is wired —
no `/api/v1/sequencer/lab` lookup, no `/api/v1/labs/{instrument-id}`, no Firehose
`instrumentObservation` ingestion, no consensus/confidence engine, no curator
instrument-proposal review UI. The "existing API endpoints / domain models" the doc
lists are **Scala-era and do not exist** in Rust. The consensus source in Rust is
`fed.sequencerun.instrument_id` (crowdsourced @RG id); the
`com.decodingus.atmosphere.instrumentObservation` lexicon + its `fed.*` mirror are
**not yet defined** (design-roadmap notes the record shape is TBD).

**Drift:** Scala/Slick/Tapir/Pekko throughout; "Current State" lists endpoints that
were never ported.

**Recommended action (later):** keep as the forward design (D8) but reconcile —
Rust status header: the **full schema (incl. consensus + proposal tables) is in
place; logic is unbuilt**; fix the schema specifics (genomics schema, no `lab_id`
FK, actual instrument columns, observation/proposal tables already present); note
the consensus source is `fed.sequencerun.instrument_id` and the observation
lexicon/mirror is still to define; restate services/APIs in Rust terms (axum +
utoipa; Firehose = the existing Jetstream consumer). Drop the "existing endpoints"
section. No code change required now.

---

## 7. haplogroup-discovery-system-overview.md — ✅ Doc current (minor terminology)

**Compared against:** `tree.proposed_branch*` / `tree.biosample_private_variant` /
`tree.discovery_config` (schema), `du_db::proposal`, `/curator/proposals`
(review/promote) + `/manage/curation/proposals` intake.

**Finding:** this is a **stack-agnostic conceptual overview** (discover → correlate
→ propose → review → evolve; evidence sources; thresholds; curator workflow;
privacy/visibility; federated model). It still describes the intended system
accurately, and the curator-review half it describes **is built**. No
implementation specifics to drift. Two minor nits:
- **Terminology:** "Firehose / real-time stream" → the implemented inbound path is
  the **Jetstream summary mirror** (`fed.*`); the credential-holding inbound
  firehose was dropped. The *concept* (Edge → PDS → stream → AppView discovery)
  still holds.
- **Auto-promotion** ("10+ samples → can be automatically accepted") is aspirational
  — curator accept is the gate today; the automated ingest→consensus engine is
  forward (= design-roadmap D6).

**Recommended action (later):** optional one-line note that ingestion is via the
Jetstream summary mirror (not a credential-holding firehose) and that
auto-promotion is a future option. Otherwise leave as-is.

---

## 8. haplogroup-discovery-system.md — ⚖️ Split (curator/pool half built; engine forward = D6; architecture evolved)

**Compared against:** `du_db::proposal` (`crates/du-db/src/proposal.rs`),
`/curator/proposals` (review/promote) + `/manage/curation/proposals` intake,
`tree.proposed_branch` / `_evidence` / `_variant`, `tree.biosample_private_variant`,
`tree.discovery_config`, `tree.wip_*`. *(Triaged from the doc's
Prerequisites/Architecture + the overview + the cross-references in
multi-test-type-roadmap.md + the confirmed schema/code, rather than a full read of
all 71 KB.)*

**Finding — done:**
- The doc's **prerequisite** (`variant-schema-simplification`: universal JSONB
  coordinates, parallel-mutation handling, JSONB aliases) is **implemented** in the
  Rust variant model.
- Schema is present (proposed_branch + evidence + variant, biosample_private_variant,
  discovery_config, wip_*).
- The **curator review/promote + proposal pooling** half is **built**:
  `proposal.rs` pools submissions by (proposed_name, parent) across submitters,
  tracking `evidence_count`/`submitter_count`/`confidence`/`status`; curators work
  `/curator/proposals` (review/promote); machine intake at `/manage/curation/proposals`.

**Finding — forward + architecture evolved (= design-roadmap D6):** the doc
specifies an **AppView-side pipeline** — *Private Variant Extraction* that parses
`HaplogroupResult.mismatchingSnps` from ingested biosamples (Citizen Firehose +
External upload) → groups → ProposedBranch → consensus detection. Rust **inverts the
ingestion model**: **Navigator (Edge) extracts the private variants and submits a
proposal; the AppView pools by submitter** — there is no AppView-side raw-extraction
from `fed.biosample`. This is consistent with the no-PII / edge-compute direction.
The automated consensus/Jaccard engine + auto-reassignment remain unbuilt (D6 — the
`du-domain` algorithm spec is the open design piece).

**Drift:** Scala/Slick/Tapir, `Firehose`, `/api/v1/discovery/proposals` +
`/api/v1/curator/proposals/{id}/accept` → Rust `/curator/proposals/*` +
`/manage/curation/proposals`.

**Recommended action (later):** keep as the forward design (D6) but reconcile
substantially — Rust status header (prereqs + schema + curator/pooling half done);
**document the ingestion-model change** (Edge-submits-proposals, not AppView-side
extraction from `mismatchingSnps`); restate endpoints/services in Rust terms; mark
the consensus/Jaccard engine + auto-reassignment as the remaining D6 work. No code
change required now.

---

## 9. ibd-matching-system.md — 🗑️ Superseded by D1 + D3

**Compared against:** `ibd.*` schema (mig 0007: `match_request`, `match_consent`,
`match_suggestion`, `ibd_discovery_index`, `ibd_pds_attestation`, `population_*`),
the new `d1-encrypted-edge-exchange.md` + `d3-ibd-matching-impl.md` (read in full
earlier). **No `du-db::ibd` code exists** (schema-only).

**Finding:** this is the **original (Scala/Tapir-era) IBD requirements** doc — it
invents its own crypto, key exchange, and P2P channel, and references the Java Edge
App. It is **explicitly superseded** by the two new docs we just added:
- **D1** generalizes its crypto/consent/channel into the shared `exchange.*`
  substrate (D1's own note: it "supersedes/generalizes the crypto + Edge-coordination
  sections of ibd-matching-system.md"; it also fixes the Ed25519-can't-ECDH gap).
- **D3** is the **Rust build spec** that "implements the requirements in
  ibd-matching-system.md on top of D1," folding `ibd.match_request`/`match_consent`
  into `exchange.*` and keeping the IBD-specific tables.

So the doc's value is now purely as **historical requirements**; the authoritative
design is D1 + D3. IBD itself is **unbuilt** (schema present, logic forward — D3
closes the Match track).

**Recommended action (later):** add a header marking it **superseded** — point
crypto/channel/key-exchange → `d1-encrypted-edge-exchange.md`, the Rust impl →
`d3-ibd-matching-impl.md`; keep the body as historical requirements (or archive).
No code change (build per D3 when the Match track is scheduled).

---

## 10. appview-pds-backfeed-system.md — 🗑️ Superseded/dropped (one open carve-out)

**Compared against:** `rust/README.md` + STATUS (federation is **outbound-only**),
the `[[atproto-federation-direction]]` decision (drop private firehose; use
permissions/OAuth + notify-fetch), the design-roadmap Q2. No backfeed code exists
(correctly absent).

**Finding:** the doc designs a **bidirectional AppView→PDS backfeed** that pushes
refined/derived data (haplogroup refinement, branch discovery, ancestral STR/TMRCA,
matches, lab inference) back into user PDSes. The Rust rewrite **dropped this
direction**: federation is an **outbound Jetstream summary mirror** (Navigator
publishes → `fed.*`) plus a notify-fetch posture; the inbound firehose + PDS-fleet
+ backfeed model is out of scope. So the doc describes a **non-chosen
architecture**.

**Open carve-out (don't fully delete):** the design-roadmap (Q2) flags that
**member-claim** custody (D2 §6 / D5) may need a *limited* AppView→PDS write — to
be decided under D1/D4. So the general backfeed is dropped, but the narrow
member-claim write is an open question.

**Recommended action (later):** mark **superseded/dropped** with a header (Rust =
outbound-only mirror + notify-fetch; no general backfeed), and record the single
open carve-out (limited member-claim write, decide under D1/D4). Keep as historical
/decision-input or archive. No code (correctly nothing built).

---

## 11. post-mvp-roadmap.md — 📝 Update / reconcile with the design-roadmap

**Compared against:** current build state (per docs #1–#10 above) and the new
`design-roadmap-rust-rewrite.md` (the current authoritative index).

**Finding:** this is the **old central roadmap** indexing the six subsystem docs
with a dependency graph + phased plan (A–F). It is **largely superseded** by
`design-roadmap-rust-rewrite.md`, which the new doc itself only calls a "pairs
with… feature sequencing" companion — but in practice the new roadmap is the
accurate one (it has the gap catalog, the two-track D1–D8 sequencing, and the
no-PII reconciliation). Specific drift:
- **Stale statuses:** Phase A (tree schema / `test_type_definition` /
  `sequence_file` JSONB) is correctly `[X]`, and OpenAlex candidate queue `[X]` —
  but it misses that the **curator proposal/review half**, **tree versioning**, and
  **multi-test + sequencer-lab schema** are now built; and its "In Progress /
  Planned" labels predate that.
- **Omits the entire collaboration/IBD-via-D1 platform** (D1–D5) — it still lists
  IBD as the standalone `ibd-matching-system.md` (now superseded by D1+D3).
- **Scala terms** throughout (Firehose, `PrivateVariantExtractionService`,
  `publication_candidates` plural, etc.).

**Recommended action (later):** **reconcile with `design-roadmap-rust-rewrite.md`**
— either demote post-mvp-roadmap to historical with a header pointing at the new
roadmap as authoritative, or refresh its status table + terminology and graft in
the D1–D5 platform track. Keep its still-useful bits (per-phase detail,
JSONB-distributed-across-phases plan, success metrics). No code action.

---

## Summary of verdicts

| Verdict | Docs |
|---------|------|
| ✅ Doc current | #1 variant-naming-authority, #7 discovery-overview (minor terminology) |
| 📝 Update doc | #2 tree-versioning, #3 openalex (light), #11 post-mvp-roadmap |
| ⚖️ Split (reconcile built + forward design) | #5 multi-test-type (D7), #6 sequencer-lab (D8), #8 discovery (D6) |
| 🗑️ Superseded / dropped | #4 jsonb-consolidation (realized), #9 ibd (→ D1+D3), #10 backfeed (dropped; 1 carve-out) |

**Cross-cutting themes**
- **No code is wrong.** Every verdict is "update the doc," never "make the code
  comply" — the Rust build is the source of truth; the pre-rewrite docs carry
  Scala/Slick/Tapir/Pekko/Firehose specifics, stale schemas/endpoints, and
  out-of-date status.
- **Recurring fixes:** add a "Rust implementation status" reconciliation header
  (as variant-naming-authority.md already has); swap Scala→Rust specifics; correct
  `/api/v1/*` + `/curator/*` + `/manage/*` route surfaces; replace granular
  `tree.version.*`/`*.permission` with the `Curator` role; "Firehose" → the
  outbound **Jetstream** summary mirror.
- **Two architecture evolutions to capture:** (a) discovery ingestion is
  **Edge-submits-proposals**, not AppView-side extraction (#8); (b) IBD crypto/
  channel is now the shared **D1 `exchange.*`** substrate (#9).
- **One open product decision:** the limited **member-claim** AppView→PDS write
  (#10), to be decided under D1/D4 (design-roadmap Q2).
- **Forward design that's still valid** lives in #5/#6/#8 (= design-roadmap
  D6–D8) and should be kept (reconciled), not discarded.
