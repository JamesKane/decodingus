# AT Protocol federation — decodingus reply to Navigator/Edge

**Re:** `DUNavigator/documents/atmosphere/12-OAuth-Edge-Reply.md` (and 08/11)
**From:** decodingus (AppView) team
**Date:** 2026-06-01

Agreed on the whole shape: two clients / two scopes, AppView re-scoped off the
mirror, standard relay/Jetstream stays for discovery, custom REST/Kafka relay
gone. Point-by-point below, then the decisions you asked us to make.

## Answers to your asks

1. **Public-client (PKCE-only) token exchange — done.** `du-atproto::oauth` now
   exposes `par_form_public` and `token_form_public` (no `client_assertion`;
   PKCE + DPoP only). Confirmed by test (`public_client_forms_omit_client_assertion`).
   So Navigator reuses the same `Pkce`, `dpop_proof`, DID/handle resolution, and
   PDS discovery; only the form builders differ from the confidential path. The
   confidential pieces (`client_assertion`, served metadata/JWKS, cookie session)
   stay decodingus-only, as you noted.

2. **Hosting Navigator's client metadata — yes.** We'll serve your native
   `client-metadata.json` at `https://decoding-us.com/navigator/client-metadata.json`
   (static, alongside the web client's). Send us the JSON contents (or a PR) and
   we'll wire the route.

3. **Scopes.** Confirmed: AppView requests **no PDS read scope** for now — our
   two surviving flows don't need it (variants via the curation submission API;
   coverage from public summary records). We'll revisit a read scope only if/when
   private match data uses notify-fetch (your #7, deferred). Our web OAuth is
   effectively **user login/identity**. You own the `navigatorCore` write-set
   lexicon; the collection NSIDs in 11 §3 match our expectations.

4. **DPoP nonce.** We implement single-retry on `DPoP-Nonce` at PAR + token. We'll
   share the auth server's actual nonce behavior once we have a test server.

5. **AppView re-scope acknowledged.** We will **not** build the full-CRUD
   `subscribeRepos` mirror or per-collection ingestion handlers/tables. Note: the
   Rust rewrite never ported the legacy `FirehoseController`/`AtmosphereEventHandler`
   mirror, so there's nothing to remove — we simply build to the new two-flow role.

## Decisions you asked decodingus to make

- **Shared-crate extraction (your ask #1): DECIDED — a dedicated `decodingus-shared`
  git repo.** We'll extract `du-domain`, `du-atproto`, `du-bio` there; both
  decodingus and DUNavigator git-dep on it (fixes flow both ways, clear ownership).
  They're already cleanly separated with no server/web coupling, so the move is
  mechanical. Coordinating repo creation + remote next; we'll send the repo URL.
- **Haploid variant caller (your ask #3): DECIDED — Navigator-only crate.** Keep
  it in a Navigator-owned crate so any heavy/edge-only deps stay off the AppView;
  `du-bio` stays I/O + liftover + callable. If the AppView later needs the same
  calling logic we can promote a pure subset into `du-bio` then.

## What we're building next (AppView side)

1. **Curation submission API** — a Navigator-authenticated endpoint accepting
   variant/branch proposals → pool/consensus → curator review → promote to the
   catalog. Maps onto our existing `tree.proposed_branch` / `proposed_branch_variant`
   / `proposed_branch_evidence` schema. We'll share the request shape for your
   `navigator submit` path.
2. **Coverage mirror (revised, supersedes "on-demand aggregation").** We reversed
   the doc 08 §3 / pointers-only plan: on-demand aggregation means an HTTP fan-out
   to every PDS per query, which doesn't scale. Instead the AppView **mirrors the
   public coverage *summaries*** — a Jetstream consumer subscribed to
   `com.decodingus.atmosphere.alignment` writes each record's QC metrics (summary
   only, never raw reads) into `fed.coverage_summary`; population views aggregate
   that table with query-time SQL. This is **not** the old full-CRUD network
   mirror — one collection, summary metrics only, no per-sample raw data, no
   orphan/sync machinery. Please update 08-AppView-Lifecycle.md §3 to match (we're
   reading published `alignment.metrics`, so the record shape is unchanged for
   you). Cursor-resumed + reconnecting; the upsert is idempotent and ordered.

## Still needed from you to test end-to-end

- A **test PDS + account** (handle + DID) and its auth-server endpoints.
- Confirmation the auth server accepts a **public client (PKCE + DPoP + loopback)**.
- The **DID method** edge accounts use (`did:plc` via `plc.directory` — self-hosted? — or `did:web`).
- The `navigatorCore` set lexicon (NSIDs) once published, and the Navigator native
  client-metadata JSON to host.
