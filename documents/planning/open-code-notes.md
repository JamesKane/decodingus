# Tracked: open in-code notes (TODO / transitional)

Created 2026-06-10. A small backlog of the deliberate forward-looking notes left in
the Rust source, surfaced by a TODO/hack sweep. Neither is a bug; both are
intentional and scoped. Tracked here so they don't get lost in code comments.

## 1. Jobs: variant-export to a file artifact

- **Where:** `rust/crates/du-jobs/src/main.rs` (`TODO(jobs)`)
- **What:** add a scheduled job that exports the variant catalog to a file artifact.
- **Context:** the live path already exists — `GET /api/v1/variants/export` streams CSV
  on demand. This would be the batch/artifact equivalent (e.g. a periodic dump for
  downstream consumers). Match-discovery is explicitly **out of scope** (IBD is not in
  production — see the AppView-coordinator track in `collab-platform-d1-d5`).
- **Priority:** low. No consumer is blocked; the live endpoint covers current needs.

## 2. Curation intake auth: X-API-Key → OAuth bearer

- **Where:** `rust/crates/du-web/src/routes/curation.rs` (module doc)
- **What:** the Navigator → curation intake endpoint authenticates with a static
  `X-API-Key` today; it should become the OAuth bearer once the Edge handshake is live.
- **Context:** gated on the encrypted Edge-exchange substrate — see
  `documents/planning/d1-encrypted-edge-exchange.md`. Until that handshake exists, the
  API key is the machine-auth stopgap.
- **Priority:** sequenced after D1. Functional and acceptable in the interim.

---

*If/when `gh` is authenticated, these can be promoted to GitHub issues; for now they
follow the repo's `documents/planning/` issue convention.*
