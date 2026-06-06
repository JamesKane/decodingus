# D4 — Assertion Store (Split Rails)

**Status:** Design (v0, 2026-06-06). AppView roadmap §5 D4 — the **collaboration
primitive**. **Uses** D1 (PII channel), D2 (`research_subject` registry), **consumes**
D3 (same-person → assertion); **feeds** D5 (group-project ACL/UI). **Cross-repo:**
AppView non-PII store + Navigator local store (all PII).

## 1. Purpose

Co-admin research is modeled as **attributed, scoped, append-only assertions** over a
`research_subject_id`, not as direct mutation of shared rows. One shape carries
branch assignments, same-person links, haplogroup labels, MDKA, and notes — and the
**PII-ness of each assertion decides whether it can ever touch a server**.

## 2. The assertion shape

```
Assertion {
  id,
  subject:     research_subject_id,        -- pseudonymous (D2)
  predicate:   SAME_PERSON_AS | BELONGS_TO_BRANCH | HAPLOGROUP_IS | MDKA_IS | NOTE | ...,
  value:       <predicate-specific JSONB>,
  author_did,                              -- attribution
  scope:       PUBLIC | PROJECT(<id>) | LOCAL,   -- visibility/consent boundary
  evidence:    optional (STR distance, SNP, IBD summary ref, doc citation),
  created_at,
  supersedes:  Assertion.id | null,        -- append-only edit chain
  retracted_at: ts | null,
}
```

Append-only + `supersedes`/`retracted_at` gives **conflict-with-provenance**: two
admins disagree → two live assertions, both attributed; nothing is silently
overwritten (§6).

## 3. Predicate catalog — PII class drives the rail

| Predicate | `value` | PII? | Rail (§4) |
| --- | --- | --- | --- |
| `SAME_PERSON_AS` | `{other_subject_id, confidence, method}` | no (pseudonymous ids) | non-PII |
| `BELONGS_TO_BRANCH` | `{clade_path / haplogroup_node}` | no | non-PII |
| `HAPLOGROUP_IS` | `{dna_type, haplogroup, status}` | no (a classification, not an identifier) | non-PII |
| `MDKA_IS` | `{lineage, ancestor_name, dates, place, lat/long}` | **YES** (names/places) | **PII → P2P only** |
| `IDENTITY` | `{member_name, external_ids[]}` | **YES** | **PII → P2P only** |
| `NOTE` | `{text}` | **maybe** (free text) | **PII rail by default**; non-PII only if author marks "no PII" |

**Rule:** predicate PII-class is the *default*; free-text (`NOTE`) defaults to the PII
rail unless the author explicitly clears it. A value-level scrubber can flag obvious
PII (emails/names) and force the PII rail regardless (mirrors the FTDNA `Note`-column
lesson — free text can't be auto-cleaned, so it's PII until proven otherwise).

## 4. The three rails (PII-ness × scope)

```
                 │ scope=PUBLIC            │ scope=PROJECT(id)          │ scope=LOCAL
  ───────────────┼─────────────────────────┼────────────────────────────┼──────────────
  non-PII        │ R1: PDS public record   │ R2: AppView project store  │ local only
                 │   → du-jobs ingest      │   (current_view, D5 ACL)   │
  ───────────────┼─────────────────────────┼────────────────────────────┼──────────────
  PII            │ ✗ FORBIDDEN             │ R3: D1 encrypted P2P only  │ local only
                 │ (consent can't make     │   (folded LOCALLY, never   │
                 │  PII public here)       │    on AppView)             │
```

- **R1 — PDS public record (non-PII, public):** e.g. `HAPLOGROUP_IS` when the member
  consents to public. A signed `com.decodingus.research.assertion` record in the
  author's PDS → ingested by du-jobs into the AppView store, same path as `fed.*`.
