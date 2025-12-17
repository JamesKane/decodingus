# Curator Guide: Tree Versioning System

This guide explains how to use the Tree Versioning System to review, validate, and apply bulk changes to the haplogroup tree.

---

## Overview

When large external tree sources (like ISOGG or ytree.net) are merged into the system, the changes don't go directly to production. Instead, they're captured in a **Change Set** that you can review before applying.

This gives you:
- Time to review changes at your own pace
- Ability to see what will change before it affects users
- Tools to handle ambiguous placements
- An audit trail of all changes

---

## Accessing the Change Sets Dashboard

1. Navigate to **Curator > Change Sets** from the main menu
2. Or go directly to `/curator/change-sets`

**Required Permission:** `tree.version.view`

---

## Understanding the Dashboard

The dashboard shows a master-detail layout:

### Left Panel: Change Set List

Each change set shows:
- **Name**: Descriptive name (e.g., "isogg-2025-12")
- **Source**: Where the data came from (ISOGG, ytree.net, etc.)
- **Type**: Y-DNA or mtDNA
- **Status**: Current state (see below)
- **Changes**: Total count and pending items
- **Created**: When and by whom

### Right Panel: Change Set Details

Click a change set to see:
- Full statistics (nodes created, updated, reparented)
- Ambiguity warnings
- Available actions based on status
- Comments from other curators

---

## Change Set Statuses

| Status | Meaning | Your Action |
|--------|---------|-------------|
| **Draft** | Merge in progress | Wait for completion |
| **Ready for Review** | Merge complete, needs review | Start review |
| **Under Review** | Being actively reviewed | Continue reviewing |
| **Applied** | Changes are in production | None (read-only) |
| **Discarded** | Changes were abandoned | None (read-only) |

---

## Filtering Change Sets

Use the dropdown filters at the top:
- **Type Filter**: Show only Y-DNA or mtDNA change sets
- **Status Filter**: Show only sets in a specific status

---

## Reviewing a Change Set

### Step 1: Start the Review

1. Click a change set with "Ready for Review" status
2. Click **Start Review** in the detail panel
3. Status changes to "Under Review"

### Step 2: View the Full Diff

Click **View Full Diff** to see all changes in the set:

- **Green rows**: New nodes being created
- **Yellow rows**: Existing nodes being updated
- **Blue rows**: Nodes being reparented (moved in tree)

The diff view shows:
- What changed (name, variants, parent)
- Before and after values for updates
- Confidence scores for ambiguous placements

### Step 2b: View Tree Preview (ASCII)

For a structural overview of proposed changes, click the **Tree Preview** button in the change set detail panel (next to "View Diff"). The preview opens in a new browser tab.

> **Direct URL:** You can also access it at `/curator/change-sets/{id}/tree-preview` if needed.

This returns a plain-text ASCII tree showing affected subtrees with markers:

```
=== Tree Preview: Y merge from ISOGG ===
Type: Y | Status: DRAFT
New nodes: 8322 | Reparents: 306 | Variant additions: 76673

Legend: [+] = new node, [→] = reparented, [~] = modified
==================================================

Y
├── [+] A00-T (V60, V168, +3 more)
│   ├── [→] A00
│   └── [+] A0-T
│       ├── [→] A0
│       └── [→] A1
└── BT

--- Nodes reparented to new WIP nodes ---
  A00: Y → A00-T [+]
  A0: Y → A0-T [+]

--- Variant additions to existing nodes ---
  E1a-Y947: (M4671, CTS9320, +38 more)
```

**Legend:**
| Marker | Meaning |
|--------|---------|
| `[+]` | New node to be created |
| `[→]` | Existing node being reparented |
| `[~]` | Existing node with variant additions |

The preview shows:
- New nodes in their proposed tree position
- Existing siblings for context
- Up to 5 variant names per node
- Summary of reparent operations
- Summary of variant additions to existing nodes

#### Tips for Navigating Large Previews

For large merges with thousands of nodes, the ASCII preview can be dense. Here are some practical tips:

1. **Use browser search (Ctrl+F / Cmd+F)** - Search for specific haplogroup names or variant names to jump directly to areas of interest

2. **Focus on the summary sections** - Scroll to the bottom for:
   - "Nodes reparented to new WIP nodes" — shows all reparent operations in a compact list
   - "Variant additions to existing nodes" — lists all existing nodes receiving new variants

