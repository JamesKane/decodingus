# D2 — ResearchSubject Registry + Cross-Admin Identity Resolution

**Status:** Design (v0, 2026-06-06). AppView roadmap §5 D2. **Builds on:** D1
(`d1-encrypted-edge-exchange.md`) for the encrypted channel; **reuses** the IBD
resolver (D3) for genetic same-person signals; **feeds** D4 (assertions) / D5
(group projects). **Cross-repo:** AppView registry + Navigator local mapping.

> **Corrects the earlier sketch.** The Navigator FTDNA design (§8.3) proposed AppView
> storing **salted `id_hashes[]`** of kit numbers (`HMAC(project_salt, kit#)`) as the
> deterministic match key. **That does not survive scrutiny** (§4): kit numbers are a
> small, enumerable space, and any salt AppView can see (or extract from a client) lets
> it brute-force every hash back to the kit#. D2 replaces it: **AppView stores no
> identifiers or hashes at all**; exact matching happens **Edge-to-Edge over D1**.

## 1. Purpose

Give the collaboration layer a **vendor-neutral, PII-free "person" node** that
co-admins can attach assertions to and resolve across each other's imports — without
AppView ever learning a name, a kit number, or even a hash of one.

## 2. What a ResearchSubject is (and is not)

A **ResearchSubject** is a pseudonymous handle for "a person under research in a
project context." At AppView it is **almost empty**:

```
research_subject_id : UUID        -- random; the ONLY cross-admin handle
custody_did         : DID | null  -- null = admin-stewarded; set when the member claims it
                                  -- (no names, no kit#, no MDKA, no hashes)
```

It **is not**:
- `core.biosample` — that is a *federated, anonymized sample* from `fed.*` ingest. A
  ResearchSubject **may** point at one (if the person published anonymized data) but
  usually **does not**: the common bootstrap case is an FTDNA member who is **not on
  platform**, whose clear-text identity lives only in the importing admin's local
  store. ResearchSubject is the sparser, person-level node.
- A PII record. Names/MDKA/kit# never appear here or anywhere server-side.

## 3. The three-layer identity picture

```
  LOCAL (each admin's Navigator, clear-text, PII)        SHARED (AppView, pseudonymous)
  ┌───────────────────────────────────────┐             ┌──────────────────────────┐
  │ biosample.guid  (local Subject)        │  maps to    │ research_subject_id (UUID)│
  │ external_id(source,id)  e.g. FTDNA #128753 │◀────────▶│   + project membership   │
  │ ftdna_member / mdka  (names, ancestors)│   (local    │   + custody_did          │
  └───────────────────────────────────────┘    table)   │   + current_view (non-PII)│
            ▲  exact match via D1 channel ▲              └──────────────────────────┘
            │  (co-admins exchange id lists, consented)          ▲
            └──────── genetic match via IBD/D3 ──────────────────┘
                                                          (optional) → core.biosample
                                                          if the person federates data
```

