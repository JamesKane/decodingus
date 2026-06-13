# D5 — Group-Project Reconciliation + Admin-Team ACL

**Status:** Design (v0, 2026-06-06). AppView roadmap §5 D5 — closes the **Platform
track**. **Reconciles** `proposals/group-project-system.md` (the member-sovereign
proposal) with **D1–D4**, and supplies the **admin-team ACL** that gates D4's R2/R3,
D1's PII introductions, and D4's dispute-resolution authority.

## 1. Purpose

A *project* is the unit of collaboration and the **consent/scope boundary** every
prior doc references (`scope=project:<id>`). D5 defines what a project is, **who is in
its trust circle**, the **roles/permissions** that gate the stack, and how the
existing member-sovereign proposal and the FTDNA admin-stewarded bootstrap are **one
lifecycle, not two systems**.

## 2. The reconciliation — two modes on one lifecycle

`group-project-system.md` assumes members are **on-platform, own a PDS, and
self-manage visibility** (sovereign). The FTDNA bootstrap (D2/D4) assumes the studied
people are **not on platform** — admins import and steward them. These are the **ends
of one lifecycle**:

| | **Stewarded mode** (FTDNA bootstrap) | **Sovereign mode** (the proposal's target) |
| --- | --- | --- |
| Studied person | pseudonymous `research_subject`, **no DID** | a member **DID**, self-present |
| PII custody | the steward admin (local + P2P) | the member's own PDS |
| Visibility control | admin team, **capped by the consent flag** | the member, **per-field opt-in** |
| Governance | admin team (D5 roles) | member self-sovereignty + admin governance |

A subject moves stewarded → sovereign by **member-claim** (D2 §6). A single project can
hold **both** kinds of subject at once; D5 handles the union.

## 3. Two memberships — the disentanglement the proposal needs

The proposal conflates "member" (the studied person) with "participant" (a DID in the
project). D1–D4 require these be **separate**:

- **Collaborator team** = the **DIDs** who run/contribute to the project, each with a
  **role**. This is the **trust circle / ACL / consent boundary**. D1 brokers PII
  exchange *between these DIDs*; D4 R2 is served *to these DIDs*; disputes are resolved
  *by these DIDs* (per role). → AppView `project_member` (D5).
- **Subject membership** = which **`research_subject`s** (pseudonymous studied people)
  belong to the project. → D2 `research.subject_membership` (already exists).

In stewarded mode these are disjoint (admins ≠ subjects). In sovereign mode a claimed
member is **both** a collaborator DID *and* a subject (their `custody_did` = their
team DID). D5's ACL is over the **collaborator team**, never the subjects.

## 4. Roles & permissions (adopt the proposal's, bind to the stack)

Keep the proposal's `projectRole` model — `ADMIN`, `CO_ADMIN`, `MODERATOR`, `CURATOR`
+ granular permissions (`APPROVE_MEMBERS`, `MANAGE_ROLES`, …) — and bind each to what
it gates across D1–D4:

| Capability | Min role/permission | Gates |
| --- | --- | --- |
| Join the PII exchange circle (D1) | any team member (`ADMIN`/`CO_ADMIN`) | D1 broker checks team membership before relaying |
| Write R2 project assertions (D4) | `CO_ADMIN`+ (or `MANAGE_ASSERTIONS`) | D4 R2 accept |
| Read R2 project current_view | any team member | D4 R2 serve / D5 ACL |
| Resolve a dispute (supersede) | `ADMIN`/`CURATOR` (D4 §11.3) | D4 fold resolution |
| Invite/approve collaborators, set roles | `ADMIN` (`MANAGE_ROLES`) | D5 membership |
| Promote a finding to the catalog tree | `CURATOR` → existing `tree.change_set` | catalog (D4 §7) |

`MODERATOR` ≈ community management (sovereign-mode member relations); `CURATOR` is the
bridge to the existing tree-curation path.

## 5. AppView project + ACL schema (PII-free)

```sql
CREATE TABLE research.project (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_uri   TEXT UNIQUE,                  -- at:// of the groupProject PDS record (owner-authored)
  name          TEXT,                          -- project names are not member PII
  kind          TEXT,                          -- SURNAME | HAPLOGROUP | GEOGRAPHIC | STUDY
  join_policy   TEXT NOT NULL,                 -- OPEN | APPROVAL_REQUIRED | INVITE_ONLY | HAPLOGROUP_VERIFIED
  succession    TEXT NOT NULL DEFAULT 'CO_ADMIN_INHERITS',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE research.project_member (         -- the COLLABORATOR TEAM (the ACL)
  project_id    BIGINT NOT NULL REFERENCES research.project(id) ON DELETE CASCADE,
  member_did    TEXT NOT NULL,
  role          TEXT NOT NULL,                 -- ADMIN | CO_ADMIN | MODERATOR | CURATOR
  permissions   TEXT[] NOT NULL DEFAULT '{}',
  appointed_by  TEXT,
  joined_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  left_at       TIMESTAMPTZ,                    -- revocation (§8)
  PRIMARY KEY (project_id, member_did)
);
CREATE INDEX project_member_did_idx ON research.project_member (member_did) WHERE left_at IS NULL;
```

`research.subject_membership` (D2) holds the studied subjects. **No PII anywhere** —
DIDs, roles, pseudonymous subject ids, and project *names* (which are not member PII).

## 6. The ACL enforces across the whole stack

`project_member` (live rows) is the single ACL the broker and assertion store consult:

- **D1 PII exchange:** before relaying a `GENEALOGY_PII` envelope between A and B, the
  broker checks **both are live `project_member`s** of the named project (and the
  request `scope` matches). Non-members can't be in the circle.
- **D4 R2 (project assertions):** read = any live team member; write/dispute = role per
  §4. AppView enforces on the `research.assertion` API.
- **D4 R3 visibility cap:** an admin may only emit PII (R3) to **fellow team members**;
  the consent flag (`publicly_shares`) still caps *public* scope (D4 §5).
- **Revocation:** `left_at` set → immediately drops from the ACL (proposal's "Revocable
  Participation"). Caveat §8.

## 7. The proposal's aggregate records = R1/R2 (don't duplicate)

The proposal's `projectTreeView`, `projectModal`, `strComparison` records are
**aggregate, non-PII** ("genetic distance not raw STR; member counts not lists") — i.e.
they are exactly the **R1/R2 non-PII layer** of D4 plus existing AppView aggregates:

- `projectTreeView` / project clade tree = the fold of `BELONGS_TO_BRANCH` assertions
  (D4 §7), rendered against `tree.*`.
- `projectModal` (modal haplotype) = the existing `ystr` modal aggregation, scoped to a
  project's subjects.
- `strComparison` (genetic distance, not raw values) = a derived R2 aggregate.

So D5 **maps the proposal's records onto D4's rails** rather than adding a parallel
record set: the proposal's "privacy-preserving research value" *is* the non-PII
assertion/aggregate layer. Raw individual STR/PII = R3 (P2P), never these records.

## 8. PII durability & succession (a real bootstrap risk)

In stewarded mode, a subject's PII lives **only in the steward admin's local store**.
If that admin vanishes, the PII is **gone** — and so is the project's research memory.
Mitigations D5 must specify:

- **Replicate stewarded PII across the consent circle.** When co-admins join, the
  steward **P2P-exchanges (D1) the relevant `SUBJECT_BUNDLE`s** so ≥2 team members hold
  each subject's PII. This turns the consent circle into a redundancy set (and is the
  same exchange that enables collaboration — durability is a free side effect).
- **Succession** (`research.project.succession`, from the proposal): `CO_ADMIN_INHERITS`
  (default) hands the `ADMIN` role and steward duty to a co-admin who already holds the
  replicated PII; `MEMBER_VOTE` / `PROJECT_CLOSES` per the proposal. On `PROJECT_CLOSES`,
  AppView drops the project + ACL; local PII remains with whoever holds it (E2E reality).
- **Revocation caveat:** removing a DID from the ACL stops *future* access, but PII
  already exchanged to them cannot be recalled (same E2E truth as D4 §8). The ACL gates
  the *channel*, not memory. Surface this honestly in the UI.

## 9. Membership policy & join flows

- **Join policy** (`join_policy`): `OPEN` / `APPROVAL_REQUIRED` (admin approves) /
  `INVITE_ONLY` / `HAPLOGROUP_VERIFIED` (must match a haplogroup, checked against
  non-PII fed/assertion data). Applies to **collaborators** joining the team.
- **Sovereign members joining** (proposal's flow): a member with a DID joins, becomes a
  `subject` *and* (optionally) a low-privilege team member; sets their own
  per-field visibility (which then *replaces* the admin's consent-flag cap for their
  data). This is the claim/sovereign path (D2 §6) at project granularity.
- **Invite** rides D1 (a signed invite → consent → `project_member` insert).

## 10. Lifecycle (per project, mixed subjects allowed)

```
Admin creates project P (stewarded)  → research.project + ADMIN project_member
Admin imports FTDNA roster           → pseudonymous subjects + subject_membership (D2)
Co-admins invited                    → project_member rows; steward P2P-replicates PII (§8)
Team collaborates                    → R2 assertions (AppView) + R3 PII (P2P) gated by ACL (§6)
Member M onboards + claims subject S  → S.custody_did=M; M becomes a team member;
                                        M's per-field visibility supersedes the consent cap
Project matures                      → more subjects sovereign; admins govern, members own
```

## 11. Module placement & schema deltas

- **AppView:** `research.project` + `research.project_member` (new, mig in the
  `research.*` set with D2/D4); `du-db::research::project` (ACL checks, role/permission
  queries) consumed by the D1 broker endpoints and the D4 assertion API; the
  `groupProject` PDS record ingested by du-jobs into `research.project`.
- **Navigator:** local project + team roster mirror; the invite/consent + PII-replicate
  flows over D1; per-subject visibility/consent UI.
- **Shared `du-domain`:** the role/permission enum + the capability→permission map (so
  Edge and AppView agree on who-can-do-what).
- **Reconcile with the proposal:** D5 supersedes the proposal's *governance/membership*
  sections (now AppView-enforced ACL) and *adopts* its roles/policies/succession; the
  proposal's aggregate records map onto D4 (§7). The proposal's member-sovereign
  visibility model = the post-claim state.

## 12. Open questions / decisions

1. **PII replication default** — auto-replicate every subject's PII to all co-admins
   (max durability, max exposure within the consented circle) vs. on-demand per
   subject. Recommend **auto-replicate within the team** (the circle is already
   consented; durability matters) with a per-subject opt-out.
2. **Sovereign-member privilege** — does a claimed member auto-get a team role, or stay
   subject-only until invited? Recommend **subject-only + self-visibility control**;
   team roles remain invite-gated.
3. **`HAPLOGROUP_VERIFIED` join** — checked against which non-PII signal (fed haplogroup
   reconciliation / a `HAPLOGROUP_IS` assertion)? Define.
4. **Project as PDS record owner** — the proposal makes `groupProject` an admin's PDS
   record; confirm AppView treats it as the source of truth (ingest) vs. AppView-native.
   Recommend **PDS record = source, AppView mirrors** (consistent with the rest).
5. **Cross-project subject** — a subject in two projects: confirm per-project assertion
   isolation (D4 §11.5) and that PII replication is per-project (no leakage across).

## ✅ AppView ACL BUILT (2026-06-12)

The collaborator-team ACL is built. **Reconciliation:** reused the existing
`social.group_project` (mig 0009) as the project (not a new `research.project`);
`owner_did` is the founding ADMIN. Added `research.project_member` (mig 0034:
project_id → social.group_project, member_did, role, permissions[], appointed_by,
joined_at, left_at). `du_db::research`: `Role` (ADMIN/CO_ADMIN/MODERATOR/CURATOR) +
`Capability` + `Role::allows` (the §4 map), `role_of` (owner⇒ADMIN, else live
project_member), `is_team_member`, `can`, `add_member`/`revoke_member`(left_at)/
`members_of`. **Wired in:** D2's register is now `ManageSubjects`-gated (ADMIN/CO_ADMIN),
the subjects read is team-member-gated; D1's project-scoped request + consent require
the actor be a live team member (`exchange::request_meta` + `project_scope_id`). Team
endpoints `/api/v1/research/project/{member,member/revoke,members}` (signed, ADMIN-gated).
Memory `group-project-acl`. **Remaining (Navigator/D4):** the groupProject PDS-record
ingest (§12 Q4); invite/join + PII replication/succession over D1 (§8/§9); the D4
capabilities (WriteAssertions/ResolveDispute/PromoteToCatalog defined, enforced when D4
lands); shared `du-domain` role enum; granular `permissions[]` overrides.

## 13. Next step — Platform track complete

D1–D5 are the full collaboration platform: **channel (D1) · pseudonymous registry (D2)
· genetic resolver (D3) · attributed claims with split rails (D4) · projects + ACL +
lifecycle (D5)** — with the no-PII-in-AppView invariant intact end to end. Buildable
order across the track: D1 `du-exchange` + broker → D2 `research_subject` + id-exchange
→ D4 `research.assertion` + rails → D5 `project`/`project_member` ACL → D3 IBD on the
same channel. The **Catalog track (D6 discovery automation, D7 multi-test-type, D8
sequencer-lab)** is independent and can proceed in parallel whenever the team chooses.
