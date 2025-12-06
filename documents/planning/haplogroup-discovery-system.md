# Haplogroup Discovery System

## Executive Summary

This document outlines a comprehensive system for evolving Y-DNA and mtDNA haplogroup trees based on discoveries from **all biosample sources**: both Citizen Biosamples (AT Protocol) and External/Publication Biosamples loaded by curators. The system manages "private branches" (proposed terminal variants), tracks consensus formation across multiple biosamples regardless of source, and provides curator oversight for tree modifications.

---

## Problem Statement

Biosamples enter the system from two primary sources:

1. **Citizen Biosamples** - Ingested via the Atmosphere/AT Protocol integration from Personal Data Servers
2. **External Biosamples** - Loaded by curators from academic publications and research datasets

Both sources include `HaplogroupResult` data containing:
- **matchingSnps**: Count of SNPs matching the terminal haplogroup
- **mismatchingSnps**: Count of SNPs that do NOT match (represent potential new discoveries)
- **lineagePath**: The path from root to terminal haplogroup

These "mismatching SNPs" represent **private variants**—mutations that extend beyond the current terminal branch of the tree. When multiple independent biosamples (from any source) share the same private variants, this provides evidence for a new branch that should be incorporated into the canonical tree.

### Current Gap

The existing system stores haplogroup assignments but does not:
1. Track the specific private variants (mismatches) per biosample
2. Detect when multiple biosamples share private variants (across both sample types)
3. Propose new branches based on shared discoveries
4. Provide curator workflow for reviewing and accepting proposals
5. Maintain a distinction between "proposed" (hidden) and "accepted" branches
6. Unify discovery tracking across Citizen and External biosample sources

---

## System Goals

1. **Unified Sample Tracking**: Handle private variants from both Citizen and External biosamples through a common abstraction
2. **Capture Private Variants**: Store the specific SNP mismatches for each biosample's haplogroup assignment
3. **Track Discovery Proposals**: Create "private branch" proposals when new variants are found
4. **Build Consensus**: Aggregate evidence across biosamples sharing the same private variants (regardless of source)
5. **Threshold-Based Promotion**: Automatically flag proposals that reach configurable consensus thresholds
6. **Curator Workflow**: Provide secure APIs for trusted curators to review, modify, and accept/reject proposals
7. **Tree Evolution**: Incorporate accepted proposals into the canonical tree with full audit trail
8. **Reporting Isolation**: Exclude proposed branches from public reporting until accepted

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Biosample Ingestion Sources                      │
├─────────────────────────────────┬───────────────────────────────────────┤
│     Citizen Biosamples          │       External Biosamples              │
│  (Firehose / REST / AT Protocol)│    (Curator Upload / Publication)     │
└─────────────────────────────────┴───────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Private Variant Extraction                           │
│  • Parse HaplogroupResult.mismatchingSnps from either source             │
│  • Resolve variant identifiers to variant table                          │
│  • Link variants to unified sample reference as "private discoveries"    │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Discovery Proposal Engine                            │
│  • Group biosamples by shared private variants                           │
│  • Create/update ProposedBranch records                                  │
│  • Track consensus count and supporting evidence                         │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Consensus Detection Service                          │
│  • Monitor proposal thresholds                                           │
│  • Detect branch splits (when biosamples diverge)                        │
│  • Generate curator notifications                                        │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Curator Workflow System                              │
│  • Secure API for proposal review                                        │
│  • Accept/Reject/Modify operations                                       │
│  • Manual branch creation and variant assignment                         │
│  • Audit trail for all curator actions                                   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Tree Evolution Service                               │
│  • Create new haplogroup nodes from accepted proposals                   │
│  • Update parent-child relationships                                     │
│  • Associate defining variants                                           │
│  • Reassign biosamples to new terminal                                   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Data Model

### Unified Sample Reference Strategy

The system uses a **polymorphic reference** pattern to track private variants across both biosample types. This allows consensus building from any combination of Citizen and External biosamples.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Sample Reference Pattern                          │
├─────────────────────────────────────────────────────────────────────────┤
│  sample_type: ENUM('CITIZEN', 'EXTERNAL')                               │
│  sample_id: INT (FK to citizen_biosample.id OR biosample.id)            │
│  sample_guid: UUID (denormalized for efficient lookups)                 │
└─────────────────────────────────────────────────────────────────────────┘
```

This approach:
- Avoids complex union tables or inheritance hierarchies
- Allows efficient queries across both sample types
- Maintains referential integrity through application-level checks
- Enables future sample types without schema changes

### New Domain Entities

#### 1. SampleReference (Value Object)

A value object representing a reference to any biosample type.

```scala
case class SampleReference(
  sampleType: BiosampleSourceType,   // CITIZEN or EXTERNAL
  sampleId: Int,                      // FK to respective table
  sampleGuid: UUID                    // Denormalized for lookups
)

enum BiosampleSourceType:
  case Citizen   // citizen_biosample table
  case External  // biosample table (publication/external)
```

#### 2. BiosamplePrivateVariant

Tracks the specific private (mismatching) variants discovered in each biosample.

```scala
case class BiosamplePrivateVariant(
  id: Option[Int],
  sampleType: BiosampleSourceType,   // CITIZEN or EXTERNAL
  sampleId: Int,                      // FK to citizen_biosample OR biosample
  sampleGuid: UUID,                   // Denormalized for efficient joins
  variantId: Int,                     // FK to variant table
  haplogroupType: HaplogroupType,     // Y or MT (which tree)
  terminalHaplogroupId: Int,          // The terminal haplogroup at time of discovery
  discoveredAt: LocalDateTime,
  status: PrivateVariantStatus        // ACTIVE, PROMOTED, INVALIDATED
)

enum PrivateVariantStatus:
  case Active      // Currently a private variant
  case Promoted    // Variant has been added to an accepted branch
  case Invalidated // Variant was determined to be artifact/error
```

#### 3. ProposedBranch

Represents a proposed new branch based on shared private variants.

```scala
case class ProposedBranch(
  id: Option[Int],
  parentHaplogroupId: Int,           // The existing terminal haplogroup
  proposedName: Option[String],      // Curator-assigned name (nullable initially)
  haplogroupType: HaplogroupType,    // Y or MT
  status: ProposedBranchStatus,
  consensusCount: Int,               // Number of supporting biosamples
  confidenceScore: Double,           // Calculated confidence metric
  createdAt: LocalDateTime,
  updatedAt: LocalDateTime,
  reviewedAt: Option[LocalDateTime],
  reviewedBy: Option[String],        // Curator identifier
  notes: Option[String],
  promotedHaplogroupId: Option[Int]  // Set when promoted to real haplogroup
)

