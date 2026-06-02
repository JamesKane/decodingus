# DecodingUs Rust rewrite — status & handoff

Living snapshot of the Play/Scala 3 → Rust port. Pairs with `README.md` (roadmap)
and the plan at `~/.claude/plans/robust-knitting-lampson.md`. Last updated after
the tree-versioning + merge work.

## TL;DR

The **spine is done**: redesigned schema, data layer, public HTML/HTMX surface,
auth + curator tools, the full production ETL, the public JSON API, and tree
versioning + merge (end-to-end). The big remaining mass is **federation HTTP
endpoints**. Several subsystems are intentionally absent (see "Out of scope").

Measured against the *full* legacy Scala app this is still less than half by
surface area — but most of the missing half is either deliberately gone (moved to
Navigator/edge, or not in production) or concentrated in federation.

## Layout

- **`/Users/jkane/Development/decodingus/rust`** — this workspace (AppView-only crates).
  - `du-db`, `du-external`, `du-web`, `du-jobs`, `du-migrate`
- **`/Users/jkane/Development/decodingus-shared/crates`** — shared crates, separate git repo.
  - `du-domain` (pure types + algorithms, incl. `merge`), `du-atproto`, `du-bio`
  - Referenced via path deps (`../../decodingus-shared/crates/*`). **Not pushed
    to a remote yet** → flip to git deps once pushed (also unblocks Docker build).
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
- Tests: `DATABASE_URL=... cargo test -p du-db` (live-DB tests skip/pass if unset).
  - `du-domain` tests need no DB (`cargo test -p du-domain`).
- Migrations auto-apply on web/ETL startup; the `du-db` migrations test also applies them.
- **Gotcha:** if a *committed* migration changes, recreate the dev DB
  (`decodingus`) — sqlx errors on a checksum mismatch (`VersionMismatch`).

### Databases in use
- `decodingus` — dev DB (migrations + live tests).
- `decodingus_legacy` — loaded from `scripts/mock-legacy.sql` (current-schema mock).
- `decodingus_etl` — ETL target (the migrate binary recreates/migrates it).

## What's done (✅)

- **Schema** — `migrations/0001–0010`. JSONB "document columns" (variant
  coordinates/aliases, biosample source_attrs/atproto, haplogroup provenance,
  coverage, …). `ident.audit_log` added in 0010.
- **`du-db`** — query modules for every aggregate (variant, haplogroup,
  biosample, publication, genome_region, coverage, proposal, study, change_set,
  merge, auth).
- **Public HTML/HTMX** (`du-web/routes`) — variants browser, Y/MT tree,
  references + per-pub biosamples, biosample map (PostGIS), coverage benchmarks;
  i18n (en/es/fr), `HX-Request` fragment negotiation, vendored assets.
- **Auth + curator** — signed-cookie sessions, `Curator` RBAC extractor, curator
  CRUD for haplogroups/variants/genome-regions, curation proposal
  intake→review→promote.
- **ETL** (`du-migrate`) — **full production surface**: catalog (donors,
  biosamples, variants, tree, studies, publications), ident/auth, genomics.
  Validated vs `db.schema` (schema-only) and the current-schema mock with data;
  all aggregates reconcile.
- **Public JSON API** (`du-web/api.rs`) — 13 read endpoints under `/api/v1/*` +
  OpenAPI 3 + Swagger UI at `/api` (utoipa). Clean DTOs.
- **Tree versioning** (`du-db/change_set.rs`, `du-web/routes/versioning.rs`) —
  change-set lifecycle (DRAFT→READY_FOR_REVIEW→UNDER_REVIEW→APPLIED/DISCARDED),
  per-change review/approve-all, diff, and a temporal apply engine
  (CREATE/UPDATE/DELETE/REPARENT/VARIANT_EDIT). Curator-gated management API at
  `/api/v1/manage/change-sets/*`. Integration-tested.
