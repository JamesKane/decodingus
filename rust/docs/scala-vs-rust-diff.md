# Scala (legacy) ↔ Rust (rewrite) — functional difference catalog

What the legacy Play/Scala app does vs. what the Rust AppView does, by domain.
Derived from a survey of the Scala surface (47 controllers, ~385 routes, ~490
files, Slick model, Pekko/Quartz jobs) cross-referenced against the Rust
workspace (`rust/`, verified route + schema inventory, 2026-06).

**Legend:** ✅ ported (equivalent) · 🔁 re-scoped/replaced by design · 🟡 partial
(core ported, sub-features missing) · ⬜ in scope, not yet built · ➖ dropped
(deliberately out of scope) · 🌐 built but externally-gated.

## TL;DR — the shape of the difference

The Rust app is the **AppView**: a curated catalog + public read surface +
federated aggregation, with a **narrowed, inverted federation model** and several
legacy subsystems deliberately gone.

- **Biggest re-scope — federation.** Scala ran a **credential-holding relay**: an
  inbound `POST /api/firehose/event` ingesting 11 collection types, a PDS fleet
  (register/heartbeat/submissions/config), and an IBD relay (WebSocket) — all
  authenticated by holding PDS keys / app passwords. Rust **inverts and narrows**
  this: an **outbound Jetstream consumer** mirrors anonymized *summary* records
  into `fed.*` reporting tables, plus a single machine-auth **proposal-intake**
  endpoint. No inbound firehose, no fleet, no relay.
- **Auth replaced/upgraded.** Scala = app-password login + a bespoke
  PDS-signature credential-holding scheme. Rust = proper **AT Protocol OAuth**
  (PKCE, DPoP, `private_key_jwt`), app passwords deprecated.
- **Manual sample ingestion dropped.** Scala had hand-entry biosample/donor/
  sequence/publication-link endpoints (standard/external/PGP). Rust drops these —
  curators work in Navigator; the AppView keeps catalog **review + naming** only.
- **Whole subsystems dropped/not-in-production:** IBD matching, social/reputation/
  messaging, group projects, patronage/billing, the sequencer-lab inference, STR
  profiles. (Several have placeholder tables but no logic/endpoints.)
- **Heavy genomics confirmed edge-only on both sides:** neither app does BAM/CRAM
  extraction or variant calling server-side (Navigator/edge does it).

---

## 1. Public HTTP / UI surface

| Capability (Scala) | Rust | Notes |
|:---|:--|:---|
| Home / nav | ✅ | `/` |
| FAQ, Terms, Privacy, App-password help | ✅ | `/faq /terms /privacy /help/app-password` |
| About | ✅ | Rust-only consolidation of "content" pages |
| Reputation, How-to-submit-tree static pages | ➖ | reputation is social (out of scope); how-to-submit not ported |
| sitemap.xml, robots.txt, health | ✅ | |
| Login / logout | ✅ | session cookie |
| App-password auth | 🔁 | replaced by OAuth; app passwords deprecated |
| Cookie consent | 🟡 | Rust: `POST /cookie-consent` + JS banner. Scala also had `GET /cookies/check`; Rust checks the cookie client-side |
| Profile view | 🟡 | Rust `/profile` is **read-only**; Scala also had `POST /profile` (update display name) — not ported |
| Language switch | ✅ | `/language/:lang` |
| Y/MT tree — two SVG cladogram render modes (horizontal + vertical) | ✅ | **Rewritten 2026-06** (`du-web/tree_layout.rs` ports `TreeLayoutService`): server-computed inline SVG, breadcrumb re-root, `?orient=h\|v` toggle persisted to `tree_orient` cookie, search-by-name-or-variant, backbone/recent node coloring + legend, fixed depth window (re-root descends) replacing the legacy backbone-collapse. `/ytree /mtree`, HTMX `#tree-container` fragment, `/api/v1/{y,mt}-tree` |
| SNP sidebar fragment | ✅ | `GET /{y,mt}tree/snp/:name` → HTMX `#snpSidebar`; lists defining variants (name/type/aliases/coords) |
| Variant browser + fragments + by-id + by-haplogroup API | ✅ | |
| Variant export | 🔁 | Scala: daily **gzipped JSONL** file artifact + metadata. Rust: **live CSV** stream `/api/v1/variants/export` + `/export/metadata` |
| References/publications list + API | ✅ | |
| Public "submit publication" DOI form | ✅ | **Built 2026-06** `GET/POST /references/submit` (`references.rs`): resolves the DOI via OpenAlex and queues a pending `publication_candidate` for curator review (never a published reference directly) — feeds `/curator/publications`. reCAPTCHA-guarded when configured |
| Biosample map (PostGIS) + geo-data + studies API | ✅ | |
| Coverage benchmarks UI + API | ✅ | |
| Coverage per-lab list + lab-benchmark fragments | 🟡 | Scala had `/coverage/labs` + per-lab HTMX fragments; Rust serves the aggregate benchmark only |
| Genome-regions public API (builds + by-build) | ✅ | |
| Contact form | ✅ | `/contact` → `support.contact_message` (+ reCAPTCHA when configured) |
| My-messages (user threads + badge) | ➖ | rides social/messaging (out of scope) |
| Sequencer lab-by-instrument lookup API | ➖ | lab-inference subsystem deferred |
| Inbound firehose event endpoint | 🔁 | see Federation — replaced by outbound Jetstream consume |
| PDS registration endpoint | ➖ | fleet model dropped |
| IBD discovery/match/relay endpoints | ➖ | IBD not in production |
| Legacy project CRUD endpoints | ➖ | were already deprecated in Scala |
| OpenAPI / Swagger UI | ✅ | Rust documents the **public read API only**; mgmt/curation deliberately excluded |
| **Federated population reports** | ➕ | **Rust-new:** `/api/v1/reports/{coverage,ancestry,haplogroups}` over the `fed.*` mirror |

