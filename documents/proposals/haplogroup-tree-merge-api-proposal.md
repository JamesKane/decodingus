# Haplogroup Tree Merge API Proposal

**Status**: Draft
**Created**: 2025-12-12
**Author**: DecodingUs Team

---

## Executive Summary

DecodingUs maintains a comprehensive haplogroup tree that serves as a foundation for genetic genealogy research. As the field matures, multiple authoritative sources—ISOGG, ytree.net, academic researchers, and citizen scientists—independently develop and refine portions of the phylogenetic tree. Currently, integrating updates from these sources requires manual curation, which is time-consuming and error-prone.

This proposal introduces an automated Tree Merge API that enables programmatic integration of external haplogroup trees into the DecodingUs baseline. The system is source-agnostic: any researcher or institution can submit tree data through a secured API endpoint, with configurable priority rules determining how conflicts are resolved.

A key design decision is **variant-based matching**. Because different sources use different naming conventions (ytree.net uses "R-L21", ISOGG uses "R1b1a1a2a1a1", DecodingUs uses "R1b-L21"), the merge algorithm matches nodes by their defining genetic variants rather than names. This ensures accurate alignment regardless of nomenclature differences.

The system tracks **multi-source provenance** through a JSONB column storing which sources contributed to each node and variant. ISOGG serves as the authoritative backbone and retains primary credit on existing nodes, while incoming sources receive credit for new discoveries—splits that reveal finer structure and new terminal branches they contribute.

The API supports both full tree replacement and subtree merging under a designated anchor node, with dry-run capability for previewing changes before application. All endpoints are protected by API key authentication, ensuring only authorized integrations can modify tree data.

This infrastructure positions DecodingUs as a collaborative hub for phylogenetic research while preserving attribution for original discoveries and maintaining data integrity through priority-based conflict resolution.

---

## Overview

Add API-key protected endpoints for automated haplogroup tree migration from external researcher sources into the DecodingUs baseline tree, with multi-source provenance tracking via JSONB column.

## Design Philosophy

- **DecodingUs is the baseline** - The existing internal tree that external sources merge into
- **Source-agnostic** - Any researcher or institution can submit trees (e.g., ISOGG, ytree.net, academic researchers, citizen scientists)
- **Priority ranking retained** - Configurable source priority for conflict resolution
- **Full attribution** - Track all contributing sources per node and variant

## Credit Assignment Rules

Merges are applied tree-by-tree from a designated anchor node. Credit follows a tiered model:

1. **ISOGG is primary.** Existing nodes with ISOGG credit retain it. ISOGG serves as the authoritative backbone for haplogroup nomenclature.

2. **Incoming source gets credit for new discoveries:**
   - **New splits** - When incoming data reveals finer structure (new intermediate branches), the source gets credit for those split nodes
   - **New terminal branches** - When incoming data adds leaf nodes not in the existing tree, the source gets credit

This ensures ISOGG maintains credit for the established tree structure while researchers who discover new sub-branches or terminal clades receive attribution for their contributions.

The `primaryCredit` field in provenance tracks which source gets discovery attribution, separate from `nodeProvenance` which tracks all contributors.

## Requirements

- **Attribution**: JSONB column for multi-source provenance tracking
- **Input Format**: Nested JSON tree structure (PhyloNode-like)
- **Conflict Resolution**: Priority-based (caller-specified source ordering)
- **Update Modes**: Both subtree anchor and full tree replacement

---

## Technical Design

### 1. Database Schema Changes

Add `provenance JSONB` column to `tree.haplogroup` table with GIN index.

```sql
ALTER TABLE tree.haplogroup ADD COLUMN provenance JSONB;
CREATE INDEX idx_haplogroup_provenance ON tree.haplogroup USING GIN (provenance);
```

### 2. Provenance Data Model

```scala
case class HaplogroupProvenance(
  primaryCredit: String,                       // Source with discovery credit (applying credit rules)
  nodeProvenance: Set[String],                 // All sources contributing to node existence
  variantProvenance: Map[String, Set[String]], // Per-variant source attribution
  lastMergedAt: Option[LocalDateTime],
  lastMergedFrom: Option[String]
)
```

**Credit assignment:** ISOGG credit is preserved on existing nodes. Incoming source gets `primaryCredit` for new splits and new terminal branches they contribute.

### 3. Provenance JSONB Structure

