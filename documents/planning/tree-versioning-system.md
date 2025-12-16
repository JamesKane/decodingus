# Tree Versioning System: Production and WIP Trees

## Executive Summary

This document outlines a system for managing multiple versions of the haplogroup tree: a **Production** (canonical, public-facing) version and a **WIP** (Work-In-Progress, staging) version. This enables large-scale tree merges (ISOGG, ytree.net, academic sources) to be ingested, reviewed, and validated before affecting production reporting.

---

## Problem Statement

### Current Pain Points

When merging large external trees (e.g., ISOGG with 10,000+ nodes), we encounter:

1. **High Ambiguity Counts**: A recent ISOGG merge generated 684 ambiguous placements requiring curator review
2. **Immediate Production Impact**: Changes are applied directly to the live tree
3. **No Rollback Path**: If a merge introduces errors, manual correction is required
4. **Conflict Resolution Pressure**: Curators must resolve conflicts before or during merge, not at their own pace
5. **No A/B Comparison**: Cannot compare "before" and "after" states easily

### Example Scenario

```
ISOGG Merge (10,232 nodes):
  - 7,537 nodes created
  - 2,695 nodes matched/updated
  - 684 ambiguities detected
  - 138 split operations

Current Behavior:
  All changes immediately affect production tree.
  Users see partially-validated data.
  684 ambiguities logged to file, but tree is already modified.

Desired Behavior:
  Changes staged in WIP tree.
  Curator reviews ambiguity report.
  Curator resolves conflicts in WIP.
  Curator promotes validated WIP to Production.
```

---

## Relationship to Discovery System

The [Haplogroup Discovery System](haplogroup-discovery-system.md) handles **bottom-up** tree evolution:
- Individual biosample observations suggest new terminal branches
- Consensus builds from multiple independent samples
- Proposed branches are promoted when threshold is reached

This Tree Versioning System handles **top-down** tree evolution:
- Bulk imports from external authorities (ISOGG, ytree.net, researchers)
- Large-scale structural changes
- Curator-driven validation and promotion

**Integration Points:**
- Discovery proposals are made against the **Production** tree
- When WIP is promoted, discovery proposals may need re-evaluation
- Both systems use curator workflow for validation

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         External Tree Sources                           │
│              (ISOGG, ytree.net, Academic Publications)                  │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Tree Merge Service                                   │
│  • Merges into WIP tree (never directly to Production)                  │
│  • Generates ambiguity/conflict reports                                 │
│  • Tracks all changes for review                                        │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         WIP Tree                                         │
│  • Staging area for bulk changes                                        │
│  • Curator can review, edit, resolve conflicts                          │
│  • Can be reset/discarded if needed                                     │
│  • Diff view against Production                                         │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                          ┌─────────┴─────────┐
                          │  Curator Review   │
                          │  & Validation     │
                          └─────────┬─────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                       Production Tree                                    │
│  • Canonical, validated tree                                            │
│  • Used for public APIs and reporting                                   │
│  • Biosample assignments reference this tree                            │
│  • Discovery proposals target this tree                                 │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Data Model

### Option A: Branching Model (Git-like)

Each tree version is a complete snapshot. Changes are tracked as "commits" with parent references.

```scala
case class TreeVersion(
  id: Option[Int],
  haplogroupType: HaplogroupType,      // Y or MT
  versionType: TreeVersionType,         // PRODUCTION, WIP, ARCHIVED
  name: String,                         // "production", "wip-isogg-2025-12", etc.
  description: Option[String],
  parentVersionId: Option[Int],         // For lineage tracking
  createdAt: LocalDateTime,
  createdBy: String,                    // Curator/system
  promotedAt: Option[LocalDateTime],    // When WIP became Production
  promotedBy: Option[String],
  status: TreeVersionStatus             // ACTIVE, PROMOTED, DISCARDED
)

enum TreeVersionType:
  case Production  // The canonical public tree
  case Wip         // Work-in-progress staging tree
  case Archived    // Previous production versions (for rollback)

enum TreeVersionStatus:
  case Active      // Currently in use
  case Promoted    // WIP that was promoted to Production
  case Discarded   // WIP that was abandoned
  case Superseded  // Old Production replaced by newer version
```

**Schema Changes:**