## 2. Curator / admin surface

| Capability (Scala) | Rust | Notes |
|:---|:--|:---|
| Change-set lifecycle (list/detail/start-review/apply/discard/approve-all/per-change review/comments/diff) | ✅ | JSON `/manage/change-sets/*` + HTMX UI `/curator/change-sets` |
| Change-set conflict resolution (resolve reparent/edit-variants/merge-existing/defer, deferred list, tree-preview, ambiguity report) | ✅ | **Built 2026-06** (`du-db/wip.rs` + `du-web/routes/reviews.rs`, `/curator/reviews`): SNP-graft Phase-4 flags + name-collisions + graft-blocked items are staged into the `tree.wip_*` tables (`tree-init --stage-review`); a two-panel HTMX screen shows each with SNP-scatter context + tree-preview and a resolution form (accept-anchor / reparent / merge-existing / defer); decisions (`wip_resolution`) are enacted by the change-set apply engine's WIP pass. Remaining: `EDIT_VARIANTS` resolution + cascading a graft-blocked *subtree* from one decision |
| Haplogroup merge (full + preview) | ✅ | `/manage/haplogroups/merge[/preview]` |
| Haplogroup merge — explicit subtree endpoint | 🟡 | Rust's merge algorithm is subtree-scoped by design; no separate `/merge/subtree` route |
| Haplogroup CRUD | ✅ | `/curator/haplogroups/*` |
| Haplogroup restructure (split / merge-into-parent / reparent as discrete ops) | ⬜ | in Rust these are expressed as change-set `tree_change`s, not standalone curator endpoints |
| Variant CRUD | ✅ | `/curator/variants/*` |
| Haplogroup↔variant associate/remove | ✅ | curator |
| Haplogroup↔variant association history | 🟡 | `ident.audit_log` exists (mig 0010); no per-association history route |
| Genome-region curation (CRUD UI) | ✅ | `/curator/regions/*` |
| Genome-region management API (+ bulk + bootstrap-from-CHM13) | ⬜ | Rust does region ingestion via jobs/ETL (du-bio), not a curator API |
| Genomics admin manual triggers (YBrowse/HipSTR/regions bootstrap) | 🔁 | Rust runs **YBrowse ingest as a scheduled job**; no manual admin trigger endpoints; HipSTR not ported |
| Curation/discovery proposals — intake → review → name → promote (proposed branches) | ✅ | `/manage/curation/proposals` (X-API-Key) → `/curator/proposals` review/promote → `tree.proposed_branch` → catalog |
| Publication-candidate review UI (accept/reject/defer/bulk) | ✅ | **Built 2026-06** (`du-db/publication.rs` candidate fns + `du-web/routes/publications.rs`, `/curator/publications`): status-filtered queue + review panel (title/journal/date/DOI/abstract/relevance) with Accept (promote → `pubs.publication`) / Reject / Defer. Single-item; **bulk** actions not yet built |
| Sequencing-lab admin CRUD | ➖ | lab-inference deferred |
| Instrument/sequencer proposals review | ➖ | lab-inference deferred |
| Support admin (message triage/reply/status) | ➖ | rides messaging (out of scope) |
| Biosample original-haplogroup assignment (per-publication) | ➖ | manual-ingestion concern → Navigator |
| Curator dashboard | ✅ | `/curator` |