```json
{
  "primaryCredit": "ytree.net",
  "nodeProvenance": ["ytree.net", "DecodingUs"],
  "variantProvenance": {
    "M269": ["ytree.net", "DecodingUs"],
    "L21": ["ytree.net"]
  },
  "lastMergedAt": "2025-12-12T10:30:00",
  "lastMergedFrom": "ytree.net"
}
```

---

## API Design

### Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/manage/haplogroups/merge` | Merge full haplogroup tree |
| POST | `/api/v1/manage/haplogroups/merge/subtree` | Merge subtree under anchor node |
| POST | `/api/v1/manage/haplogroups/merge/preview` | Preview merge without applying |

All endpoints are secured with X-API-Key authentication.

### Request Models

**PhyloNodeInput** - Input tree node structure (source-agnostic)
```scala
case class PhyloNodeInput(
  name: String,
  variants: List[String] = List.empty,
  formedYbp: Option[Int] = None,
  formedYbpLower: Option[Int] = None,
  formedYbpUpper: Option[Int] = None,
  tmrcaYbp: Option[Int] = None,
  tmrcaYbpLower: Option[Int] = None,
  tmrcaYbpUpper: Option[Int] = None,
  children: List[PhyloNodeInput] = List.empty
)
```

**SourcePriorityConfig** - Dynamic priority ordering (caller specifies)
```scala
case class SourcePriorityConfig(
  sourcePriorities: List[String],  // First = highest priority
  defaultPriority: Int = 100
)
```

**ConflictStrategy** - Conflict resolution modes
- `HigherPriorityWins` - Higher priority source wins conflicts
- `KeepExisting` - Always keep existing values
- `AlwaysUpdate` - Always use incoming values

**SubtreeMergeRequest**
```scala
case class SubtreeMergeRequest(
  haplogroupType: HaplogroupType,     // Y or MT
  anchorHaplogroupName: String,       // e.g., "R1b"
  sourceTree: PhyloNodeInput,
  sourceName: String,                 // Any identifier
  priorityConfig: Option[SourcePriorityConfig] = None,
  conflictStrategy: Option[ConflictStrategy] = None,
  dryRun: Boolean = false
)
```

### Response Models

**TreeMergeResponse**
```scala
case class TreeMergeResponse(
  success: Boolean,
  message: String,
  statistics: MergeStatistics,
  conflicts: List[MergeConflict] = List.empty,
  errors: List[String] = List.empty
)

case class MergeStatistics(
  nodesProcessed: Int,
  nodesCreated: Int,
  nodesUpdated: Int,
  nodesUnchanged: Int,
  variantsAdded: Int,
  variantsUpdated: Int,
  relationshipsCreated: Int,
  relationshipsUpdated: Int
)

case class MergeConflict(
  haplogroupName: String,
  field: String,
  existingValue: String,
  newValue: String,
  resolution: String,
  existingSource: String,
  newSource: String
)
```

### API Usage Example

```bash
# Merge ytree.net tree under R1b anchor - ytree.net gets primary credit (default)
curl -X POST https://api.decodingus.com/api/v1/manage/haplogroups/merge/subtree \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "haplogroupType": "Y",
    "anchorHaplogroupName": "R1b",
    "sourceName": "ytree.net",
    "sourceTree": {
      "name": "R1b-L21",
      "variants": ["L21", "S145"],
      "children": [
        {
          "name": "R1b-DF13",
          "variants": ["DF13"],
          "children": []
        }
      ]
    },
    "priorityConfig": {
      "sourcePriorities": ["ytree.net", "DecodingUs"]
    },
    "dryRun": false
  }'
```

---

## Merge Algorithm

### Node Matching Strategy

**Match on variants, not names.** Different sources use different naming schemes:
- ytree.net: `R-L21`
- ISOGG: `R1b1a1a2a1a1`
- DecodingUs: `R1b-L21`

All refer to the same haplogroup defined by variant `L21`. The merge algorithm matches nodes by their defining variants.

### Process Flow

Starting from anchor (e.g., R1b), walk down the tree:

1. **Index existing tree by variant sets** - Build lookup from variant → haplogroup
2. **For each incoming node**, find matching existing node by variants:
   - Exact match: Same defining variants → merge/update
   - Partial overlap: Shared variants → potential match, check tree position
   - No match: New branch → create
3. **Assign primary credit** - ISOGG preserved on existing nodes; incoming source credited for new splits and terminal branches
4. **Merge node data** based on priority config (age estimates, metadata)
5. **Recurse into children**, maintaining parent-child relationships
6. Return statistics and conflicts

### Example: Merging ytree.net under R1b

