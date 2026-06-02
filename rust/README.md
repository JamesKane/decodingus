# DecodingUs — Rust port

A work-in-progress rewrite of the DecodingUs platform (originally Play Framework
/ Scala 3) in Rust. It coexists with the Scala app under `rust/` during the
transition and will replace it at a single cutover.

**Status:** foundation + public read surface + auth/curator + ETL are working and
verified against a live PostGIS database. Workspace builds clean; **11/11 tests
pass**. Not yet production-complete — see [Roadmap](#roadmap).

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
| Genomics | `du-bio` — coordinate math + text parsing (VCF/BED/chain liftover). Raw reads (BAM/CRAM) + calling are out of scope (done in Navigator) |
| Async | **tokio** |
| Auth | Argon2 (bcrypt-verify fallback), signed-cookie sessions |
| i18n | embedded `key=value` catalogs (en/es/fr) |
| Local Postgres | Apple `container` running `imresamu/postgis` (arm64) |

## Workspace layout

Shared crates live in the sibling **`decodingus-shared`** repo (also used by
Navigator); decodingus pulls them via path deps (→ git deps once pushed):

```
../decodingus-shared/crates/
    du-domain/    pure types/enums/IDs + JSONB payload structs, no IO
    du-atproto/   AT Protocol identity/crypto + OAuth client (PKCE/DPoP/metadata)
    du-bio/       genomics: callable-loci (BED), liftover (UCSC chain), VCF, YBrowse

rust/                          (this repo — AppView/server-specific)
  crates/
    du-db/        SQLx pool + per-aggregate query modules
    du-external/  OpenAlex / ENA / AWS SES / Secrets — scaffold
    du-web/        Axum app: routes, Askama templates, i18n, HTMX, auth, OAuth
    du-jobs/       tokio scheduler harness + DB heartbeat (real jobs as clients land)
    du-migrate/    one-time legacy -> new-schema ETL
  migrations/     redesigned schema (0001–0009)
  locales/        en / es / fr message catalogs
  scripts/        test-db.sh (Apple container), mock-legacy.sql
  Dockerfile, compose.yaml, .env.example
```

## Schema redesign (`migrations/0001`–`0009`)

Ten Postgres schemas: `core`, `tree`, `genomics`, `pubs`, `ident`, `ibd`, `fed`,
`social`, `support`, `billing`. Key de-sprawl moves:

- **3 biosample tables → 1** `core.biosample` (a `source` enum discriminator +
  `source_attrs` JSONB).
- **Deprecated child tables folded into JSONB** on their parents (variant aliases
  & coordinates, sequence-file checksums/locations, alignment coverage, original
  haplogroups).
- The legacy second **"metadata" database collapses into the `fed` schema**.
- Scattered `at_uri`/`at_cid` columns → one consistent **`atproto` JSONB** column.
- PostGIS (`geometry(Point,4326)`), `citext`, native enums, GIN/GiST/expression
  indexes on queried JSONB paths.

## What's implemented

**Public surface** (server-rendered, HTMX, i18n en/es/fr):

| Area | Routes |
|---|---|
| Home | `/` |
| Variant browser | `/variants` (+ list/detail fragments; JSONB alias search) |
| Y/MT tree | `/ytree` `/mtree` (unified page/fragment via `HX-Request`) |
| References + biosample report | `/references` (+ list / per-publication report) |
| Biosample map | `/biosamples/map` (PostGIS `ST_X/ST_Y` → Leaflet GeoJSON) |
| Coverage benchmarks | `/coverage-benchmarks` (coverage-JSONB aggregation) |
| Health | `/health` |

**Auth & curator** (Argon2 + signed-cookie sessions, RBAC):

- `/login` `/logout`; `Curator` route guard (`TreeCurator`/`Admin`).
- Two-panel HTMX CRUD for **haplogroups**, **variants**, and **genome regions**
  (the region editor edits the coordinates/properties JSONB as validated JSON).
  Mutations are server-driven via `HX-Trigger` (the panel returns + the list
  reloads), with delete guards for referenced rows.

**ETL** (`du-migrate`): legacy → new schema, preserving PKs and `sample_guid` so
FKs carry over 1:1; idempotent; applies target migrations then runs the
transformers + a reconciliation pass. The transformers are written against the
**current production schema** (`db.schema`): positional `public.variant` +
`variant_alias` → JSONB `coordinates`/`aliases`; the three biosample tables +
their `*_original_haplogroup` tables → unified `core.biosample` with `atproto`
and `original_haplogroups` JSONB; `tree.haplogroup` age bounds → `provenance`
JSONB; both publication↔(std|citizen)biosample link tables → one
`pubs.publication_biosample` on `sample_guid`. The **ident/auth** group carries
UUID PKs over 1:1 (`public.users`→`ident.users`, the `auth.*` RBAC/OAuth/PDS/
consent tables, and `curator.audit_log`→`ident.audit_log`); pre-seeded base
roles are relocated onto the legacy role UUIDs so `user_roles` FKs resolve, and
`password_hash` stays NULL (production auth is AT Protocol OAuth, not passwords).
The **genomics** group folds the `alignment_coverage` /
`pangenome_alignment_coverage` child tables (plus inline Picard metrics) into the
`coverage`/`metadata` JSONB the coverage page reads (`meanDepth`,
`percent_coverage_at_10x`), resolves `sequence_library.lab` names to migrated lab
ids, dedups `sequencer_instrument` on `instrument_id`, and skips soft-deleted
`genotype_data`. Validated two ways: schema-only
against `db.schema` (0 column errors) and end-to-end against a current-schema
mock with seed data (all 10 aggregates reconcile; JSONB shapes spot-checked).
See below.

## Getting started

### Prerequisites

- Rust (stable/nightly) — `cargo`.
- **Apple `container`** for the local database. First run once:
  ```sh
  container system start        # installs the default Linux kernel on first run
  ```
  (No Docker required. Any `DATABASE_URL` also works as a fallback.)
- *Optional:* `sqlx-cli` (`cargo install sqlx-cli --no-default-features --features postgres,rustls`)
  to auto-apply migrations and, later, enable compile-time `query!` checking.

### Run the app

```sh
# Start Postgres (PostGIS) and print the DATABASE_URL to export:
eval "$(./scripts/test-db.sh up)"      # Apple container gives it its own IP

# Run the web server (connects + applies migrations on startup):
cargo run -p du-web --bin decodingus   # serves on http://localhost:9000
```

Apple `container` assigns each container its own IP (no `localhost` port
forwarding), so `test-db.sh` discovers it and emits the right `DATABASE_URL`
(e.g. `postgres://postgres:dev@192.168.64.2:5432/decodingus`). Stop it with
`./scripts/test-db.sh down`.

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
live PostGIS (migrations, JSONB round-trips, query modules); without it they skip
and the suite stays green. The i18n test enforces that es/fr cover every English
key.

## Running the ETL

The production source is a self-managed Postgres on EC2.

```sh
decodingus-migrate \
  --legacy "postgres://user:pass@ec2-host:5432/decodingus?sslmode=require" \
  --target "$DATABASE_URL"            # runs transformers + reconciliation

decodingus-migrate --legacy ... --target ... --verify   # counts only
```

Verify the transformers locally first with the mock legacy DB:

```sh
# create decodingus_legacy + decodingus_etl, load scripts/mock-legacy.sql into the
# former and the migrations into the latter, then run the ETL between them.
```

> ⚠️ The transformer `SELECT`s encode the *reconstructed* legacy column layout.
> Validate them against the live EC2 schema before the production run.

## Deploy

Multi-stage `Dockerfile` builds a single binary on a slim runtime (no JRE, no C
deps); `compose.yaml` runs it with `postgis/postgis`. `SQLX_OFFLINE=true` is set
for DB-less builds.

## Roadmap

- [x] Workspace + redesigned schema (verified on live PostGIS)
- [x] `du-db` query modules + read-side domain types
- [x] Public read surface (trees, variants, references, map, coverage)
- [x] Asset vendoring, i18n (en/es/fr), `HX-Request` negotiation
- [x] Session auth + RBAC; curator CRUD (haplogroups, variants, regions)
- [x] Tree versioning: change-set lifecycle (DRAFT→READY_FOR_REVIEW→
      UNDER_REVIEW→APPLIED/DISCARDED), per-change review/approve-all, diff, and
      an apply engine that writes the production tree via the temporal edge model
      (CREATE/UPDATE/DELETE/REPARENT/VARIANT_EDIT) — curator-gated management API
      at `/api/v1/manage/change-sets/*`; apply engine integration-tested
- [ ] Tree merge (Identify-Match-Graft) — re-implementation against curated
      fixtures (legacy is buggy); produces change-sets + WIP staging rows
- [x] Public JSON API (`/api/v1/*`): tree (y/mt), coverage benchmarks,
      references + per-publication biosamples, biosample studies, variant
      search/detail/by-haplogroup, variant CSV export, genome-region builds —
      clean DTOs, OpenAPI 3 spec + Swagger UI at `/api` (utoipa, Tapir analog)
- [x] `du-migrate` ETL: catalog aggregates (donors, biosamples, variants, tree,
      studies, publications), **ident/auth** (users, RBAC, AT Protocol OAuth/PDS,
      consent, curator audit), and **genomics** (labs, instruments, test types,
      sequencing libraries/files, alignment + pangenome coverage, genotype data,
      pangenome graph) — verified vs current-schema mock DB. This is the full
      production ETL surface (ibd/fed/social/billing are not yet in production).
- [ ] ETL: validate read SQL against a current-schema dump or read-only EC2
      rehearsal before cutover
- [x] `du-bio` core: callable-loci (BED), liftover (UCSC chain), VCF reader
- [x] `du-jobs` scheduler harness (tokio; error-isolated jobs + run-on-start)
- [x] `du-external` OpenAlex + ENA clients (parsing unit-tested; abstract
      reconstruction from the inverted index) + publication jobs
      (`publication-update` enrichment by DOI, `publication-discovery` by search;
      rate-limited, env-gated on `OPENALEX_MAILTO`) — verified live against OpenAlex
- [x] `du-external` transactional email (`Mailer`) + secrets (`CachedSecrets`,
      1h TTL): logging mailer + env-secret source by default (testable/lean);
      Amazon SES v2 + Secrets Manager behind the optional `aws` feature
      (compiles; prod builds with `--features aws`). Consumers wired as the
      contact/patron flows + secret-backed config land.
- [x] YBrowse variant ingest: GRCh38 VCF → lift to GRCh37/hs1 (chain files) →
      multi-build `core.variant` upsert (`du-bio::ybrowse` + `du-jobs`, env-gated)
- [ ] Remaining ingestion (HipSTR, genome regions); publication/match jobs once
      du-external lands. (Raw reads BAM/CRAM + variant calling are out of scope —
      Navigator does local calling; the AppView aggregates summaries/proposals.)
- [x] AT Protocol identity/crypto core (`du-atproto`): DID/AT-URI parse, did:key
      Ed25519 verification, DID-doc/PDS resolution
- [x] AT Protocol OAuth **client wiring** — confidential web client
      (`private_key_jwt`/DPoP/ES256, client-metadata + JWKS, PAR/token flow) **and**
      public-client builders (PKCE-only) for the Navigator desktop app to reuse.
      See `docs/atproto-oauth-findings.md` + `docs/atproto-edge-reply.md`.
- [x] Curation **proposal intake** (`POST /api/v1/curation/proposals`, X-API-Key →
      OAuth bearer later) with pooling/consensus, and a curator **review queue**
      (`/curator/proposals`: list/detail/approve-reject-defer → `curator_action`).
      Manual sample-entry APIs intentionally **dropped** (curators work in Navigator).
- [x] **Promote** an accepted proposal into the named catalog: creates the
      `tree.haplogroup` branch under its parent + relationship edge + `core.variant`
      links from the evidence (status → PROMOTED, `curator_action` recorded);
      the branch appears immediately in the public Y/MT tree.
- [ ] Federated ingest, remaining: **Jetstream/relay discovery** (URI index)
      + on-demand coverage aggregation. NB: standard relay/Jetstream
      ingest stays (reads are out of OAuth scope); only the custom REST/Kafka relay
      is dropped.
- [x] Extracted `du-domain`/`du-atproto`/`du-bio` to the sibling `decodingus-shared`
      repo (consumed via path deps for now); haploid caller stays Navigator-only.
      **Follow-up:** push `decodingus-shared` to a remote, then flip the three deps
      to git deps (pinned tag) — also fixes the Docker build (path deps to a
      sibling aren't in the `rust/` build context).
- [ ] `du-external` remaining: reCAPTCHA (contact form); wire ENA study
      enrichment + the SES mailer / secret-backed config into their consumers
- [ ] Tree-versioning change-sets; haplogroup↔variant association editing
- [ ] Vendor remaining assets; full OpenAPI parity; cutover rehearsal

> The tree-merge algorithm is a known-buggy area in the legacy app and will be
> re-implemented (not faithfully ported) when that subsystem lands.