enum ProposedBranchStatus:
  case Pending          // Newly created, below threshold
  case ReadyForReview   // Reached consensus threshold
  case UnderReview      // Curator is actively reviewing
  case Accepted         // Approved, pending promotion
  case Promoted         // Successfully added to tree
  case Rejected         // Curator rejected
  case Split            // Branch was split into child proposals
```

#### 4. ProposedBranchVariant

Links proposed branches to their defining variants.

```scala
case class ProposedBranchVariant(
  id: Option[Int],
  proposedBranchId: Int,             // FK to proposed_branch
  variantId: Int,                    // FK to variant
  isDefining: Boolean,               // True if this is a defining mutation
  evidenceCount: Int,                // How many biosamples have this variant
  firstObservedAt: LocalDateTime,
  lastObservedAt: LocalDateTime
)
```

#### 5. ProposedBranchEvidence

Links biosamples (of any type) to proposed branches they support.

```scala
case class ProposedBranchEvidence(
  id: Option[Int],
  proposedBranchId: Int,             // FK to proposed_branch
  sampleType: BiosampleSourceType,   // CITIZEN or EXTERNAL
  sampleId: Int,                      // FK to citizen_biosample OR biosample
  sampleGuid: UUID,                   // Denormalized for lookups
  addedAt: LocalDateTime,
  variantMatchCount: Int,            // How many defining variants this sample has
  variantMismatchCount: Int          // Variants in proposal NOT in this sample
)
```

#### 6. CuratorAction

Audit trail for all curator operations.

```scala
case class CuratorAction(
  id: Option[Int],
  curatorId: String,                 // Curator identifier (DID or username)
  actionType: CuratorActionType,
  targetType: CuratorTargetType,     // PROPOSED_BRANCH, HAPLOGROUP, VARIANT
  targetId: Int,
  previousState: Option[String],     // JSON snapshot of state before action
  newState: Option[String],          // JSON snapshot of state after action
  reason: Option[String],            // Curator's justification
  timestamp: LocalDateTime
)

enum CuratorActionType:
  case Review, Accept, Reject, Modify, Split, Merge, Create, Delete, Reassign

enum CuratorTargetType:
  case ProposedBranch, Haplogroup, HaplogroupRelationship, Variant, Biosample
```

---

### Schema Reorganization: Introducing the `tree` Schema

As part of this major update, we'll reorganize haplogroup-related tables into a dedicated `tree` schema. This provides cleaner separation of concerns and makes the tree evolution system more cohesive.

#### Current State (public schema)

All tables currently reside in the `public` schema:

| Table | Domain |
|-------|--------|
| `haplogroup` | Tree |
| `haplogroup_relationship` | Tree |
| `haplogroup_variant` | Tree |
| `relationship_revision_metadata` | Tree |
| `haplogroup_variant_metadata` | Tree |
| `variant` | Shared (Tree + Genomics) |
| `genbank_contig` | Shared (Tree + Genomics) |
| `biosample_haplogroup` | Bridge (Genomics → Tree) |

#### Target State

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            tree schema                                   │
├─────────────────────────────────────────────────────────────────────────┤
│  CORE TREE STRUCTURE                                                     │
│  • tree.haplogroup                                                       │
│  • tree.haplogroup_relationship                                          │
│  • tree.haplogroup_variant                                               │
│                                                                          │
│  REVISION TRACKING                                                       │
│  • tree.relationship_revision_metadata                                   │
│  • tree.haplogroup_variant_metadata                                      │
│                                                                          │
│  DISCOVERY SYSTEM (NEW)                                                  │
│  • tree.proposed_branch                                                  │
│  • tree.proposed_branch_variant                                          │
│  • tree.proposed_branch_evidence                                         │
│  • tree.biosample_private_variant                                        │
│  • tree.curator_action                                                   │
│  • tree.discovery_config                                                 │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                           public schema                                  │
├─────────────────────────────────────────────────────────────────────────┤
│  GENOMICS                                                                │
│  • biosample, citizen_biosample, pgp_biosample                          │
│  • specimen_donor                                                        │
│  • sequence_library, sequence_file, etc.                                │
│  • reported_variant, reported_negative_variant                          │
│  • biosample_haplogroup (references tree.haplogroup)                    │
│                                                                          │
│  REFERENCE DATA                                                          │
│  • genbank_contig (shared - referenced by tree.variant)                 │
│  • variant (shared - lives in public, used by tree)                     │
│                                                                          │
│  PUBLICATIONS                                                            │
│  • publication, ena_study, publication_biosample, etc.                  │
│  • biosample_original_haplogroup                                         │
│                                                                          │
│  ANCESTRY                                                                │
│  • population, analysis_method, ancestry_analysis                       │
└─────────────────────────────────────────────────────────────────────────┘
```

#### Migration Strategy

The schema migration will be performed in a single evolution script with careful ordering:

```sql
-- Evolution XX: Create tree schema and migrate haplogroup tables

-- 1. Create the tree schema
CREATE SCHEMA IF NOT EXISTS tree;

-- 2. Move existing tables to tree schema
ALTER TABLE haplogroup SET SCHEMA tree;
ALTER TABLE haplogroup_relationship SET SCHEMA tree;
ALTER TABLE haplogroup_variant SET SCHEMA tree;
ALTER TABLE relationship_revision_metadata SET SCHEMA tree;
ALTER TABLE haplogroup_variant_metadata SET SCHEMA tree;

-- 3. Update foreign key references from public schema tables
-- biosample_haplogroup references tree.haplogroup
ALTER TABLE biosample_haplogroup
    DROP CONSTRAINT biosample_haplogroup_y_haplogroup_id_fkey,
    DROP CONSTRAINT biosample_haplogroup_mt_haplogroup_id_fkey,
    ADD CONSTRAINT biosample_haplogroup_y_haplogroup_id_fkey
        FOREIGN KEY (y_haplogroup_id) REFERENCES tree.haplogroup(haplogroup_id) ON DELETE CASCADE,
    ADD CONSTRAINT biosample_haplogroup_mt_haplogroup_id_fkey
        FOREIGN KEY (mt_haplogroup_id) REFERENCES tree.haplogroup(haplogroup_id) ON DELETE CASCADE;

-- 4. tree.variant references public.genbank_contig (cross-schema FK is fine)
-- tree.haplogroup_variant references public.variant (cross-schema FK is fine)

-- 5. Create new discovery tables in tree schema (see below)
```

#### Benefits of Schema Separation

1. **Logical Grouping**: All tree-related tables (structure, revisions, discovery) in one namespace
2. **Access Control**: Can grant/revoke permissions at schema level for curator operations
3. **Backup Strategy**: Can backup/restore tree schema independently
4. **Query Clarity**: `tree.haplogroup` vs `public.biosample` makes intent clear
5. **Future Flexibility**: Easy to add tree-specific functions, views, types to the schema

