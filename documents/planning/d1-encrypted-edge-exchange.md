# D1 — Encrypted Edge-to-Edge Exchange + AppView Broker

**Status:** Design (v0, 2026-06-06). The shared foundation in the AppView roadmap
(`design-roadmap-rust-rewrite.md` §5). **Cross-repo:** specifies both the AppView
**broker** (decodingus) and the Navigator/Edge **exchange endpoint** (DUNavigator).
**Supersedes/generalizes** the crypto + Edge-coordination sections of the
original IBD requirements (now folded into D3), and is the substrate the Navigator FTDNA design
(`ftdna-project-import.md` §8) calls for.

## 1. Purpose

One encrypted, consent-gated, peer-to-peer exchange substrate that carries **any**
sensitive payload between two AT-Proto identities (DIDs), with AppView acting only
as a **broker** that never sees plaintext. Two consumers at launch:

- **IBD comparison** — exchange encrypted variant positions / segment boundaries for
  Edge-to-Edge IBD detection (the original IBD use; see D3).
- **Genealogy PII** — exchange member names, MDKA, kit↔subject linkage, and
  PII-bearing assertions between **co-admins** of a project (FTDNA platform, §8).

Both are the same problem: *get sensitive data from one Edge to another, with mutual
consent, without any server holding it.* Build the channel once.

## 2. Invariants (non-negotiable)

1. **AppView never sees plaintext.** It brokers discovery, consent, key-exchange
   messages, and (optionally) relays **ciphertext only**. No PII, no genetic data,
   no session keys at rest on AppView — ever. (Preserves the "anonymized-only"
   posture; roadmap §3.)
2. **Dual consent precedes any key exchange.** Both DIDs must sign a consent record;
   the broker verifies **both signatures** before notifying either Edge to begin.
3. **Forward secrecy.** Every session uses **ephemeral** ECDH keys; compromise of a
   long-term key does not decrypt past sessions.
4. **Verifiable peer identity.** Session keys are bound to each peer's **DID
   identity key** (Ed25519), so a peer cannot be impersonated and AppView cannot
   MITM (it never holds a usable key).
5. **Plaintext at rest only on the Edge, encrypted.** Received PII/variants are
   stored locally (Navigator SQLite), encrypted at rest; never re-uploaded.
6. **Least metadata.** The broker learns *that* A and B exchanged, when, and rough
   size — the same social-graph metadata it already has from match requests. It
   learns nothing about content. Padding/batching mitigations in §11.

## 3. Role split

| | **Edge (Navigator)** | **Broker (AppView)** |
| --- | --- | --- |
| Holds plaintext (PII/variants) | ✅ local, encrypted | ❌ never |
| Long-term identity key (Ed25519) | ✅ (via PDS/DID) | verifies signatures only |
| Ephemeral session keys (X25519) | ✅ generates/rotates | ❌ |
| Discovery / intent | consumes suggestions | ✅ generates (IBD suggestions; project co-membership) |
| Consent records | signs, writes to PDS | ✅ mirrors + **verifies dual-signature** |
| Key-exchange messages | sends/receives | ✅ **relays** (opaque) |
| Ciphertext payload | encrypts/decrypts | ✅ **blind relay** (store-and-forward) or ❌ (direct P2P) |
| Post-exchange action | IBD: compute+attest · Genealogy: decrypt+fold locally | indexes attestations (IBD) / records exchange-occurred |

This is the IBD doc's split (§ "Edge App Responsibilities" / "DecodingUs
Responsibilities"), generalized beyond IBD.

## 4. Cryptographic suite

Reaffirms the IBD spec, with the identity-binding gap fixed:

```
Identity / signatures:  Ed25519           (AT Proto DID key; already in du-atproto)
Key agreement:          X25519 ECDH       (NEW — add x25519-dalek)
Session key derivation: HKDF-SHA-256
Payload encryption:     AES-256-GCM       (AEAD; per-message random 96-bit IV)
Integrity / summaries:  SHA-256
```