## 3. Federation / AT Protocol / identity

| Capability (Scala) | Rust | Notes |
|:---|:--|:---|
| Inbound firehose `POST /api/firehose/event` (11 collection handlers, credential-holding) | 🔁 | **Replaced** by an outbound **Jetstream consumer** mirroring published *summary* records into `fed.*` (alignment/biosample/sequencerun/project/workspace/genotype/populationBreakdown/haplogroupReconciliation) |
| Branch-discovery harvest of `privateVariants` from ingested biosamples | 🔁 | replaced by the **proposal-intake** API (Navigator submits → pool/consensus → curator) |
| instrumentObservation / matchConsent / matchRequest / groupProject / projectMembership handlers | ➖ | lab-inference / IBD / social — out of scope |
| PDS registration + fleet (heartbeat, submissions, config, node removal) | ➖ | the credential-holding fleet is the dropped network-mirror; `fed.pds_*` tables exist but are unused |
| IBD matching: discovery/suggestions, requests, consent, WebSocket relay | ➖ | IBD not in production (`ibd` schema is a placeholder) |
| Auth: app-password login + PDS-signature (Ed25519/P-256) credential-holding verification | 🔁 | **Replaced** by AT Protocol **OAuth** — PKCE(S256), DPoP, `private_key_jwt` confidential client + public/loopback client; served `client-metadata.json`/`jwks.json` |
| AT Proto OAuth (auth-server/client metadata models only, endpoints unimplemented) | 🌐 | Rust **implements** the handshake; verified live to consent against a local PDS; confidential round-trip is the Edge joint test |
| DID/handle resolution (DNS+well-known, did:plc/did:web), PDS discovery | ✅ | `du-atproto` |
| Patronage / billing API (subscriptions, tiers, Stripe/PayPal) | ➖ | not in production (`billing` placeholder) |

## 4. Sample ingestion / donors / sequencing / genomics

| Capability (Scala) | Rust | Notes |
|:---|:--|:---|
| Biosample create/update — standard, external/citizen, PGP (manual) | ➖ | manual sample-entry APIs dropped — curators use Navigator |
| Sequence-data + file-metadata linking, publication linking, haplogroup assignment (manual) | ➖ | dropped (manual ingestion) |
| Specimen-donor merge (conflict strategies) | ➖ | manual ingestion concern |
| Sequencer↔lab association + proposals | ➖ | lab-inference deferred |
| Projects (controller scaffolded/empty in Scala) | 🟡 | Rust mirrors `project` as a read-only `fed.*` reporting row; no project management |
| YBrowse Y-SNP ingest (GFF3 parse, normalize, **liftover** to GRCh38/GRCh37/hs1) | ✅ | `du-bio` (BED/chain liftover, VCF) + `ybrowse-variant-ingest` job |
| HipSTR STR ingest + liftover | ➖ | STR subsystem not in production |
| Genome-region bootstrap from S3/CHM13 + liftover | 🟡 | du-bio has the liftover; the S3-bootstrap pipeline isn't ported (regions seeded via migrations/ETL) |
| BAM/CRAM extraction, coverage compute, variant calling | ➖ (both) | edge-only on both sides — Navigator does it; AppView aggregates summaries |
| ENA study-metadata client + enrichment | ✅ | `du-external::ena` + `ena-study-enrichment` job (Scala fetched ENA via the submit form) |
| NCBI/PubMed metadata client + enrichment | ✅ | `du-external::ncbi` + `publication-pubmed-update` job |

## 5. Data model / schema

Rust schema (migrations `0001–0012`): `core, tree, genomics, pubs, ident, fed,
ibd, social, billing` + audit + coverage-mirror + fed-reporting.