#### Slick Configuration Updates

The DAL layer will need updates to reference the new schema:

```scala
// In table definitions
class HaplogroupTable(tag: Tag) extends Table[HaplogroupRow](tag, Some("tree"), "haplogroup") {
  // ...
}

class ProposedBranchTable(tag: Tag) extends Table[ProposedBranchRow](tag, Some("tree"), "proposed_branch") {
  // ...
}
```

---

### Database Schema (Evolution)

```sql
-- Evolution XX: Haplogroup Discovery System
-- This evolution:
--   1. Creates the 'tree' schema
--   2. Migrates existing haplogroup tables to tree schema
--   3. Creates new discovery system tables in tree schema

-- ============================================================================
-- PART 1: Create tree schema and migrate existing tables
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS tree;

-- Move existing haplogroup tables to tree schema
ALTER TABLE haplogroup SET SCHEMA tree;
ALTER TABLE haplogroup_relationship SET SCHEMA tree;
ALTER TABLE haplogroup_variant SET SCHEMA tree;
ALTER TABLE relationship_revision_metadata SET SCHEMA tree;
ALTER TABLE haplogroup_variant_metadata SET SCHEMA tree;

-- Update cross-schema foreign key references
-- biosample_haplogroup stays in public but references tree.haplogroup
ALTER TABLE biosample_haplogroup
    DROP CONSTRAINT IF EXISTS biosample_haplogroup_y_haplogroup_id_fkey,
    DROP CONSTRAINT IF EXISTS biosample_haplogroup_mt_haplogroup_id_fkey,
    ADD CONSTRAINT biosample_haplogroup_y_haplogroup_id_fkey
        FOREIGN KEY (y_haplogroup_id) REFERENCES tree.haplogroup(haplogroup_id) ON DELETE CASCADE,
    ADD CONSTRAINT biosample_haplogroup_mt_haplogroup_id_fkey
        FOREIGN KEY (mt_haplogroup_id) REFERENCES tree.haplogroup(haplogroup_id) ON DELETE CASCADE;

-- ============================================================================
-- PART 2: Create discovery system tables in tree schema
-- ============================================================================

-- Biosample source type enum (used across discovery tables)
CREATE TYPE tree.biosample_source_type AS ENUM ('CITIZEN', 'EXTERNAL');

-- Private variants discovered in biosamples (unified across both types)
CREATE TABLE tree.biosample_private_variant (
    id SERIAL PRIMARY KEY,
    sample_type tree.biosample_source_type NOT NULL,
    sample_id INTEGER NOT NULL,           -- FK to citizen_biosample.id OR biosample.id
    sample_guid UUID NOT NULL,            -- Denormalized for efficient joins
    variant_id INTEGER NOT NULL REFERENCES public.variant(variant_id) ON DELETE RESTRICT,
    haplogroup_type VARCHAR(10) NOT NULL CHECK (haplogroup_type IN ('Y', 'MT')),
    terminal_haplogroup_id INTEGER NOT NULL REFERENCES tree.haplogroup(haplogroup_id),
    discovered_at TIMESTAMP NOT NULL DEFAULT NOW(),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PROMOTED', 'INVALIDATED')),
    UNIQUE(sample_type, sample_id, variant_id, haplogroup_type)
);

-- Note: Referential integrity for sample_id is enforced at application level
-- since it references different tables based on sample_type

CREATE INDEX idx_bpv_sample ON tree.biosample_private_variant(sample_type, sample_id);
CREATE INDEX idx_bpv_guid ON tree.biosample_private_variant(sample_guid);
CREATE INDEX idx_bpv_variant ON tree.biosample_private_variant(variant_id);
CREATE INDEX idx_bpv_terminal ON tree.biosample_private_variant(terminal_haplogroup_id);
CREATE INDEX idx_bpv_status ON tree.biosample_private_variant(status);

-- Proposed branches awaiting consensus/review
CREATE TABLE tree.proposed_branch (
    id SERIAL PRIMARY KEY,
    parent_haplogroup_id INTEGER NOT NULL REFERENCES tree.haplogroup(haplogroup_id),
    proposed_name VARCHAR(100),
    haplogroup_type VARCHAR(10) NOT NULL CHECK (haplogroup_type IN ('Y', 'MT')),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'READY_FOR_REVIEW', 'UNDER_REVIEW',
                          'ACCEPTED', 'PROMOTED', 'REJECTED', 'SPLIT')),
    consensus_count INTEGER NOT NULL DEFAULT 0,
    confidence_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    reviewed_at TIMESTAMP,
    reviewed_by VARCHAR(255),
    notes TEXT,
    promoted_haplogroup_id INTEGER REFERENCES tree.haplogroup(haplogroup_id)
);

CREATE INDEX idx_pb_parent ON tree.proposed_branch(parent_haplogroup_id);
CREATE INDEX idx_pb_status ON tree.proposed_branch(status);
CREATE INDEX idx_pb_type ON tree.proposed_branch(haplogroup_type);
CREATE INDEX idx_pb_consensus ON tree.proposed_branch(consensus_count);

-- Variants associated with proposed branches
CREATE TABLE tree.proposed_branch_variant (
    id SERIAL PRIMARY KEY,
    proposed_branch_id INTEGER NOT NULL REFERENCES tree.proposed_branch(id) ON DELETE CASCADE,
    variant_id INTEGER NOT NULL REFERENCES public.variant(variant_id) ON DELETE RESTRICT,
    is_defining BOOLEAN NOT NULL DEFAULT TRUE,
    evidence_count INTEGER NOT NULL DEFAULT 1,
    first_observed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_observed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(proposed_branch_id, variant_id)
);

CREATE INDEX idx_pbv_variant ON tree.proposed_branch_variant(variant_id);

-- Biosamples supporting proposed branches (unified across both types)
CREATE TABLE tree.proposed_branch_evidence (
    id SERIAL PRIMARY KEY,
    proposed_branch_id INTEGER NOT NULL REFERENCES tree.proposed_branch(id) ON DELETE CASCADE,
    sample_type tree.biosample_source_type NOT NULL,
    sample_id INTEGER NOT NULL,           -- FK to citizen_biosample.id OR biosample.id
    sample_guid UUID NOT NULL,            -- Denormalized for lookups
    added_at TIMESTAMP NOT NULL DEFAULT NOW(),
    variant_match_count INTEGER NOT NULL DEFAULT 0,
    variant_mismatch_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE(proposed_branch_id, sample_type, sample_id)
);

CREATE INDEX idx_pbe_sample ON tree.proposed_branch_evidence(sample_type, sample_id);
CREATE INDEX idx_pbe_guid ON tree.proposed_branch_evidence(sample_guid);

-- Curator audit trail
CREATE TABLE tree.curator_action (
    id SERIAL PRIMARY KEY,
    curator_id VARCHAR(255) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id INTEGER NOT NULL,
    previous_state JSONB,
    new_state JSONB,
    reason TEXT,
    timestamp TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ca_curator ON tree.curator_action(curator_id);
CREATE INDEX idx_ca_timestamp ON tree.curator_action(timestamp);
CREATE INDEX idx_ca_target ON tree.curator_action(target_type, target_id);

-- Configuration for consensus thresholds
CREATE TABLE tree.discovery_config (
    id SERIAL PRIMARY KEY,
    haplogroup_type VARCHAR(10) NOT NULL CHECK (haplogroup_type IN ('Y', 'MT')),
    config_key VARCHAR(100) NOT NULL,
    config_value TEXT NOT NULL,
    description TEXT,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(255),
    UNIQUE(haplogroup_type, config_key)
);

-- Initial configuration
INSERT INTO tree.discovery_config (haplogroup_type, config_key, config_value, description) VALUES
('Y', 'consensus_threshold', '3', 'Minimum biosamples required to reach ReadyForReview'),
('Y', 'auto_promote_threshold', '10', 'Biosamples required for automatic promotion'),
('Y', 'confidence_threshold', '0.95', 'Minimum confidence score for promotion'),
('MT', 'consensus_threshold', '3', 'Minimum biosamples required to reach ReadyForReview'),
('MT', 'auto_promote_threshold', '10', 'Biosamples required for automatic promotion'),
('MT', 'confidence_threshold', '0.95', 'Minimum confidence score for promotion');

-- ============================================================================
-- PART 3: Utility views for cross-schema queries
-- ============================================================================

-- View for unified sample access in discovery queries
CREATE VIEW tree.discovery_sample_view AS
SELECT
    'CITIZEN'::tree.biosample_source_type AS sample_type,
    id AS sample_id,
    sample_guid,
    y_haplogroup->>'haplogroupName' AS y_terminal,
    mt_haplogroup->>'haplogroupName' AS mt_terminal,
    (y_haplogroup->>'mismatchingSnps')::int AS y_mismatches,
    (mt_haplogroup->>'mismatchingSnps')::int AS mt_mismatches
FROM public.citizen_biosample
WHERE deleted = false

UNION ALL

SELECT
    'EXTERNAL'::tree.biosample_source_type AS sample_type,
    b.id AS sample_id,
    b.sample_guid,
    boh.y_haplogroup_result->>'haplogroupName' AS y_terminal,
    boh.mt_haplogroup_result->>'haplogroupName' AS mt_terminal,
    (boh.y_haplogroup_result->>'mismatchingSnps')::int AS y_mismatches,
    (boh.mt_haplogroup_result->>'mismatchingSnps')::int AS mt_mismatches
FROM public.biosample b
JOIN public.biosample_original_haplogroup boh ON boh.biosample_id = b.id;

-- View for haplogroup tree with proposal indicators
CREATE VIEW tree.haplogroup_with_proposals AS
SELECT
    h.*,
    COALESCE(pb.proposal_count, 0) AS pending_proposal_count,
    COALESCE(pb.total_evidence, 0) AS proposal_evidence_count
FROM tree.haplogroup h
LEFT JOIN (
    SELECT
        parent_haplogroup_id,
        COUNT(*) AS proposal_count,
        SUM(consensus_count) AS total_evidence
    FROM tree.proposed_branch
    WHERE status NOT IN ('PROMOTED', 'REJECTED')
    GROUP BY parent_haplogroup_id
) pb ON pb.parent_haplogroup_id = h.haplogroup_id;
```