```sql
-- Tree version tracking
CREATE TABLE tree.tree_version (
    id SERIAL PRIMARY KEY,
    haplogroup_type VARCHAR(10) NOT NULL CHECK (haplogroup_type IN ('Y', 'MT')),
    version_type VARCHAR(20) NOT NULL CHECK (version_type IN ('PRODUCTION', 'WIP', 'ARCHIVED')),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    parent_version_id INTEGER REFERENCES tree.tree_version(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255) NOT NULL,
    promoted_at TIMESTAMP,
    promoted_by VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'PROMOTED', 'DISCARDED', 'SUPERSEDED')),
    UNIQUE(haplogroup_type, name)
);

-- Add version reference to haplogroup table
ALTER TABLE tree.haplogroup ADD COLUMN tree_version_id INTEGER REFERENCES tree.tree_version(id);
CREATE INDEX idx_haplogroup_version ON tree.haplogroup(tree_version_id);

-- Add version reference to relationships
ALTER TABLE tree.haplogroup_relationship ADD COLUMN tree_version_id INTEGER REFERENCES tree.tree_version(id);
```

**Pros:**
- Complete isolation between versions
- Easy rollback (just change which version is "Production")
- Clear audit trail of tree evolution

**Cons:**
- Storage overhead (full tree copy per version)
- Query complexity (must always filter by version)
- Biosample assignments need version awareness

---

### Option B: Layer/Overlay Model

WIP changes are stored as a delta/overlay on top of Production. Merged view is computed at query time.

```scala
case class TreeChange(
  id: Option[Int],
  haplogroupType: HaplogroupType,
  changeSetId: Int,                     // Groups related changes
  changeType: TreeChangeType,           // CREATE, UPDATE, DELETE, REPARENT
  haplogroupId: Option[Int],            // For UPDATE/DELETE/REPARENT
  haplogroupData: Option[Haplogroup],   // Full data for CREATE/UPDATE
  oldParentId: Option[Int],             // For REPARENT
  newParentId: Option[Int],             // For REPARENT
  createdAt: LocalDateTime,
  createdBy: String,
  status: TreeChangeStatus              // PENDING, APPLIED, REVERTED
)

case class ChangeSet(
  id: Option[Int],
  haplogroupType: HaplogroupType,
  name: String,                         // "isogg-2025-12", "curator-fixes-batch-1"
  description: Option[String],
  sourceName: String,                   // "ISOGG", "ytree.net", "Curator"
  createdAt: LocalDateTime,
  createdBy: String,
  appliedAt: Option[LocalDateTime],
  appliedBy: Option[String],
  status: ChangeSetStatus               // DRAFT, READY_FOR_REVIEW, APPLIED, DISCARDED
)

enum TreeChangeType:
  case Create, Update, Delete, Reparent, AddVariant, RemoveVariant

enum ChangeSetStatus:
  case Draft           // Being built (merge in progress)
  case ReadyForReview  // Merge complete, awaiting curator
  case UnderReview     // Curator actively reviewing
  case Applied         // Changes applied to Production
  case Discarded       // Changes abandoned
```

**Schema Changes:**

```sql
-- Change sets group related changes
CREATE TABLE tree.change_set (
    id SERIAL PRIMARY KEY,
    haplogroup_type VARCHAR(10) NOT NULL CHECK (haplogroup_type IN ('Y', 'MT')),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    source_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255) NOT NULL,
    applied_at TIMESTAMP,
    applied_by VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'READY_FOR_REVIEW', 'UNDER_REVIEW', 'APPLIED', 'DISCARDED')),
    ambiguity_report_path VARCHAR(500),  -- Path to generated report
    statistics JSONB                      -- MergeStatistics snapshot
);

-- Individual changes within a set
CREATE TABLE tree.tree_change (
    id SERIAL PRIMARY KEY,
    change_set_id INTEGER NOT NULL REFERENCES tree.change_set(id) ON DELETE CASCADE,
    change_type VARCHAR(20) NOT NULL
        CHECK (change_type IN ('CREATE', 'UPDATE', 'DELETE', 'REPARENT', 'ADD_VARIANT', 'REMOVE_VARIANT')),
    haplogroup_id INTEGER,               -- NULL for CREATE
    haplogroup_data JSONB,               -- Full haplogroup for CREATE/UPDATE
    variant_id INTEGER,                  -- For ADD_VARIANT/REMOVE_VARIANT
    old_parent_id INTEGER,
    new_parent_id INTEGER,
    old_data JSONB,                      -- Previous state for UPDATE
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    sequence_num INTEGER NOT NULL,       -- Order within change set
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPLIED', 'REVERTED', 'SKIPPED'))
);

CREATE INDEX idx_tree_change_set ON tree.tree_change(change_set_id);
CREATE INDEX idx_tree_change_hg ON tree.tree_change(haplogroup_id);
```

