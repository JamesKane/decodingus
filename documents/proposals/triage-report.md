# Proposals ↔ Rust Triage Report

**Started:** 2026-06-07. Companion to `../planning/design-doc-triage-report.md`,
same method: compare each `documents/proposals/` doc against the Rust
implementation and record a verdict + recommended action **for later action**.

**Verdict legend:** ✅ current · 📝 update doc · 🔧 make code compliant · ⚖️ split ·
🗑️ remove (superseded/realized/dropped).

## Execution log (2026-06-07)

- **Removed:** #1 variant-schema-simplification, #2 haplogroup-tree-merge-api,
  #8 pds-workbench-biosample-flow. Inbound refs rewired in `planning/`
  (haplogroup-discovery-system → "realized in `core.variant`"; design-roadmap
  Bucket-B list drops pds-workbench).
- **Headers added** to the kept proposals: #3 (realized; kept for methodology),
  #4 (forward; reconciled by D5), #5/#6 (forward Bucket B), #7 (deferred). #3's
  dead `appview-pds-backfeed-system.md` refs and #4's dead `ibd-matching-system.md`
  ref were rewired.
- **#7 Patronage kept as deferred** (revive past ~a few hundred active users).

## Status index

| # | Proposal | Verdict | Action |
|---|----------|---------|--------|
| 1 | variant-schema-simplification.md | 🗑️ REMOVED (realized) | done |
| 2 | haplogroup-tree-merge-api-proposal.md | 🗑️ REMOVED (realized + extended) | done |
| 3 | branch-age-estimation.md | 📝 Keep (realized; methodology ref) | header + ref fix ✓ |
| 4 | group-project-system.md | 📝 Keep + reconcile via D5 | header + ref fix ✓ |
| 5 | Messaging_and_Feed_System.md | 📝 Keep + reconcile (forward; no-PII) | header ✓ |
| 6 | Reputation_System_Implementation.md | 📝 Keep + reconcile (forward) | header ✓ |
| 7 | Patronage_Donation_System.md | 📝 Keep (deferred — revive at scale) | header ✓ |
| 8 | pds-workbench-biosample-flow.md | 🗑️ REMOVED (Navigator-side) | done |

---

## 1. variant-schema-simplification.md — 🗑️ Remove (realized)

**Compared against:** `core.variant` (mig 0002, universal JSONB coordinates/aliases),
`du_db::variant`, the YBrowse ingestion pipeline.

**Finding:** the doc is already marked **"✅ Implemented" (2025-12-14)** and is just a
thin documentation index for the Scala `variant_v2` model. The Rust rewrite realized
it as `core.variant` (single row per site, JSONB `coordinates`/`aliases`,
`ON CONFLICT` batch upserts in the YBrowse pipeline). Nothing to keep — it's a
realized-status pointer with the Scala table name.

**Inbound ref to fix on removal:** `planning/haplogroup-discovery-system.md` cites it
as the variant-schema prerequisite ("See: documents/proposals/variant-schema-simplification.md").
Its other links point at `schema/` + `deployment/` guides (separate doc sets, their
own passes).

**Recommended action:** **remove**; reword the discovery-system prereq line to
"realized in `core.variant` (mig 0002)" instead of linking this file.

---

## 2. haplogroup-tree-merge-api-proposal.md — 🗑️ Remove (realized + extended)

**Compared against:** `/manage/haplogroups/merge` + `/merge/preview`
(`routes/versioning.rs`), `du_domain::merge` (Identify-Match-Graft),
`du_db::snp_graft`, `tree.haplogroup.provenance` JSONB, change-sets + `/curator/reviews`.

**Finding:** the proposal's core is **realized**: variant/SNP-based node matching,
the `provenance` JSONB on `tree.haplogroup` (used for backbone/aliases/credit), the
`/manage/haplogroups/merge[/preview]` endpoints with dry-run, and split detection.
The Rust impl **went further**: merges materialize into reviewable **change-sets**
(not direct writes), and external source trees graft into the **ISOGG foundation**
via **SNP-anchored grafting** (`du_db::snp_graft`, `--graft`/`--reattach`) with the
`/curator/reviews` (`wip_*`) resolution flow — documented in `rust/README.md` and
the reconciled `planning/tree-versioning-system.md` + the curator guide. The
proposal itself is Scala (Tapir, `evolutions/52.sql`, `app/...`).