---

## Service Layer

### 1. PrivateVariantExtractionService

Extracts and records private variants from incoming biosample data. Handles both Citizen and External biosamples through the unified `SampleReference`.

```scala
trait PrivateVariantExtractionService {
  /**
   * Process a biosample and extract its private variants.
   * Called after biosample ingestion when HaplogroupResult contains mismatches.
   * Works for both Citizen and External biosamples.
   */
  def extractPrivateVariants(
    sampleRef: SampleReference,        // Unified reference to either sample type
    haplogroupResult: HaplogroupResult,
    haplogroupType: HaplogroupType,
    rawVariantData: Seq[VariantCall]   // Detailed variant calls from sequencing
  ): Future[Seq[BiosamplePrivateVariant]]

  /**
   * Process a Citizen biosample (convenience method).
   */
  def extractFromCitizenBiosample(
    citizenBiosampleId: Int,
    sampleGuid: UUID,
    haplogroupResult: HaplogroupResult,
    haplogroupType: HaplogroupType,
    rawVariantData: Seq[VariantCall]
  ): Future[Seq[BiosamplePrivateVariant]]

  /**
   * Process an External biosample (convenience method).
   * Called when curators load publication data with private variants.
   */
  def extractFromExternalBiosample(
    biosampleId: Int,
    sampleGuid: UUID,
    haplogroupResult: HaplogroupResult,
    haplogroupType: HaplogroupType,
    rawVariantData: Seq[VariantCall]
  ): Future[Seq[BiosamplePrivateVariant]]

  /**
   * Resolve variant calls to existing variant records or create new ones.
   */
  def resolveOrCreateVariants(
    variantCalls: Seq[VariantCall]
  ): Future[Map[VariantCall, Int]]  // Returns variant IDs
}
```

**Implementation Notes:**
- The Atmosphere Lexicon provides `mismatchingSnps` as a count; we need the actual variant calls
- Extend `HaplogroupResult` or add a parallel `PrivateVariantData` structure to capture detailed calls
- Variants not in the `variant` table should be created with appropriate reference genome coordinates
- External biosamples may come with variant data from publications or curator uploads

### 2. ProposalEngine

Creates and updates proposed branches based on shared private variants.

```scala
trait ProposalEngine {
  /**
   * Process a biosample's private variants and update proposals.
   */
  def processDiscovery(
    biosampleId: Int,
    privateVariants: Seq[BiosamplePrivateVariant]
  ): Future[Seq[ProposedBranch]]

  /**
   * Find or create a proposal matching the given variant set.
   * Uses Jaccard similarity to match existing proposals.
   */
  def findOrCreateProposal(
    parentHaplogroupId: Int,
    haplogroupType: HaplogroupType,
    variants: Set[Int]
  ): Future[ProposedBranch]

  /**
   * Update consensus counts and check thresholds.
   */
  def updateConsensus(proposalId: Int): Future[ProposedBranch]

  /**
   * Detect when an existing proposal should be split.
   * Triggered when biosamples diverge on some variants.
   */
  def detectSplits(proposalId: Int): Future[Option[SplitProposal]]
}
```