**The identity-binding fix.** The IBD doc says "keys derived from PDS signing keys
(verifiable)" — but the DID identity key is **Ed25519 (a signing key); it cannot do
ECDH**. Resolution: each participant publishes a **static X25519 exchange public
key** as an **Ed25519-signed PDS record** (`com.decodingus.exchange.publicKey`). The
signature ties the X25519 key to the DID, so a peer fetches it, verifies the
signature against the DID's identity key (`du-atproto::signature::verify_did_key`),
and trusts it. (Do **not** birationally map Ed25519→X25519; publish a dedicated key.)

**Per-session handshake (X3DH-lite, gives forward secrecy):**
- Each peer holds: static `IK_x25519` (published, signed) + a fresh **ephemeral**
  `EK_x25519` per session.
- Shared secret `= ECDH(IK_A, EK_B) ‖ ECDH(EK_A, IK_B) ‖ ECDH(EK_A, EK_B)` →
  `HKDF-SHA-256` → a session key. Static×ephemeral binds identity; ephemeral×
  ephemeral gives forward secrecy.
- Session key encrypts payloads with AES-256-GCM (fresh IV per message; `seq`
  counter in AAD to order/dedupe). Keys **rotated per session** (IBD doc).

## 5. Handshake & session state machine (generic)

```
   Edge A                         AppView (broker)                    Edge B
     │   1. intent (suggestion / co-membership)  │                       │
     │◀──────────────────────────────────────────│                       │
     │   2. exchange_request (signed PDS record)  │                       │
     │───────────────────────────────────────────▶  mirror + notify B    │
     │                                            │──────────────────────▶│
     │                                            │   3. consent (signed)  │
     │                                            │◀──────────────────────│
     │              verify BOTH signatures (dual-consent gate)            │
     │   4. exchange-ready {partnerDid, partnerExchangeKeyUri}            │
     │◀───────────────────────────────────────────────────────────────▶│
     │   5. ECDH: publish/fetch static keys, swap ephemeral EK (relayed) │
     │◀───────────────── key-exchange messages (opaque) ───────────────▶│
     │   6. encrypted payload  ── blind relay (ciphertext) ──▶            │
     │                                            │──────────────────────▶│
     │                                            │   (B decrypts locally) │
     │   7a. IBD: B computes, both sign + attest → AppView indexes        │
     │   7b. Genealogy: B folds PII locally; ack (exchange-occurred)      │
```

Steps 1–4 are the broker's job (PII-free); steps 5–7 are Edge-to-Edge (opaque to the
broker). The state machine generalizes IBD's Phase 1–4 (per D3):
**intent → request → dual-consent → exchange-ready → ECDH → encrypted
exchange → attest/ack.**

## 6. Transport — DECIDED (2026-06-06): AppView-hosted blind relay primary, direct P2P later

Edges are **desktop apps that are rarely online simultaneously**, and live behind
NAT. So:

> **Default: AppView-hosted blind store-and-forward relay.** The sender posts an
> opaque envelope (ciphertext + minimal routing header) to the broker; it is held
> until the recipient pulls it, then **deleted on ack** (or on TTL). AppView can
> read **none** of it — it sees `{from_did, to_did, session_id, seq, size,
> created_at}` and an opaque blob. This is consistent with Invariant 1: a transport
> buffer of ciphertext is **not** a PII store.

- **Why relay, not PDS-as-mailbox:** putting the ciphertext in a public AT-Proto
  record would leak the *envelope metadata to the whole network*; the relay keeps it
  within AppView, which already knows the social graph from consent records. Relay
  also handles offline peers and TTL cleanly.
- **Why relay, not direct P2P (for now):** direct P2P (QUIC/WebRTC + NAT traversal)
  needs both peers online and a signaling/TURN path — more moving parts for the
  common "other admin is offline" case. **Direct P2P is a later optimization** for
  large payloads when both are online; the relay remains the fallback.
