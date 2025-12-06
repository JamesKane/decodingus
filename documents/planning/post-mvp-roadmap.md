# Post-MVP Feature Roadmap

## Overview

This document serves as the central planning reference for features targeted after MVP completion. Each feature has a detailed planning document; this roadmap provides the high-level view, dependencies, and sequencing.

### Planning Documents

| Feature | Document | Status |
|---------|----------|--------|
| Haplogroup Discovery System | [`haplogroup-discovery-system.md`](./haplogroup-discovery-system.md) | Planned |
| Sequencer Lab Inference | [`sequencer-lab-inference-system.md`](./sequencer-lab-inference-system.md) | Planned |
| Multi-Test-Type Support | [`multi-test-type-roadmap.md`](./multi-test-type-roadmap.md) | Planned |
| IBD Matching System | [`ibd-matching-system.md`](./ibd-matching-system.md) | Planned |
| JSONB Consolidation | [`jsonb-consolidation-analysis.md`](./jsonb-consolidation-analysis.md) | Technical Debt |

### Non-Technical Summary

For a less technical overview of the Haplogroup Discovery System, see:
- [`haplogroup-discovery-system-overview.md`](./haplogroup-discovery-system-overview.md)

---

## Feature Summary

### 1. Haplogroup Discovery System

**Purpose:** Automatically evolve Y-DNA and mtDNA trees based on community discoveries.

**Key Capabilities:**
- Track private variants (mutations beyond known tree) from all biosample sources
- Detect consensus when multiple independent samples share private variants
- Propose new branches with configurable thresholds
- Curator workflow for review, accept, reject, split
- Audit trail for all tree modifications

**Data Flow:** PDS → Firehose → PrivateVariantExtractionService → ProposalEngine → CuratorReview → TreeEvolution

**Schema Impact:** Creates `tree` schema, migrates existing haplogroup tables, adds 6 new tables.

---

### 2. Sequencer Lab Inference System

**Purpose:** Enable Edge Apps to infer sequencing laboratory from BAM/CRAM read metadata.

**Key Capabilities:**
- Public lookup API for instrument ID → lab resolution
- Consensus-based discovery from citizen observations
- New Lexicon (`instrumentObservation`) for community contributions
- Curator workflow for proposal review
- Confidence scoring based on observation count and diversity

**Data Flow:** Edge App queries API → If unknown, creates `instrumentObservation` in PDS → Firehose → Observation aggregation → Proposal → CuratorReview

**Schema Impact:** Adds 2 new tables, extends `sequencer_instrument` with confidence tracking.

---

### 3. Multi-Test-Type Support

**Purpose:** Extend beyond WGS to support targeted sequencing (Big Y-700, Y Elite, mtDNA Full Sequence) and chip data (23andMe, AncestryDNA).

**Key Capabilities:**
- Test type taxonomy and capability matrix
- Metadata-only indexing for chip data (raw genotypes stay on Edge)
- Y/mtDNA variant extraction for tree building
- Test-type-aware haplogroup confidence scoring
- Reference data for marker coverage per test type

**Data Flow:** Edge App processes locally → Includes test type + Y/mtDNA variants in biosample PDS record → Firehose → ChipDataExtractionService / TargetedSequencingExtractionService

**Schema Impact:** Adds test type definition tables, `genotyping_test_summary`, marker coverage reference tables.

**Integration:** Y/mtDNA variants feed into Haplogroup Discovery System.

---

### 4. IBD Matching System

**Purpose:** Enable genetic genealogists to discover and confirm IBD relationships.

**Key Capabilities:**
- Match list and population breakdown Lexicon elements
- Discovery based on shared matches, population overlap, haplogroup match
- Dual-consent voting for match confirmation
- Edge App coordination for encrypted data exchange
- Match suggestions and tracking

**Data Flow:** Edge App → Match discovery signals in PDS → Firehose → Suggestion engine → Consent request → Edge-to-Edge comparison

**Schema Impact:** Extends existing `ibd_discovery_index`, `ibd_pds_attestation` tables; adds match suggestion tables.

**Note:** Autosomal comparisons happen Edge-to-Edge; DecodingUs coordinates but doesn't store raw autosomal data.

---

### 5. JSONB Consolidation (Technical Debt)

**Purpose:** Reduce table proliferation and JOIN overhead by consolidating 1:1 and 1:few relationships into JSONB columns.