**Matching Algorithm:**

```
For a biosample with private variants V = {v1, v2, v3}:

1. Find all existing proposals P under the same parent haplogroup
2. For each proposal p in P:
   - Calculate Jaccard similarity: J = |V ∩ p.variants| / |V ∪ p.variants|
   - If J >= 0.8 (configurable), consider it a match
3. If no match found, create new proposal with V as defining variants
4. If match found:
   - Add biosample as evidence
   - Update variant evidence counts
   - Recalculate consensus
5. If partial match (0.5 <= J < 0.8):
   - Flag for potential split review
```

### 3. ConsensusDetectionService

Monitors proposals and handles threshold transitions.

```scala
trait ConsensusDetectionService {
  /**
   * Check all pending proposals against thresholds.
   * Called periodically or after proposal updates.
   */
  def evaluateThresholds(): Future[Seq[ThresholdEvent]]

  /**
   * Handle a proposal reaching the review threshold.
   */
  def handleReadyForReview(proposalId: Int): Future[Unit]

  /**
   * Handle a proposal reaching auto-promote threshold.
   */
  def handleAutoPromote(proposalId: Int): Future[Unit]

  /**
   * Calculate confidence score for a proposal.
   */
  def calculateConfidence(proposalId: Int): Future[Double]
}

case class ThresholdEvent(
  proposalId: Int,
  previousStatus: ProposedBranchStatus,
  newStatus: ProposedBranchStatus,
  trigger: String
)
```

**Confidence Score Calculation:**

```
confidence = w1 * (consensusCount / autoPromoteThreshold) +
             w2 * (avgVariantMatchRatio) +
             w3 * (geographicDiversity) +
             w4 * (timeSpan)

Where:
- w1 = 0.4 (weight for sample count)
- w2 = 0.3 (weight for variant consistency)
- w3 = 0.2 (weight for geographic independence)
- w4 = 0.1 (weight for temporal spread)
```

### 4. CuratorService

Provides curator operations with full audit trail.

```scala
trait CuratorService {
  /**
   * Get proposals ready for review.
   */
  def getProposalsForReview(
    haplogroupType: Option[HaplogroupType],
    status: Option[ProposedBranchStatus],
    page: Int,
    pageSize: Int
  ): Future[Page[ProposedBranchView]]

  /**
   * Get detailed view of a proposal with all evidence.
   */
  def getProposalDetails(proposalId: Int): Future[ProposedBranchDetails]

  /**
   * Accept a proposal for promotion.
   */
  def acceptProposal(
    proposalId: Int,
    curatorId: String,
    proposedName: String,
    reason: Option[String]
  ): Future[ProposedBranch]

  /**
   * Reject a proposal.
   */
  def rejectProposal(
    proposalId: Int,
    curatorId: String,
    reason: String
  ): Future[ProposedBranch]

  /**
   * Modify a proposal's defining variants.
   */
  def modifyProposal(
    proposalId: Int,
    curatorId: String,
    modifications: ProposalModification
  ): Future[ProposedBranch]

  /**
   * Split a proposal into child proposals.
   */
  def splitProposal(
    proposalId: Int,
    curatorId: String,
    splitConfig: SplitConfiguration
  ): Future[Seq[ProposedBranch]]

  /**
   * Manually create a new branch (curator override).
   */
  def createManualBranch(
    parentHaplogroupId: Int,
    curatorId: String,
    branchConfig: ManualBranchConfig
  ): Future[Haplogroup]

  /**
   * Reassign a biosample to a different haplogroup.
   */
  def reassignBiosample(
    biosampleId: Int,
    newHaplogroupId: Int,
    curatorId: String,
    reason: String
  ): Future[Unit]
}
```

### 5. TreeEvolutionService

Promotes accepted proposals to the canonical tree.

```scala
trait TreeEvolutionService {
  /**
   * Promote an accepted proposal to the haplogroup tree.
   */
  def promoteProposal(proposalId: Int): Future[Haplogroup]

  /**
   * Create a new haplogroup from proposal data.
   */
  def createHaplogroupFromProposal(
    proposal: ProposedBranch,
    variants: Seq[ProposedBranchVariant]
  ): Future[Haplogroup]

  /**
   * Update biosample assignments after tree modification.
   */
  def reassignBiosamplesToNewTerminal(
    oldTerminalId: Int,
    newTerminalId: Int,
    affectedBiosamples: Seq[Int]
  ): Future[Int]

  /**
   * Update private variant statuses after promotion.
   */
  def promotePrivateVariants(
    variants: Seq[Int],
    newHaplogroupId: Int
  ): Future[Unit]
}
```

---

## API Endpoints

### Discovery Management API

```
# Proposal Queries
GET  /api/v1/discovery/proposals
     ?type={Y|MT}
     &status={PENDING|READY_FOR_REVIEW|UNDER_REVIEW|...}
     &parentHaplogroup={name}
     &minConsensus={int}
     &page={int}
     &pageSize={int}

GET  /api/v1/discovery/proposals/{id}
GET  /api/v1/discovery/proposals/{id}/evidence
GET  /api/v1/discovery/proposals/{id}/variants

# Proposal Statistics
GET  /api/v1/discovery/stats
     ?type={Y|MT}

# Private Variant Queries
GET  /api/v1/discovery/private-variants
     ?biosampleId={id}
     &terminalHaplogroup={name}
     &status={ACTIVE|PROMOTED|INVALIDATED}
```

### Curator API (Authenticated)

```
# Curator Actions (requires curator role)
POST   /api/v1/curator/proposals/{id}/accept
       Body: { "proposedName": "R-ABC123", "reason": "..." }

POST   /api/v1/curator/proposals/{id}/reject
       Body: { "reason": "..." }

PATCH  /api/v1/curator/proposals/{id}
       Body: { "addVariants": [...], "removeVariants": [...], "notes": "..." }

POST   /api/v1/curator/proposals/{id}/split
       Body: { "splits": [{ "variants": [...], "name": "..." }, ...] }

# Manual Branch Creation
POST   /api/v1/curator/haplogroups
       Body: {
         "parentHaplogroupId": 123,
         "name": "R-XYZ789",
         "variants": [...],
         "reason": "..."
       }

# Biosample Reassignment
POST   /api/v1/curator/biosamples/{id}/reassign
       Body: { "newHaplogroupId": 456, "reason": "..." }

# Audit Trail
GET    /api/v1/curator/actions
       ?curatorId={id}
       &targetType={PROPOSED_BRANCH|HAPLOGROUP|...}
       &startDate={date}
       &endDate={date}
```

