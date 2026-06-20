# Social Layer Roadmap

> **Status (2026-06-19): partially built.** The team↔tester communication core is
> **live** (mig 0041 + `du_db::social` + web + signed Edge API — see "Shipped" below).
> This roadmap scopes the remaining social work into three tiers and records the scoping
> decisions. Authoritative successor to the forward headers on
> `proposals/Messaging_and_Feed_System.md`, `proposals/Reputation_System_Implementation.md`,
> and `proposals/group-project-system.md` (those keep the detailed Slick→Rust designs;
> this doc is the build plan + reconciliation).

## Framing

The social layer exists to let **alpha/beta testers communicate with the team** and with
each other around the genealogy work — **not** to be a standalone social-media silo. It
reuses the dormant mig-0009 `social.*` schema. It must respect the project invariants:
**AppView holds zero PII** (citizen↔citizen private content rides the D1 encrypted relay,
never central plaintext); the AppView **aggregates and reports, it does not analyze**.

### Locked scoping decisions (2026-06-19)

- **Identity:** testers are **DID-bridged into `ident.users`** (`auth::upsert_user_by_did`
  + the existing `ident.users.did`), so one set of `social.*` rows serves both the web
  client and the Navigator Edge.
- **Transport:** team↔tester support threads + announcements + community feed are
  **central plaintext** — justified because this is **operator** communication, not the
  citizen↔citizen P2P that the no-PII rule guards. Peer DMs (Tier 3) are the exception
  and ride D1.
- **Team = a role**, not a conversation participant: a `SUPPORT` thread has one
  participant (the requester); any Curator/Admin replies (`from_team=true`).
- **Channels in scope:** tester→team threads, team→all announcements, open community
  feed. (Tester↔tester DMs are Tier 3, over D1.)
- **Tier 3 forward pieces — all KEPT** (none cut): federated feed, peer DMs over D1,
  recruitment campaigns. Sequenced last, gated on their dependencies.

## Shipped (2026-06-19) — the communication core

`mig 0041_social_orchestration.sql` (ALTERs the mig-0009 tables, no recreation) +
`du_db::social` + du-web routes. All on branch `feat/social-layer-orchestration`.

- **DB:** support-thread lifecycle (open / reply / status open↔replied↔closed /
  per-side read marks / unread counts), feed (announcement + community, topic, replies,
  pin, soft-delete), blocks (bidirectional), reputation-score read, canonical signed
  Edge messages.
- **Team web:** `/curator/inbox` (HTMX triage) + `/curator/announcements`.
- **Member web:** `/messages` (open + own threads + reply, cross-user isolation) +
  `/feed` (read + post, reputation/block gated). New `auth::User` extractor.