3. **Look for markers first** - Search for `[+]` to find all new nodes, or `[→]` to find all reparented nodes

4. **Copy to a text editor** - For very large previews, copy the text to an editor with better navigation (code folding, outline view, etc.)

5. **Cross-reference with the Diff view** - Use the Tree Preview to understand structure, then switch to the Diff view for detailed change-by-change review

> **Future Enhancement:** A graphical side-by-side tree comparison view is planned for a future release, which will provide a more visual way to review structural changes.

### Step 3: Handle Ambiguities

If the change set has ambiguities:

1. Look for the yellow warning banner showing the count
2. Click **View Report** to see the ambiguity report
3. For each ambiguity, decide:
   - **Accept the placement** (approve the change)
   - **Reject the placement** (skip this change)
   - **Manually fix** the data before applying

Ambiguities occur when:
- Multiple possible parent placements exist
- The algorithm chose based on heuristics
- A confidence score below threshold was assigned

#### Understanding Confidence Scores

Each ambiguous placement includes a confidence score from 0.0 to 1.0. Use this guide to prioritize your review:

| Score Range | Risk Level | Recommended Action |
|-------------|------------|-------------------|
| **0.80 – 1.00** | Low | Generally safe to approve. Algorithm had strong SNP overlap. Spot-check a few. |
| **0.50 – 0.79** | Medium | Review the placement. Check if the shared SNPs make sense for this branch. |
| **0.20 – 0.49** | High | Manual verification required. Compare source data against known phylogeny. |
| **0.00 – 0.19** | Very High | Likely incorrect placement. Consider skipping or manually researching. |

**What affects confidence:**
- **SNP overlap** — More shared defining variants = higher confidence
- **Conflicting variants** — Variants that contradict the placement lower confidence
- **Tree depth** — Deeper placements with fewer distinguishing SNPs may have lower scores
- **Source quality** — Some sources have more complete variant data than others

### Step 4: Review Individual Changes

In the "Under Review" status, you'll see pending changes:

