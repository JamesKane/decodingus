# AppView (decodingus) — Design-Gap Roadmap for the Rust Rewrite

**Status:** Living index. Drafted 2026-06-06.
**Purpose:** One map of *what design work remains* for the Rust AppView, what
already has a doc, what must be **reconciled** with the new Navigator-side
genealogical-platform direction, and a recommended **order**. This is a
navigational doc — it points at the real design docs (existing and to-write), it
does not restate them.

**Pairs with:** `rust/STATUS.md` (build status), `planning/post-mvp-roadmap.md`
(feature sequencing). Navigator-side companions live in the **DUNavigator** repo:
`docs/design/ftdna-project-import.md` and `docs/design/academic-ena-import.md`.

## 1. Where the rewrite stands

Per `rust/STATUS.md` (2026-06-05): the **spine is done and cutover-verified**.
Built: schema (migrations 0001–0022), `du-db` query layer, public HTML/HTMX + JSON
API, auth + curator tools, haplotree build/merge/versioning, SNP-graft + review,
YBrowse mirror→reconcile (~3M variants), Y-STR signatures/prediction/age, variant
naming authority, ETL (verified on a real prod dump), and **federation reporting**
(Jetstream → `fed.*` mirror + report endpoints).

Launch-critical path is just **(1) cutover execution** + alias-aware mt resolution,
and **(2) the live cross-host AT Proto OAuth test**. Everything below is the
**post-launch feature mass** — and it's where the design gaps are.

## 2. The two buckets of remaining design

**Bucket A — documented subsystems, not yet built in Rust.** Each has a planning
doc; the gap is a *Rust-implementation spec* (exact SQL, state machines, endpoints)
and reconciliation with the new schema. Mostly schema-only today.

**Bucket B — the collaboration / genealogy-platform layer.** This is what the
Navigator FTDNA work (`ftdna-project-import.md` §8) depends on. It is **partly
covered by older proposals** (`proposals/group-project-system.md`,
`Messaging_and_Feed_System.md`, `Reputation_System_Implementation.md`) — but those **predate** the
ResearchSubject/assertion model *and take the opposite privacy stance* (see §3).
The gap here is **reconciliation + the net-new pieces**, not greenfield.

## 3. The central reconciliation — RESOLVED (2026-06-06): no PII in AppView

The apparent tension between the privacy-first `group-project-system.md` and the
Navigator FTDNA design is **decided in favor of the privacy-first stance**:

> **AppView holds NO PII. It is a pure broker.** It keeps its anonymized/aggregate-
> only posture (the `fed.*` mirror drops donor PII at ingest). Member PII — names,
> MDKA, kit↔identity linkage — is exchanged **admin-to-admin over an encrypted
> Edge-to-Edge (P2P) channel**, the **same mechanism the IBD system uses** for
> genetic comparison (now D1/D3: ECDH X25519 + AES-256-GCM,
> AT-Proto-brokered handshake, P2P/relay transport). AppView coordinates discovery,
> consent, and key exchange, and persists **PII-free** match/assertion *state*.

This **reinforces** `group-project-system.md` (member-sovereign, refs-not-copies)
and **corrects** the earlier Navigator draft (which had PII landing in an AppView
private tier — now amended in `ftdna-project-import.md` §8 to P2P-only).

The bootstrap→sovereign **lifecycle still holds**, but no server-side PII copy
exists at any stage:

```
[Admin-stewarded bootstrap]            [Member-sovereign steady state]
 admin imports FTDNA project   ──►  member onboards, proves kit control,
 PII stays LOCAL; shared with        CLAIMS their ResearchSubject ──► custody
 co-admins via encrypted P2P          (DID) moves to them; they decide their
 (our FTDNA on-ramp)                  own visibility (group-project-system.md)
```

**Consequence — one shared substrate.** Because both IBD comparison and genealogy-
PII exchange need the same encrypted Edge-to-Edge channel + AppView broker, **design
it once** (§5, D1) and let both tracks ride it. This is the highest-leverage
foundational piece; it underpins Bucket B and the IBD impl alike.