**Pros:**
- Storage efficient (only changes stored)
- Single source of truth for Production
- Easier biosample handling (always points to Production)

**Cons:**
- Complex merge view computation
- Must replay changes to see WIP state
- Harder to compare full tree states

---

### Recommendation: Hybrid Approach

Use **Option B (Layer/Overlay)** for storage efficiency, but provide **materialized WIP views** for curator experience:

1. **Production Tree**: Direct access to `tree.haplogroup` (no version filter needed for most queries)
2. **Change Sets**: Track pending changes as overlay
3. **WIP Materialized View**: Periodically computed full tree state incorporating pending changes
4. **Diff View**: Computed comparison between Production and WIP

---

## Service Layer

### TreeVersioningService

```scala
trait TreeVersioningService {
  /**
   * Create a new change set for bulk operations.
   * All subsequent merge operations target this change set.
   */
  def createChangeSet(
    haplogroupType: HaplogroupType,
    name: String,
    sourceName: String,
    description: Option[String],
    createdBy: String
  ): Future[ChangeSet]

  /**
   * Get the active change set for a haplogroup type (if any).
   * Only one DRAFT/READY_FOR_REVIEW change set per type at a time.
   */
  def getActiveChangeSet(haplogroupType: HaplogroupType): Future[Option[ChangeSet]]

  /**
   * Record a change within the active change set.
   * Called by HaplogroupTreeMergeService during merge operations.
   */
  def recordChange(
    changeSetId: Int,
    changeType: TreeChangeType,
    haplogroupId: Option[Int],
    haplogroupData: Option[Haplogroup],
    variantId: Option[Int] = None,
    oldParentId: Option[Int] = None,
    newParentId: Option[Int] = None,
    oldData: Option[JsValue] = None
  ): Future[TreeChange]

  /**
   * Finalize a change set, marking it ready for review.
   * Generates/links the ambiguity report.
   */
  def finalizeChangeSet(
    changeSetId: Int,
    statistics: MergeStatistics,
    ambiguityReportPath: Option[String]
  ): Future[ChangeSet]

  /**
   * Get WIP tree state (Production + pending changes applied).
   */
  def getWipTree(haplogroupType: HaplogroupType): Future[Seq[Haplogroup]]

  /**
   * Get diff between Production and WIP.
   */
  def getTreeDiff(haplogroupType: HaplogroupType): Future[TreeDiff]

  /**
   * Apply a change set to Production (promote WIP).
   */
  def applyChangeSet(changeSetId: Int, curatorId: String): Future[ChangeSet]

  /**
   * Discard a change set (abandon WIP).
   */
  def discardChangeSet(changeSetId: Int, curatorId: String, reason: String): Future[ChangeSet]

  /**
   * Revert a specific change within a set (curator correction).
   */
  def revertChange(changeId: Int, curatorId: String, reason: String): Future[TreeChange]
}
```

### Modified HaplogroupTreeMergeService

The merge service needs to be version-aware:

```scala
// In HaplogroupTreeMergeService

def mergeFullTree(request: TreeMergeRequest): Future[TreeMergeResponse] = {
  for {
    // Step 1: Create or get active change set
    changeSet <- if (request.targetChangeSet.isDefined) {
      treeVersioningService.getChangeSet(request.targetChangeSet.get)
    } else {
      treeVersioningService.createChangeSet(
        haplogroupType = request.haplogroupType,
        name = s"merge-${request.sourceName}-${LocalDateTime.now().format(formatter)}",
        sourceName = request.sourceName,
        description = Some(s"Full tree merge from ${request.sourceName}"),
        createdBy = "system"
      )
    }

    // Step 2: Perform merge, recording changes to change set
    result <- performMergeWithChangeTracking(
      changeSetId = changeSet.id.get,
      // ... existing parameters
    )

    // Step 3: Finalize change set
    _ <- treeVersioningService.finalizeChangeSet(
      changeSetId = changeSet.id.get,
      statistics = result.statistics,
      ambiguityReportPath = result.ambiguityReportPath
    )
  } yield result
}
```

---

## Curator Workflow

### 1. Initiate Merge (Automatic or Manual)

```
Curator/System triggers ISOGG merge
  └── Creates ChangeSet: "isogg-2025-12" (status: DRAFT)
      └── Merge runs, recording all changes
          └── ChangeSet finalized (status: READY_FOR_REVIEW)
              └── Ambiguity report generated
```

### 2. Review Phase

