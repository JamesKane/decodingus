# DecodingUs Rust rewrite — status & handoff

Living snapshot of the Play/Scala 3 → Rust port. Pairs with `README.md` (roadmap)
and the plan at `~/.claude/plans/robust-knitting-lampson.md`. Last updated 2026-06-04
(variant naming authority + SNP-graft & curator review surfaces + YBrowse
mirror/reconcile + variant coordinate enrichment + federation reporting endpoints
+ Y-STR signatures/prediction/ages + ephemeral-DB test isolation + tree depth
selector).

## TL;DR

The **spine is done and then some**: redesigned schema, data layer, public
HTML/HTMX surface, auth + curator tools, the full production ETL, the public JSON
API, tree versioning + merge, the SNP-anchored graft + its curator review UIs, the
YBrowse mirror→reconcile catalog pipeline (≈3M variants), federated **reporting**
(mirror **and** web endpoints), branch ages, and Y-STR signatures/prediction/age.

The launch-critical path is now just two things: **(1) the data cutover** — the
ETL has been **verified end-to-end against a real production dump** (2026-06-04,
363 MB / PG 15): all 34 aggregates reconcile; what's left is executing the cutover
against live/final data — and **(2) the live AT Proto OAuth handshake** (the
cross-host "Edge joint test").
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
  - **One-shot ops:** `decodingus-jobs run-once ybrowse` (full GFF3 stream + reconcile;
    needs `YBROWSE_GFF` [+ optional `YBROWSE_CHAIN_GRCH37/HS1`]) and
    `decodingus-jobs run-once reconcile` (re-derive `core.variant` from the loaded
    mirror without re-streaming — e.g. after curator edits).
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