**Candidates (7 tables):**

| Table | Target Parent | Priority |
|-------|---------------|----------|
| `sequence_file_checksum` | `sequence_file.checksums` | P1 |
| `sequence_http_location` | `sequence_file.http_locations` | P1 |
| `sequence_atp_location` | `sequence_file.atp_location` | P1 |
| `alignment_coverage` | `alignment_metadata.coverage` | P2 |
| `pangenome_alignment_coverage` | `pangenome_alignment_metadata.coverage` | P2 |
| `citizen_biosample_original_haplogroup` | `citizen_biosample.original_haplogroups_by_publication` | P3 |
| `biosample_original_haplogroup` | `biosample.original_haplogroups_by_publication` | P3 |

**Expected Outcome:** 7 fewer tables, 3-4 fewer JOINs per query, simpler data access patterns.

---

## Dependencies

```
                    ┌─────────────────────────────┐
                    │      JSONB Phase 1          │
                    │  (sequence_file cleanup)    │
                    └──────────────┬──────────────┘
                                   │
         ┌─────────────────────────┼─────────────────────────┐
         │                         │                         │
         ▼                         ▼                         ▼
┌─────────────────┐    ┌─────────────────────┐    ┌─────────────────┐
│  Haplogroup     │    │  Multi-Test-Type    │    │  Sequencer Lab  │
│  Discovery      │◄───│  Support            │    │  Inference      │
│  (Phase 0-2)    │    │  (Phase 1-2)        │    │  (Phase 1-2)    │
└────────┬────────┘    └──────────┬──────────┘    └────────┬────────┘
         │                        │                        │
         │                        │                        │
         ▼                        ▼                        │
┌─────────────────┐    ┌─────────────────────┐             │
│  Haplogroup     │    │  Multi-Test-Type    │             │
│  Discovery      │◄───│  Support            │             │
│  (Phase 3-5)    │    │  (Phase 3-5)        │             │
└────────┬────────┘    └─────────────────────┘             │
         │                                                 │
         │    ┌────────────────────────────────────────────┘
         │    │
         ▼    ▼
┌─────────────────────────────────────────────────────────┐
│                    IBD Matching System                   │
│           (depends on stable biosample pipeline)         │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│              JSONB Phase 2-3 (cleanup)                   │
│     (after features stabilize, before optimization)     │
└─────────────────────────────────────────────────────────┘
```

### Critical Path

1. **JSONB Phase 1** - Clean up sequence_file relationships before adding complexity
2. **Haplogroup Discovery Phase 0** - Create `tree` schema (blocks all tree-related work)
3. **Multi-Test-Type Phase 1-2** - Test type definitions needed for confidence scoring
4. **Haplogroup Discovery Phase 1-3** - Core discovery engine
5. **Multi-Test-Type Phase 3-5** - Full integration with discovery system

### Parallel Tracks

- **Sequencer Lab Inference** - Independent, can proceed in parallel
- **IBD Matching** - Independent of tree work, but needs stable biosample pipeline

---

## Implementation Phases

### Phase A: Foundation (Estimated: 2-3 sprints)

| Task | Feature | Deliverables |
|------|---------|--------------|
| A.1 | JSONB | Phase 1: sequence_file consolidation |
| A.2 | Haplogroup Discovery | Phase 0: Create `tree` schema, migrate tables |
| A.3 | Multi-Test-Type | Phase 1: Test type definitions and taxonomy |

**Exit Criteria:**
- [ ] `tree` schema exists with migrated haplogroup tables
- [ ] `sequence_file` JSONB columns working
- [ ] `test_type_definition` table populated

---

### Phase B: Core Discovery (Estimated: 3-4 sprints)

| Task | Feature | Deliverables |
|------|---------|--------------|
| B.1 | Haplogroup Discovery | Phase 1: Private variant capture |
| B.2 | Haplogroup Discovery | Phase 2: Proposal engine |
| B.3 | Multi-Test-Type | Phase 2: Chip metadata registration |
| B.4 | Sequencer Lab | Phase 1-2: Schema + Firehose integration |

**Exit Criteria:**
- [ ] Private variants extracted from biosamples
- [ ] Proposals created from shared variants
- [ ] Chip test summaries recorded
- [ ] Instrument observations flowing through Firehose

