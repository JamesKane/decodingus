# Tree Schema Documentation

## Overview

The `tree` schema contains all haplogroup tree-related tables, separating tree structure and evolution concerns from genomics and biosample data in the `public` schema.

This reorganization was implemented as part of the Haplogroup Discovery System (Phase 0) to provide:

1. **Logical Grouping** - All tree-related tables (structure, revisions, discovery) in one namespace
2. **Access Control** - Schema-level permissions for curator operations
3. **Backup Strategy** - Independent backup/restore of tree data
4. **Query Clarity** - `tree.haplogroup` vs `public.biosample` makes intent clear
5. **Future Flexibility** - Easy to add tree-specific functions, views, types

---

## Schema Layout

```
tree schema
├── Core Tree Structure
│   ├── haplogroup
│   ├── haplogroup_relationship
│   └── haplogroup_variant
├── Revision Tracking
│   ├── relationship_revision_metadata
│   └── haplogroup_variant_metadata
└── Discovery System (Future - Phase 1+)
    ├── proposed_branch
    ├── proposed_branch_variant
    ├── proposed_branch_evidence
    ├── biosample_private_variant
    └── discovery_config

curator schema
└── Audit Trail
    └── audit_log
```

---

## Core Tables

### tree.haplogroup

Primary table for haplogroup definitions (Y-DNA and mtDNA).

| Column | Type | Description |
|--------|------|-------------|
| `haplogroup_id` | SERIAL PK | Unique identifier |
| `name` | VARCHAR(100) | Haplogroup name (e.g., "R-M269") |
| `lineage` | VARCHAR(500) | Full lineage path (optional) |
| `description` | TEXT | Optional description |
| `haplogroup_type` | VARCHAR(10) | "Y" or "MT" (enum: `HaplogroupType`) |
| `revision_id` | INT | Revision/version of record |
| `source` | VARCHAR(255) | Data source (e.g., "ISOGG", "PhyloTree") |
| `confidence_level` | VARCHAR(50) | Confidence classification |
| `valid_from` | TIMESTAMP | When record becomes valid |
| `valid_until` | TIMESTAMP | When record becomes invalid (optional) |
| `formed_ybp` | INT | Branch formation estimate (years before present) |
| `formed_ybp_lower` | INT | Lower bound of formation estimate |
| `formed_ybp_upper` | INT | Upper bound of formation estimate |
| `tmrca_ybp` | INT | TMRCA estimate (years before present) |
| `tmrca_ybp_lower` | INT | Lower bound of TMRCA estimate |
| `tmrca_ybp_upper` | INT | Upper bound of TMRCA estimate |
| `age_estimate_source` | VARCHAR(100) | Source of age estimates |

**DAL:** `models.dal.domain.haplogroups.HaplogroupsTable`

### tree.haplogroup_relationship

Parent-child relationships between haplogroups with temporal validity.

| Column | Type | Description |
|--------|------|-------------|
| `haplogroup_relationship_id` | SERIAL PK | Unique identifier |
| `parent_haplogroup_id` | INT FK | Reference to parent haplogroup |
| `child_haplogroup_id` | INT FK | Reference to child haplogroup |
| `revision_id` | INT | Revision identifier |
| `valid_from` | TIMESTAMP | When relationship becomes valid |
| `valid_until` | TIMESTAMP | When relationship becomes invalid (optional) |
| `source` | VARCHAR(255) | Source of relationship data |

**Indexes:**
- `unique_child_revision` on `(child_haplogroup_id, revision_id)`

**Foreign Keys:**
- `child_haplogroup_fk` → `tree.haplogroup` (CASCADE DELETE)
- `parent_haplogroup_fk` → `tree.haplogroup` (CASCADE DELETE)

**DAL:** `models.dal.domain.haplogroups.HaplogroupRelationshipsTable`

### tree.haplogroup_variant

Association between haplogroups and their defining variants.

| Column | Type | Description |
|--------|------|-------------|
| `haplogroup_variant_id` | SERIAL PK | Unique identifier |
| `haplogroup_id` | INT FK | Reference to haplogroup |
| `variant_id` | INT FK | Reference to `public.variant` |

**Indexes:**
- `unique_haplogroup_variant` on `(haplogroup_id, variant_id)`

**Foreign Keys:**
- `haplogroup_fk` → `tree.haplogroup` (CASCADE DELETE)
- `variant_fk` → `public.variant` (CASCADE DELETE)

**DAL:** `models.dal.domain.haplogroups.HaplogroupVariantsTable`

---

## Revision Tracking Tables

### tree.relationship_revision_metadata

Tracks revision history for haplogroup relationships.

| Column | Type | Description |
|--------|------|-------------|
| `haplogroup_relationship_id` | INT | Part of composite PK, FK to relationship |
| `revision_id` | INT | Part of composite PK |
| `author` | VARCHAR(255) | Who made the change |
| `timestamp` | TIMESTAMP | When change was made |
| `comment` | TEXT | Description of change |
| `change_type` | VARCHAR(50) | Type of change (add, remove, update) |
| `previous_revision_id` | INT | Link to previous revision (optional) |

