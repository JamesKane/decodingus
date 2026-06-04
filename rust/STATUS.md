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
The remaining *feature* mass is post-launch: **haplogroup-discovery automation**
and **multi-test-type completion**. Several subsystems are intentionally absent
(see "Out of scope").

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

1. **Cutover** (see "Cutover blocker") — verification is **DONE** (real prod dump,
   all aggregates reconcile). What remains is *executing* the cutover: run the ETL
   against the final/live data (read-only EC2 or a same-day dump), then flip the
   app's `DATABASE_URL` to the migrated DB. Re-run is idempotent.
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
5. **Smaller in-scope finishers:**
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
6. **Tech debt** — JSONB consolidation (7 tables → JSONB columns,
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
- **IBD/matching, sequencer-lab inference, social/messaging, reputation,
  patronage/billing, group-projects** — not in production (schemas 0007/0009 +
  genomics lab tables exist as placeholders; no ETL, no endpoints).

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

**The finding (now fixed, commit fbc298a):** legacy `public.variant` is one row
per (SNP, build); the redesigned `core.variant` is one row per physical SNP with
a multi-build `coordinates` JSONB. The old 1:1 transform tripped the
naming-authority partial unique index on the first multi-build SNP. The transform
now **folds by SNP** (3,023,051 → 2,901,369; within-build homoplasy copies kept
as UNNAMED variants) and `haplogroup_variant` repoints links to the fold anchor
and dedups per-build duplicates (~209k → 86,810).

**To execute the real cutover:** point `--legacy` at the live EC2 (read-only, via
opened SG / SSH tunnel) or a same-day dump, run the ETL, confirm reconcile, then
flip the app's `DATABASE_URL` to the migrated DB. The run is idempotent.

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