```
Curator opens WIP Review Dashboard
  ├── Summary: 7,537 creates, 2,695 updates, 684 ambiguities
  ├── Ambiguity Report (sortable by confidence)
  ├── Tree Diff View (visual comparison)
  └── Individual Change Review
      ├── Approve change
      ├── Modify change
      ├── Skip/Revert change
      └── Add notes
```

### 3. Resolution Actions

| Action | Description |
|--------|-------------|
| **Approve** | Mark change as validated, ready for promotion |
| **Modify** | Edit the change (e.g., fix name, adjust parent) |
| **Skip** | Exclude this change from promotion |
| **Revert** | Undo this change in WIP |
| **Split** | Break a problematic change into smaller pieces |

### 4. Promotion

```
Curator clicks "Promote to Production"
  └── Validation checks run
      ├── No unreviewed high-priority ambiguities
      ├── Tree integrity checks pass
      └── Confirmation dialog
          └── Changes applied to Production
              └── ChangeSet status: APPLIED
                  └── Old Production archived
```

---

## API Endpoints

### Change Set Management

```
# List change sets
GET  /api/v1/tree/change-sets
     ?type={Y|MT}
     &status={DRAFT|READY_FOR_REVIEW|UNDER_REVIEW|...}

# Get change set details
GET  /api/v1/tree/change-sets/{id}

# Get changes within a set
GET  /api/v1/tree/change-sets/{id}/changes
     ?changeType={CREATE|UPDATE|...}
     &page={int}
     &pageSize={int}

# Get ambiguity report for change set
GET  /api/v1/tree/change-sets/{id}/ambiguities
```

### Curator Operations

```
# Review a change
POST /api/v1/curator/changes/{id}/review
     Body: { "action": "APPROVE|SKIP|REVERT", "notes": "..." }

# Modify a change
PATCH /api/v1/curator/changes/{id}
      Body: { "haplogroupData": {...}, "reason": "..." }

# Promote change set to Production
POST /api/v1/curator/change-sets/{id}/promote
     Body: { "confirmationCode": "...", "notes": "..." }

# Discard change set
POST /api/v1/curator/change-sets/{id}/discard
     Body: { "reason": "..." }
```

### Tree Views

```
# Get Production tree (default)
GET  /api/v1/tree/haplogroups/{type}

# Get WIP tree (merged view)
GET  /api/v1/tree/haplogroups/{type}?view=wip

# Get diff between Production and WIP
GET  /api/v1/tree/haplogroups/{type}/diff
```

---

## UI Components

### Change Set Dashboard

- List of pending change sets with status badges
- Summary statistics for each set
- Quick actions: Review, Promote, Discard

### Tree Diff Viewer

- Side-by-side or unified diff view
- Color coding: Green (new), Yellow (modified), Red (deleted)
- Click to expand details for each change
- Filter by change type, ambiguity status

### Ambiguity Resolution Panel

- Sortable list of ambiguities
- Confidence score highlighting (red < 0.5, yellow 0.5-0.8, green > 0.8)
- One-click resolution actions
- Bulk operations for low-risk ambiguities

### Tree Comparison View

- Interactive tree visualization
- Toggle between Production and WIP
- Highlight differences
- Drill-down to specific nodes

---

## Implementation Phases

### Phase 1: Foundation ✅ COMPLETE

- [x] Database schema for change sets and tree changes
- [x] `TreeVersioningService` core implementation
- [x] Modify `HaplogroupTreeMergeService` to record changes
- [x] Basic API endpoints for change set management

### Phase 2: Curator Workflow ✅ COMPLETE

- [x] Change set review dashboard
- [x] Individual change review/modify/revert
- [x] Promotion workflow with validation
- [x] Discard workflow

### Phase 3: Tree Views ✅ COMPLETE

- [x] WIP tree materialization (change-based diff computation)
- [x] Tree diff computation (`getTreeDiff`, `computeTreeDiff`)
- [x] Diff viewer UI (`diffView.scala.html`, `diffFragment.scala.html`)
- [x] Production/WIP toggle in tree explorer (WIP status indicator on curator dashboard)

### Phase 4: Integration ✅ COMPLETE

- [x] Link ambiguity reports to change sets
  - Ambiguity reports generated during merge are stored at `ambiguityReportPath`
  - Curator UI shows report viewer at `/curator/change-sets/:id/ambiguity-report`
  - Download available at `/curator/change-sets/:id/ambiguity-report/download`
- [x] Notification system for change set status
  - Dashboard shows prominent "Action Required" banner for ReadyForReview change sets
  - Active change sets shown with status badges and quick action links