- **Local (Navigator):** clear-text identity — `biosample.guid`, `external_id`
  (kit#), `ftdna_member`, `mdka`. Never leaves the box except as encrypted D1 payload.
- **Shared (AppView):** the pseudonymous `research_subject_id` + project memberships
  + custody + the non-PII `current_view`. **No identifiers.**
- **The map between them** (`biosample.guid ↔ research_subject_id`) is held **locally
  by each admin**; admins reconcile their maps to a common `research_subject_id`
  through the resolution mechanisms in §4.

## 4. Resolution — how two admins agree on the same ResearchSubject

Three mechanisms, in precedence order. **None reveals an identifier to AppView.**

### 4.1 Deterministic exact match — id-list exchange over D1 (v1)

Within a **shared project**, two co-admins have already consented to collaborate
(D1 dual-consent). Co-admins in FTDNA's GAP see *all* members of a shared project
anyway, so **exchanging their `(source, external_id)` lists over the encrypted D1
channel is within the consented scope** — no fancy crypto needed:

1. Admin A and B establish a D1 session (`purpose=GENEALOGY_PII`).
2. They exchange their project's `(source, external_id)` lists (encrypted).
3. Each computes the **intersection locally**; matching kits ⇒ same person.
4. For a match, they agree on a **shared `research_subject_id`** (lexicographically-
   lower admin's existing id wins, or mint one) and each records the mapping locally;
   one registers the subject + both memberships at AppView (pseudonymous).

**AppView sees:** two `research_subject_id`s gained a second project membership.
**It never sees** the kit numbers or that "128753" was the link.

> **Why not AppView-side hashing?** Kit numbers are ~6–7 digit enumerable values; a
> broker that holds `HMAC(salt, kit#)` and can obtain the salt (it ships in the
> client) brute-forces the whole space in milliseconds. Edge-to-Edge id exchange
> keeps the broker blind by construction. (PSI — §4.4 — is the upgrade for the
> *cross-project* case where admins should learn only the intersection.)

### 4.2 Genetic same-person / close-kin — IBD over D1 (reuses D3)

When ids differ but the people may be the same (or close kin), run the **IBD/
haplotype comparison Edge-to-Edge** (D3, same D1 channel) → a *suggested* merge with
a confidence, surfaced to both admins. **Never auto-merged.** This catches duplicates
across vendors (FTDNA kit vs. a direct-WGS sample) where no shared id exists.

### 4.3 Assertion-mediated — pseudonymous `same_person` (D4)

An admin publishes a `same_person(research_subject_id_A, research_subject_id_B)`
assertion (pseudonymous ids only, no kit#). The group accepts/rejects; provenance
retained. This is the manual override and the audit trail for 4.1/4.2 outcomes.

### 4.4 Cross-project linking — member-claim only (NOT silent AppView merge)

Auto-linking the *same person across projects they did not consent to be linked
across* is **privacy-hostile** (cross-context deanonymization) and is **deliberately
not** an AppView background job. Cross-project consolidation happens only when the
**member themselves claims** their subjects (§6) and chooses to merge them. PSI
(§4.1 note) is the future tool that would let two *non-co-admins* discover a shared
member with consent — out of scope for v1.

## 5. AppView schema (`research.*`, PII-free)

```sql
CREATE SCHEMA research;

CREATE TABLE research.research_subject (
  research_subject_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  custody_did   TEXT,                          -- null = admin-stewarded; set on claim
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);                                             -- deliberately no ids/names/hashes

CREATE TABLE research.subject_membership (
  research_subject_id UUID NOT NULL REFERENCES research.research_subject(research_subject_id) ON DELETE CASCADE,
  project_id    BIGINT NOT NULL,               -- group-project (D5)
  steward_did   TEXT NOT NULL,                 -- the admin who holds the local clear-text identity
  added_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (research_subject_id, project_id)
);
CREATE INDEX subject_membership_project_idx ON research.subject_membership(project_id);

CREATE TABLE research.subject_link (             -- audit of 4.1/4.2/4.3 merges (pseudonymous)
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  subject_a     UUID NOT NULL, subject_b UUID NOT NULL,   -- merged ids (a kept, b retired)
  method        TEXT NOT NULL,                  -- ID_EXCHANGE | GENETIC | ASSERTION | CLAIM
  asserted_by_did TEXT NOT NULL, confidence DOUBLE PRECISION, created_at TIMESTAMPTZ DEFAULT now()
);

-- optional, sparse: link to a federated sample IF the person published anonymized data
CREATE TABLE research.subject_biosample (
  research_subject_id UUID REFERENCES research.research_subject(research_subject_id) ON DELETE CASCADE,
  sample_guid   UUID REFERENCES core.biosample(sample_guid),
  PRIMARY KEY (research_subject_id, sample_guid)
);
```

`current_view` (the materialized, non-PII per-subject summary — branch assignments,
pseudonymous links, aggregate stats) is produced by the **assertion store (D4)**, not
D2; D2 only owns the registry + memberships + link audit.

**Invariant:** every column above is pseudonymous. A reviewer can confirm no PII path
exists into `research.*`.

## 6. Custody & member-claim (high level; proof = open Q)

- **Stewardship:** on import, each Subject is **admin-stewarded** — `custody_did =
  null`, `subject_membership.steward_did` = the importing admin. "Steward" just means
  "the admin whose local store holds the clear-text identity"; AppView stores no PII.
- **Claim:** a member onboards (gets a DID/PDS), proves to a steward admin that they
  control the kit (mechanism TBD — §10), the admin transfers custody over D1, and the
  steward sets `custody_did = member_did` (a pseudonymous pointer flip). The member
  then controls their own clear-text data locally and decides federation/visibility
  (the `group-project-system.md` sovereign end-state).
- **Proof of kit control is the open question** — AppView can't verify it (no PII).
  Likely admin-mediated (the steward, who *does* hold the kit data, vouches) or a
  vendor credential the member presents to the admin. Specify in D5/claim follow-up.

## 7. Navigator-side local model

Each admin's Navigator gains the local map (clear-text side stays in SQLite):

```sql
-- Navigator local (migration alongside FTDNA import 0014–0016)
CREATE TABLE subject_shared_id (
  biosample_guid     TEXT PRIMARY KEY REFERENCES biosample(guid),
  research_subject_id TEXT NOT NULL,            -- the AppView pseudonym
  project_id         INTEGER,                   -- which shared project this binding is for
  custody            TEXT NOT NULL DEFAULT 'STEWARDED'  -- STEWARDED | CLAIMED_BY_ME | CLAIMED_BY_OTHER
);
```

- On **import**, a local Subject has no shared id until the project is joined/shared.
- On **join a shared project**, Navigator mints a `research_subject_id` per Subject (or
  links existing via §4.1 id-exchange), registers pseudonymous nodes + memberships at
  AppView, and stores the binding locally.
- The `external_id`↔`research_subject_id` correspondence is **only** local + exchanged
  over D1; never sent to AppView.

## 8. End-to-end: two admins, one shared project

```
Admin A imports FTDNA project P (local Subjects, kit#s, MDKA — all local)
Admin B is invited as co-admin of P  ── D1 dual-consent ──▶ session
A ⇄ B  exchange (source,id) lists over D1 (encrypted)         [§4.1]
       → local intersection: kits 128753, 145002 match
A & B  agree shared research_subject_id for each match; mint new for the rest
       register pseudonymous subjects + memberships at AppView (no ids)
A ⇄ B  exchange SUBJECT_BUNDLE (names, MDKA) over D1 for shared subjects [D1/§7]
       → each folds PII into its own local store
later  genetic suggestion (D3) flags kit 9001 (A) ≈ sample S (B) → suggested merge
       → B accepts via same_person assertion (D4); subject_link audited
member M onboards, proves control of 128753 to steward A → custody flips to M_did [§6]
```

AppView's whole view of this: a few pseudonymous `research_subject` rows gained
memberships and links. No names, no kits, no MDKA — ever.

## 9. Threat model / what AppView learns

- **Pseudonymous social graph:** which `research_subject_id`s belong to which projects
  and which DIDs steward them — the same membership graph it needs for D5 ACLs, and no
  worse than GAP's "admins know their project's members." No identifiers.
- **No identifier exposure:** kit#/name/MDKA never reach AppView; exact matching is
  Edge-to-Edge (§4.1); the broker can't brute-force what it never stores.
- **Steward de-anonymization risk:** `steward_did` links a person-node to a real admin
  DID. That's inherent (someone must hold the data) and bounded — it reveals
  *custodianship*, not identity. Mitigation: stewardship is per-project, and claim
  (§6) lets the member take over.
- **Malicious admin** can mint bogus subjects or wrong links — bounded by the
  assertion/provenance layer (D4): links are attributed and disputable.

## 10. Open questions / decisions

1. ~~Deterministic mechanism~~ **DECIDED: id-list exchange over D1** within a shared
   project (§4.1, GAP-equivalent); AppView stores no ids/hashes. PSI deferred for the
   cross-project case.
2. ~~Cross-project linking policy~~ **DECIDED: member-claim only** (§4.4); no silent
   AppView cross-context merge.
3. **Proof of kit control** for member-claim (§6) — admin-vouch vs. vendor credential
   vs. a challenge the member completes. Blocks the claim flow, not the registry.
4. **`research.*` vs. fold into group-project schema (D5)** — separate schema
   (recommended for the PII-free invariant clarity) vs. co-locate with projects.
5. **Merge mechanics** — keep-lower-id vs. mint-new on merge; how `subject_link`
   retirement cascades to memberships/assertions. Define with D4.

## 11. Next step

Confirm §10 Q1–Q2 (they're the load-bearing privacy calls). Then D2's buildable slice
is small: the `research.*` schema + the Navigator `subject_shared_id` table + the
join-project flow that mints/links pseudonymous subjects via a D1 id-exchange — with
PII bundles and assertions layered on by D1/D4. Proceed to **D3 (IBD impl spec)**,
which supplies §4.2's genetic resolver and shares D1's channel.