For each change you can:
- **Approve**: Mark as validated
- **Skip**: Exclude from this promotion (stays in set but won't apply)

Use **Approve All Pending** to quickly approve remaining changes after you've reviewed the ambiguities.

---

## Reviewing Large Change Sets

When dealing with thousands of changes (common for major source updates like ISOGG), a systematic approach is essential.

### Current Workflow

1. **Triage by confidence** — Start with the Ambiguity Report, sorted by lowest confidence first
2. **Use Tree Preview** — Get a structural overview before diving into details
3. **Spot-check by branch** — Use browser search (Ctrl+F) in the Tree Preview or Diff view to find specific clades
4. **Approve in bulk** — After reviewing ambiguities, use "Approve All Pending" for remaining items

### Current Limitations

The following features are not yet available but are on the roadmap:

| Desired Feature | Current Workaround |
|-----------------|-------------------|
| Filter diff by branch (e.g., "only R1b") | Use Ctrl+F in Tree Preview or Diff view |
| Bulk approve by subclade | Review ambiguities, then "Approve All Pending" |
| Assign branches to expert curators | Coordinate manually; add comments noting who reviewed what |
| Export diff to spreadsheet | Copy Tree Preview text to external tools |

### Recommended Review Strategy for 5,000+ Changes

1. **Don't review every change** — Focus on ambiguities and structural changes (reparents)
2. **Trust high-confidence placements** — Scores above 0.80 rarely need individual review
3. **Divide by expertise** — If multiple curators are available, coordinate by major branch:
   - "I'll review everything under R1b"
   - "You take the E-M96 subtree"
4. **Use comments** — Add comments to the change set noting what you reviewed
5. **Time-box your review** — Set a limit (e.g., 2 hours) then assess if more review is needed

> **Feature Requests:** If you need filtering by branch or bulk approval by subclade, please submit a feature request. These are high-priority UX improvements under consideration.

---

## Conflict Resolution

Beyond simply approving or skipping changes, you can now create **resolutions** to correct merge algorithm decisions before applying to production.

### Resolution Types

| Type | Description | Use Case |
|------|-------------|----------|
| **REPARENT** | Change the parent of a node | Algorithm placed node under wrong parent |
| **EDIT_VARIANTS** | Add or remove variant associations | Missing or incorrect SNP assignments |
| **MERGE_EXISTING** | Map WIP node to existing production node | Duplicate detection — don't create, link instead |
| **DEFER** | Move to manual review queue | Needs expert review or more research |

### Creating Resolutions

Resolutions are created via the API. Each resolution targets either a WIP haplogroup (new node) or a WIP reparent (move operation).

#### REPARENT Resolution

When the algorithm placed a node under the wrong parent:

```bash
# Via curl (example)
curl -X POST /curator/change-sets/123/resolve/reparent \
  -d "wipHaplogroupId=456" \
  -d "newParentId=789" \
  -d "notes=Source data shows this should be under R-M269"
```

Parameters:
- `wipHaplogroupId` or `wipReparentId` — What to resolve (one required)
- `newParentId` or `newParentPlaceholderId` — New parent (one required)
- `notes` — Explanation for audit trail

#### EDIT_VARIANTS Resolution

When variant associations need correction:

```bash
curl -X POST /curator/change-sets/123/resolve/edit-variants \
  -d "wipHaplogroupId=456" \
  -d 'variantsToAdd=[101, 102, 103]' \
  -d 'variantsToRemove=[50]' \
  -d "notes=Adding missing defining SNPs per ISOGG"
```

Parameters:
- `wipHaplogroupId` or `wipReparentId` — What to resolve
- `variantsToAdd` — JSON array of variant IDs to add
- `variantsToRemove` — JSON array of variant IDs to remove
- `notes` — Explanation

#### MERGE_EXISTING Resolution

When a WIP node duplicates an existing production node:

```bash
curl -X POST /curator/change-sets/123/resolve/merge-existing \
  -d "wipHaplogroupId=456" \
  -d "mergeTargetId=200" \
  -d "notes=R-M269 already exists as ID 200"
```

This prevents creating a duplicate — the WIP node's relationships will be redirected to the existing production node.

#### DEFER Resolution

When an item needs expert review before deciding:

```bash
curl -X POST /curator/change-sets/123/resolve/defer \
  -d "wipHaplogroupId=456" \
  -d "priority=HIGH" \
  -d "reason=Disputed placement - needs phylogeny expert review" \
  -d "notes=See ISOGG discussion thread #4521"
```

Priority levels: `LOW`, `NORMAL`, `HIGH`, `CRITICAL`

Deferred items are excluded from Apply until resolved.

### Viewing Resolutions

**All resolutions for a change set:**
```
GET /curator/change-sets/123/resolutions
```

**Deferred items only:**
```
GET /curator/change-sets/123/deferred
```

### Cancelling a Resolution

If you created a resolution by mistake:

```bash
curl -X DELETE /curator/change-sets/123/resolutions/999
```

This sets the resolution status to `CANCELLED`, effectively removing it.

### Resolution Workflow

1. **During review**, identify problematic placements via the Ambiguity Report or Tree Preview
2. **Create resolutions** for items needing correction
3. **View resolutions** to verify all corrections are in place
4. **Apply** — the system applies your resolutions during promotion:
   - REPARENT: Uses your specified parent instead of the original
   - EDIT_VARIANTS: Adds/removes variants after node creation
   - MERGE_EXISTING: Skips node creation, remaps relationships
   - DEFER: Skips the item entirely (remains in WIP)
5. **Resolution status** is updated to `APPLIED` after successful processing

### API Summary

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/curator/change-sets/:id/resolutions` | GET | List all resolutions |
| `/curator/change-sets/:id/deferred` | GET | List deferred items |
| `/curator/change-sets/:id/resolve/reparent` | POST | Create REPARENT resolution |
| `/curator/change-sets/:id/resolve/edit-variants` | POST | Create EDIT_VARIANTS resolution |
| `/curator/change-sets/:id/resolve/merge-existing` | POST | Create MERGE_EXISTING resolution |
| `/curator/change-sets/:id/resolve/defer` | POST | Create DEFER resolution |
| `/curator/change-sets/:csId/resolutions/:rId` | DELETE | Cancel a resolution |

**Required Permission:** `tree.version.review`

---

## Applying to Production

When you're satisfied with the review:

1. Ensure all high-priority items have been reviewed
2. Click **Apply to Production**
3. Confirm in the dialog

**What happens:**
- All approved changes are applied to the live tree
- Users will see the updated tree structure
- Change set status becomes "Applied"
- An audit record is created

**Required Permission:** `tree.version.promote`

---

## Discarding a Change Set

If the changes should not be applied:

1. Click **Discard** (red button)
2. Enter a reason (required, minimum 10 characters)
3. Click **Confirm Discard**

**Common reasons to discard:**
- Data quality issues discovered
- Superseded by a newer merge
- Test merge that was never intended for production

**Required Permission:** `tree.version.discard`

---

## Best Practices

### Before Reviewing

- Check when the change set was created
- Review the source - is this a trusted authority?
- Note the scale - more changes = more careful review needed

### During Review

- Start with the ambiguity report
- Focus on low-confidence placements first
- Use the diff view to understand structural changes
- Look for unexpected reparenting operations

### Before Applying

- Have another curator spot-check large change sets
- Verify the merge statistics look reasonable
- Consider the timing (avoid applying during peak usage)

---

## Workflow Example

**Scenario:** ISOGG monthly update with 7,537 new nodes

1. Receive notification that change set "isogg-2025-12" is ready
2. Navigate to Change Sets dashboard
3. Click the new change set to see details:
   - 7,537 nodes created
   - 2,695 nodes updated
   - 684 ambiguities detected
4. Click "Start Review"
5. View the tree preview at `/curator/change-sets/{id}/tree-preview` to understand the structural changes
6. Click "View Report" to handle the 684 ambiguities
7. Review each ambiguity:
   - Most are low-risk automatic placements (approve)
   - Some need manual verification (check in tree view)
   - A few should be skipped (data quality issues)
8. **Create resolutions** for items needing correction:
   - Use REPARENT to fix incorrect parent placements
   - Use EDIT_VARIANTS to add missing SNPs
   - Use MERGE_EXISTING for duplicate nodes
   - Use DEFER for items needing expert research
9. Return to detail panel
10. Click "View Full Diff" to spot-check changes
11. Check `/curator/change-sets/{id}/resolutions` to verify all corrections are in place
12. Click "Approve All Pending" for remaining items
13. Click "Apply to Production"
14. Confirm in the dialog
15. Done! Check the tree explorer to verify

---

## Troubleshooting

| Symptom | Possible Cause | Solution |
|---------|----------------|----------|
| "No change sets found" | Filters hiding results | Reset filters to "All Types" and "All Statuses" |
| "No change sets found" | Missing permissions | Request `tree.version.view` from Admin |
| "No change sets found" | No recent merges | Check with data team if merges are scheduled |
| "Apply" button disabled | Change set already applied | Check status — if "Applied", no action needed |
| "Apply" button disabled | Missing permissions | Request `tree.version.promote` from Admin |
| "Apply" button disabled | Unresolved ambiguities | View Ambiguity Report and resolve all items |
| "Discard" button not visible | Missing permissions | Request `tree.version.discard` from Admin |
| Changes not showing in production | Browser cache | Open in private/incognito window or clear cache |
| Changes not showing in production | Page not refreshed | Refresh the tree explorer page |
| Changes not showing in production | Not yet applied | Verify change set status is "Applied" |
| Tree structure looks wrong | Viewing cached data | Hard refresh (Ctrl+Shift+R / Cmd+Shift+R) |
| Tree structure looks wrong | Merge had errors | Check merge logs and ambiguity report |
| Ambiguity count seems high | Large structural changes | Normal for major source updates — review systematically |
| Resolution not applied | Status still "PENDING" | Check if Apply was run; resolutions apply during promotion |
| Resolution API returns 400 | Missing required fields | Ensure wipHaplogroupId or wipReparentId is provided |
| Deferred items still visible | Change set re-applied | Deferred items remain in WIP until manually resolved |
| Cannot create resolution | Missing permissions | Request `tree.version.review` from Admin |

---

## Permissions Summary

| Action | Permission Required |
|--------|---------------------|
| View change sets | `tree.version.view` |
| View resolutions | `tree.version.view` |
| Review changes | `tree.version.review` |
| Create resolutions | `tree.version.review` |
| Cancel resolutions | `tree.version.review` |
| Apply to production | `tree.version.promote` |
| Discard change set | `tree.version.discard` |

Contact an administrator if you need additional permissions.

---

## Related Documentation

- [Tree Versioning System (Technical)](planning/tree-versioning-system.md) - Architecture and implementation details
- [Conflict Resolution System (Technical)](planning/conflict-resolution-system.md) - Resolution types and data model
- [Haplogroup Discovery System](planning/haplogroup-discovery-system.md) - How user observations propose new branches