- Relay caps: per-envelope size limit, TTL (e.g. 7 days), at-rest encryption of the
  blob on AppView (defense-in-depth; it's already ciphertext), rate limits.

*(Open: confirm relay-primary vs. P2P-primary — §12 Q1. Recommended: relay-primary.)*

## 7. The generic envelope & payload families

```
ExchangeEnvelope {
  session_id:  UUID,
  seq:         u64,                 // ordering / replay guard (in AEAD AAD)
  purpose:     'IBD_AUTOSOMAL' | 'IBD_Y' | 'IBD_MT' | 'GENEALOGY_PII' | ...,
  payload_type:'VARIANT_POSITIONS' | 'SEGMENT_BOUNDARIES'
             | 'SUBJECT_BUNDLE' | 'PII_ASSERTION',
  iv:          [u8;12],
  ciphertext:  Vec<u8>,            // AES-256-GCM
  auth_tag:    [u8;16],
}
```

**Payload families (plaintext shapes, defined per consumer):**
- **IBD** — `VARIANT_POSITIONS` / `SEGMENT_BOUNDARIES` (unchanged from the IBD doc).
- **Genealogy** — `SUBJECT_BUNDLE`: `{ subject_id, external_ids[] (kit#…), member_name,
  mdka[], notes }` (the PII the FTDNA importer holds, §4.2/§4.3 of the Navigator
  doc); `PII_ASSERTION`: a single scoped assertion whose value contains PII
  (`mdka_is`, `note`-with-name). These are exactly the records the Navigator design
  routes to "encrypted P2P only" (§8.4).

The envelope is payload-agnostic; consumers register a `purpose`/`payload_type` and a
post-decrypt handler.

## 8. Broker schema (PII-free) — generalize `ibd.match_*` into `exchange.*`

The existing `ibd.match_request` / `ibd.match_consent` (mig 0007) are the IBD-specific
seed. Generalize to a purpose-tagged `exchange` schema that IBD and genealogy share;
IBD's tables become a specialization (or a view) keyed by `purpose='IBD_*'`.

```
exchange.exchange_request (
  request_uri   TEXT PRIMARY KEY,          -- at:// URI of the signed PDS record
  initiator_did TEXT NOT NULL,
  partner_did   TEXT NOT NULL,
  purpose       TEXT NOT NULL,             -- IBD_* | GENEALOGY_PII
  scope         TEXT,                      -- e.g. 'project:<id>' (consent boundary)
  status        TEXT NOT NULL,             -- PENDING/CONSENTED/DECLINED/CANCELLED/EXPIRED
  details       JSONB NOT NULL DEFAULT '{}', created_at, updated_at
);
exchange.exchange_consent (
  id, request_uri REFERENCES exchange_request, consenting_did, consent_given BOOL,
  consent_uri TEXT, signature TEXT NOT NULL, created_at      -- both sigs verified
);
exchange.exchange_session (
  session_id UUID PRIMARY KEY, request_uri REFERENCES exchange_request,
  status TEXT,                              -- ESTABLISHING/ACTIVE/COMPLETE/EXPIRED
  created_at, expires_at
);
exchange.relay_envelope (                   -- the blind buffer; ciphertext only
  id, session_id REFERENCES exchange_session, from_did, to_did, seq INT,
  size_bytes INT, blob BYTEA NOT NULL,      -- opaque AES-GCM ciphertext envelope
  created_at, expires_at, delivered_at      -- deleted on ack or TTL
);
exchange.exchange_publickey (               -- mirror of the published, signed X25519 key
  did TEXT PRIMARY KEY, x25519_pub BYTEA NOT NULL, key_uri TEXT, sig_verified_at
);
```

**Note:** `relay_envelope.blob` holds **ciphertext only**; storing it does **not**
violate Invariant 1 (AppView cannot decrypt it; it isn't a PII row). IBD's
`ibd_discovery_index` / `ibd_pds_attestation` keep their existing roles (attestation
indexing) downstream of a completed session.

## 9. Code placement

- **New shared crate `du-exchange`** (in `decodingus-shared`, used by Navigator and
  the eventual Edge/IBD logic): X25519 (`x25519-dalek`), HKDF-SHA-256, AES-256-GCM
  (`aes-gcm`), the `ExchangeEnvelope` (de)serialization, the X3DH-lite session
  derivation, and the published-key record format. Pure Rust, no PII knowledge.
- **`du-atproto`** already provides Ed25519 signing/verification + DID resolution —
  reused for the signed key record and consent signatures (no change beyond adding
  the key-record helpers).
- **Navigator `navigator-sync`** gains the Edge endpoint: publish/fetch the exchange
  key record (via `PdsClient`), the relay client (post/pull/ack envelopes against the
  broker), and the session driver. Builds on the existing `Session`/`PdsClient`.
- **AppView `du-web` + `du-db`**: the `exchange.*` query module + broker endpoints
  (request mirror, dual-consent verify, exchange-ready notify, relay post/pull/ack).

## 10. How the two consumers specialize

| | **IBD** | **Genealogy PII** |
| --- | --- | --- |
| Intent source | `match_suggestion` (shared haplogroup/pop overlap) | shared **project co-membership** (admin team) |
| `purpose` | `IBD_AUTOSOMAL`/`IBD_Y`/`IBD_MT` | `GENEALOGY_PII` |
| Payload | variant positions / segment boundaries | `SUBJECT_BUNDLE` / `PII_ASSERTION` |
| Post-decrypt | both compute IBD, hash, **sign + attest** → AppView indexes match | recipient **folds PII into local store**; ack only (no server index) |
| Server record | `ibd_discovery_index` (match summary, PII-free) | none — exchange is private; only `current_view` of **non-PII** assertions (§8.2) |

Same channel; different intent trigger and post-decrypt handler.

## 11. Threat model & residual metadata

- **Honest-but-curious AppView:** sees the social graph (who exchanged, when, size) —
  identical to what consent records already reveal — and opaque ciphertext. Cannot
  read content, cannot MITM (no usable key; static keys are DID-signed). Acceptable
  given it already brokers matches.
- **Mitigations (later):** envelope **padding** to fixed size buckets; **batching**/
  cover traffic to blur timing; short relay TTL + delete-on-ack to minimize the
  at-rest window.
- **Replay/reorder:** `seq` in AEAD AAD + session expiry.
- **Malicious peer:** can lie about *content* (e.g. a wrong MDKA) — out of scope for
  the channel; handled at the assertion/provenance layer (§8.4) where claims are
  attributed and disputable. The channel guarantees *who* and *confidentiality*, not
  *truth*.
- **Compromised Edge:** plaintext at rest is encrypted; key material in OS keychain
  (as the OAuth tokens already are, `navigator-sync`).

## 12. Open questions / decisions

1. ~~Transport~~ **DECIDED: blind-relay-primary** (§6); direct P2P is a later
   large-payload optimization.
2. ~~Relay host~~ **DECIDED: AppView-hosted blind relay** (ciphertext + routing
   metadata only, delete-on-ack/TTL).
3. ~~Generalize now vs. IBD-first~~ **DECIDED: introduce `exchange.*` now**; IBD's
   eventual impl rides it, `ibd.match_*` folds in.
4. **Static-key rotation/revocation** — lifetime of the published X25519 key; revoke
   by superseding the signed record. Define a rotation policy.
5. **Padding/cover-traffic** — in v1 or deferred? (Recommend: fixed-bucket padding in
   v1; cover traffic later.)
6. **Group exchange** — a project has *N* co-admins; is exchange pairwise (N²) or is
   there a group-key optimization? Pairwise for v1; revisit for large admin teams.

## 13. Next step

Confirm §12 Q1–Q3 (transport, relay host, generalize-now). Then the buildable v1
slice is: **`du-exchange` crate** (X25519 + AEAD + envelope + X3DH-lite) +
**published-key record** + **AppView `exchange.*` schema and broker endpoints** +
**Navigator relay client** — proven end-to-end by a `GENEALOGY_PII` `SUBJECT_BUNDLE`
round-trip between two test admins, then reused for IBD.
```
