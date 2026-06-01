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

- **Shared-crate extraction (your ask #1):** pending a call from our side on
  *where* the shared crates live (`decodingus-shared` repo vs path/git deps vs
  publish). `du-domain`, `du-atproto`, `du-bio` are already cleanly separated
  with no server/web coupling, so extraction is mechanical once located. We'll
  confirm the location and open the move.
- **Haploid variant caller (your ask #3):** leaning toward landing it in
  **`du-bio`** (shared) so the AppView's curation/consensus side can reuse the
  same calling logic that produced a proposal — pending the extraction decision
  above. If it pulls in heavy/edge-only deps we'd reconsider a Navigator-only crate.

## What we're building next (AppView side)

1. **Curation submission API** — a Navigator-authenticated endpoint accepting
   variant/branch proposals → pool/consensus → curator review → promote to the
   catalog. Maps onto our existing `tree.proposed_branch` / `proposed_branch_variant`
   / `proposed_branch_evidence` schema. We'll share the request shape for your
   `navigator submit` path.
2. **Coverage discovery + on-demand aggregation** — we lean toward the
   **lightweight firehose-derived URI index** (record pointers only, no mirror)
   for discovering published summaries, then aggregate at query time. Open to a
   relay-of-record query instead — let's pick one together.

## Still needed from you to test end-to-end

- A **test PDS + account** (handle + DID) and its auth-server endpoints.
- Confirmation the auth server accepts a **public client (PKCE + DPoP + loopback)**.
- The **DID method** edge accounts use (`did:plc` via `plc.directory` — self-hosted? — or `did:web`).
- The `navigatorCore` set lexicon (NSIDs) once published, and the Navigator native
  client-metadata JSON to host.