## 4. Gap catalog

Legend: ✅ done · ◐ partial · ☐ schema-only · ✎ has design doc · ✶ net-new design needed

### Bucket A — finish the documented subsystems

| Subsystem | Code | Schema | Design doc | Remaining design work |
| --- | --- | --- | --- | --- |
| **IBD matching** | ☐ | `ibd` (mig 0007) | → D1 + D3 (orig planning doc removed) | Designed: candidate-pair mining SQL over `fed.*`, dual-consent state machine, Edge↔AppView handoff, match-list endpoints — see `d3-ibd-matching-impl.md` on `d1-encrypted-edge-exchange.md`. **Reused by Bucket B's cross-admin resolver.** |
| **Haplogroup-discovery automation** | ◐ (curator half ✅) | `tree.proposal`/`wip_*`/`discovery_config` | ✎ `planning/haplogroup-discovery-system.md` (71 KB) | The *ingest→consensus engine*: private-variant extraction from `fed.biosample`/`fed.str_profile`, Jaccard/consensus + thresholds, sample de-dup, auto-reassignment on accept. du-domain algorithm spec. |
| **Multi-test-type** | ◐ ~30% | `genomics.test_type_definition` (mig 0004/0014) | ✎ `planning/multi-test-type-roadmap.md` (47 KB) | Marker-coverage + target-region reference tables; **test-type-aware confidence** (Big Y-700 vs chip); seed `test_type_definition`. Feeds discovery confidence. |
| **Sequencer-lab inference** | ◐ ~20% | `genomics` lab/instrument | ✎ `planning/sequencer-lab-inference-system.md` (30 KB) | Public `GET /api/v1/labs/{instrument-id}`; consensus from `fed.instrumentObservation` (record shape not yet defined); curator review + confidence scoring. |
| **OpenAlex pub discovery** | ◐ | `pubs` | ✎ `planning/openalex-publication-discovery.md` | Mostly built; finish discovery/enrichment edges. Low risk. |
| **JSONB consolidation** | ✅ realized | mig 0002/0004 | (removed — done) | Realized in the Rust redesign (7 child tables → JSONB on parents). No action. |
| **PDS backfeed** | ➖ dropped | — | (removed — superseded) | Outbound-only mirror; general backfeed dropped. **Open carve-out:** a *limited* AppView→PDS write for member-claim (§3) — decide under D1/D4. |

### Bucket B — collaboration / genealogy platform

