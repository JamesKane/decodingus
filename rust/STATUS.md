# DecodingUs Rust rewrite — status & handoff

Living snapshot of the Play/Scala 3 → Rust port. Pairs with `README.md` (roadmap).
Last updated **2026-06-11** (this session: **the McDonald-2021 branch-age model built
out end-to-end** + the **T2T/Hallast Y reference-region pipeline** — all post-launch
catalog refinement, no change to the launch-critical path):
- **Y reference-region ingest** (`du-jobs/yregions`, `run-once yregions`) — loads
  T2T-CHM13v2.0 Y structural BEDs (AZF/DYZ, **amplicons v2**, **inverted-repeats v2**,
  chrXY sequence-class) into `core.genome_region` via `genome_region::upsert_by_key`
  + `prune_source_orphans` (full-snapshot sync). `du_db::variant::refresh_region_overlaps`
  flags low-confidence-for-placement variants (`annotations.region_overlaps`), consumed
  by `snp_graft` (`UnreliableAnchor` → curator review). (memory `yregions-ingest`.)
- **PDF branch-age engine** (`du_db::pdf`) — discretized age PDFs (poisson / gaussian /
  **mixture**, `multiply`=Eq 1, `convolve`=Eq 7, `gaussian_on`/`poisson_on` grid-param),
  replacing the inverse-variance shortcut.
- **SNP age = bottom-up tree propagation** (`age::propagate`, Eq 5–8, `HET_MASK`).
  **STR age = multi-step `P(g|m)`** (McDonald **Table 1** embedded + ω convolution
  fallback, `ystr`) → per-marker Poisson mixture → **tree-propagated**
  (`ystr::propagate_str` + §2.5.2 ancestral-motif reconstruction), retiring the
  star-phylogeny pooling.
- **COMBINED = direct PDF product** of the SNP / STR / genealogical terms (Eq 1) on a
  shared TREE grid — non-Gaussian shape preserved; disjoint terms fall back to the
  inverse-variance combine.
- **Hallast 2026 incorporation** — v2 BEDs + callable-mask validation; BEAST **0.76e-9
  cross-check clock** (`age::HALLAST_RATE`, not swapped for Helgason); genealogical
  calibration anchors (`scripts/seed-hallast-anchors.sql`, D1 TMRCA 19,450 ybp, model-
  dated). P9 palindrome **BLOCKED** on supplementary coords. (`documents/planning/
  y-preprint-hallast-2026-incorporation.md`.)
- **Real STR mutation rates** — `scripts/seed-str-mutation-rates.sql` (137 markers:
  Willems 2016 1000G MUTEA + 95% CI primary, YHRD gap-fill for core markers) replaces
  the `DEFAULT_STR_RATE` fallback; ω columns stay at the Ballantyne-derived global model.

Prior (2026-06-07): public per-sample report (`/sample/:slug`, mig 0022); static/footer
pages reconciled with legacy Scala; collaboration-platform design docs (d1–d5);
design-doc triage (superseded docs removed, rest reconciled).
Prior (2026-06-05): FTDNA Y-tree SNP-graft + `--reattach`; recurrent-link scrub;
mtDNA tree wired as an FTDNA RSRS foundation; ETL `--skip-tree` cutover option.

## TL;DR

The **spine is done and then some**: redesigned schema, data layer, public
HTML/HTMX surface, auth + curator tools, the full production ETL, the public JSON
API, tree versioning + merge, the SNP-anchored graft + its curator review UIs, the
YBrowse mirror→reconcile catalog pipeline (≈3M variants), federated **reporting**
(mirror **and** web endpoints), branch ages, and Y-STR signatures/prediction/age.

The launch-critical path is now just two things: **(1) the data cutover** — the
ETL has been **verified end-to-end against a real production dump** (2026-06-04,
363 MB / PG 15): all 34 aggregates reconcile, and the **`--skip-tree` + tree-init**
cutover flow is verified (prod→`decodingus_cutover`: tree empty, non-tree
aggregates reconcile, the multi-source tree builds into the empty namespace).
What's left is *executing* the cutover against live/final data (+ alias-aware mt
name resolution) — and **(2) the live AT Proto OAuth handshake** (the cross-host
"Edge joint test").
The remaining *feature* mass is post-launch: **haplogroup-discovery automation**,
**multi-test-type completion**, **IBD matching + the social layer**, and
**sequencer-lab inference** (the AppView coordinates IBD introductions, hosts the
social surfaces, and resolves instrument→lab for the Edge — only patronage/billing
is now fully out of scope). See "What's left".

## Layout

- **`/Users/jkane/Development/decodingus/rust`** — this workspace (AppView-only crates).
  - `du-db`, `du-external`, `du-web`, `du-jobs`, `du-migrate`
- **`/Users/jkane/Development/decodingus-shared/crates`** — shared crates, separate git repo.
  - `du-domain` (pure types + algorithms, incl. `merge`), `du-atproto`, `du-bio`
  - Pushed to `github.com/JamesKane/decodingus-shared`; consumed via **git deps
    pinned to a rev** in `rust/Cargo.toml` (Docker build unblocked — no sibling
    path dep needed). To update: push the shared repo, then bump `rev` (or switch
    to a pushed tag, e.g. `v0.1.0` — created locally, not yet pushed). For local
    co-dev against working-tree changes, add a `[patch]` back to the sibling paths.
- Legacy Scala app: `/Users/jkane/Development/decodingus` (parent dir). Navigator:
  `/Users/jkane/Development/scala/DUNavigator`.