---

### Phase C: Curator Workflows (Estimated: 2-3 sprints)

| Task | Feature | Deliverables |
|------|---------|--------------|
| C.1 | Haplogroup Discovery | Phase 3: Curator API and audit trail |
| C.2 | Sequencer Lab | Phase 3-4: Proposal engine + Curator API |
| C.3 | Multi-Test-Type | Phase 3: Proposal engine integration |

**Exit Criteria:**
- [ ] Curators can accept/reject haplogroup proposals
- [ ] Curators can accept/reject instrument proposals
- [ ] Full audit trail for both systems

---

### Phase D: Tree Evolution (Estimated: 2 sprints)

| Task | Feature | Deliverables |
|------|---------|--------------|
| D.1 | Haplogroup Discovery | Phase 4: Tree promotion and biosample reassignment |
| D.2 | Multi-Test-Type | Phase 4-5: Haplogroup confidence integration |
| D.3 | JSONB | Phase 2: alignment_coverage consolidation |

**Exit Criteria:**
- [ ] Accepted proposals become tree branches
- [ ] Biosample assignments update automatically
- [ ] Test-type-aware confidence scoring

---

### Phase E: IBD Matching (Estimated: 3-4 sprints)

| Task | Feature | Deliverables |
|------|---------|--------------|
| E.1 | IBD Matching | Lexicon definitions |
| E.2 | IBD Matching | Match discovery and suggestion engine |
| E.3 | IBD Matching | Consent workflow |
| E.4 | IBD Matching | Edge App coordination protocol |

**Exit Criteria:**
- [ ] Users can discover potential matches
- [ ] Dual-consent voting works
- [ ] Edge-to-Edge comparison protocol documented

---

### Phase F: Polish & Debt (Estimated: 2 sprints)

| Task | Feature | Deliverables |
|------|---------|--------------|
| F.1 | Haplogroup Discovery | Phase 5: UI and notifications |
| F.2 | Sequencer Lab | Phase 5: Biosample integration + backfill |
| F.3 | JSONB | Phase 3: haplogroup tracking consolidation |

**Exit Criteria:**
- [ ] Curator dashboard functional
- [ ] Existing biosamples processed for observations
- [ ] All JSONB consolidation complete

---

## JSONB Work Distribution

The JSONB consolidation work is distributed across feature phases to minimize disruption:

| JSONB Phase | When | Rationale |
|-------------|------|-----------|
| Phase 1 (sequence_file) | Phase A | Clean foundation before new features |
| Phase 2 (alignment_coverage) | Phase D | After discovery system stable, before optimization |
| Phase 3 (original_haplogroup) | Phase F | After haplogroup discovery using these tables |

---

## Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| `tree` schema migration breaks existing queries | High | Staged rollout, comprehensive test coverage |
| Proposal matching algorithm too aggressive/conservative | Medium | Configurable thresholds, curator override |
| Firehose ingestion can't keep up with volume | Medium | Async processing, backpressure handling |
| JSONB aggregation performance degrades | Medium | Expression indexes, benchmark before/after |
| Edge App adoption slower than expected | Low | System works with curator-only input initially |

---

## Success Metrics

### Haplogroup Discovery
- Proposals created per month
- Time from first evidence to acceptance
- Curator review queue size
- Tree growth rate (new branches per quarter)

### Sequencer Lab Inference
- Instrument lookup hit rate
- Community observation count
- Time from observation to acceptance

### Multi-Test-Type
- Test types registered
- Chip summaries indexed
- Confidence score distribution

### IBD Matching
- Match suggestions generated
- Consent completion rate
- Successful Edge-to-Edge comparisons

---

## References

- [Haplogroup Discovery System](./haplogroup-discovery-system.md) - Full technical design
- [Haplogroup Discovery Overview](./haplogroup-discovery-system-overview.md) - Non-technical summary
- [Sequencer Lab Inference](./sequencer-lab-inference-system.md) - Full technical design
- [Multi-Test-Type Roadmap](./multi-test-type-roadmap.md) - Full technical design
- [IBD Matching System](./ibd-matching-system.md) - Full technical design
- [JSONB Consolidation Analysis](./jsonb-consolidation-analysis.md) - Technical debt analysis
- [Atmosphere Lexicon](../Atmosphere_Lexicon.md) - AT Protocol record definitions