### Configuration API (Admin)

```
GET    /api/v1/admin/discovery/config
       ?type={Y|MT}

PUT    /api/v1/admin/discovery/config/{key}
       Body: { "value": "5", "reason": "Increasing threshold for Y-DNA" }
```

---

## Tapir Endpoint Definitions

```scala
// DiscoveryEndpoints.scala
object DiscoveryEndpoints {

  val getProposals: Endpoint[Unit, ProposalQuery, ApiError, Page[ProposedBranchView], Any] =
    endpoint.get
      .in("api" / "v1" / "discovery" / "proposals")
      .in(query[Option[String]]("type"))
      .in(query[Option[String]]("status"))
      .in(query[Option[String]]("parentHaplogroup"))
      .in(query[Option[Int]]("minConsensus"))
      .in(query[Int]("page").default(1))
      .in(query[Int]("pageSize").default(20))
      .out(jsonBody[Page[ProposedBranchView]])
      .errorOut(jsonBody[ApiError])

  val getProposalDetails: Endpoint[Unit, Int, ApiError, ProposedBranchDetails, Any] =
    endpoint.get
      .in("api" / "v1" / "discovery" / "proposals" / path[Int]("id"))
      .out(jsonBody[ProposedBranchDetails])
      .errorOut(jsonBody[ApiError])
}

// CuratorEndpoints.scala
object CuratorEndpoints {

  private val curatorBase = endpoint
    .securityIn(auth.bearer[String]())
    .in("api" / "v1" / "curator")

  val acceptProposal: Endpoint[String, (Int, AcceptProposalRequest), ApiError, ProposedBranchView, Any] =
    curatorBase.post
      .in("proposals" / path[Int]("id") / "accept")
      .in(jsonBody[AcceptProposalRequest])
      .out(jsonBody[ProposedBranchView])
      .errorOut(jsonBody[ApiError])

  val rejectProposal: Endpoint[String, (Int, RejectProposalRequest), ApiError, ProposedBranchView, Any] =
    curatorBase.post
      .in("proposals" / path[Int]("id") / "reject")
      .in(jsonBody[RejectProposalRequest])
      .out(jsonBody[ProposedBranchView])
      .errorOut(jsonBody[ApiError])

  val createManualBranch: Endpoint[String, CreateBranchRequest, ApiError, HaplogroupView, Any] =
    curatorBase.post
      .in("haplogroups")
      .in(jsonBody[CreateBranchRequest])
      .out(jsonBody[HaplogroupView])
      .errorOut(jsonBody[ApiError])
}
```

---

## Integration with Existing System

### 1. Biosample Ingestion Hooks

Both biosample sources require integration points for private variant extraction.

#### 1a. Citizen Biosample Hook

Modify `CitizenBiosampleEventHandler` to trigger private variant extraction:

```scala
// In CitizenBiosampleEventHandler.handle() - after biosample creation

private def postProcessCitizenBiosample(
  biosample: CitizenBiosample,
  request: ExternalBiosampleRequest
): Future[Unit] = {
  for {
    // Process Y-DNA private variants
    _ <- biosample.yHaplogroup match {
      case Some(hr) if hr.mismatchingSnps > 0 =>
        privateVariantService.extractFromCitizenBiosample(
          biosample.id.get,
          biosample.sampleGuid,
          hr,
          HaplogroupType.Y,
          request.privateVariantData.flatMap(_.yDna).getOrElse(Seq.empty)
        )
      case _ => Future.successful(Seq.empty)
    }
    // Process mtDNA private variants
    _ <- biosample.mtHaplogroup match {
      case Some(hr) if hr.mismatchingSnps > 0 =>
        privateVariantService.extractFromCitizenBiosample(
          biosample.id.get,
          biosample.sampleGuid,
          hr,
          HaplogroupType.MT,
          request.privateVariantData.flatMap(_.mtDna).getOrElse(Seq.empty)
        )
      case _ => Future.successful(Seq.empty)
    }
  } yield ()
}
```

#### 1b. External Biosample Hook

Create or modify service for External (publication) biosample processing:

```scala
// In ExternalBiosampleService or a new BiosampleOriginalHaplogroupService

private def postProcessExternalBiosample(
  biosample: Biosample,
  originalHaplogroup: BiosampleOriginalHaplogroup,
  privateVariantData: Option[PrivateVariantData]
): Future[Unit] = {
  for {
    // Process Y-DNA private variants from publication data
    _ <- originalHaplogroup.originalYHaplogroup match {
      case Some(hr) if hr.mismatchingSnps > 0 =>
        privateVariantService.extractFromExternalBiosample(
          biosample.id.get,
          biosample.sampleGuid,
          hr,
          HaplogroupType.Y,
          privateVariantData.flatMap(_.yDna).getOrElse(Seq.empty)
        )
      case _ => Future.successful(Seq.empty)
    }
    // Process mtDNA private variants
    _ <- originalHaplogroup.originalMtHaplogroup match {
      case Some(hr) if hr.mismatchingSnps > 0 =>
        privateVariantService.extractFromExternalBiosample(
          biosample.id.get,
          biosample.sampleGuid,
          hr,
          HaplogroupType.MT,
          privateVariantData.flatMap(_.mtDna).getOrElse(Seq.empty)
        )
      case _ => Future.successful(Seq.empty)
    }
  } yield ()
}
```

#### 1c. Curator Bulk Upload Integration

For curator-driven publication uploads, integrate with existing or new bulk upload endpoints:

```scala
// New endpoint for curator publication upload with variant data
trait PublicationUploadService {
  /**
   * Process a publication upload containing multiple biosamples with
   * haplogroup assignments and private variant data.
   */
  def uploadPublicationBiosamples(
    publicationId: Int,
    biosamples: Seq[PublicationBiosampleUpload],
    curatorId: String
  ): Future[PublicationUploadResult]
}

case class PublicationBiosampleUpload(
  sampleAccession: String,
  donorIdentifier: Option[String],
  yHaplogroup: Option[HaplogroupResult],
  mtHaplogroup: Option[HaplogroupResult],
  privateVariantData: Option[PrivateVariantData],
  // ... other biosample fields
)

case class PublicationUploadResult(
  biosamplesCreated: Int,
  privateVariantsExtracted: Int,
  proposalsCreated: Int,
  proposalsUpdated: Int
)
```

### 2. Extended Data Structures

Add detailed private variant data to API requests for both sources:

```scala
// Shared structure for private variant data
case class PrivateVariantData(
  yDna: Option[Seq[VariantCall]],
  mtDna: Option[Seq[VariantCall]]
)

case class VariantCall(
  contigAccession: String,       // e.g., "NC_000024.10" for chrY
  position: Int,
  referenceAllele: String,
  alternateAllele: String,
  rsId: Option[String],
  variantName: Option[String]    // Common name if known (e.g., "M269")
)

// Extended ExternalBiosampleRequest (for Citizen samples via AT Protocol)
case class ExternalBiosampleRequest(
  // ... existing fields ...

  // NEW: Detailed private variant data (extends beyond mismatchingSnps count)
  privateVariantData: Option[PrivateVariantData]
)

// Extended BiosampleOriginalHaplogroupUpdate (for External samples via curator)
case class BiosampleOriginalHaplogroupUpdate(
  // ... existing fields ...

  // NEW: Detailed private variant data from publication
  privateVariantData: Option[PrivateVariantData]
)
```

### 3. Tree Visibility Filter

Modify tree queries to exclude proposed (non-promoted) branches:

```scala
// In HaplogroupCoreRepository

def getVisibleSubtree(
  rootId: Int,
  includeProposed: Boolean = false
): Future[Seq[HaplogroupWithRelationship]] = {
  val baseQuery = if (includeProposed) {
    // Include all haplogroups (for curator view)
    haplogroupQuery
  } else {
    // Exclude haplogroups that came from proposals not yet promoted
    haplogroupQuery.filter(h =>
      !h.source.like("proposed:%") || h.confidenceLevel === "ACCEPTED"
    )
  }
  // ... rest of query
}
```

---

## Reporting Isolation

### Public Tree Views

- Default tree queries exclude `status != PROMOTED` proposal-derived haplogroups
- Tree API endpoints filter by `source` field to show only canonical branches
- Biosample haplogroup assignments show the **last accepted terminal**, not proposed extensions

### Curator Tree Views

- Curator endpoints include `includeProposed=true` parameter
- Visual distinction (e.g., dashed lines, different color) for proposed branches
- Tooltip/detail shows proposal status and evidence count

### Biosample Reporting

```scala
case class BiosampleHaplogroupReport(
  sampleType: BiosampleSourceType,     // CITIZEN or EXTERNAL
  sampleGuid: UUID,
  assignedHaplogroup: String,          // Current tree terminal
  proposedExtension: Option[String],   // Proposed branch if exists
  privateVariantCount: Int,
  isParticipatingInProposal: Boolean,
  proposalIds: Seq[Int]                // Proposals this sample supports
)

// View model for proposal details showing evidence from both sources
case class ProposedBranchDetails(
  proposal: ProposedBranch,
  definingVariants: Seq[VariantInfo],
  citizenEvidence: Seq[CitizenBiosampleSummary],    // Citizen samples
  externalEvidence: Seq[ExternalBiosampleSummary],  // Publication samples
  totalEvidenceCount: Int,
  geographicDistribution: Map[String, Int]          // Country -> count
)
```

---

## Configuration

### Configurable Parameters

| Parameter | Default (Y) | Default (MT) | Description |
|-----------|-------------|--------------|-------------|
| `consensus_threshold` | 3 | 3 | Minimum biosamples to reach READY_FOR_REVIEW |
| `auto_promote_threshold` | 10 | 10 | Biosamples for automatic promotion |
| `confidence_threshold` | 0.95 | 0.95 | Minimum confidence score |
| `similarity_match_threshold` | 0.80 | 0.80 | Jaccard similarity for proposal matching |
| `similarity_split_threshold` | 0.50 | 0.50 | Similarity triggering split detection |
| `max_variants_per_proposal` | 20 | 15 | Maximum defining variants |
| `min_variants_per_proposal` | 1 | 1 | Minimum defining variants |

### Runtime Configuration

```hocon
decodingus.discovery {
  y-dna {
    consensus-threshold = 3
    auto-promote-threshold = 10
    confidence-threshold = 0.95
  }
  mt-dna {
    consensus-threshold = 3
    auto-promote-threshold = 10
    confidence-threshold = 0.95
  }

  # Background job settings
  evaluation-interval = 5 minutes
  notification-enabled = true
}
```

---

## Security Model

### Role-Based Access Control

| Role | Permissions |
|------|-------------|
| `anonymous` | View public tree, view promoted branches |
| `citizen` | View own biosample's private variants and proposals |
| `researcher` | View all proposals (read-only) |
| `curator` | Accept/reject/modify proposals, manual branch creation |
| `admin` | All curator permissions + configuration management |

### Curator Authentication

- Curators authenticated via existing `ApiSecurityAction`
- Curator role checked against user claims
- All curator actions require `curator_id` in audit trail

### Audit Requirements

- All state changes logged to `curator_action` table
- Previous/new state captured as JSONB snapshots
- Immutable audit trail (no deletion of audit records)

---

## Implementation Phases

### Phase 0: Schema Reorganization

**Scope:**
- Create `tree` schema
- Migrate existing haplogroup tables to `tree` schema
- Update DAL layer for new schema references
- Update all repositories and services for schema-qualified queries

**Deliverables:**
- [ ] Database evolution script for schema creation and table migration
- [ ] Updated Slick table definitions with `Some("tree")` schema parameter
- [ ] Updated `DatabaseSchema.scala` with tree schema table references
- [ ] Updated all haplogroup repositories for cross-schema queries
- [ ] Regression tests to verify existing functionality
- [ ] Documentation update for new schema structure

**Risk Mitigation:**
- Run migration on staging environment first
- Verify all foreign key constraints work cross-schema
- Test recursive CTE queries still function correctly

### Phase 1: Data Capture

**Scope:**
- Database schema evolution (discovery tables in `tree` schema)
- `SampleReference` value object and `BiosampleSourceType` enum
- `BiosamplePrivateVariant` entity and repository
- Extended request DTOs for both sample types
- Private variant extraction service
- Integration with both Citizen and External biosample ingestion

**Deliverables:**
- [ ] Database migration script (`tree.biosample_source_type` enum, `tree.biosample_private_variant` table)
- [ ] `BiosampleSourceType` enum and `SampleReference` value object
- [ ] Domain models and DAL tables for private variants (in `tree` schema)
- [ ] `PrivateVariantExtractionService` with unified and source-specific methods
- [ ] `BiosamplePrivateVariantRepository`
- [ ] Modified `CitizenBiosampleEventHandler` with post-processing hook
- [ ] Modified/new External biosample service with post-processing hook
- [ ] Extended `ExternalBiosampleRequest` with `PrivateVariantData`
- [ ] Extended `BiosampleOriginalHaplogroupUpdate` with `PrivateVariantData`

### Phase 2: Proposal Engine

**Scope:**
- `ProposedBranch` entities and repositories (with unified sample references)
- Proposal matching algorithm (across both sample types)
- Consensus calculation aggregating evidence from all sources
- Threshold detection