## Local dev / how to run

Postgres runs under Apple `container` (name `du-pg`), reachable at its own IP
(no localhost forwarding):

```
DATABASE_URL="postgres://postgres:dev@192.168.64.2:5432/decodingus?sslmode=disable"
APP_SECRET="<any 32+ char string>"   # signs session cookies
```

- Run web: `DATABASE_URL=... APP_SECRET=... PORT=9000 cargo run -p du-web` (binary `decodingus`).
- Run jobs scheduler: `DATABASE_URL=... cargo run -p du-jobs` (binary `decodingus-jobs`).
  - **One-shot ops:** `decodingus-jobs run-once <job>` — `ybrowse` (full GFF3 stream +
    reconcile; needs `YBROWSE_GFF` [+ optional `YBROWSE_CHAIN_GRCH37/HS1`]),
    `reconcile` (re-derive `core.variant` from the loaded mirror without re-streaming),
    `yregions` (load the T2T-CHM13 Y reference-region BEDs + refresh region flags), and
    `branch-age` (recompute STR signatures + the combined branch ages).
- Tests: `DATABASE_URL=... cargo test -p du-db` (live-DB tests skip/pass if unset).
  - **Safe against any DB:** every du-db integration test now provisions a private,
    throwaway database via `du_db::testing::ephemeral_db` (migrated, dropped on Drop),
    so `cargo test` never touches the catalog `DATABASE_URL` points at.
  - `du-domain` tests need no DB (`cargo test -p du-domain`).
- Migrations auto-apply on web/ETL startup; the `du-db` migrations test also applies them.
- **Gotcha:** if a *committed* migration changes, recreate the dev DB
  (`decodingus`) — sqlx errors on a checksum mismatch (`VersionMismatch`).