- **Schema** — `migrations/0001–0020`. JSONB "document columns" (variant
  coordinates/aliases/**evidence**, biosample source_attrs/atproto, haplogroup
  provenance, coverage, …). Highlights since the merge work: `ident.audit_log`
  (0010), fed reporting (0011–0012), Y-STR (0013–0014), backbone (0015), **variant
  naming authority** (0016, nullable `canonical_name` + partial unique index +
  `core.next_du_name()`), **variant evidence** (0017), **YBrowse mirror +
  reconcile machinery** (0018), **strand-canonical fold** (0019), **INDEL/MNP
  canon** (0020).
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
- **ETL** (`du-migrate`) — **full production surface**: catalog (donors, biosamples,
  variants, tree, studies, publications), ident/auth, genomics. Validated vs the
  schema-only `db.schema` and the current-schema mock with data; all aggregates
  reconcile.
- **Public JSON API** (`du-web/api.rs`) — read endpoints under `/api/v1/*` +
  OpenAPI 3 + Swagger UI at `/api` (utoipa). Includes the federated population
  reports `/api/v1/reports/{coverage,ancestry,haplogroups}` aggregated from the
  `fed.*` mirror with query-time SQL, plus `haplogroups/:name/{str-signature,age}`
  and `POST /api/v1/str/predict`.
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
  at `POST /api/v1/str/predict`; STR-variance age (McDonald 2021) folded into the
  combine.
- **Combined branch age (McDonald 2021)** (`du-db/age.rs`, mig 0014) — labeled
  per-evidence rows multiplied as Gaussians; SNP-Poisson + genealogical/aDNA-anchor
  terms; gap-fills `tree.haplogroup.tmrca_ybp` (curated values never overwritten);
  runs in `branch-age-recompute`. SNP/anchor terms data-gated (sparse pre-cutover).
- **`du-jobs`** — tokio scheduler + **`run-once`** one-shot mode; jobs:
  `db-heartbeat`, `ybrowse-variant-ingest`, `publication-update`,
  `publication-discovery`, `publication-pubmed-update`, `ena-study-enrichment`,
  `str-signature-recompute`, `branch-age-recompute`; plus the Jetstream
  reporting-mirror consumer (set `JETSTREAM_URL`).
- **`du-external`** — OpenAlex, ENA, NCBI/PubMed; AWS SES + Secrets Manager behind
  the `aws` feature.
- **`du-atproto`** — DID/handle resolution, Ed25519 verify, PKCE/DPoP/private-key-
  JWT OAuth client + metadata builders (library; HTTP surface = the Edge test below).
- **Secondary web surfaces** — static pages (about/FAQ/terms/privacy + app-password
  help), `sitemap.xml`/`robots.txt`, footer nav, GDPR cookie-consent banner,
  read-only **profile** page, reCAPTCHA-verified **contact** form.
- **Testing** — du-domain unit tests (no DB); du-db integration tests isolated to
  ephemeral databases (`du_db::testing::ephemeral_db`); du-web i18n parity test
  enforces es/fr cover every English key.

## What's left, in scope (⬜)

Launch-critical first, then the post-launch feature mass.

1. **Cutover** (see "Cutover strategy") — ETL verified end-to-end. Chosen strategy:
   freeze prod read-only → fresh dump → prepare locally (ETL data + ISOGG-founded
   tree build) → `pg_dump` → restore on AWS → flip. **`--skip-tree` DONE** (commit
   0f83dbc): `decodingus-migrate --skip-tree` skips the 3 tree transforms +
   reconcile checks (the tree is built by `tree-init` into the empty namespace);
   biosamples carry haplogroup names as JSON and resolve at read time; `core.variant`
   still migrates (tree-init reuses by `canonical_name`). Cutover order: migrate
   `--skip-tree` → tree-init. Verified prod→`decodingus_cutover`: tree empty, all
   non-tree aggregates reconcile, mt-tree built into it, 1,444/1,687 biosample mt
   names resolve by name. **Remaining:** alias-aware resolution for the ~14% mt /
   the Y aliases (mt has no ISOGG-style alias source — PhyloTree-version mapping is
   a follow-up), and a perf pass on the per-variant `ON CONFLICT (canonical_name)`
   against the 2.9M-row catalog (1s slow-statement during the tree build).
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
   exists as a placeholder; logic + endpoints to build.
   (`documents/planning/ibd-matching-system.md`.)
6. **Social layer — supports IBD coordination + stands alone.** Full layer:
   messaging/consent threads, notifications, blocks, public feed, reputation,
   group projects. Underpins the IBD introduction + match-confirmation flow
   (consent/notify) and is a user-facing social surface in its own right. Schema
   `social` (mig 0009) exists as a placeholder; logic + endpoints to build.
   Includes the legacy **my-messages** surface.
7. **Sequencer-lab inference — AppView lookup + consensus (NOT dropped).** A public
   **instrument-ID → lab** lookup API lets Edge nodes auto-resolve the sequencing
   lab and skip a manual data-entry step. The AppView also runs consensus discovery
   from citizen `instrumentObservation` records (via `fed.*`), with a curator
   review queue and confidence scoring. Genomics lab tables exist; lookup +
   consensus + endpoints to build.
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
   - **Branch age:** per-sample/`formed_ybp` refinement + aDNA-calibration weighting;
     import a real `str_mutation_rate` table (Ballantyne/Willems) to replace the
     default rate.
   - **API:** surface unnamed variants (cross-repo change — `du-domain::Variant.
     canonical_name` `String` → `Option`, shared with Navigator).
   - More `fed.*` report shapes (genotype-provider mix, platform/test-type
     distribution) as the UI needs them.
9. **Tech debt** — JSONB consolidation (7 tables → JSONB columns,
   `documents/planning/jsonb-consolidation-analysis.md`); terms/privacy prose pending
   legal review; optional internal/curator OpenAPI document.

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
3. Pick the next arc — launch-critical is **cutover** + the **OAuth Edge test**;
   the next big feature arc is **haplogroup-discovery automation**.
4. Reload the mock if needed: recreate `decodingus_legacy`, load
   `scripts/mock-legacy.sql`; recreate `decodingus_etl`; run `decodingus-migrate`.

## Reference paths

- Plan: `~/.claude/plans/robust-knitting-lampson.md`
- Prod schema (authoritative for ETL, confirmed current 2026-06): `~/db.schema`
- Old data dump (may lag — get a fresh one for cutover): `/Volumes/nas/stuff/dump.sql`
- AT Proto notes: `docs/atproto-oauth-findings.md`, `docs/atproto-edge-reply.md`
- **Scala↔Rust functional diff catalog: `docs/scala-vs-rust-diff.md`**
- Navigator atmosphere docs: `/Users/jkane/Development/DUNavigator/documents/atmosphere`