**Primary Key:** `(haplogroup_relationship_id, revision_id)`

**DAL:** `models.dal.domain.haplogroups.RelationshipRevisionMetadataTable`

### tree.haplogroup_variant_metadata

Tracks revision history for haplogroup-variant associations.

| Column | Type | Description |
|--------|------|-------------|
| `haplogroup_variant_id` | INT | Part of composite PK, FK to haplogroup_variant |
| `revision_id` | INT | Part of composite PK |
| `author` | VARCHAR(255) | Who made the change |
| `timestamp` | TIMESTAMP | When change was made |
| `comment` | TEXT | Description of change |
| `change_type` | VARCHAR(50) | Type of change (add, remove) |
| `previous_revision_id` | INT | Link to previous revision (optional) |

**Primary Key:** `(haplogroup_variant_id, revision_id)`

**Foreign Keys:**
- `fk_haplogroup_variant_metadata_variant` → `tree.haplogroup_variant` (CASCADE DELETE)

**DAL:** `models.dal.domain.haplogroups.HaplogroupVariantMetadataTable`

---

## Curator Schema

### curator.audit_log

General audit trail for all curator operations across entities.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID PK | Unique identifier |
| `user_id` | UUID | Curator who performed action |
| `entity_type` | VARCHAR(50) | Type: "haplogroup", "variant" |
| `entity_id` | INT | ID of affected entity |
| `action` | VARCHAR(50) | Action: "create", "update", "delete", "split", "merge" |
| `old_value` | JSONB | State before action (optional) |
| `new_value` | JSONB | State after action (optional) |
| `comment` | TEXT | Curator's justification (optional) |
| `created_at` | TIMESTAMP | When action occurred |

**DAL:** `models.dal.curator.AuditLogTable`

**Service:** `services.CuratorAuditService`

---

## Cross-Schema References

### References FROM public schema TO tree schema

| Public Table | Column | References |
|--------------|--------|------------|
| `biosample_haplogroup` | `y_haplogroup_id` | `tree.haplogroup(haplogroup_id)` |
| `biosample_haplogroup` | `mt_haplogroup_id` | `tree.haplogroup(haplogroup_id)` |

### References FROM tree schema TO public schema

| Tree Table | Column | References |
|------------|--------|------------|
| `haplogroup_variant` | `variant_id` | `public.variant(variant_id)` |

---

## Slick Table Definitions

All tree schema tables use the schema parameter in their Slick definitions:

```scala
// Tree schema tables
class HaplogroupsTable(tag: Tag) extends Table[Haplogroup](tag, Some("tree"), "haplogroup")
class HaplogroupRelationshipsTable(tag: Tag) extends Table[HaplogroupRelationship](tag, Some("tree"), "haplogroup_relationship")
class HaplogroupVariantsTable(tag: Tag) extends Table[HaplogroupVariant](tag, Some("tree"), "haplogroup_variant")
class HaplogroupVariantMetadataTable(tag: Tag) extends Table[HaplogroupVariantMetadata](tag, Some("tree"), "haplogroup_variant_metadata")
class RelationshipRevisionMetadataTable(tag: Tag) extends Table[RelationshipRevisionMetadata](tag, Some("tree"), "relationship_revision_metadata")

// Curator schema tables
class AuditLogTable(tag: Tag) extends Table[AuditLogEntry](tag, Some("curator"), "audit_log")
```

---

## Related Services

### CuratorAuditService

Located at `app/services/CuratorAuditService.scala`

Provides audit logging for:
- Haplogroup create/update/delete
- Variant create/update/delete
- Haplogroup-variant association changes
- Tree restructuring operations (split, merge)

### TreeRestructuringService

Located at `app/services/TreeRestructuringService.scala`

Provides tree modification operations:
- **Split branch:** Create subclade by moving variants and/or re-parenting children
- **Merge into parent:** Absorb child haplogroup (variants move up, grandchildren promoted, child deleted)

---

## Migration History

| Evolution | Description |
|-----------|-------------|
| 56 | Created `tree` schema and migrated haplogroup tables from public schema |
| (TBD) | Created `curator` schema and audit_log table |

---

## Future: Discovery System Tables (Phase 1+)

The following tables will be added in Phase 1 of the Haplogroup Discovery System:

- `tree.biosample_private_variant` - Private variants discovered in biosamples
- `tree.proposed_branch` - Proposed new branches awaiting consensus/review
- `tree.proposed_branch_variant` - Variants associated with proposed branches
- `tree.proposed_branch_evidence` - Biosamples supporting proposed branches
- `tree.discovery_config` - Configuration for consensus thresholds

See `documents/planning/haplogroup-discovery-system.md` for full details.
