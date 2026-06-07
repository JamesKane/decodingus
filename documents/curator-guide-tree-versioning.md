# Curator Guide: Tree Versioning System

This guide explains how to review, validate, and apply bulk changes to the
haplogroup tree, and how to resolve the placements the merge/graft couldn't make
confidently.

The Rust AppView splits this across **two curator screens**:

- **Change Sets** (`/curator/change-sets`) — the lifecycle: review the diff,
  approve/reject changes, then apply or discard.
- **Merge Reviews** (`/curator/reviews`) — resolve the ambiguous or blocked
  placements the SNP-graft/merge staged for a human (the `wip_*` items).

Both are reached from the **Curator dashboard** (`/curator`) and are gated by the
**Curator** role (`Admin`, `TreeCurator`, or `Curator`) — there are no finer-grained
per-action permissions.

---

## Overview

When large external tree sources (ISOGG, decoding-us, FTDNA) are merged or
grafted in, the changes don't go straight to production. They're captured in a
**Change Set** you review before applying.

This gives you:
- Time to review changes at your own pace
- A diff of exactly what will change before it affects users
- A separate worklist for ambiguous placements that need a human decision
- An audit trail of all changes

---

## Change Sets screen (`/curator/change-sets`)

A two-panel (master-detail) HTMX screen.

### Left panel — the change-set list

Each change set shows its name, source, DNA type (Y/mt), status, change count,
and who created it. Filter by status with the dropdown.

### Right panel — the detail/diff

Selecting a change set loads its panel (`/curator/change-sets/:id/panel`) with:
- Summary stats: **added / removed / modified / reparented**
- The **diff** rows (type, node name, before→after detail) — rendered inline
- The per-change list with each change's status
- Comments, and the status-appropriate lifecycle actions

### Statuses

| Status | Meaning | Your action |
|--------|---------|-------------|
| **DRAFT** | Merge/graft still materializing | Wait, then Start Review |
| **READY_FOR_REVIEW** | Materialized, awaiting review | Start Review (or Apply) |
| **UNDER_REVIEW** | Being actively reviewed | Approve/reject changes, then Apply |
| **APPLIED** | Live in production | None (read-only) |
| **DISCARDED** | Abandoned | None (read-only) |

Per-change status runs `PENDING → APPROVED`/`REJECTED → APPLIED`.

### Lifecycle actions

| Action | Endpoint | Available when |
|--------|----------|----------------|
| **Start Review** | `POST /curator/change-sets/:id/start-review` | DRAFT or READY_FOR_REVIEW → UNDER_REVIEW |
| **Review one change** (approve/reject) | `POST /curator/change-sets/:id/changes/:change_id/review` | UNDER_REVIEW |
| **Approve All Pending** | `POST /curator/change-sets/:id/approve-all` | UNDER_REVIEW |
| **Apply** | `POST /curator/change-sets/:id/apply` | READY_FOR_REVIEW or UNDER_REVIEW |
| **Discard** | `POST /curator/change-sets/:id/discard` | any non-APPLIED state |
| **Comment** | `POST /curator/change-sets/:id/comments` | any |

**Apply** promotes only the **APPROVED** changes to the live (temporal) tree and
marks the set APPLIED; it's idempotent (re-applying an APPLIED set is a no-op).
**Discard** requires a reason.

---

## Reviewing a change set

1. **Start the review** — select a READY_FOR_REVIEW set and click Start Review
   (status → UNDER_REVIEW).
2. **Read the diff** in the detail panel:
   - added (new nodes), removed, modified (e.g. variant edits), reparented (moved).
3. **Resolve any flagged placements** in the **Merge Reviews** screen (below) — the
   merge/graft routes anything it couldn't place confidently there, rather than
   guessing.
4. **Approve individual changes**, or **Approve All Pending** once you've vetted the
   flagged items.
5. **Apply** when satisfied.

### Confidence scores

Flagged placements carry an anchor strength (0–100%). Prioritize accordingly:

| Strength | Risk | Recommended action |
|----------|------|--------------------|
| **80–100%** | Low | Generally safe; spot-check a few |
| **50–79%** | Medium | Review the shared SNPs make sense for the branch |
| **20–49%** | High | Manual verification against known phylogeny |
| **0–19%** | Very high | Likely wrong; defer or research before accepting |

What affects it: SNP overlap (more shared defining variants = stronger), conflicting
variants, tree depth, and source completeness.

---

## Merge Reviews screen (`/curator/reviews`)

This is where you resolve the items the SNP-graft/merge staged for a human — the
`tree.wip_*` rows: SNP-graft Phase-4 flags, name collisions, and graft-blocked
branches.

### Left panel — the worklist

The staged items, filterable by **status** and **category**. Each row shows the
source, node name, category, best anchor, and any resolution already chosen.

### Right panel — one item's context + resolution form

Selecting an item loads its panel (`/curator/reviews/:wip_id/panel`) with the full
decision context:
- The reason it was flagged and its **category**
- **Best anchor** + **anchor strength %**, and the candidate anchor nodes (with hit
  counts)
- Defining-SNP counts (and how many are known to the foundation)
- The source parent and its status; whether it's backbone
- The **tentative parent** and a preview of where it would land (that parent's
  current children)