- **Tree merge** (`du-domain/merge.rs` + `du-db/merge.rs`) — pure Identify-Match-
  Graft re-implementation (subtree-scoped matching = recurrent-SNP guard;
  full-match / contraction+downflow / descendant / new / ambiguity-flagged).
  `materialize` → change-set via placeholder-chained `tree_change`; apply resolves
  placeholders. Endpoints `/api/v1/manage/haplogroups/merge[/preview]`.
  Fixtures + end-to-end tests pass.
- **`du-bio`** — BED callable-loci, UCSC chain liftover, VCF reader, YBrowse ingest.
- **`du-bio`** — BED callable-loci, UCSC chain liftover, VCF reader, YBrowse ingest.
- **Federated reporting mirror** (`du-db/src/fed/`, `du-jobs/jetstream.rs`,
  migrations 0011–0012) — the AppView **aggregates and reports; it does not
  analyze.** A long-lived Jetstream consumer mirrors Navigator's published
  anonymized computed-summary records (the legacy `✅ AppView Complete` set:
  alignment coverage, biosample, sequencerun, project, workspace, genotype,
  populationBreakdown, haplogroupReconciliation) into dedicated `fed.*` reporting
  tables keyed `(did, rkey)`. Cursor-resumed, reconnecting, idempotent+ordered
  upsert; reports aggregate via query-time SQL (`coverage::aggregate_by_build`,
  `analytics::super_population_distribution`). **Privacy:** PII-bearing records
  (biosample/sequencerun/project/workspace) keep typed anonymized columns only —
  no raw JSONB, so donorIdentifier/accession/description/file paths can't leak;
  analytics records keep the computed payload minus `files`. **Not** the dropped
  full-CRUD raw-data mirror (summaries only). Live-DB + unit tested (incl. PII
  drop). The reporting **web endpoints** over these tables (flow c) are next.
- **`du-jobs`** — tokio scheduler; jobs: `db-heartbeat`, `ybrowse-variant-ingest`,
  `publication-update`, `publication-discovery`; plus the Jetstream reporting-mirror
  consumer (set `JETSTREAM_URL`; runs beside the scheduler, not on an interval).
- **`du-external`** — OpenAlex, ENA; AWS SES + Secrets Manager behind the `aws`
  feature (1h TTL secret cache).
- **`du-atproto`** — DID/handle resolution, Ed25519 verify, PKCE/DPoP/private-key-
  JWT OAuth client + metadata builders (library; HTTP surface not wired — see below).

## What's left, in scope (⬜)

Roughly in priority order:

1. **Federation — AppView aggregates + reports (NOT the legacy PDS fleet, NOT a
   raw-data mirror).** The `fed.pds_node` / `pds_heartbeat` / fleet-admin tables
   (migration 0008) map to the **dropped** network-mirror design — don't build
   registration / heartbeat / fleet endpoints. The federated flows: **(a) proposal
   intake + curator review queue — DONE** (`/api/v1/curation/proposals` X-API-Key
   intake → `tree.proposed_branch` → `/curator/proposals` review/promote);
   **(b) reporting-mirror ingest — DONE** (Jetstream → `fed.*` reporting tables for
   the full `✅ AppView Complete` summary set, see "What's done"). Remaining:
   **(c) the reporting web endpoints** over the mirror (query-time SQL; e.g.
   coverage-by-build and ancestry super-population distribution — `du_db::fed::
   coverage::aggregate_by_build` / `analytics::super_population_distribution` are
   the seeds; add `/api/v1/...` routes + DTOs). See memory
   `atproto-federation-direction` for the full re-scope + privacy boundary.
2. **Live AT Protocol OAuth handshake** — scaffolded in `du-web/oauth.rs`; needs a
   test PDS and joint testing with the Edge team. See `docs/atproto-*.md`.
3. **Remaining scheduled jobs** — `variant-export`, `match-discovery`, ENA study
   enrichment (`du-jobs/src/main.rs:82` TODO). `variant-export` could back a file
   artifact, but `/api/v1/variants/export` already streams CSV live.