- **R2 — AppView project store (non-PII, project-scoped):** e.g. `BELONGS_TO_BRANCH`
  within a project. Held in `research.assertion` (it's **not PII**), served only to the
  project's admin team (D5 ACL). This is consistent with "no PII in AppView" — these
  rows carry **no identifiers**.
- **R3 — D1 P2P (PII):** `MDKA_IS`, `IDENTITY`, PII `NOTE`. Travels as a D1
  `PII_ASSERTION` payload (D1 §7), folded into each recipient admin's **local** store.
  **Never** a PDS record, **never** an AppView row.

**PII can never be public (R1 cell is ✗):** even with member consent, MDKA/names don't
go to a world-readable record — consent raises visibility to the project circle (R3),
not the world. (If a member truly wants their own ancestor public, that's their PDS
choice post-claim, outside this layer.)

## 5. Consent-flag enforcement (roadmap Q4)

The FTDNA roster's `publicly_shares` (per member, on `ftdna_member`) and `access_granted`
set the **maximum scope** an admin's Navigator may assign to assertions about that
subject:
- `publicly_shares = NO` → assertions about that subject are capped at `PROJECT`
  (R2/R3); the client refuses to emit a `PUBLIC` (R1) assertion.
- Default everything to `PROJECT` scope; `PUBLIC` requires an explicit, consent-backed
  opt-in.
Enforced **Navigator-side at emit time** (the producer), and re-checked at the AppView
ingest boundary for R1 (reject public assertions about a subject flagged non-public —
though AppView only knows the pseudonym, so this is primarily a client-side guarantee).

## 6. current_view — fold with conflict-and-provenance

AppView materializes a **per-(subject, predicate) `current_view`** from the **live**
(non-retracted, non-superseded) **non-PII** assertions (R1+R2). PII (R3) is folded the
same way but **locally in each Navigator**, never centrally.

- **Single-valued predicates** (`HAPLOGROUP_IS`, `BELONGS_TO_BRANCH`): if one live
  assertion → settled; if ≥2 disagree → **`DISPUTED`**, surfacing all claims with
  `author_did` + `created_at` + `evidence`. The group resolves by an admin issuing a
  superseding assertion (or a `RESOLVES` meta-assertion) — never auto-collapsed.
- **Set-valued** (`NOTE`, multiple `SAME_PERSON_AS`): all live members shown.
- **`SAME_PERSON_AS`** additionally **drives a D2 merge**: an accepted same-person
  assertion writes `research.subject_link` (method `ASSERTION`) and merges the two
  subjects' views (D2 §5). D3's genetic same-person (§7) arrives as a pre-filled
  `SAME_PERSON_AS` with `method=GENETIC` + IBD evidence, awaiting group accept.

Materialization runs on ingest (R1/R2) like the existing `fed.*` reporting fold;
`du-db::research::refold(subject_id)` after each new assertion.

## 7. Branch/clade assertions vs. the curated tree (roadmap Q3)

`BELONGS_TO_BRANCH` assertions are a **project's** view of where its subjects sit — they
are **not catalog truth**. They are surfaced **against** the curated AppView haplotree
(`tree.*`), never merged into it. A project's clade tree = the fold of its
`BELONGS_TO_BRANCH` assertions, rendered alongside (and reconcilable with) the
authoritative tree. Promotion of a project finding into the catalog goes through the
existing **curator change-set** path (`tree.change_set`), not silently.

## 8. Retraction & supersede

- **Supersede:** an edit is a new assertion with `supersedes = old.id`; the chain head
  is "live." Preserves full history + attribution.
- **Retract:** `retracted_at` set; drops out of `current_view` but stays for audit.
- **PII (R3):** retraction is a P2P `PII_ASSERTION` with a `retract` op; recipients drop
  it from their **local** fold. (No central enforcement possible — by design; the
  recipient already had the plaintext, exactly as in any E2E system.)

## 9. Schema

**AppView (`research.*`, non-PII only — R1/R2):**
```sql
CREATE TABLE research.assertion (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  subject_id    UUID NOT NULL REFERENCES research.research_subject(research_subject_id) ON DELETE CASCADE,
  predicate     TEXT NOT NULL,                  -- SAME_PERSON_AS | BELONGS_TO_BRANCH | HAPLOGROUP_IS | NOTE(non-PII)
  value         JSONB NOT NULL,
  author_did    TEXT NOT NULL,
  scope         TEXT NOT NULL,                  -- PUBLIC | PROJECT:<id>
  evidence      JSONB,
  record_uri    TEXT,                           -- at:// of the PDS record (R1) if any
  supersedes_id BIGINT REFERENCES research.assertion(id),
  retracted_at  TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX assertion_subject_pred_idx ON research.assertion (subject_id, predicate) WHERE retracted_at IS NULL;

CREATE TABLE research.subject_current_view (    -- materialized fold (non-PII)
  subject_id UUID NOT NULL, predicate TEXT NOT NULL,
  state      TEXT NOT NULL,                      -- SETTLED | DISPUTED
  view       JSONB NOT NULL,                     -- live claims + authors + evidence
  refolded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (subject_id, predicate)
);
```
**No PII column exists** in `research.*` — a reviewer can confirm `MDKA_IS`/`IDENTITY`
have no server table.

**Navigator local (all assertions incl. PII):**
```sql
CREATE TABLE assertion_local (
  id INTEGER PRIMARY KEY, subject_guid TEXT, research_subject_id TEXT,
  predicate TEXT, value TEXT,                    -- JSON; PII lives ONLY here + D1 payloads
  author_did TEXT, scope TEXT, evidence TEXT,
  supersedes INTEGER, retracted_at TEXT, created_at TEXT NOT NULL
);
```

**Lexicon:** `com.decodingus.research.assertion` (R1 public records only). PII
assertions have **no lexicon** — they are D1 `PII_ASSERTION` payloads (D1 §7), by
construction never recordable.

## 10. Module placement

- **AppView:** `du-db::research` (assertion CRUD, `refold`, current_view, subject_link
  on same-person), `du-web::routes::research` (project-scoped assertion API + ACL via
  D5), du-jobs Jetstream ingest of the `research.assertion` collection (R1).
- **Navigator:** `assertion_local` store + the local fold + the D1 driver to emit/ingest
  `PII_ASSERTION` payloads (R3) and publish R1 records (via `PdsClient`).
- **Shared `du-domain`:** the `Assertion` shape, predicate catalog + PII-class table,
  and the fold rules (so Edge and AppView fold identically).

## 11. Open questions / decisions

1. **PII classifier strictness** — predicate-default + value scrubber (recommended).
   Confirm `NOTE` defaults to PII (safer) vs. defaults to non-PII with an opt-in PII
   flag. Recommend **PII-by-default for free text**.
2. **current_view storage** — materialized table (recommended, mirrors `fed.*`
   reporting) vs. compute-on-read. Materialize.
3. **Dispute resolution authority** — any admin supersedes vs. owner/role-gated (D5).
   Likely role-gated; finalize with D5.
4. **R1 ingest consent re-check** — AppView can only see the pseudonym, so public-scope
   enforcement is primarily client-side; accept that, or add a per-subject "public-ok"
   pseudonymous flag the member sets on claim? Recommend client-side + claim-time flag.
5. **Cross-project assertion leakage** — a subject in two projects: are PROJECT-scoped
   assertions isolated per project, or visible to any project the subject is in?
   Recommend **per-project isolation** (scope = the specific project).

## 12. Next step

D4 + D2 + D1 are the full **private collaboration stack**: registry (D2) + channel
(D1) + the attributed-claim primitive (D4), with D3 feeding genetic same-person. The
buildable slice: `research.assertion` + `refold`/current_view + the Navigator
`assertion_local` store + D1 `PII_ASSERTION` round-trip + R1 public-record ingest —
provable by a `BELONGS_TO_BRANCH` (R2) and an `MDKA_IS` (R3) between two test admins.
Then **D5 (group-project reconciliation)** adds the admin-team ACL, roles, and UI that
gate all of R2/R3 and resolve disputes.