```
Anchor: R1b (matched by variant M343)
  └─ ytree.net sends: R-L21 [variants: L21, S145]
     └─ Matches existing: R1b-L21 [variants: L21, S145] ✓
        └─ ytree.net sends: R-DF13 [variants: DF13]
           └─ Matches existing: R1b-DF13 [variants: DF13] ✓
              └─ ytree.net sends: R-ZZ123 [variants: ZZ123]
                 └─ No match → CREATE new branch
```

### Handling Branch Splits

As phylogenetic research advances, existing branches often need to be split into finer sub-branches. The merge algorithm detects and handles these splits automatically.

**Split Detection:**
A split is detected when incoming data introduces intermediate nodes between an existing parent-child relationship. This occurs when:
1. Incoming tree has a node with variants that are a subset of an existing node's variants
2. The incoming node positions itself between the existing node and its parent
3. Some existing children should be reassigned to the new intermediate node

**Split Process:**

```
BEFORE (DecodingUs tree):
R1b-L21 [variants: L21, S145, Z290]
  └─ R1b-DF13 [variants: DF13]
  └─ R1b-L513 [variants: L513]

INCOMING (ytree.net):
R-L21 [variants: L21, S145]
  └─ R-Z290 [variants: Z290]        ← NEW intermediate branch
     └─ R-DF13 [variants: DF13]
     └─ R-L513 [variants: L513]

AFTER (merged):
R1b-L21 [variants: L21, S145]       ← Z290 removed, moved to child
  └─ R1b-Z290 [variants: Z290]      ← NEW intermediate node created
     └─ R1b-DF13 [variants: DF13]   ← Reassigned under Z290
     └─ R1b-L513 [variants: L513]   ← Reassigned under Z290
```

**Split Algorithm:**
1. **Identify variant redistribution** - Compare incoming node's variants against existing node
2. **Create intermediate node** - If incoming shows finer structure, create new branch with subset of variants
3. **Reassign children** - Move existing children under the new intermediate based on incoming tree structure
4. **Update parent node** - Remove variants that moved to the new intermediate
5. **Record provenance** - Credit the source that provided the split information

**Conflict Handling:**
- If split conflicts with existing structure (e.g., would orphan branches), flag for manual review
- Priority config determines whether to apply split or preserve existing structure
- Dry-run mode shows proposed splits before application

**Helper methods:**
- `findByVariants(variants: Set[String]): Option[Haplogroup]` - Lookup existing haplogroup by defining variants
- `variantOverlap(a: Set[String], b: Set[String]): Double` - Calculate Jaccard similarity for fuzzy matching

---

## Implementation Files

| File | Action |
|------|--------|
| `conf/evolutions/default/52.sql` | CREATE - Schema migration |
| `app/models/domain/haplogroups/HaplogroupProvenance.scala` | CREATE - Provenance model |
| `app/models/domain/haplogroups/Haplogroup.scala` | MODIFY - Add provenance field |
| `app/models/dal/domain/haplogroups/HaplogroupsTable.scala` | MODIFY - Add column + projection |
| `app/models/dal/MyPostgresProfile.scala` | MODIFY - Add JSONB type mapper |
| `app/models/api/haplogroups/TreeMergeModels.scala` | CREATE - API DTOs |
| `app/repositories/HaplogroupCoreRepository.scala` | MODIFY - Add provenance methods |
| `app/services/HaplogroupTreeMergeService.scala` | CREATE - Merge service |
| `app/controllers/HaplogroupTreeMergeController.scala` | CREATE - API controller |
| `conf/routes` | MODIFY - Add 3 routes |
| `app/modules/ServicesModule.scala` | MODIFY - Add service binding |
| `app/api/TreeMergeEndpoints.scala` | CREATE (optional) - Swagger docs |

## Implementation Order

1. Evolution (52.sql)
2. HaplogroupProvenance.scala
3. Haplogroup.scala update
4. HaplogroupsTable.scala update
5. MyPostgresProfile.scala type mapper
6. TreeMergeModels.scala
7. HaplogroupCoreRepository.scala updates
8. HaplogroupTreeMergeService.scala
9. HaplogroupTreeMergeController.scala
10. Routes update
11. ServicesModule.scala binding
12. Tapir endpoints (optional)

---

## Notes

- `sourceName` field accepts any string identifier (institution, researcher name, project name)
- `nodeProvenance` in input is optional - defaults to `sourceName`
- The service applies credit rules automatically based on variant prefixes and ancestry
- Dry-run mode available for testing merges without applying changes