| Scala entity area | Rust | Notes |
|:---|:--|:---|
| Variants, haplogroups, relationships, anchors, change-sets, tree_change, wip_*, proposed-branch | ✅ | full catalog + versioning + merge |
| Biosamples, donors, callable-loci, variant-calls | ✅ | catalog side (ETL-loaded; no manual create) |
| Genomics: sequencing, alignment, coverage, test-types, populations | ✅ | |
| **Pangenome** (graph/node/path/variant-link/coverage) | 🟡 | tables + ETL present (mig 0004 / `du-migrate`); **no surfaced API/UI** (same as Scala — modeled, thin surface) |
| **STR profiles + signatures + prediction + age** | ✅ | **Brought into scope 2026-06.** P1: `fed.str_profile` mirror (Jetstream), `du-db::ystr` modal aggregation → `tree.haplogroup_ancestral_str`, recompute job, `GET …/str-signature`. P2: `ystr::predict` (genetic distance) at `POST /api/v1/str/predict` + STR→WGS nudge. **STR age** (`StrAgeService` analog, McDonald 2021): `ystr::compute_str_age` → `tree.haplogroup_age_estimate` (`STR_VARIANCE`), `GET …/age` — a contributing factor, not authoritative `tmrca_ybp`. `genomics.str_mutation_rate` table present (ships empty; default rate until imported). **Combined-age framework DONE** (`du-db::age`): inverse-variance Gaussian combine of STR + **SNP-Poisson** (`t=Σm/(µ·Σb)`) + **genealogical/aDNA anchor** terms → `COMBINED` estimate, gap-fills `tmrca_ybp` (curated values preserved). SNP/anchor terms data-gated. Remaining: `formed_ybp` + aDNA-calibration refinement ⬜ |
| Publications, studies, candidates, search configs | ✅ | |
| ident: users, roles, permissions, login-info, pds-info, cookie-consent | ✅ | + `audit_log` |
| federation: `pds_node/heartbeat/fleet_config/submission` | 🟡 | tables exist (mig 0008) but **unused** (fleet dropped); `fed.coverage_summary` + `fed.*` reporting tables are the live federation store |
| social: messages, conversations, feed, blocks, reputation | ➖ | placeholder tables (mig 0009); no logic/endpoints |
| group projects + membership/policies | ➖ | not ported (rich group model dropped) |
| billing: patron subscriptions | ➖ | placeholder; no logic |
| IBD: suggestions, discovery-index, attestations, overlap scores | ➖ | placeholder (mig 0007); no logic |
| support: contact messages | ✅ | `support.contact_message` + `du-db::support` |

## 6. Scheduled jobs

| Scala job | Rust | Notes |
|:---|:--|:---|
| PublicationUpdater (OpenAlex, bi-weekly) | ✅ | `publication-update` |
| PublicationDiscovery (OpenAlex, weekly) | ✅ | `publication-discovery` (creates candidates; review UI at `/curator/publications`) |
| YBrowseVariantUpdate (weekly) | ✅ | `ybrowse-variant-ingest` |
| VariantExport (daily gzipped JSONL) | 🔁 | replaced by the live CSV endpoint; no file-artifact job |
| MatchDiscovery (daily IBD) | ➖ | IBD not in production |
| — | ➕ | **Rust-new:** `ena-study-enrichment`, `publication-pubmed-update`, `db-heartbeat`, the **Jetstream coverage/reporting-mirror consumer** |

## Net summary

- **Equivalent or improved:** catalog (variants/haplogroups/tree) + versioning +
  merge, public read surface + JSON API, coverage/maps/references, OpenAlex/ENA/
  NCBI enrichment, OAuth (upgraded), curation proposal flow, curator change-set
  review UI.
- **Re-scoped by design:** federation (inbound relay → outbound summary mirror +
  proposal intake), auth (app-password/PDS-signature → OAuth), variant export
  (file → live CSV), genomics ingest triggers (manual → scheduled).
- **Dropped (out of scope / not in production):** manual sample ingestion, IBD
  matching, social/reputation/messaging, group projects, patronage/billing,
  sequencer-lab inference, PDS fleet. (STR profiles were **brought back into
  scope** 2026-06 — Phase 1 shipped; prediction is Phase 2.)
- **In scope, not yet built:** haplogroup restructure as discrete curator ops,
  profile update, region management API/bootstrap, per-lab coverage fragments.
  (Built 2026-06: change-set conflict-resolution UI + `wip_*` staging — §2
  `/curator/reviews`; publication-candidate review UI — §2 `/curator/publications`;
  public DOI-submit form — §1 `/references/submit`.)
- **Externally gated:** confidential-OAuth Edge joint test; current-schema dump
  for ETL cutover (see STATUS "Cutover blocker").