- **Edge API:** signed `/api/v1/social/*` for the Navigator (the testers' real client) —
  `verify_signed` + DID bridge, ownership-isolated reads.
- **Navbar:** lazy unread badges (member + curator).

What's intentionally still a stub: the `MIN_FEED_REPUTATION` gate reads the score but
**nothing awards reputation yet** (Tier 1); `kind=SYSTEM` is schema-allowed but unused
(Tier 2); blocks have no UI action (Tier 1).

---

## Tier 1 — Finish the social core

Central, no new infrastructure, beta-ready. Makes the layer self-standing, quality-gated,
and safe. **Recommended next.**

### 1a. Reputation engine
`proposals/Reputation_System_Implementation.md` (schema in mig 0009; logic unbuilt).
- **Seed** `social.reputation_event_type` (migration): `ACCOUNT_VERIFIED +10`,
  `NEW_USER_BONUS +5`, `LAB_OBSERVATION_ACCEPTED +5`, `FEED_POST_UPVOTED +1`,
  `FEED_POST_DOWNVOTED -1`, `SPAM_REPORT_VALIDATED -50`, `RECRUITMENT_ACCEPTED +2`.
- **`du_db::reputation`** (or extend `du_db::social`): `record_event(user, type,
  related_entity?, source_user?)` = insert ledger row **+ transactional
  `user_reputation_score`** upsert; `score_of` (already have `reputation_score`); a
  `Guard` (`can_post_to_feed` / `can_initiate_dm` / `can_create_group`) replacing the
  hard-coded `MIN_FEED_REPUTATION` constant.
- **Award hooks:** `NEW_USER_BONUS` at `upsert_user_by_did` / first login;
  `ACCOUNT_VERIFIED` at email/OAuth verify; `LAB_OBSERVATION_ACCEPTED` from the D8
  sequencer-lab consensus accept (existing). Double-vote guard: unique on
  `(source_user_id, related_entity_id, event_type_id)` or a check in `record_event`.

### 1b. Feed voting + report/moderation
- **Upvote/downvote** on `feed_post` → `record_event(FEED_POST_UPVOTED/…, FEED_POST,
  postId, voterId)`; vote buttons on the feed; reflect author score.
- **Report/flag** a post → a moderation queue (reuse the `/curator/inbox` two-panel
  pattern, e.g. `/curator/moderation`); a confirmed report fires `SPAM_REPORT_VALIDATED`
  and soft-deletes (`delete_post(id, None)` already supports curator delete). `set_pinned`
  already exists for announcements.

### 1c. Block / mute UX
DB layer is done (`block`/`unblock`/`is_blocked_either`, already block-filtering the
feed). Missing: a **"Block" button** on feed posts / member context + a **managed block
list** in the member area. Small.

---

## Tier 2 — Orchestration tie-ins

The actual "orchestration": connect the social layer to the collaboration platform. Each
piece is buildable independently but proves out only when its upstream events exist.

### 2a. Notifications + the SYSTEM rail — **BUILT (2026-06-19)**
`mig 0044 social.notification` + `du_db::notification`: `notify`/`notify_system`,
`list`/`unread_count`/`mark_read`(recipient-scoped)/`mark_all_read`. Producers wired into
`du_db::social`: a **team reply** → `THREAD_REPLY` to the requester; a **feed reply** →
`FEED_REPLY` to the parent author (self-notify skipped). Web: navbar **bell** (lazy
badge) + `/notifications`. Signed Edge: `GET /api/v1/social/notifications` +
`POST …/notifications/read`. **`notify_system` (actor = NULL) is the rail** the collab
flows call:
- **IBD/match-consent** (`planning/d3-ibd-matching-impl.md`, `d1-encrypted-edge-exchange.md`)
  → `notify_system(recipient, "A possible match wants to connect", …)`.
- **D4 research assertions** (dispute/settle) → notify project members.
The rail is live now; the IBD/D4 *producers* land when those flows do (one `notify_system`
call each — no further notification work).

### 2b. Group-project social surface — **BUILT (2026-06-19)**
Unblocked by a **web project-create flow** (`routes/projects.rs`): a logged-in member
with an AT-Proto DID creates a project (becoming founding ADMIN `owner_did`), gets a
**members-only feed** (`feed_post kind=PROJECT, topic=project:<id>` — isolated from the
global COMMUNITY feed) and a **roster** + admin member management, all on the built **D5
ACL** (`du_db::research` role/capability checks). The viewer's DID is bridged from
`ident.users` (`auth::did_of`); accounts without a DID get a "needs AT-Proto account"
notice. New `du_db::research` project helpers (`create_project`/`get_project`/
`projects_for_member`). Project posts are membership-gated, not reputation-gated.
`proposals/group-project-system.md` / `planning/d5-group-project-reconciliation.md`.
(Projects will ALSO arrive via Navigator groupProject PDS ingest later; this web flow is
the bridge until then.) Remaining social surface, lower priority:
- **Project feed** (reuse `feed_post` with `topic=project:<id>`, gated by D5 membership).
- **Membership UI** (roster, roles, join/leave) on top of the D5 ACL.
- **Project discussion / aggregate views** (`projectTreeView`/`strComparison` map onto
  D4 rails per D5).
Gated on D5 (done) + groupProject PDS-record ingest (Navigator side).

---

## Tier 3 — Federation & P2P (all KEPT, sequenced last)

Larger lifts gated on protocol/crypto maturity. Confirmed in scope 2026-06-19.

### 3a. Peer DMs over the D1 encrypted relay — **AppView side BUILT (2026-06-19)**
The D1 broker (`exchange.*`, mig 0032) already provides the full ciphertext relay
(request → dual consent → session → blind `relay_envelope` store-and-forward). What was
missing on the AppView is the **orchestration hook**, now wired: an exchange **request
arrives → SYSTEM notification** to the partner (purpose-aware: IBD match / genealogy-PII
DM / generic), and **dual consent → notification** to the other party. `create_request`
returns an inserted-bool so re-sends don't re-notify; the partner DID is bridged into
`ident.users` for the (UUID-keyed) notification. This is the **real D1 producer for the
2a SYSTEM rail** — and it serves both peer DMs *and* IBD-match consent. The AppView stays
a **blind ciphertext relay**; the crypto (X25519/AES-GCM) remains Navigator-side, so the
end-to-end DM still needs the Edge `du-exchange` crate. No central plaintext — invariant
held. **Remaining (Edge):** the Navigator crypto + a DM UI (the AppView can't render
ciphertext, by design).

### 3b. Federated public feed — **AppView side BUILT (2026-06-19)**
The AppView now ingests `com.decodingus.atmosphere.feed.post` records and merges them into
the community feed (read-only). `mig 0045 fed.feed_post` mirror + `du_db::fed::feed`
(`upsert` time_us-guarded / `recent`); the du-jobs Jetstream consumer adds the collection
(`build_feed_post`: top-level `createdAt`, `reply.{root,parent}.uri`); the web `/feed`
**interleaves** federated posts with central community posts by recency, badged "via
Atmosphere" and read-only (vote/reply/block stay AppView-native); the Edge feed returns
them in a `federated` array. Lexicon documented in `documents/atmosphere/10-Feed-Records.md`.
**Remaining (Edge):** Navigator must **publish** `feed.post` records (the AppView only
ingests — the mirror is empty until it does). Read-path follow-ups: cross-author reply
threading + federated-author block-filtering.

### 3c. Recruitment campaigns
`Messaging_and_Feed_System.md §6`: researchers bulk-message cohorts selected by genetics
(`RECRUITMENT` conversation type, cohort-builder → target DIDs). **The most PII-sensitive
piece** — targeting by haplogroup/ancestry. Build only behind: verified-researcher
reputation gate (Tier 1) + project ACL (D5) + **D1 consent** so the AppView never hands a
researcher "everyone." Sequenced after 2a/2b so the consent + notification rails exist.

---

## Build order

1. **Tier 1** (1a → 1b → 1c) — finish the core; self-contained, beta-ready.
2. **Tier 2** when D1/D3 events exist (2a first — it's the rail 3c needs; 2b alongside).
3. **Tier 3** last: 3a/3b in parallel (independent), 3c after 2a/2b.

**Deferred/separate:** Patronage (`proposals/Patronage_Donation_System.md`) — revive at
~a few hundred active users.

## Cross-references

- Shipped core: branch `feat/social-layer-orchestration` (mig 0041 + `du_db::social`).
- Collab platform: `planning/d1-encrypted-edge-exchange.md` … `d5-group-project-reconciliation.md`
  (D5 ACL is the group-project gating layer).
- Invariants: the no-PII / ingest-aggregate federation direction; `proposals/triage-report.md` §4–§6.