- Open / resolved / deferred counts for the parent change set

### Resolving an item

`POST /curator/reviews/:wip_id/resolve` with an `action`, an optional `target`
(a **node name**, resolved server-side — not a numeric id), and `notes`:

| Action | `target` | Use case |
|--------|----------|----------|
| **REPARENT** | new parent's name (**required**) | Confirm the suggested anchor, or choose a different parent |
| **MERGE_EXISTING** | existing node's name (**required**) | The staged node duplicates a production node — link instead of creating |
| **DEFER** | — | Needs more research; excluded from Apply until resolved |

An unknown `target` name is rejected with a notice (no node is created from a typo).
Decisions are written to `wip_resolution` and attributed to you.

### Applying resolutions

`POST /curator/reviews/:wip_id/apply` bumps the parent change set to UNDER_REVIEW
and runs the **same tested change-set apply engine**, enacting your resolutions. It
reports created / variant-edits / skipped counts. Deferred items are skipped and
remain in the worklist.

---

## Reviewing large change sets

Major source updates produce thousands of changes. A systematic pass:

1. **Triage by anchor strength** — handle the lowest-confidence flagged items first
   in Merge Reviews.
2. **Read the diff** for structural changes (reparents) before bulk-approving.
3. **Search** — use the browser find (Ctrl/Cmd-F) to jump to specific clades in the
   diff.
4. **Approve in bulk** — after resolving the flagged items, use **Approve All
   Pending** for the rest.

Recommended strategy for 5,000+ changes:
- Don't review every change — focus on flagged items and reparents.
- Trust high-strength placements (>80%).
- Divide by expertise across curators (e.g. "I'll take R1b, you take E-M96"); use
  change-set **comments** to note who reviewed what.
- Time-box the review, then reassess.

---

## Applying to production

When you're satisfied:
1. Ensure flagged items are resolved (or deferred) in Merge Reviews.
2. Click **Apply** on the change set.
3. Confirm.

The approved changes are applied to the live tree, the status becomes **APPLIED**,
and an audit record (`promoted_by`/`promoted_at`) is written.

## Discarding a change set

If the changes shouldn't be applied, click **Discard** and enter a reason. Common
reasons: data-quality issues, superseded by a newer merge, or a test merge.

---

## Machine / scripted access (management API)

The interactive screens above are for curators. A separate **management API**
(under `/manage/*`, X-API-Key) drives the same lifecycle for automation — e.g. the
tree-init/graft tooling:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/manage/haplogroups/merge` · `/merge/preview` | POST | Run / preview a merge |
| `/manage/change-sets` | GET / POST | List / create change sets |
| `/manage/change-sets/:id` | GET | Change-set detail |
| `/manage/change-sets/:id/changes` | POST | Add a change |
| `/manage/change-sets/:id/diff` | GET | Full diff (JSON) |
| `/manage/change-sets/:id/{start-review,approve-all,apply,discard}` | POST | Lifecycle |
| `/manage/change-sets/:id/changes/:change_id/review` | POST | Review one change |

---

## Best practices

- **Before reviewing:** check the source (trusted authority?) and the scale.
- **During review:** start with the lowest-confidence flagged items; read the diff
  for unexpected reparents.
- **Before applying:** have another curator spot-check large sets; apply outside
  peak usage.

---

## Workflow example

**Scenario:** an ISOGG update with thousands of new nodes.

1. A change set appears as **READY_FOR_REVIEW** in `/curator/change-sets`.
2. Open it; read the summary (e.g. *N created, M reparented*) and the diff.
3. Click **Start Review** (→ UNDER_REVIEW).
4. Switch to `/curator/reviews` and work the flagged worklist:
   - **REPARENT** to fix or confirm a parent,
   - **MERGE_EXISTING** for duplicates,
   - **DEFER** items needing research.
5. **Apply** the resolutions from a review item (enacts them via the change-set
   apply engine).
6. Back in `/curator/change-sets`, spot-check the diff and **Approve All Pending**.
7. **Apply** the change set → **APPLIED**.
8. Verify in the tree explorer (`/ytree` / `/mtree`).

---

## Troubleshooting

| Symptom | Possible cause | Solution |
|---------|----------------|----------|
| "No change sets found" | Status filter hiding results | Reset the status filter |
| Can't reach the screen | Not a curator | Need the `Curator` / `TreeCurator` / `Admin` role |
| **Apply** unavailable | Already APPLIED | No action needed |
| **Apply** unavailable | Wrong status | Apply needs READY_FOR_REVIEW or UNDER_REVIEW (Start Review first) |
| Resolve returns a notice | Unknown target node name | Use an existing node's exact name |
| REPARENT/MERGE rejected | No `target` given | REPARENT and MERGE_EXISTING both require a target node name |
| Deferred items still listed | By design | Deferred items stay in the worklist until resolved |
| Changes not showing in production | Browser cache / not applied | Hard-refresh; confirm status is APPLIED |

---

## Related documentation

- [Tree Versioning System (technical)](planning/tree-versioning-system.md) — architecture and data model.
- [Haplogroup Discovery System](planning/haplogroup-discovery-system.md) — how observations propose new branches.
- [`rust/README.md`](../rust/README.md) — the curator suite, merge/SNP-graft, and the management API in context.