**Recommended action:** **remove** — realized and better-documented by the rust
README (SNP-graft) + tree-versioning-system.md + the curator guide. (Check inbound
refs on removal.)

---

## 3. branch-age-estimation.md — 📝 Kept (realized; methodology reference)

**Compared against:** mig 0013 (`tree.haplogroup_ancestral_str`) + 0014 (combined
age), `tree.genealogical_anchor`, `genomics.str_mutation_rate`,
`genomics.biosample_callable_loci`, `du_db::age` (`combine`,
`recompute_combined_ages`), `du_db::ystr`, the `branch-age-recompute` job, and
`/api/v1/haplogroups/{name}/age`.

**Finding:** essentially **fully realized**. **Every** table the proposal designs
exists (ancestral STR, genealogical anchor, STR mutation rate, per-sample callable
loci), plus the SNP+STR combined-age compute, the weekly→daily recompute job, and
the age API. Caveats: the combination is **inverse-variance** (a simplification of
the doc's full PDF-multiplication `P(t|e)=k∏P(t|eᵢ)`), and genealogical-anchor
wiring into the combine may be partial. The doc is Scala (`evolutions/48.sql`,
`BranchAgeEstimationService`, etc.) and **references the now-removed
`appview-pds-backfeed-system.md`** (lines 21, 204).

**Recommended action:** **remove** (built; the McDonald framework lives in
`du_db::age`/`ystr` + mig 0013/0014) — **but confirm**, since the doc carries
non-trivial *scientific methodology reference* (mutation rates, multi-step STR
frequencies, expected-precision tables, the McDonald port pointers) that isn't
fully captured in code. If kept instead: add a Rust-status header and drop the two
backfeed references.

---

## 4. group-project-system.md — 📝 Keep + reconcile via D5

**Compared against:** `d5-group-project-reconciliation.md` (read in full),
`social` placeholder schema (mig 0009). Unbuilt.

**Finding:** this is the member-sovereign group-project proposal that **D5 exists to
reconcile** with D1–D4. Per D5: it **supersedes the proposal's governance/membership
sections** (now the AppView-enforced `research.project`/`project_member` ACL),
**adopts** its roles/policies/succession, **maps** its aggregate records
(`projectTreeView`/`projectModal`/`strComparison`) onto D4's R1/R2 rails, and treats
its member-sovereign visibility model as the **post-claim** state. The platform is
**forward** (`social` placeholder only). The proposal is AT-Proto/lexicon-focused
(no Scala). It **links the now-removed `ibd-matching-system.md`** (line 6).

**Recommended action:** **keep** (D5 builds on it; platform unbuilt) — add a header
pointing to D5 as the authoritative reconciliation and noting it's forward; fix the
dangling `ibd-matching-system.md` link → D1/D3.

---

## 5. Messaging_and_Feed_System.md — 📝 Keep + reconcile (forward)

**Compared against:** `social.{user_block, conversation, message, feed_post}`
(mig 0009). Schema present; **zero logic** (only the static `/reputation` page exists).

**Finding:** forward Bucket-B social design (Scala/Slick). The schema is in place but
unbuilt. **Reconciliation needed with the no-PII direction:** the proposal stores
**DMs centrally as plaintext** (`social.message.content`) — that conflicts with the
"AppView holds no PII" invariant. Under the new model, DMs should ride the **D1
encrypted relay** (or AT-Proto records), not a central plaintext mailbox; the public
feed (AT-Proto `feed.post` records + AppView index) is consistent. Also reuses the
Reputation system (#6) and should reconcile with D4 assertion threads (roadmap).

**Recommended action:** **keep** — add a forward/Bucket-B header noting: schema
exists (mig 0009), logic unbuilt, refresh Slick→Rust, and **rework DM transport to
D1/AT-Proto (no central plaintext)**. Build after the social layer is scheduled.

---

## 6. Reputation_System_Implementation.md — 📝 Keep + reconcile (forward)

**Compared against:** `social.{reputation_event_type, reputation_event,
user_reputation_score}` (mig 0009), the public `/reputation` page (static content,
no backend).

**Finding:** forward Bucket-B design (Scala/Slick). The schema matches (singularized
table names) and is in place; the service/guard logic is **unbuilt**. The
user-facing `/reputation` page already describes the system. Lower priority
(roadmap: "depends on social being live").

**Recommended action:** **keep** — add a forward/Bucket-B header (schema exists
mig 0009; logic unbuilt; refresh Slick→Rust). No code action now.

---

## 7. Patronage_Donation_System.md — 📝 Keep (deferred — revive at scale)

**Compared against:** `rust/README.md` (billing not in production today) and the FAQ
(`/faq` lists a "Patronage Donation System" under sustainability).

**Finding:** a Scala/Play + Stripe donation-tier design. Not in scope for the
current rewrite, **but explicitly deferred, not dead** — per the owner, patronage/
billing will likely return to fund infrastructure once the platform crosses ~a few
hundred active users. The FAQ already names it as the sustainability path. Only
Scala/payment specifics are stale.

**Recommended action:** **keep** as a deferred proposal — add a light header:
"deferred; revive when active users cross ~a few hundred; refresh the
Scala/Play/Stripe specifics to the Rust stack at that time." No code action.

---

## 8. pds-workbench-biosample-flow.md — 🗑️ REMOVED (Navigator-side)

**Compared against:** the Jetstream ingest reality (`fed.biosample` + the du-jobs
Jetstream consumer), D2 (`research_subject` model), biosample consolidation.
*(Triaged from the overview + the known ingest model — 65 KB, not read in full.)*

**Finding:** this is **predominantly a Navigator (Edge / DUNavigator) design** —
the desktop workspace, local GATK pipeline, project organization, and PDS sync. Its
AppView-relevant slice (researchers' biosamples reaching the AppView) is the
ingest, and that's **realized differently**: the "Current State" REST APIs it cites
(`POST /api/private/external/biosamples`, `/api/external-biosamples`) are Scala-era
and **don't exist** — ingest is now the outbound **Jetstream → `fed.biosample`**
mirror. It also **predates the D2 ResearchSubject / consolidation model**.

**Recommended action:** **remove from this repo** (Navigator-side + superseded for
the AppView) — **but confirm**: if its Edge-workflow detail still has value, it
belongs in the **DUNavigator** repo, so consider relocating rather than deleting.

---

## Summary

| Verdict | Proposals |
|---------|-----------|
| 🗑️ Removed (realized) | #1 variant-schema-simplification, #2 tree-merge-api |
| 🗑️ Removed (Navigator-side) | #8 pds-workbench |
| 📝 Kept (realized; methodology reference) | #3 branch-age-estimation |
| 📝 Kept + reconcile (forward Bucket B) | #4 group-project (via D5), #5 messaging, #6 reputation |
| 📝 Kept (deferred — revive at scale) | #7 Patronage |

**Themes**
- As in the planning set, **no code is wrong** — verdicts are remove (realized/
  dropped) or keep-and-reconcile (forward).
- **Realized & removable:** the variant model, the tree-merge API, and branch age
  are all built (schema + compute + endpoints); their proposals are historical.
- **Forward Bucket B (keep):** group-project (D5 reconciles it), messaging,
  reputation — all have `social`/`research` placeholder schema (mig 0009) but no
  logic. Headers should mark them forward + reconcile to Rust and the **no-PII /
  D1–D5** model (esp. messaging: DMs must not be central plaintext).
- **Confirm before deleting:** #3 (scientific methodology reference) and #8 (large,
  Navigator-side — relocate vs delete).
- **Refs to fix on removal:** `planning/haplogroup-discovery-system.md` cites #1;
  `#4` links the removed `ibd-matching-system.md`; check inbound refs for #2/#3/#8.