**Deliverables:**
- [ ] Database migration (proposed_branch, proposed_branch_variant, proposed_branch_evidence tables)
- [ ] Proposal domain models
- [ ] `ProposedBranchRepository`
- [ ] `ProposedBranchVariantRepository`
- [ ] `ProposedBranchEvidenceRepository` (supporting unified sample references)
- [ ] `ProposalEngine` service with Jaccard similarity matching
- [ ] `ConsensusDetectionService` with unified evidence aggregation

### Phase 3: Curator Workflow

**Scope:**
- Curator API endpoints for proposal management
- Accept/reject/modify operations
- Publication bulk upload with private variants
- Audit trail implementation
- Curator dashboard views

**Deliverables:**
- [ ] Database migration (curator_action, discovery_config tables)
- [ ] `CuratorService` with full proposal lifecycle management
- [ ] `CuratorActionRepository`
- [ ] `PublicationUploadService` for bulk biosample+variant uploads
- [ ] Tapir endpoints for curator API
- [ ] Tapir endpoints for publication upload API
- [ ] Curator authentication/authorization
- [ ] Audit logging

### Phase 4: Tree Evolution

**Scope:**
- Promotion workflow
- Tree update mechanics
- Biosample reassignment (both sample types)
- Reporting isolation
- Private variant status updates

**Deliverables:**
- [ ] `TreeEvolutionService` with promotion logic
- [ ] Modified tree queries (visibility filter for public vs curator views)
- [ ] Biosample reassignment logic (unified across sample types)
- [ ] Private variant status transition logic (ACTIVE → PROMOTED)
- [ ] Integration tests for full workflow (Citizen samples)
- [ ] Integration tests for full workflow (External samples)
- [ ] Integration tests for mixed-source consensus scenarios

### Phase 5: UI and Notifications

**Scope:**
- Curator dashboard UI
- Proposal review interface
- Notification system for threshold events
- Public tree with proposal indicators (curator view)

**Deliverables:**
- [ ] Twirl templates for curator views
- [ ] JavaScript for interactive proposal review
- [ ] Notification service (email/webhook)
- [ ] Tree visualization with proposal overlay

---

## Testing Strategy

### Unit Tests

- Jaccard similarity calculation
- Confidence score calculation
- Threshold evaluation logic
- Variant matching/deduplication

### Integration Tests

- Full biosample ingestion with private variants
- Proposal creation and matching
- Curator accept/reject workflows
- Tree promotion and reassignment

### End-to-End Tests

- REST API workflow for discovery
- Curator workflow via API
- Tree visibility filtering

### Test Data

- Generate synthetic biosamples with known private variants
- Create scenarios for consensus building
- Test edge cases (single-variant proposals, high-similarity matches)

---

## Monitoring and Metrics

### Key Metrics

- Proposals created per day (by type)
- Average time to reach consensus threshold
- Curator action rate (accepts/rejects per day)
- Proposal backlog (pending review count)
- Auto-promotion rate

### Alerts

- Proposal backlog exceeds threshold
- Curator action rate drops below baseline
- Failed promotions
- Duplicate variant detection failures

---

## Future Considerations

### Potential Enhancements

1. **ML-Based Variant Validation**: Use machine learning to score variant quality
2. **Cross-Tree Analysis**: Detect patterns across Y-DNA and mtDNA proposals
3. **Publication Integration**: Automatically create proposals from new publications
4. **Collaborative Curation**: Multi-curator review workflow with voting
5. **Geographic Correlation**: Analyze proposal evidence by geographic distribution

### Scalability

- Proposal matching query optimization (consider materialized views)
- Async processing for large consensus recalculations
- Partitioned curator_action table for audit trail growth

---

## Appendix A: Lexicon Alignment

The Atmosphere Lexicon (`com.decodingus.atmosphere.biosample`) defines:

```json
"haplogroupResult": {
  "haplogroupName": "...",
  "score": 0.998,
  "matchingSnps": 145,
  "mismatchingSnps": 2,   // <-- Trigger for discovery
  "ancestralMatches": 3000,
  "treeDepth": 25,
  "lineagePath": ["R", "R1", "R1b", "R-M269"]
}
```

This system extends the Lexicon by:

1. Requiring detailed variant calls alongside the summary counts
2. Tracking the specific variants that contribute to `mismatchingSnps`
3. Building consensus across Citizen PDS records sharing the same private variants
4. Evolving the tree based on network-wide observations

The BGS Nodes (or Edge Apps) should be updated to provide the detailed variant data in the `privateVariantData` extension field when creating biosample records.

---

## Appendix B: Dual-Source Sample Tracking

### Why Unified Tracking?

The discovery system must aggregate evidence from all biosample sources to build accurate consensus. A proposed branch gains credibility when supported by:

1. **Independent Citizen samples** - Users submitting their own sequencing data
2. **Publication samples** - Academic datasets curated from peer-reviewed research

Treating these as separate silos would:
- Require duplicate proposals for the same variants
- Delay consensus building unnecessarily
- Miss correlations between independent discoveries

### Sample Type Characteristics

| Characteristic | Citizen Biosamples | External Biosamples |
|---------------|-------------------|---------------------|
| **Source** | AT Protocol / PDS | Publications / Curator upload |
| **Haplogroup Storage** | `citizen_biosample.y_haplogroup` (JSONB) | `biosample_original_haplogroup` table |
| **Primary Key** | `citizen_biosample.id` | `biosample.id` |
| **UUID** | `citizen_biosample.sample_guid` | `biosample.sample_guid` |
| **Ownership** | Citizen DID (AT Protocol) | Publication / Center |
| **Update Pattern** | Event-driven (Firehose) | Curator batch uploads |

### Query Patterns

The `tree.discovery_sample_view` (created in the evolution script) provides unified access across both sample types:

```sql
-- Example: Find all samples with mismatching variants under terminal R-M269
SELECT *
FROM tree.discovery_sample_view
WHERE y_terminal = 'R-M269'
  AND y_mismatches > 0;

-- Example: Get proposal evidence breakdown by source
SELECT
    pb.id AS proposal_id,
    pb.proposed_name,
    COUNT(*) FILTER (WHERE pbe.sample_type = 'CITIZEN') AS citizen_count,
    COUNT(*) FILTER (WHERE pbe.sample_type = 'EXTERNAL') AS publication_count
FROM tree.proposed_branch pb
JOIN tree.proposed_branch_evidence pbe ON pbe.proposed_branch_id = pb.id
GROUP BY pb.id, pb.proposed_name;
```

The `tree.haplogroup_with_proposals` view surfaces proposal activity for curator dashboards without requiring complex joins in the application layer.
