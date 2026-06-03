# DecodingUs — Rust port

A rewrite of the DecodingUs platform (originally Play Framework / Scala 3) in
Rust. It coexists with the Scala app during the transition and replaces it at a
single cutover. The Rust app is the **AppView**: a curated Y/mtDNA phylogenetic
catalog, a public read surface + JSON API, the curator tooling, and
privacy-preserving federated *reporting* (it aggregates, it does not analyze).

**Status:** the spine is done — redesigned schema, data layer, public HTML/HTMX +
JSON API, auth + the full curator suite, tree versioning + merge + SNP-graft, the
production ETL, the YBrowse ingestion pipeline, the variant naming authority, and
the federated reporting mirror. Workspace builds clean; live-DB integration tests
(gated on `DATABASE_URL`) + unit tests pass. The main remaining mass is the live
AT-Protocol OAuth handshake (verified to consent locally; confidential round-trip
is an Edge joint test) and the ETL cutover. See [Roadmap](#roadmap). A living,
detailed status lives in [`STATUS.md`](STATUS.md); the feature-by-feature
comparison with the Scala app is in [`docs/scala-vs-rust-diff.md`](docs/scala-vs-rust-diff.md).

---

## Why the rewrite

- Drop the JVM's memory/startup overhead for a single static binary.
- Replace a sprawling, accreted schema (~84 tables across 6 schemas + a second
  "metadata" DB) with a de-sprawled design that leans on Postgres **JSONB** for
  document-shaped data.
- Run fully **Docker-less for local dev/test** on Apple Silicon via Apple's
  `container` CLI, while remaining Docker-deployable for production.

## Stack

| Concern | Choice |
|---|---|
| Web | **Axum** 0.7 (+ tower / tower-http / tower-cookies) |
| Templates | **Askama** (compile-time typed, Twirl analog) |
| Frontend | **HTMX** 2 + Bootstrap 5 (vendored), HATEOAS-first |
| Database | **SQLx** 0.8 (Postgres, runtime-checked queries) |
| Genomics | `du-bio` — coordinate math + text parsing (VCF / BED callable-loci / UCSC-chain liftover); the YBrowse GFF3 parser lives in `du-jobs`. Raw reads (BAM/CRAM) + calling are out of scope (done in Navigator) |
| Async | **tokio** |
| Auth | AT Protocol OAuth (PKCE/DPoP/`private_key_jwt`); legacy Argon2 sessions for dev/curator seeding |
| External | OpenAlex, ENA, NCBI/PubMed; AWS SES + Secrets Manager behind the `aws` feature; reCAPTCHA |
| i18n | embedded `key=value` catalogs (en/es/fr) |
| Local Postgres | Apple `container` running `imresamu/postgis` (arm64) |

## Workspace layout

Shared crates live in the sibling **`decodingus-shared`** repo (also consumed by
Navigator), pulled in as **git deps pinned to a rev** in `Cargo.toml` (so the
Docker build needs no sibling path). To co-develop locally, add a `[patch]`
pointing the three deps back at the sibling working tree.

```
github.com/JamesKane/decodingus-shared  (separate repo)
    du-domain/    pure types/enums/IDs + JSONB payload structs + the merge algorithm, no IO
    du-atproto/   AT Protocol identity/crypto + OAuth client (PKCE/DPoP/metadata)
    du-bio/       genomics: callable-loci (BED), liftover (UCSC chain), VCF reader

rust/                          (this repo — AppView/server-specific)
  crates/
    du-db/        SQLx pool + per-aggregate query modules + versioning/merge/graft/naming/ybrowse engines
    du-external/  OpenAlex / ENA / NCBI / AWS SES / Secrets clients
    du-web/        Axum app: routes, Askama templates, i18n, HTMX, auth, OAuth, JSON API
    du-jobs/       tokio scheduler + scheduled jobs + the Jetstream reporting-mirror consumer
    du-migrate/    legacy → new-schema ETL + the `decodingus-tree-init` ISOGG/graft loader
  migrations/     redesigned schema (0001–0020)
  locales/        en / es / fr message catalogs
  docs/           STATUS pointers, Scala↔Rust diff, AT-Proto OAuth findings
  scripts/        test-db.sh (Apple container), mock-legacy.sql
  Dockerfile, compose.yaml, .env.example
```

## Schema redesign (`migrations/0001`–`0020`)

Postgres schemas: `core`, `tree`, `genomics`, `pubs`, `ident`, `fed`, `ibd`,
`social`, `support`, `billing`, `source`. Key de-sprawl moves:

- **3 biosample tables → 1** `core.biosample` (a `source` enum discriminator +
  `source_attrs` JSONB).
- **Deprecated child tables folded into JSONB** on their parents (variant aliases
  & coordinates, sequence-file checksums/locations, alignment coverage, original
  haplogroups, per-revision tree metadata).
- The legacy second **"metadata" database collapses into the `fed` schema**.
- Scattered `at_uri`/`at_cid` columns → one consistent **`atproto` JSONB** column.
- **Tree** is temporal: no `parent_id`; hierarchy lives in
  `tree.haplogroup_relationship` with bitemporal `valid_from`/`valid_until`.
- **Universal variant model**: one `core.variant` per physical SNP;
  `canonical_name` (nullable — unnamed variants are identified by coordinates),
  `aliases`/`coordinates`/`evidence` JSONB, `naming_status`, `mutation_type`.
- PostGIS (`geometry(Point,4326)`), `citext`, native enums, GIN/GiST/expression
  indexes on queried JSONB paths.

## What's implemented

### Public surface (server-rendered, HTMX, i18n en/es/fr)

| Area | Routes |
|---|---|
| Home / about / FAQ / terms / privacy / app-password help | `/` `/about` `/faq` `/terms` `/privacy` `/help/app-password` |
| Variant browser | `/variants` (+ fragments; JSONB alias/rs-id search) |
| Y/MT tree — two SVG cladograms (horizontal + vertical) | `/ytree` `/mtree` (breadcrumb re-root, orientation toggle, name/variant search, SNP sidebar, backbone/recent coloring) |
| References + per-publication biosamples; suggest-a-paper | `/references` (+ report), `/references/submit` (public DOI → candidate queue) |
| Biosample map (PostGIS → Leaflet GeoJSON) | `/biosamples/map` `/biosamples/geo-data` |
| Coverage benchmarks + per-lab drill-down | `/coverage-benchmarks` `/coverage/labs` |
| Profile (view + display-name update); contact (reCAPTCHA) | `/profile` `/contact` |
| sitemap / robots / health; cookie-consent banner | `/sitemap.xml` `/robots.txt` `/health` `/cookie-consent` |
| Public JSON API + OpenAPI 3 / Swagger UI | `/api`, `/api/v1/*` (see below) |

### Public JSON API (`/api/v1/*`, OpenAPI at `/api`)

Y/MT tree, coverage benchmarks, references + biosamples, biosample studies,
variant search/detail/by-haplogroup, variant **CSV** + **GFF3** export, genome
regions, STR signature + prediction, branch age, and federated population
**reports** (`/reports/{coverage,ancestry,haplogroups}`).

### Auth & curator

AT Protocol **OAuth** (`/login/atproto`, dev public-client path); legacy
signed-cookie sessions for dev. `Curator` RBAC guard. The curator dashboard
(`/curator`) links a full suite:

| Tool | Route | What |
|---|---|---|
| Haplogroups | `/curator/haplogroups` | CRUD + structural ops: **reparent / merge-into-parent / split** (direct temporal edits, cycle/name guards) |
| Variants | `/curator/variants` | CRUD; alias/coordinate JSONB editing |
| Genome regions | `/curator/regions` | CRUD (coordinates/properties JSONB) |
| Curation proposals | `/curator/proposals` | review/promote Navigator-submitted branch proposals → catalog |
| Publication candidates | `/curator/publications` | review OpenAlex discoveries → promote to references |
| Change-sets | `/curator/change-sets` | tree-versioning lifecycle + diff + per-change review/apply |
| Merge review | `/curator/reviews` | resolve SNP-graft flags / merge ambiguities via the `wip_*` staging tables (accept-anchor / reparent / merge / defer) |
| Variant naming | `/curator/naming` | the **DU naming authority**: queue + mint `DUxxxxx` + lifecycle |
| Reconcile flags | `/curator/reconcile-flags` | merge YBrowse synonym clusters split across catalog variants |

A separate **management API** for machine/curator callers lives under
`/manage/*` (change-set lifecycle, `/manage/haplogroups/merge[/preview]`,
`/manage/curation/proposals` X-API-Key intake) — deliberately outside the public
`/api/v1`.

### Tree versioning, merge & SNP-graft

- **Change-set** lifecycle (DRAFT → READY_FOR_REVIEW → UNDER_REVIEW →
  APPLIED/DISCARDED), per-change review, diff, and a temporal apply engine
  (CREATE/UPDATE/DELETE/REPARENT/VARIANT_EDIT) — including a **WIP pass** that
  enacts curator merge-review resolutions.
- **Tree merge** (Identify-Match-Graft) — a pure `du-domain::merge`
  re-implementation against curated fixtures (the legacy was buggy):
  subtree-scoped matching, ambiguity-flagged-not-guessed, materialized into a
  reviewable change-set.
- **SNP-anchored graft** (`du-db::snp_graft`) — reconciles an external source tree
  (decoding-us now, ytree.net later) into the ISOGG foundation by SNP plurality:
  enrich matches, graft truly-novel branches, flag the rest for curator review.

### Variant Naming Authority

DecodingUs owns the `DU` Y-variant prefix. `core.du_variant_name_seq` +
`core.next_du_name()`, a curator naming queue (`UNNAMED`→`PENDING_REVIEW`→`NAMED`,
mint-on-assign with same-coordinate dedup), and a GFF3 propagation export
(`/api/v1/variants/export.gff`).

### YBrowse ingestion (the central authority document)

`snps_hg38.gff3` (~3M SNP lines, full snapshot, no deltas) is streamed into the
verbatim **`source.ybrowse_snp` mirror**; `du-db::ybrowse::reconcile` then
*derives* `core.variant` so curator decisions survive every re-ingest:
synonyms fold deterministically (strand-canonical key; INDELs VCF-trim-normalized;
MNPs left intact), existing catalog variants match by name **or coordinate** and
are enriched in place (canonical/`naming_status` locked), and clusters split
across multiple existing rows are flagged for `/curator/reconcile-flags`.

### Federation (outbound, summaries only)

A long-lived **Jetstream consumer** mirrors Navigator's published anonymized
computed-summary records into dedicated `fed.*` reporting tables (PII-bearing
records keep typed anonymized columns only). Reports aggregate via query-time SQL.
The inbound credential-holding firehose / PDS-fleet model is **dropped**;
curators submit branch proposals through the machine-auth intake endpoint.

### Genomics, STR & age

`du-bio` (BED callable-loci, UCSC chain liftover, VCF). Y-STR per-branch modal
signatures + STR→branch prediction + STR-variance age; a combined branch-age
estimate (McDonald 2021: SNP-Poisson + STR + genealogical/aDNA anchor terms,
inverse-variance combined) gap-filling `tmrca_ybp`.

### Scheduled jobs (`du-jobs`)

`db-heartbeat`, `ybrowse-variant-ingest` (GFF3 → mirror → reconcile),
`publication-update` (OpenAlex), `publication-discovery`,
`publication-pubmed-update` (NCBI), `ena-study-enrichment`,
`branch-age-recompute` (STR + combined age) — plus the Jetstream reporting-mirror
consumer. Error-isolated; each registers only when its env config is present.

### ETL (`du-migrate`)

Legacy → new schema, preserving PKs and `sample_guid` so FKs carry over 1:1;
idempotent; runs target migrations then the transformers + a reconciliation pass.
Covers the full production surface — catalog (donors, biosamples, variants, tree,
studies, publications), ident/auth (users, RBAC, AT-Protocol OAuth/PDS, consent,
audit), and genomics (labs, instruments, test types, libraries/files, alignment +
pangenome coverage, genotype data, pangenome graph). Validated against `db.schema`
(schema-only) and a current-schema mock with seed data. `decodingus-tree-init`
seeds the Y tree from ISOGG and grafts the decoding-us production tree.

## Getting started

### Prerequisites

- Rust (stable) — `cargo`.
- **Apple `container`** for the local database. First run once:
  ```sh
  container system start        # installs the default Linux kernel on first run
  ```
  (No Docker required. Any `DATABASE_URL` also works as a fallback.)

### Run the app

```sh
# Start Postgres (PostGIS) and print the DATABASE_URL to export:
eval "$(./scripts/test-db.sh up)"      # Apple container gives it its own IP

# Run the web server (connects + applies migrations on startup):
DATABASE_URL=... APP_SECRET=<32+ chars> cargo run -p du-web   # serves on :9000 (PORT to change)
```

Apple `container` assigns each container its own IP (no `localhost` port
forwarding), so `test-db.sh` discovers it and emits the right `DATABASE_URL`
(e.g. `postgres://postgres:dev@192.168.64.2:5432/decodingus`). Stop it with
`./scripts/test-db.sh down`.

> **Gotcha:** if a *committed* migration changes, recreate the dev DB — SQLx
> errors on a checksum mismatch.

### Seed a curator (to use the curator tools)

```sh
HASH=$(cargo run -q -p du-web --bin decodingus -- hash-password 'yourpassword')
# then insert ident.users + ident.user_login_info(provider_id='credentials',
# provider_key='<handle>', password_hash=$HASH) + ident.user_roles('TreeCurator').
```

## Testing

```sh
eval "$(./scripts/test-db.sh up)"
cargo test --workspace
```

Integration tests are gated on `DATABASE_URL`: with it set they run against the
live PostGIS (migrations, JSONB round-trips, query modules, the apply/merge/graft/
reconcile engines); without it they skip and the suite stays green. The i18n test
enforces that es/fr cover every English key.

## Running the ETL

The production source is a self-managed Postgres on EC2.

```sh
decodingus-migrate \
  --legacy "postgres://user:pass@ec2-host:5432/decodingus?sslmode=require" \
  --target "$DATABASE_URL"            # runs transformers + reconciliation

decodingus-migrate --legacy ... --target ... --verify   # counts only
```

Verify the transformers locally first with the mock legacy DB (`scripts/mock-legacy.sql`).

> ⚠️ The transformer `SELECT`s encode the production column layout — validate
> against the live EC2 schema (or a current-schema dump) before the production run.

## Seeding / ingesting the tree & variants

```sh
# Seed the Y tree from ISOGG, then graft the decoding-us prod tree (Phase 2/3/4):
decodingus-tree-init --isogg /path/isogg_full_tree.json --apply
decodingus-tree-init --merge-prod https://decoding-us.com/api/v1/y-tree --snp-graft --graft --apply
decodingus-tree-init --merge-prod ... --snp-graft --stage-review   # → /curator/reviews

# YBrowse variant ingest (mirror + reconcile); deploy-time, large file:
YBROWSE_GFF=/path/snps_hg38.gff3 [YBROWSE_CHAIN_GRCH37=… YBROWSE_CHAIN_HS1=…] cargo run -p du-jobs
```

## Deploy

Multi-stage `Dockerfile` builds a single binary on a slim runtime (no JRE, no C
deps); `compose.yaml` runs it with `postgis/postgis`. `SQLX_OFFLINE=true` for
DB-less builds. The shared crates are git deps (no sibling path needed in the
build context).

## Roadmap

**Done** (✅): redesigned schema + temporal tree; `du-db` aggregates; public read
surface + JSON API + OpenAPI; auth + the full curator suite; tree versioning +
merge + SNP-graft + curator merge-review; the variant naming authority; YBrowse
GFF3 ingestion (mirror + reconcile, synonym/strand/INDEL handling); federated
reporting mirror + reports; STR signature/prediction + combined branch age;
`du-bio` core; the scheduled-job suite; the full production ETL; shared crates
extracted to `decodingus-shared` (git deps).

**Remaining, in scope** (⬜):

- [ ] **AT-Protocol OAuth — live handshake.** Client wiring is built and verified
      to the consent page against a local PDS; the confidential
      `private_key_jwt` round-trip is the **Edge joint test** (see
      `docs/atproto-oauth-findings.md`).
- [ ] **ETL cutover.** Validate the transformer SQL against a current-schema dump
      or read-only EC2 rehearsal, then `decodingus-migrate --verify`.
- [ ] **Region management API + bootstrap-from-CHM13** (the S3/CHM13 pipeline; the
      region CRUD UI already exists).
- [ ] **Discovery automation** — the curator review/promote half is built; the
      automated half (private-variant capture, consensus, auto-reassignment) is
      forward work.
- [ ] **Multi-test-type completion** — taxonomy + chip ingest exist; marker
      coverage / confidence scoring tables are forward work.

**Out of scope / not in production** (➖): inbound PDS firehose + fleet, IBD
matching, social/messaging/reputation, group projects, patronage/billing,
sequencer-lab inference, AppView→PDS backfeed (superseded by the outbound mirror /
notify-fetch direction). Several have placeholder tables but no logic.