4. **Curator HTML UI for change-sets/merge review** — only the JSON management API
   exists; the two-panel HTMX review screens aren't built.
5. **Secondary web surfaces** — profile, contact/support + my-messages, cookie-
   consent UI, app-password help, static pages (home/FAQ/terms/privacy),
   sitemap.xml/robots.txt.
6. **reCAPTCHA** (contact form), **NCBI** client.
7. **OpenAPI for the management API** — only the public read API is documented;
   the `/api/v1/manage/*` curator endpoints aren't in the spec.
8. **WIP shadow-table staging UI** — `tree.wip_*` tables exist but are unused;
   merge takes the simpler placeholder path. Only needed for a richer curator
   pre-apply editing flow.

## Out of scope / deliberately absent (➖)

- **Manual sample-ingestion APIs** (standard/PGP/external biosample create +
  sequences + publication-link) — curators use Navigator now; the AppView keeps
  catalog **review + naming** only.
- **BAM/CRAM extraction + variant calling** — done at the edge (Navigator); the
  AppView aggregates summaries/proposals (so `du-bio` is text + coordinate math, no htslib/noodles).
- **IBD/matching, patronage/billing, social/reputation** — not in production yet
  (schemas `0007`/`0009` exist as placeholders; no ETL, no endpoints).

## Cutover blocker

The 35MB `/Volumes/nas/stuff/dump.sql` **predates** the current `~/db.schema`
(no `tree` schema, `citizen_biosample_did` vs `at_uri`, no `*_result` columns).
So the ETL is validated against a current-schema mock, **not** real data. Before
cutover: get a **current-schema dump** or do a **read-only EC2 rehearsal**
(production runs on a self-managed EC2 instance), then `decodingus-migrate
--verify`.

## Key decisions & gotchas (don't relearn these)

- **Tree merge was buggy in legacy** → re-implemented against curated fixtures,
  NOT golden-tested against legacy output. Conservative: flag ambiguities, don't guess.
- **Merge materialization** uses `tree_change` + negative placeholders through the
  tested apply engine; the `wip_*` tables are reserved for a future staging UI.
- **Temporal DELETE**: nodes are temporal (`valid_until`); DELETE expires the node
  and `roots`/`children`/`subtree`/`existing_tree` exclude expired nodes.
- **Enums** fetched as `::text` + parsed (`parse_pg_enum`); JSONB via `Json<T>`.
- **utoipa** kept out of `du-domain` (shared with Navigator/edge); API DTOs +
  `From` impls live in `du-web/api.rs`. Recursive `HaplogroupNodeDto.children`
  needs `#[schema(no_recursion)]` or schema-gen stack-overflows at startup.
- **Management API auth**: session/`Curator` (legacy used X-API-Key). Unauth →
  303 to /login even for JSON endpoints.
- **`DbError::Conflict` → HTTP 422** (mapped in `du-web/error.rs`).
- **ETL preserves PKs** via `OVERRIDING SYSTEM VALUE` + `sample_guid`; sequences
  fixed up post-load; idempotent upserts.

## Resume checklist

1. `eval "$(./scripts/test-db.sh up)"` (or set `DATABASE_URL`); confirm `du-pg`
   container is running.
2. `cargo test --workspace` (du-domain needs no DB; du-db live tests need DATABASE_URL).
3. Pick the next arc — **federation endpoints** is the recommended next mass.
4. Reload the mock if needed: recreate `decodingus_legacy`, load
   `scripts/mock-legacy.sql`; recreate `decodingus_etl`; run `decodingus-migrate`.

## Reference paths

- Plan: `~/.claude/plans/robust-knitting-lampson.md`
- Prod schema (authoritative for ETL): `~/db.schema`
- Old data dump (stale): `/Volumes/nas/stuff/dump.sql`
- AT Proto notes: `docs/atproto-oauth-findings.md`, `docs/atproto-edge-reply.md`
- Navigator atmosphere docs: `/Users/jkane/Development/scala/DUNavigator/documents/atmosphere`