### Databases in use
- `decodingus` — dev DB (migrations + live tests' base server for ephemeral DBs).
- `decodingus_legacy` — loaded from `scripts/mock-legacy.sql` (current-schema mock).
- `decodingus_etl` — ETL target (the migrate binary recreates/migrates it).

## What's done (✅)

- **Schema** — `migrations/0001–0023`. JSONB "document columns" (variant
  coordinates/aliases/**evidence**, biosample source_attrs/atproto, haplogroup
  provenance, coverage, …). Highlights since the merge work: `ident.audit_log`
  (0010), fed reporting (0011–0012), Y-STR (0013–0014), backbone (0015), **variant
  naming authority** (0016, nullable `canonical_name` + partial unique index +
  `core.next_du_name()`), **variant evidence** (0017), **YBrowse mirror +
  reconcile machinery** (0018), **strand-canonical fold** (0019), **INDEL/MNP
  canon** (0020), **ancestral-state / per-branch ASR** (0021), **`is_public`
  biosample gate** (0022, the public per-sample report), **variant
  `defining_haplogroup_id` recurrence model** (0023).
- **`du-db`** — query modules for every aggregate (variant, haplogroup, biosample,
  publication, genome_region, coverage, proposal, study, change_set, merge, auth,
  naming, ybrowse, wip, ystr, age, fed, consent, support) + `testing` (ephemeral DB).
- **Public HTML/HTMX** (`du-web/routes`) — variants browser, **Y/MT tree as two
  server-rendered SVG cladograms** (`tree_layout.rs`; breadcrumb re-root,
  orientation cookie toggle, name/variant search, SNP-detail sidebar with
  **branch provenance + per-variant locus/anc/der**, backbone/recent coloring +
  legend, **full-viewport width**, **client-persisted depth selector**
  [localStorage, `?depth=`]), references + per-pub biosamples, biosample map
  (PostGIS), coverage benchmarks; i18n (en/es/fr), `HX-Request` fragment
  negotiation, vendored assets, **site chrome aligned with the Scala app**.
- **Auth + curator** — signed-cookie sessions, `Curator` RBAC extractor, curator
  CRUD for haplogroups/variants/genome-regions, curation proposal
  intake→review→promote, and the review surfaces below.
- **Variant Naming Authority** (mig 0016, `du_db::naming`, `/curator/naming`) —
  nullable `canonical_name`, DU-name minting (`core.next_du_name()`), lifecycle
  (UNNAMED/PENDING_REVIEW/NAMED), same-coordinate dedup; GFF3 propagation at
  `GET /api/v1/variants/export.gff`. **Gotcha:** the partial unique index means
  every `ON CONFLICT (canonical_name)` carries `WHERE canonical_name IS NOT NULL`.
- **YBrowse ingest = mirror + reconcile** (migs 0017–0020, `du-jobs/ybrowse`,
  `du-db/ybrowse`) — streams `snps_hg38.gff3` (≈3.17M lines) into a verbatim
  `source.ybrowse_snp` **mirror**, then `reconcile` *derives* `core.variant`
  idempotently: synonym-fold by strand-canonical key, coordinate-fallback match,
  INDEL trim-normalize / MNP-typing, rank-based canonical, provisional→DU mint;
  single matches **enrich existing variants** (coords + mutation_type + evidence,
  curator choices locked); multi-match clusters → `source.ybrowse_reconcile_flag`
  → **`/curator/reconcile-flags`** → `variant::merge_into`. First real full run:
  2.99M clusters → 2.88M created, **100,968 existing enriched**, 11,406 flagged;
  catalog now ~3.0M variants, ~3.0M with coordinates. (See memory
  `ybrowse-ingest-mirror`.)
- **Variant coordinate enrichment** — reconcile fills coords/types on any
  name-matching existing variant; a `decodingus-tree-init --backfill-prod-coords`
  pass fills the b37/hs1 builds the decoding-us API carries that the graft dropped
  (complement to YBrowse's GRCh38). Sidebar shows `chrY:pos anc>der [build]`.
- **SNP-anchored graft** (`du-db/snp_graft`, `decodingus-tree-init`) — classifies a
  source tree (decoding-us prod) against the catalog by defining-SNP anchor
  (enrich-match / graft-novel / review), Phase-4 curator-review export, and stages
  flags + name-collisions + graft-blocked items into a DRAFT change-set
  (`--stage-review`) triaged at **`/curator/reviews`** (SNP-scatter + tree-preview
  + accept-anchor/reparent/merge/defer; `tree.wip_*` enacted by the apply engine's
  WIP pass). (See memory `prod-tree-snp-graft`.)
- **Y reference-region pipeline** (`du-jobs/yregions`, `du-db/genome_region`,
  `du-db/variant`) — `run-once yregions` loads the T2T-CHM13v2.0 Y structural BEDs
  (AZF/DYZ heterochromatin, amplicons v2, inverted-repeats/palindromes v2, chrXY
  sequence-class) into `core.genome_region` (`upsert_by_key` + `prune_source_orphans`
  = full-snapshot sync). `refresh_region_overlaps` stamps `core.variant.annotations.
  region_overlaps` for variants in unreliable-for-placement regions; `snp_graft`
  routes anchors whose every supporting SNP is unreliable to curator review
  (`UnreliableAnchor`). Empirically validated by Hallast 2026 (Fig 5h-i callable
  mask). hs1 coords only (1-based inclusive). (Memory `yregions-ingest`.)
- **ETL** (`du-migrate`) — **full production surface**: catalog (donors, biosamples,
  variants, tree, studies, publications), ident/auth, genomics. Validated vs the
  schema-only `db.schema` and the current-schema mock with data; all aggregates
  reconcile.
- **Public JSON API** (`du-web/api.rs`) — read endpoints under `/api/v1/*` +
  OpenAPI 3 + Swagger UI at `/api` (utoipa). Includes the federated population
  reports `/api/v1/reports/{coverage,ancestry,haplogroups}` aggregated from the
  `fed.*` mirror with query-time SQL, plus `haplogroups/:name/{str-signature,age}`
  and `POST /api/v1/str/predict`. **Tree cache revalidation (2026-06-12):** the
  `{y,mt}-tree[/full]` endpoints emit a strong `ETag` + `Last-Modified` from a
  persisted `tree.tree_revision` marker (mig 0024) and honor `If-None-Match` → 304
  *before* the ~28 MB query; the marker is bumped by every tree-mutating op
  (change-set apply, coordinate/alias enrichment, reconcile, tree-init). Added
  `/api/v1/{y,mt}-tree/version`. Memory `tree-cache-revalidation`.
- **Tree versioning** (`du-db/change_set.rs`, `du-web/routes/versioning.rs` +
  `change_sets.rs`) — change-set lifecycle + per-change review + diff + temporal
  apply engine; curator-gated machine API at `/manage/change-sets/*` **plus a
  two-panel HTMX review UI** at `/curator/change-sets`. Integration-tested.
- **Tree merge** (`du-domain/merge.rs` + `du-db/merge.rs`) — pure Identify-Match-
  Graft; `materialize` → change-set via placeholder-chained `tree_change`; endpoints
  `/manage/haplogroups/merge[/preview]`. Fixtures + e2e tests pass.
- **Federated reporting** (`du-db/src/fed/`, `du-jobs/jetstream.rs`, migs 0011–0012)
  — the AppView **aggregates and reports; it does not analyze.** A long-lived
  Jetstream consumer mirrors Navigator's published anonymized computed-summary
  records (the `✅ AppView Complete` set) into `fed.*` tables, cursor-resumed,
  idempotent+ordered. **Privacy:** typed anonymized columns only, no raw JSONB for
  PII-bearing records. Flow (a) proposal intake + (b) reporting ingest + (c)
  reporting web endpoints are **all DONE**. (Memory `atproto-federation-direction`.)
- **Y-STR per-branch signatures + prediction + age** — `fed.str_profile` mirror
  (Jetstream) + `du-db::ystr` modal-haplotype aggregation → `tree.haplogroup_
  ancestral_str` (mig 0013) via `str-signature-recompute`; STR→branch `predict`
  at `POST /api/v1/str/predict`. STR age is the **McDonald multi-step PDF model**:
  `P(g|m)` from Table 1 (embedded) + ω convolution fallback → per-marker Poisson
  mixture (`du_db::pdf::Pdf::mixture`) → **tree-propagated** TMRCA PDFs
  (`ystr::propagate_str`, ancestral-motif reconstruction). Per-marker rates from
  `genomics.str_mutation_rate` (seeded, 137 markers; Willems 2016 + YHRD).
- **Combined branch age (McDonald 2021)** (`du-db/age.rs`, migs 0013/0014) — each
  evidence term is a **PDF**: SNP TMRCA (bottom-up tree propagation, Eq 5–8, on the
  `du_db::pdf` grid), STR TMRCA (`ystr::str_tmrca_pdfs`), and genealogical/aDNA-anchor
  Gaussians; `COMBINED` is their **direct product** (Eq 1, shape-preserving; disjoint
  → inverse-variance fallback), gap-filling `tree.haplogroup.{formed,tmrca}_ybp`
  (curated values never overwritten). `HET_MASK` excises heterochromatic SNPs;
  Helgason rate default with Hallast `HALLAST_RATE` as a recorded cross-check. Runs in
  `branch-age-recompute` (= `run-once branch-age`). SNP/STR/anchor terms data-gated
  (sparse pre-cutover; the dev tree is tree-only, so a live run is a near no-op).
- **`du-jobs`** — tokio scheduler + **`run-once`** one-shot mode; jobs:
  `db-heartbeat`, `ybrowse-variant-ingest`, `publication-update`,
  `publication-discovery`, `publication-pubmed-update`, `ena-study-enrichment`,
  `str-signature-recompute`, `branch-age-recompute`; plus the Jetstream
  reporting-mirror consumer (set `JETSTREAM_URL`).
- **`du-external`** — OpenAlex, ENA, NCBI/PubMed; AWS SES + Secrets Manager behind
  the `aws` feature.
- **`du-atproto`** — DID/handle resolution, Ed25519 verify, PKCE/DPoP/private-key-
  JWT OAuth client + metadata builders (library; HTTP surface = the Edge test below).
- **Public per-sample report** (`/sample/:slug`, `du-web/routes/samples.rs` +
  `templates/samples/`) — ExploreYourDNA-style page gated by `core.biosample.is_public`
  (mig 0022). `du_db::biosample::report` is the **unified read model**: anchors on the
  canonical `core.biosample` (+ donor sex/origin, publications) and attaches the
  federated analytics (`fed.biosample`/`fed.sequencerun`/`fed.coverage_summary`/
  `fed.population_breakdown`) via `atproto.uri ↔ *.biosample_ref` — the seam the
  eventual core/fed **biosample consolidation** collapses into (memory
  `biosample-consolidation`). Sections: identity, Y+mt **haplogroup pathways**
  (`du_db::haplogroup::pathway` — root→tip clades + ages + defining SNPs; graceful
  "not placed" gap), origin Leaflet map, sequencing/coverage, ancestry stacked bar.
  Curator `is_public` toggle (`/curator/samples/:slug/public`); JSON API
  `GET /api/v1/samples/:slug`. Tested (`du-db/tests/sample_report.rs`). **Follow-up:**
  the report shows one `populationBreakdown`; Navigator now publishes two methods —
  pick PCA-GMM (memory `ancestry-method-pick-followup`).
- **Secondary web surfaces** — static pages (about/contact/**reputation**/terms/
  privacy/**cookies**/FAQ; content reconciled with the legacy Scala prose —
  **App Passwords removed**), footer nav matching the legacy set, `sitemap.xml`/
  `robots.txt`, GDPR cookie-consent banner, read-only **profile** page,
  reCAPTCHA-verified **contact** form. Root README rewritten for the Rust AppView.
- **Testing** — du-domain unit tests (no DB); du-db integration tests isolated to
  ephemeral databases (`du_db::testing::ephemeral_db`); du-web i18n parity test
  enforces es/fr cover every English key.

## What's left, in scope (⬜)

Launch-critical first, then the post-launch feature mass.

> **Design landscape (2026-06-07).** The post-launch collaboration/IBD layer now has
> drafted build specs: `documents/planning/d1`–`d5` + `design-roadmap-rust-rewrite.md`
> — **D1** encrypted Edge-to-Edge exchange + AppView broker (the shared substrate),
> **D2** PII-free ResearchSubject registry, **D3** IBD impl on D1, **D4** assertion
> store (split PII rails), **D5** group-project ACL. Central invariant: **AppView
> holds no PII — it brokers** (memory `collab-platform-d1-d5`). Two tracks join at
> D1: Platform D1→D2→D4→D5, Match D1→D3; the Catalog track (D6 discovery, D7
> multi-test, D8 sequencer-lab) is independent. The original planning docs were
> triaged and reconciled/removed — see `documents/{planning,proposals}/*triage*.md`.

1. **Cutover** (see "Cutover strategy") — ETL verified end-to-end. Chosen strategy:
   freeze prod read-only → fresh dump → prepare locally (ETL data + ISOGG-founded
   tree build) → `pg_dump` → restore on AWS → flip. **`--skip-tree` DONE** (commit
   0f83dbc): `decodingus-migrate --skip-tree` skips the 3 tree transforms +
   reconcile checks (the tree is built by `tree-init` into the empty namespace);
   biosamples carry haplogroup names as JSON and resolve at read time; `core.variant`
   still migrates (tree-init reuses by `canonical_name`). Cutover order: migrate
   `--skip-tree` → tree-init.
   **FTDNA descoped (2026-06-12):** beta tree = **ISOGG foundation + decoding-us
   graft, no `--reattach`**; **no mt tree at beta**. The FTDNA-heavy subsections
   below (mt foundation, 81k hybrid, reattach) are superseded — keep for later.
   So name resolution is **Y-only**.
   **Name resolution — DONE (2026-06-12).** Diagnosed against the real prod dump:
   `public.biosample_haplogroup` (the reconciled FK) is **empty in prod**, so
   `original_haplogroups` carries the raw heterogeneous **publication** call text
   (FTDNA shorthand `R-M269`, path strings `R-DF27 > Z195 > Z198`, bare SNPs, old
   YCC longhand `R1b1a2a1a2c1g`, `n/a`). Only ~20% match a node name directly.
   `du_db::haplogroup::resolve_name_or_variant` now has a **normalization fallback**
   (`normalize_haplogroup_call`: strip FTDNA prefix, terminal path token, split SNP
   synonyms) that resolves the SNP-bearing calls via the existing defining-variant
   phase → ~70% of rows (improves the per-sample report AND tree search). Residual:
   ~59 YCC-longhand names need an authoritative old-YCC→modern crosswalk (ISOGG file
   has only 13 name-aliases — don't hand-guess). Memory `biosample-y-name-resolution`.
   **Per-variant upsert perf — DONE (2026-06-12).** The "1s slow-statement" was the
   no-op `DO UPDATE SET canonical_name = EXCLUDED.…` rewriting every *pre-existing*
   variant row (the catalog is pre-loaded by YBrowse, so the graft/merge/apply calls
   nearly all conflict) → MVCC bloat + index churn (~1.9s in bulk, +893 heap pages /
   30k rows). The index is a correct arbiter — not the issue. Fixed:
   `du_db::variant::ensure_base_variant_id` (`DO NOTHING` + read-back, zero writes on
   conflict); all 3 `get_or_create_variant` route to it. Memory
   `variant-upsert-noop-write`.
   **YCC→SNP node rename — DONE (2026-06-12).** `tree-init --rename-snp-shorthand`
   (`du_db::haplogroup::rename_to_snp_shorthand`) drops YCC-longhand node names
   (`R1b1a2`) to `<MajorClade>-<definingSNP>` (`R-M269`), single major letter
   (renormalizes decoding-us `E1b-`→`E-`), keeping the **old YCC name in
   `provenance.aliases`** — which also **closes the YCC resolution residual** (the
   resolver's alias phase now resolves old biosample YCC calls). Naming SNP: existing
   shorthand → ISOGG-designated first variant (`--isogg`) → DB-linked variant
   (SNP-shaped only). Macro/backbone nodes, coordinate-name variants, and name
   collisions are skipped + flagged (no guessing). Dev-tree dry-run: 10,254/10,516
   renamed; ~185 keep YCC (twin collisions + no-SNP). Run it as a post-graft step in
   the cutover tree build. Memory `ycc-to-snp-rename`.
2. **Live AT Protocol OAuth handshake — the cross-host "Edge joint test."** Library
   + a dev public-client path are verified locally up to the **consent click**
   (gated `decodingus-shared/.../tests/live_pds.rs`: discovery + PAR + DPoP +
   `use_dpop_nonce` → `request_uri`, then with a Caddy TLS proxy up to the authorize
   page). The confidential web-client `private_key_jwt`-PAR round-trip can't run
   under Apple `container` (no `--add-host` for the PDS to resolve our `client_id`
   host) → it's the Edge joint test. Token path wired; remainder is the browser
   consent + cross-host verify. Runbook: `docs/atproto-oauth-findings.md`,
   `docs/atproto-edge-reply.md`.
3. **Haplogroup-discovery AUTOMATION** — the largest remaining forward subsystem.
   The *curator* half (proposals → review → promote) is built; the *automated* half
   is not: `tree.biosample_private_variant` is **never written** (only read by
   `age.rs`); no private-variant extraction from the federation ingest, no
   Jaccard/consensus proposal engine, no thresholds (`discovery_config` unused),
   no auto-reassignment. Depends on the federation ingest capturing private
   variants. (`documents/planning/haplogroup-discovery-system.md`; memory
   `design-doc-forward-pieces`.)
4. **Multi-test-type completion** — taxonomy (`genomics.test_type_definition`) +
   chip/targeted metadata ingest (via `fed.*`) are built; marker-coverage /
   target-region / test-type-aware confidence-scoring tables are **not**, and
   `test_type_definition` isn't seeded by a migration (only ETL-backfilled).
   (`documents/planning/multi-test-type-roadmap.md`.)
5. **IBD matching — AppView as coordinator (NOT dropped).** The AppView is the
   only component with the cross-federation view to identify **introduction
   candidates**, so it must: mine `fed.*` for candidate pairs (shared haplogroup,
   population overlap, shared-match signals), run the **dual-consent** handshake,
   coordinate the Edge hand-off, and **persist match state** (attestations /
   overlap scores / suggestions) for ongoing match lists + dedup. It stores **no
   raw autosomal data** and does **no** segment comparison — that's Edge-to-Edge.
   Schema `ibd` (mig 0007: `ibd_discovery_index`, `ibd_pds_attestation`, overlap)
   exists as a placeholder; logic + endpoints to build **on the D1 exchange
   substrate** (`ibd.match_request`/`match_consent` fold into `exchange.*`).
   Authoritative design: `documents/planning/d3-ibd-matching-impl.md` (on
   `d1-encrypted-edge-exchange.md`).
6. **Collaboration + social layer.** The genealogy-collaboration platform (group
   projects, ResearchSubject registry, assertions) is specced in **D2/D4/D5** on the
   D1 channel; the broader social surfaces (messaging/feed/reputation/blocks) are the
   reconciled forward proposals (`documents/proposals/{group-project-system,
   Messaging_and_Feed_System,Reputation_System_Implementation}.md`). Schema `social`
   + `research` placeholders (mig 0009) exist; logic + endpoints to build. **No-PII
   caveat:** DMs must ride D1 (or AT-Proto), not a central plaintext `social.message`.
7. **Sequencer-lab inference — AppView lookup + consensus (NOT dropped).** The
   **lookup API is DONE (2026-06-12)**: `GET /api/v1/sequencer/lab?instrument_id=…`
   (→ `SequencerLabDto`, 404 if unknown) + `GET /api/v1/sequencer/lab-instruments`
   (bulk cache seed), resolving via the **preseeded** `genomics.sequencer_instrument.
   lab_id` (mig 0025 re-adds it; ETL backfills from the legacy tie;
   `du_db::sequencer`). The proposal/consensus path is **not live anywhere**, so the
   lookup uses the direct tie (memory `sequencer-lab-lookup`). The **consensus
   engine is DONE (2026-06-12)**: `du_db::sequencer::recompute_consensus` derives
   observations from `fed.sequencerun ⋈ fed.biosample.center_name`, aggregates per
   instrument into `instrument_association_proposal` (dominant lab, distinct-citizen
   counts, confidence, threshold status, conflict→PENDING), run by `du-jobs run-once
   sequencer-consensus` (+ hourly). Curator API `/manage/instrument-proposals[/:id[/
   accept|/reject]]` — **accept sets `sequencer_instrument.lab_id`** (closing the loop
   to the lookup), audited via the new `du_db::audit::log`. **Still to build:** the
   curator HTMX review UI (API done), the `instrumentObservation` lexicon, and
   recency/confidence-level scoring refinements (constants for now).
   (`documents/planning/sequencer-lab-inference-system.md`.)
8. **Smaller in-scope finishers:**
   - **Graft carries coordinates forward** at creation (fold into
     `get_or_create_variant`) so the decoding-us backfill isn't needed after each
     re-graft.
   - **YBrowse reconcile tail:** off-by-one / near-coordinate proximity detection;
     an external synonym authority (YFull/ISOGG cross-refs) to assert "X = Y" across
     genuinely different coordinates; per-name evidence consolidation.
   - **WIP/merge review:** `EDIT_VARIANTS` resolution + cascading a graft-blocked
     *subtree* from a single decision.
   - **Branch age:** the McDonald model is built end-to-end (PDF engine, SNP + STR
     tree propagation, multi-step `P(g|m)`, genealogical anchors, PDF-product combine,
     seeded STR rates). Remaining refinements are **data-shaped, not architectural**:
     the true b̄ coverage *intersection* (Eq 4 — needs per-sample callable intervals),
     the Eq 9/10 causality back-correction, the PDF-at-scale perf check once a
     densely-sampled subtree exists, and the lone missing single-copy STR rate (DYS447).
   - **API:** surface unnamed variants (cross-repo change — `du-domain::Variant.
     canonical_name` `String` → `Option`, shared with Navigator).
   - More `fed.*` report shapes (genotype-provider mix, platform/test-type
     distribution) as the UI needs them.
9. **Tech debt** — JSONB consolidation is **done** (realized in the de-sprawl,
   mig 0002/0004 — that analysis doc was removed); terms/privacy prose now mirrors
   the legacy Scala content but is still "subject to legal review"; optional
   internal/curator OpenAPI document; harden `du_db::variant::get_by_id` for a NULL
   `canonical_name` (unnamed-variant edge).

## Out of scope / deliberately absent (➖) — do NOT build

- **Manual sample-ingestion APIs** (biosample create + sequences + publication-link)
  — curators use Navigator now; the AppView keeps catalog **review + naming** only.
- **BAM/CRAM extraction + variant calling** — done at the edge (Navigator); the
  AppView aggregates summaries/proposals (so `du-bio` is text + coordinate math, no
  htslib/noodles).
- **The legacy PDS fleet / raw-data network mirror** — `fed.pds_node` /
  `pds_heartbeat` / fleet-admin tables (mig 0008) map to the **dropped** mirror
  design; don't build registration/heartbeat/fleet endpoints.
- **AppView→PDS backfeed** — the AppView writes nothing back to PDSes (inbound-only
  / notify-fetch direction).
- **Patronage / billing** — not in production (`billing` placeholder; no logic).
  **Deferred, not dead:** revive to fund infrastructure past ~a few hundred active
  users (`documents/proposals/Patronage_Donation_System.md`; FAQ already names it).
- (IBD matching, the social layer, and sequencer-lab inference are **back in
  scope** — see "What's left" items 5–7. Their schemas remain placeholders pending
  that build.)

## Cutover blocker — VERIFIED (2026-06-04)

The ETL has been run end-to-end against a **real production dump**
(`/Users/jkane/backup_file.sql`, 363 MB, PG 15.18) and **all 34 aggregates
reconcile**. Schema risk was already retired (`~/db.schema` is current prod);
this run retired the data risk too.

How it was run (repeatable):
1. `CREATE ROLE decoding_us_user;` (the dump owns objects as this role), then a
   fresh `decodingus_prod` DB.
2. Load the dump, stripping the two `\restrict`/`\unrestrict` lines — the
   container psql is **16.4**, which predates those meta-commands (added in the
   Sept-2025 security releases): `grep -vE '^\\(un)?restrict' dump.sql |
   container exec -i du-pg psql -U postgres -d decodingus_prod -q`.
3. `decodingus-migrate --legacy <decodingus_prod> --target <decodingus_etl>`
   (recreate the target first; the run migrates + transforms + reconciles).

**The variant fold (commits fbc298a → cd37657):** legacy `public.variant` is one
row per (SNP, build, mutation DIRECTION); `core.variant` is one row per physical
SNP **site**. The transform folds by site (`dense_rank` over position) and carries
per-branch ancestral/derived onto `tree.haplogroup_variant` (ASR model — see
[[etl-cutover-verified]] / migration 0021). Real-data: variant 3,023,051 →
2,899,782; haplogroup_variant → 86,744; all aggregates reconcile.

## Cutover strategy (chosen 2026-06) — read-only freeze, prepare local, ship to AWS

1. **Freeze prod** read-only (no write drift during migration).
2. **Take a fresh dump**; load locally → `decodingus_prod` (role `decoding_us_user`
   + strip `\restrict` lines for psql 16; see above).
3. **Prepare locally** (the new-schema DB):
   - ETL the **non-tree** data (donors, biosamples, pubs, variants, genomics) —
     all reconcile today.
   - Build the **tree separately, ISOGG-founded** (the chosen direction — see
     "Tree build direction" below): `tree-init --isogg <file> --apply` then
     `--merge-prod <url> --snp-graft --graft --apply`.
   - (Optional) run the YBrowse ingest for full coordinate coverage.
4. **Ship to AWS:** `pg_dump -Fc` the prepared DB, restore on AWS, point the new
   codebase at it, flip.

**The one ETL change this needs:** the ETL currently *migrates the prod
decoding-us tree*; for the ISOGG-founded build it must **skip the tree transforms**
(`haplogroup` / `haplogroup_relationship` / `haplogroup_variant`) and leave the
tree to `tree-init`. Add a `--skip-tree` flag (or split tree transforms out). NOT
yet built.

**Two integration points to settle:**
- **Name resolution must be alias-aware.** `biosample→haplogroup` is by **name**,
  not FK (`core.biosample.original_haplogroups` JSONB, `fed.biosample.y/mt_haplogroup`
  text) — so an ISOGG-founded tree works *because* decoding-us names live as
  aliases on the ISOGG nodes. Verify tree-search + biosample views resolve via
  aliases once a flip DB has data.
- **Postgres version.** Prod dump is PG 15; the local container is 16. `pg_dump`
  16 → restore into 15 can break — run the new code's AWS instance on **PG 16**
  (match local) or pin local to 15.

### Tree build direction — ISOGG foundation + SNP-graft everything (decided)

Sources differ in naming (ISOGG path-strings vs decoding-us/FTDNA SNP-names) AND
root depth, so the exact-set name merge (`du_db::haplogroup::merge_into` /
`du_domain::merge`) is useless cross-source — its subtree-scoping cascades a
root-topology mismatch to NEW (matched=1, would duplicate 10,230 nodes). Use the
**SNP-anchored graft** (`du_db::snp_graft`, `tree-init … --snp-graft`). Full
investigation + recipe in memory [[tree-source-merge]]. Decisions:

- **ISOGG is the foundation** (single `Y` root + curated backbone authority), then
  graft decoding-us and FTDNA onto it. The reverse (FTDNA- or decoding-us-founded)
  drops the deep-root region, becomes a rooted forest, and/or inverts naming
  authority. Build:
  `tree-init --isogg <file> --apply` →
  `--merge-prod <url> --snp-graft --graft --apply` →
  `--ftdna <file> --graft --reattach --apply`.
- **`--reattach` is required for FTDNA** (105k-node complete-topology source,
  `/Volumes/nas/FTDNA/`, refreshed weekly). FTDNA merges SNP blocks ISOGG splits,
  so a bush's backbone ancestor is often weak-flagged and the graft conservatively
  *blocks* it (would drop 56,855 of 70,921). Reattach walks up to the nearest
  ancestor the classifier cleanly **MATCHED** and attaches the bush there. (First
  cut used a raw SNP→node index and dumped clades onto A00 — the catalog's junk
  recurrent links, see "junk links" below, point single SNPs at basal nodes;
  MATCH dispositions are vetted by SNP-set + subtree scope, so they don't.)
- **Source tags parameterized** (commit 0e09060) — any source tags its own nodes;
  the anchor/collision guard excludes only that source's prior graft.
- **Result `decodingus_hybrid2`: 81,297 nodes, single ISOGG root**, ISOGG-named
  backbone + decoding-us + full FTDNA depth (70,748 bushes; 16,117 reattached;
  173 unanchored; ~19 land on `CT`), source names folded in as aliases, ~42k
  variants coord-enriched from FTDNA anc/der+position. Spot-verified:
  `I-BY136871 → I1a3a1b`, `G-FTH55879 → G2a2b2a1a1b1a1`; basal nodes near-empty.
- **JUNK LINKS — SCRUBBED (commit 7a0487d).** ~1.2k catalog variants were linked
  to haplogroups across unrelated macro-clades (decoding-us ASR scatter onto
  A00/H/O; also FTDNA shared-SNP blocks), which nearly broke the FTDNA reattach.
  `du_db::haplogroup::scrub_recurrent_links` (`tree-init --scrub-recurrent
  [--apply]`) keeps each variant's primary (most-concentrated) lineage — by tree
  ancestry, not names — and soft-deletes the off-lineage occurrences (self-name
  tiebreak for fully-scattered cases, e.g. `CTS9108`). Operates only on
  `haplogroup_variant`, never on topology. Applied to `decodingus_hybrid2`:
  cross-macro-clade variants 1,200 → 1, 10,908 links pruned, 81,297 nodes
  unchanged. The 45 residue on `decodingus_etl` are legitimate basal chains
  (Y→I1, NO→O) the ancestry criterion correctly keeps.

### mtDNA tree — FTDNA-only foundation (wired, commit b7c9748)

Legacy prod has only Y (2,695 nodes), **zero MT** — no decoding-us mt source, mt
API, or biosample mt assignment. So the mt-tree is FTDNA-only: load FTDNA's single
RSRS-rooted mt haplotree as the **foundation** (merge_into into an empty MT
namespace) — no graft/merge/reattach/scrub.
- `tree-init --ftdna /Volumes/nas/FTDNA/ftdna_mttree.json --ftdna-foundation
  --dna MT --apply`. `ftdna_foundation_roots` builds the nested merge tree.
- **Privacy differs from the Y graft:** `kitsCount==0` on RSRS and internal splits
  means "no kit terminates here", NOT "private individual" — backbone/internal
  nodes are kept; only private LEAVES (kits==0, no kept descendants) drop.
- **NOT scrubbed:** mtDNA homoplasy (16189, 152, …) is real and FTDNA-curated;
  1,759 multi-branch variants are legit recurrence, not ASR junk.
- Variants are RSRS-frame (`G263A`: anc G, der A @263 — rCRS has A there);
  coordinates `{chrM, position, ancestral, derived}`.
- Verified on `decodingus_hybrid2`: 4,740 nodes, single RSRS root, 56 backbone
  clades, correct PhyloTree topology (L0<RSRS, H1<H<HV, U5b2a1<U5b2a, K1a<K1),
  3,882 coord-enriched. Web `/mtree` + `/mtree/snp` render it (generic DnaType
  routing; default root falls back to RSRS since there's no bare `L` node).

## Key decisions & gotchas (don't relearn these)

- **Tree merge was buggy in legacy** → re-implemented against curated fixtures,
  NOT golden-tested against legacy output. Conservative: flag ambiguities, don't guess.
- **Partial unique index on `canonical_name`** (naming authority) → every
  `ON CONFLICT (canonical_name)` needs `WHERE canonical_name IS NOT NULL`.
- **YBrowse is a full snapshot, no deltas** → never write `core.variant` directly;
  the mirror→reconcile derivation is what keeps curator edits durable. Reconcile's
  alias name-match EXPLODES `common_names` into rows and hash-joins (the
  jsonb_path_ops GIN index does NOT serve the `?` operator → a per-row probe never
  finishes at 3M scale).
- **Destructive whole-mirror tests** use `ephemeral_db` — do NOT point the DB-gated
  du-db/du-jobs ybrowse tests at the populated dev DB without it.
- **Merge materialization** uses `tree_change` + negative placeholders through the
  tested apply engine; `wip_*` carries the SNP-graft staging review.
- **Temporal DELETE**: nodes are temporal (`valid_until`); roots/children/subtree
  exclude expired nodes.
- **Enums** fetched as `::text` + parsed (`parse_pg_enum`); JSONB via `Json<T>`.
- **utoipa** kept out of `du-domain` (shared with Navigator/edge); API DTOs +
  `From` impls live in `du-web/api.rs`. Recursive `HaplogroupNodeDto.children`
  needs `#[schema(no_recursion)]`.
- **Management API namespace**: curator/machine endpoints live under **`/manage/*`**
  (not `/api/v1`) and are excluded from the public OpenAPI doc. Auth is
  session/`Curator`; unauth → 303 to /login even for JSON endpoints.
- **`DbError::Conflict` → HTTP 422** (mapped in `du-web/error.rs`).
- **ETL preserves PKs** via `OVERRIDING SYSTEM VALUE` + `sample_guid`; sequences
  fixed up post-load; idempotent upserts.
- **i18n**: adding a UI string requires es/fr entries — `cargo test -p du-web`
  enforces parity.

## Resume checklist

1. `eval "$(./scripts/test-db.sh up)"` (or set `DATABASE_URL`); confirm `du-pg`
   container is running.
2. `cargo test --workspace` (du-domain needs no DB; du-db live tests provision
   ephemeral DBs from `DATABASE_URL`).
3. Pick the next arc — launch-critical is **cutover** + the **OAuth Edge test**.
   Post-launch: the **collaboration/IBD platform** starts at **D1** (the shared
   encrypted-exchange substrate, `documents/planning/d1-encrypted-edge-exchange.md`),
   then D2→D4→D5 (platform) / D3 (IBD); the **Catalog** track (haplogroup-discovery
   automation = D6, multi-test = D7, sequencer-lab = D8) is independent. Remaining
   doc cleanup: `documents/atmosphere/` still references removed docs (flagged in the
   triage reports).
4. Reload the mock if needed: recreate `decodingus_legacy`, load
   `scripts/mock-legacy.sql`; recreate `decodingus_etl`; run `decodingus-migrate`.

## Reference paths

- **Post-launch design specs:** `documents/planning/d1`–`d5` +
  `design-roadmap-rust-rewrite.md` (collaboration/IBD platform, no-PII broker).
- **Design-doc triage reports:** `documents/planning/design-doc-triage-report.md`,
  `documents/proposals/triage-report.md` (what was removed/reconciled + remaining
  `atmosphere/` ref cleanup).
- Prod schema (authoritative for ETL, confirmed current 2026-06): `~/db.schema`
- Old data dump (may lag — get a fresh one for cutover): `/Volumes/nas/stuff/dump.sql`
- AT Proto notes: `docs/atproto-oauth-findings.md`, `docs/atproto-edge-reply.md`
- **Scala↔Rust functional diff catalog: `docs/scala-vs-rust-diff.md`**
- Navigator atmosphere docs: `/Users/jkane/Development/DUNavigator/documents/atmosphere`
