# AT Protocol federation — initial findings (for the Edge team)

Status of the DecodingUs (Rust) side of AT Protocol federation, and the open
points to settle jointly before the end-to-end handshake can be tested.

## Design pivot

We are **dropping the custom "private firehose."** Current atproto direction
resolves its purpose:

- **Permissions / permission sets** (mature spec): apps declare granular OAuth
  scopes as lexicon-published permission sets; the PDS enforces them.
  <https://atproto.com/specs/permission>
- **Private data bypasses the firehose**: private/group-private records live in a
  separate namespace (no MST/commit, not broadcast); a consumer gets a
  **notification then fetches the record from the PDS over scoped OAuth**.
  (Working group; group-private spec still maturing upstream.)

So federation = **OAuth (permission-scoped) access to PDS records + notify/fetch**,
not a bespoke relay.

## What's built on our side (this repo)

- `du-atproto`: DID/AT-URI parsing, `did:key` Ed25519 verification, DID-doc/PDS
  resolution; OAuth client crypto — PKCE (S256), ES256 JOSE, **DPoP** proofs,
  `private_key_jwt` client assertion, client + authorization-server metadata,
  PAR/authorize/token builders. All unit-tested (PKCE vs RFC 7636 vector, ES256
  sign/verify, DPoP shape).
- `du-web`: serves the two documents below and wires `/login/atproto` (resolve →
  PAR → redirect) and `/oauth/callback` (token exchange → session). DPoP-nonce
  single-retry implemented. Session is our existing signed cookie; users are
  upserted by DID (`ident.users` + an `atproto` login_info row).

### Concrete artifacts to review / register

Served at the deployed base URL (e.g. `https://decoding-us.com`):

- **`/oauth/client-metadata.json`** — `client_id` = that URL; confidential web
  client; `token_endpoint_auth_method = private_key_jwt`, `alg = ES256`;
  `dpop_bound_access_tokens = true`; `redirect_uris = [".../oauth/callback"]`;
  `scope` from `OAUTH_SCOPE`.
- **`/oauth/jwks.json`** — the client's public P-256 JWK (no private material);
  `kid` = JWK thumbprint. Private key is supplied via `OAUTH_EC_KEY` (base64url
  of the 32-byte scalar); if unset, an ephemeral key is generated and logged.

## Open points to settle with the Edge team

1. **Client auth method.** We assume a *confidential* web client using
   `private_key_jwt` (ES256) + DPoP. Confirm the PDS/authorization server the
   edge accounts use supports this (vs requiring a public client).
2. **Hosting / registration.** `client_id` must be a publicly reachable HTTPS URL
   serving `client-metadata.json`, and `redirect_uris` must match exactly.
   Confirm the production base URL and that `/oauth/*` is deployed there.
3. **Scopes / permission sets.** We default to `atproto transition:generic`.
   What does the app actually need? We expect a **permission set lexicon**
   (e.g. under `app.decodingus.*`) granting read access to the genomic record
   collections. Edge team to define those collections + the permission set the
   PDS will grant.
4. **Signing key lifecycle.** Persist `OAUTH_EC_KEY`; agree on rotation (JWKS can
   publish multiple keys; `kid` already set).
5. **DPoP nonce.** We retry once on a server `DPoP-Nonce`. Confirm the
   authorization server's nonce behavior (PAR + token endpoints).
6. **Identity resolution.** Handle→DID uses the HTTPS well-known method only
   (DNS `_atproto` TXT is a future add). DID→PDS uses `plc.directory` for
   `did:plc` and well-known for `did:web`. Confirm whether edge accounts use
   `did:plc` (and a self-hosted PLC, if any) or `did:web`.
7. **Private data / notify-fetch.** Once group-private data lands, the app will
   fetch records from the PDS with the access token. Define: which collections,
   and the notification mechanism (does Navigator push to us, or do we poll?).

## What we need from Edge to test end-to-end

A test PDS + test account (handle + DID) and its authorization-server endpoints,
so `/login/atproto?handle=<test>` can complete the real PAR → redirect → token
flow against it. Everything up to the network handshake is implemented and
unit-tested; the live exchange is the joint step.

## Local PDS handshake — validated (2026-06)

Stood up the official PDS in a local container and validated the live
**discovery + PAR** path (the browser redirect + token exchange still needs HTTPS
identity infra — see "remaining" below).

> **Navigator team:** the shared, client-facing version of this runbook — incl.
> test-account creation and the public/loopback-client gotchas — lives in
> `DUNavigator/documents/atmosphere/13-Local-PDS-Testing.md`.

### Runbook

```sh
# 1. Pull + boot the PDS (Apple `container`; each gets its own IP, no port-map).
container image pull ghcr.io/bluesky-social/pds:latest
mkdir -p /tmp/pdsdata/blocks
container run -d --name pds -v /tmp/pdsdata:/pds \
  -e PDS_HOSTNAME=pds.test -e PDS_PORT=3000 \
  -e PDS_JWT_SECRET=$(openssl rand --hex 16) \
  -e PDS_ADMIN_PASSWORD=$(openssl rand --hex 16) \
  -e PDS_PLC_ROTATION_KEY_K256_PRIVATE_KEY_HEX=$(openssl rand --hex 32) \
  -e PDS_DATA_DIRECTORY=/pds -e PDS_BLOBSTORE_DISK_LOCATION=/pds/blocks \
  -e PDS_DID_PLC_URL=https://plc.directory -e PDS_INVITE_REQUIRED=false -e PDS_DEV_MODE=true \
  ghcr.io/bluesky-social/pds:latest
IP=$(container ls | awk '$1=="pds"{print $6}' | cut -d/ -f1)   # e.g. 192.168.64.5

# 2. Run the gated live handshake test (decodingus-shared).
PDS_TEST_URL=http://$IP:3000 cargo test -p du-atproto --test live_pds -- --nocapture
```

Gotchas learned:
- PDS rejects `.local` hostnames; use a `.test` domain. It needs `/pds` to exist
  (bind-mount). It serves HTTP on `:3000` and expects TLS termination in front;
  the OAuth **issuer is `https://PDS_HOSTNAME`** regardless.
- **DPoP `htu` must be the server's canonical endpoint** (`https://pds.test/oauth/par`
  from metadata), NOT the transport URL you connect over (`http://<ip>:3000/...`).
  Signing the transport URL yields `invalid_dpop_proof: DPoP "htu" mismatch`.
- The PDS issues a **`use_dpop_nonce`** on the first PAR; our single-retry with the
  `DPoP-Nonce` response header then returns `201` + a `request_uri`. ✓

### What this validates (`crates/du-atproto/tests/live_pds.rs`)

Authorization-server metadata fetch + parse, the **public (loopback) client** PAR
form, the DPoP proof, and the `use_dpop_nonce` retry — accepted by a real atproto
auth server (`201 Created`, `request_uri` returned). Confirms `token_endpoint_auth_
methods = [none, private_key_jwt]` and `client_id_metadata_document_supported`.

### Remaining for the full browser loop (deferred)

The redirect → consent → `code` → token exchange needs the auth server reachable
over **HTTPS at its canonical host** with a cert our client trusts, because DPoP
`htu` + the issuer are https-canonical. Options: a TLS reverse proxy at
`https://pds.test` (hosts entry + dev CA trusted by reqwest) for a full local loop,
or an HTTPS tunnel to a real account for the confidential-client path. Identity
resolution (handle→DID→PDS) similarly wants HTTPS well-known / a PLC.