- [x] Audit trail for all version operations
  - `CuratorAuditService` extended with tree versioning methods
  - Logs: `logChangeSetCreate`, `logChangeSetStatusChange`, `logChangeSetApply`, `logChangeSetDiscard`, `logChangeReview`
  - All operations logged to `tree.curator_action` table
- [x] Integration with Discovery System (documented)
  - See "Discovery System Integration" section below

---

## Discovery System Integration

The Tree Versioning System and [Haplogroup Discovery System](haplogroup-discovery-system.md) have distinct but complementary roles:

| Aspect | Tree Versioning | Discovery System |
|--------|-----------------|------------------|
| Direction | Top-down (authority imports) | Bottom-up (sample observations) |
| Scope | Bulk changes (thousands of nodes) | Individual proposals |
| Source | External authorities (ISOGG, ytree.net) | User biosample data |
| Review | Change set promotion workflow | Proposal consensus threshold |

### Integration Points

When a WIP tree is promoted to Production, the Discovery System may need to re-evaluate:

1. **Pending Proposals**
   - Proposals targeting nodes that were reparented should be reviewed
   - Proposals for nodes that now exist (imported from authority) may become duplicates
   - Parent relationship changes may invalidate proposal placement

2. **Consensus Calculations**
   - If the tree structure changes, variant assignments may need recalculation
   - New intermediate nodes may affect haplogroup assignment algorithms

### Recommended Integration Approach

```scala
// In TreeVersioningService.applyChangeSet():
override def applyChangeSet(changeSetId: Int, curatorId: String): Future[Boolean] = {
  for {
    // ... existing apply logic ...
    result <- repository.applyChangeSet(changeSetId, curatorId)

    // Trigger discovery system re-evaluation (future enhancement)
    _ <- if (result) {
      discoveryService.notifyTreeChange(changeSet.haplogroupType, changeSetId)
    } else Future.successful(())
  } yield result
}

// In DiscoveryService:
def notifyTreeChange(haplogroupType: HaplogroupType, changeSetId: Int): Future[Unit] = {
  for {
    // Get affected haplogroup IDs from the change set
    affectedIds <- treeVersioningService.getAffectedHaplogroupIds(changeSetId)

    // Find proposals targeting affected nodes
    proposals <- proposalRepository.findByTargetNodes(affectedIds)

    // Mark proposals for re-evaluation
    _ <- proposalRepository.markForReview(proposals.map(_.id.get),
      reason = s"Tree structure changed by change set $changeSetId")
  } yield ()
}
```

### Implementation Status

The integration hooks are designed but not yet implemented:
- `TreeVersioningService.applyChangeSet()` could call a discovery notification
- `DiscoveryService` would need a `notifyTreeChange()` method
- A proposal re-evaluation queue would handle flagged proposals

This is intentionally deferred as the Discovery System itself is still in planning phase.

---

## Configuration

```hocon
decodingus.tree-versioning {
  # Maximum age of a DRAFT change set before auto-cleanup warning
  draft-expiry-days = 30

  # Require curator review for ambiguities below this confidence
  ambiguity-review-threshold = 0.7

  # Maximum pending change sets per haplogroup type
  max-pending-change-sets = 3

  # Auto-archive old Production versions
  archive-retention-days = 365
}
```

---

## Security Considerations

### Permissions

| Operation | Required Permission |
|-----------|---------------------|
| View change sets | `tree.version.view` |
| Create change set | `tree.version.create` |
| Review changes | `tree.version.review` |
| Modify changes | `tree.version.modify` |
| Promote to Production | `tree.version.promote` |
| Discard change set | `tree.version.discard` |

### Audit Trail

All versioning operations logged to `tree.curator_action` with:
- Action type: `CREATE_CHANGE_SET`, `REVIEW_CHANGE`, `PROMOTE`, `DISCARD`, etc.
- Before/after state snapshots
- Curator ID and timestamp

---

## Migration Path

### For Existing Merges

1. Current merge behavior continues to work (direct to Production)
2. New `useChangeSet: Boolean` parameter enables versioned merges
3. Gradual migration: Start with ISOGG merges, expand to other sources

### For Existing Data

1. Create initial `tree_version` record for current Production state
2. No data migration needed - existing haplogroups are "Production" by default
3. `tree_version_id = NULL` means "Production" for backward compatibility

---

## Future Enhancements

1. **Branch-based Workflow**: Multiple concurrent WIP branches for different sources
2. **Merge Conflict Resolution**: Handle concurrent change sets with conflicts
3. **Automated Validation**: ML-based quality checks before promotion
4. **Rollback Support**: One-click rollback to previous Production version
5. **Change Set Templates**: Pre-defined validation rules for different source types