| Piece | Code | Schema | Existing proposal | Remaining design work |
| --- | --- | --- | --- | --- |
| **Group projects** | ☐ | `social` placeholder (mig 0009) | ✎ `proposals/group-project-system.md` | **Reconcile** with FTDNA on-ramp (§3); add admin-team membership + roles + ACL + audit; project = the scope boundary for assertions. |
| **ResearchSubject registry** | ☐ | — | ✶ none | **Net-new, PII-free.** Opaque subject node + **salted `id_hashes[]`** (not raw kit#/accession), cross-admin resolution on hashed/genetic signals (reuses IBD backbone, §3), `custody_did` for member-claim. No names/MDKA. |
| **Assertion store** | ☐ | — | ✶ none | **Net-new.** Attributed, scoped assertions, append-only + retract, conflict-with-provenance. **Split by `scope`:** non-PII → PDS records + du-jobs ingest + AppView `current_view`; **PII → encrypted P2P only, never stored in AppView.** |
| **Encrypted P2P exchange + broker** | ☐ | `ibd` (mig 0007) partial | ✶ none (crypto now spec'd in D1) | **Net-new, SHARED with IBD (§3).** The Edge-to-Edge channel (ECDH X25519 + AES-256-GCM) + AppView broker (discovery, consent, key-exchange relay, exchange attestation). Carries IBD comparison **and** genealogy PII. Build once. |
| **Messaging / feed** | ☐ | `social` placeholder | ✎ `proposals/Messaging_and_Feed_System.md` | Reconcile with assertion threads; the collaboration layer reuses messaging for discussion. Refresh to Rust schema. |
| **Reputation** | ☐ | `social` placeholder | ✎ `proposals/Reputation_System_Implementation.md` | Lower priority; depends on social being live. Refresh later. |

## 5. Recommended sequencing (design order)

Dependency-driven. Each `D#` is a doc to write (or refresh) before the matching
build work.

1. **D1 — Encrypted Edge-to-Edge exchange + AppView broker** ✅ **DRAFTED:
   `planning/d1-encrypted-edge-exchange.md`** *(net-new, SHARED foundation, gates
   both tracks)* — X25519 ECDH (X3DH-lite, forward secrecy) + AES-256-GCM, identity-
   bound via a published Ed25519-signed X25519 key (fixes the "Ed25519 can't ECDH"
   gap), **blind store-and-forward relay** (recommended) so offline peers work,
   generic `exchange.*` broker schema + `ExchangeEnvelope`, new shared `du-exchange`
   crate. Lifts/generalizes the original IBD requirements (now folded into D3). Open: transport confirm,
   relay host, generalize-now (§12).
2. **D2 — ResearchSubject + identity resolution** ✅ **DRAFTED:
   `planning/d2-research-subject-registry.md`** *(net-new, PII-free)* — pseudonymous
   `research_subject` registry (`{research_subject_id, custody_did}` + memberships,
   **no ids/hashes**), exact match via **D1 id-list exchange** (corrected the
   rejected AppView-hash idea), genetic match via D3, member-claim custody,
   cross-project = claim-only. **Uses** D1; **depends on** D3's resolver. Open:
   id-exchange-vs-PSI, cross-project policy, claim proof (§10).
3. **D3 — IBD matching impl spec** ✅ **DRAFTED:
   `planning/d3-ibd-matching-impl.md`** *(implements the IBD requirements in Rust
   on D1)* — candidate mining SQL over `fed.*` (haplogroup/population-overlap/shared-
   match → `match_suggestion`), dual-consent reuses `exchange.*`, Edge handoff = a D1
   session (`purpose=IBD_*`), summary-only attestation indexing, **relationship
   classification feeds D2's genetic resolver** (same-person → `subject_link`).
   Closes the Match track. Open: phasing, N² gate, algo provenance (§12).
4. **D4 — Assertion store (split rails)** ✅ **DRAFTED:
   `planning/d4-assertion-store.md`** *(net-new, the collaboration primitive)* —
   attributed/scoped/append-only assertions over `research_subject_id`; **PII-class ×
   scope → three rails** (R1 non-PII public→PDS record/ingest; R2 non-PII project→
   AppView `research.assertion`+current_view, D5 ACL; R3 PII→D1 P2P-only, folded
   locally, never server-side); `current_view` fold keeps disputes with provenance;
   `SAME_PERSON_AS` drives D2 merge (D3 feeds it); branch assertions surfaced *against*
   the curated tree. Open: NOTE PII-default, dispute authority (§11).
5. **D5 — Group-project reconciliation** ✅ **DRAFTED:
   `planning/d5-group-project-reconciliation.md`** *(reconciles `group-project-
   system.md` with D1–D4)* — **two memberships** disentangled (collaborator-team DIDs
   +roles = the ACL/consent-circle vs. pseudonymous subject membership); adopts the
   proposal's roles (ADMIN/CO_ADMIN/MODERATOR/CURATOR + perms) and binds each to what
   it gates in D1/D4; `research.project`+`project_member` ACL gates PII exchange/R2/
   disputes; proposal's aggregate records map onto D4 R1/R2 (no duplication);
   **stewarded→claim→sovereign lifecycle** (mixed subjects per project); PII durability
   via consent-circle P2P replication + succession. **Platform track COMPLETE.**
6. **D6 — Haplogroup-discovery automation spec** *(refresh
   `haplogroup-discovery-system.md`)* private-variant ingest→consensus engine. Mostly
   independent of B; can run in parallel anytime after launch.
7. **D7 — Multi-test-type confidence** + **D8 — Sequencer-lab inference** — finish
   the documented subsystems; both feed discovery quality. Parallelizable.
8. **Deferred:** messaging/reputation refresh, JSONB consolidation, backfeed
   decision (revisit under D1/D4).

**Two tracks** can run concurrently, joined at **D1 (the shared encrypted-exchange
substrate)**: **Platform track** D1→D2→D4→D5 (genealogy collaboration) and **Match
track** D1→D3 (IBD) share the channel; the **Catalog track** D6→D7/D8 (tree-science
quality) is independent.

## 6. Cross-repo contracts to keep in sync

Bucket B is inherently two-sided. Each net-new AppView doc must pin the
**Navigator-side contract** already drafted in DUNavigator (`ftdna-project-import.md`
§8, amended 2026-06-06 to the no-PII / P2P model):
- **Non-PII** record/NSID shapes (assertions, salted `id_hashes`, aggregate state) →
  extend `du-domain::fed`; ingested via the existing **Jetstream → du-jobs** path.
- **PII** payloads (names, MDKA, kit↔subject map, raw STR/SNP) → the **encrypted P2P
  channel (D1)**, never an AppView record. Navigator runs the Edge endpoint; AppView
  only brokers + attests.
- `ResearchSubject` ↔ Navigator `biosample.guid`: AppView stores the **opaque** id +
  hashes; the clear `external_id(source, id)` stays in Navigator's local store.
- **Sequencer-lab lookup (D8) — lookup endpoint DONE 2026-06-12; consensus engine
  remains.** Navigator's Rust rewrite **lost** the Scala lab association
  (FGC/FTDNA/YSEQ/Dante/Nebula…) + read-name platform/instrument inference; it's being
  restored Navigator-side (read-name scan → `instrument_id`/flowcell/model + a local
  `labs` catalog). The **AppView lookup endpoint is now built**:
  **`GET /api/v1/sequencer/lab?instrument_id=…`** (single lookup, 404 if unknown) and
  **`GET /api/v1/sequencer/lab-instruments`** (bulk cache seed), resolving via the
  **preseeded** `genomics.sequencer_instrument.lab_id` (mig 0025 re-adds it; the ETL
  backfills the legacy tie that the 0004 redesign had dropped; `du_db::sequencer`). The
  proposal/consensus path is **not live anywhere**, so the lookup uses the direct tie and
  the proposal tables stay dormant (memory `sequencer-lab-lookup`). **Remaining D8:** the
  consensus engine — Navigator publishes `instrument_id` on the `sequencerun` fed record →
  `instrument_observation`→`proposal`→accept (`fed.sequencerun.instrument_id` is the
  documented consensus source), with confidence scoring + curator review. Until that
  ships, Navigator sets the lab manually from its local catalog and stores the
  `instrument_id` so a later backfill can resolve it.

## 7. Open strategic questions

1. ~~PII in AppView~~ **RESOLVED (§3): no PII in AppView; PII moves via encrypted
   P2P (D1).** Remaining sub-question: choose **transport** — direct P2P (NAT
   traversal, both online) vs **blind relay** (store-and-forward ciphertext); D1
   decides. Relay is likely needed since admins are rarely online simultaneously.
2. **Backfeed in or out?** Member-claim likely needs AppView→PDS writes; STATUS
   lists backfeed as dropped. Reconcile under D1/D4.
3. **Where do FTDNA branch/clade assignments live** vs the curated AppView
   haplotree? Project `Sub Group` paths are *project assertions*, not catalog truth
   — keep them in the assertion store, surface against (not merged into) the tree.
4. **Consent-flag enforcement** — the FTDNA roster's `Publicly Share DNA Results`
   must gate federation at the AppView boundary; specify where it's checked.
5. **Sequencing vs launch** — none of this blocks the cutover; confirm it's all
   post-launch so it doesn't pull focus from the two launch-critical items.

## 8. Next step

Draft **D1 — Encrypted Edge-to-Edge exchange + AppView broker** (the shared
foundation for both the genealogy and IBD tracks), pinning the transport choice
(Q1) and the no-PII data-classification policy. Then **D2 (ResearchSubject, PII-free)**
and **D3 (IBD impl spec)** build on it.
