# Atmosphere Lexicon - Executive Summary

Current status and milestones for the DecodingUs Atmosphere Lexicon implementation across all teams.

**Last Updated:** 2025-12-09

---

## Overview

The Atmosphere Lexicon defines decentralized, user-owned genomic records for the AT Protocol (Bluesky) ecosystem. This enables citizens to own their genomic data in Personal Data Stores (PDS) while DecodingUs operates as an AppView for network-wide aggregation and analysis.

**Core Principle:** Raw genomic data (BAM, CRAM, VCF, FASTQ, genotype files) **never** leaves the user's device. All analysis is performed locally in Navigator Workbench. Only computed summaries and metadata flow through the PDS to DecodingUs.

---

## MVP Completion Estimate

**Overall MVP Progress: ~85%**

| Component | Progress | Notes |
|:----------|:---------|:------|
| Lexicon Schema Definitions | 100% | All record types defined in v1.7 |
| Database Migrations | 100% | Migrations 37-40 complete |
| Domain Models (Scala) | 100% | All models created with JSONB consolidation |
| DAL Tables (Slick) | 100% | Tables created, 22-tuple limit addressed |
| Repositories | 100% | Full CRUD operations for all new entities |
| Event Handlers | 100% | Genotype, PopulationBreakdown, Reconciliation handlers |
| Firehose Consumer | 70% | Core handlers done, needs integration testing |
| API Endpoints | 40% | REST controllers not yet implemented |
| Integration Tests | 20% | Basic compilation verified |

**Remaining MVP Work:**
- REST API endpoints for new entities
- Integration testing with mock firehose events
- End-to-end testing with Navigator Workbench

---

## Team Milestones

### DecodingUs (AppView Backend)

| Milestone | Status | Description |
|:----------|:-------|:------------|
| Core Record Schema | ✅ Complete | `biosample`, `sequencerun`, `alignment`, `project`, `workspace` |
| Firehose Event Handlers | ✅ Complete | Full CRUD for all core + new record types |
| Haplogroup Reconciliation | ✅ Complete | Multi-run consensus, conflict resolution, audit trail |
| Genotype Record Schema | ✅ Complete | Multi-test-type support with taxonomy codes |
| Population Breakdown Schema | ✅ Complete | 33 populations, 9 super-populations, PCA coordinates |
| Database Tables | ✅ Complete | `genotype_data`, `population_breakdown`, `haplogroup_reconciliation` |
| Atmosphere Records (Scala) | ✅ Complete | All record types in `AtmosphereRecords.scala` |
| Repositories | ✅ Complete | `GenotypeDataRepository`, `PopulationBreakdownRepository`, `HaplogroupReconciliationRepository` |
| Event Handler Routing | ✅ Complete | `AtmosphereEventHandler` routes all new events |
| REST API Endpoints | 🚧 In Progress | Controllers for new entities |

**Current Focus:** REST API endpoints and integration testing.

---

### Navigator Workbench (Edge App)

| Milestone | Status | Description |
|:----------|:-------|:------------|
| Chip File Parsing | 🚧 In Progress | 23andMe, AncestryDNA, FTDNA, MyHeritage, LivingDNA |
| Haplogroup Calling (Chip) | 🚧 In Progress | Y-DNA and mtDNA from ~3-4K chip markers |
| Ancestry Analysis | 🚧 In Progress | PCA projection + GMM onto 1000G + HGDP reference |
| PDS Sync (Genotype) | 📋 Planned | Sync genotype metadata to user's PDS |
| PDS Sync (Ancestry) | 📋 Planned | Sync population breakdown to user's PDS |
| Multi-Run Reconciliation | 📋 Planned | Local reconciliation UI and logic |

**Current Focus:** Multi-test-type genotype parsing and ancestry analysis pipeline.

---

### Nexus (BGS Node)

| Milestone | Status | Description |
|:----------|:-------|:------------|
| WGS Pipeline | ✅ Complete | FASTQ → BAM/CRAM → VCF pipeline |
| Haplogroup Calling (WGS) | ✅ Complete | Full Y-DNA/mtDNA SNP-based calling |
| Biosample Sync | ✅ Complete | Push biosample metadata to DecodingUs |
| Sequence Run Sync | ✅ Complete | Push sequencing metadata to DecodingUs |
| Alignment Metrics Sync | ✅ Complete | Push coverage/quality metrics |
| AT Protocol Integration | 📋 Planned | Direct PDS writes (Phase 3) |

**Current Focus:** Production stability and Phase 2 Kafka integration.

---

## AppView Implementation Status

### Completed (2025-12-09)

**Database Schema (Migrations 37-40):**
- ✅ Migration 37: Reconciliation refs on `specimen_donor`
- ✅ Migration 38: `population_breakdown`, `population_component`, `super_population_summary` tables
- ✅ Migration 39: `genotype_data` table with JSONB metrics consolidation
- ✅ Migration 40: `haplogroup_reconciliation` table with `dna_type` enum

**Domain Models:**
- ✅ `GenotypeData` with `GenotypeMetrics` JSONB wrapper (14 fields, under 22-tuple limit)
- ✅ `PopulationBreakdown`, `PopulationComponent`, `SuperPopulationSummary`
- ✅ `HaplogroupReconciliation` with `ReconciliationStatus` JSONB wrapper
- ✅ `DnaType` enum (Y_DNA, MT_DNA)

**DAL Tables (Slick):**
- ✅ `GenotypeDataTable` with nested tuple projection
- ✅ `PopulationBreakdownTable`, `PopulationComponentTable`, `SuperPopulationSummaryTable`
- ✅ `HaplogroupReconciliationTable` with JSONB column mappers
- ✅ Slick 22-tuple limit addressed via JSONB consolidation

**Repositories:**
- ✅ `GenotypeDataRepository` - full CRUD, AT URI upsert
- ✅ `PopulationBreakdownRepository` - CRUD + component/summary management
- ✅ `HaplogroupReconciliationRepository` - CRUD + donor/DNA type uniqueness

**Event Handlers (`AtmosphereEventHandler.scala`):**
- ✅ `handleGenotype` - Create, Update, Delete
- ✅ `handlePopulationBreakdown` - Create, Update, Delete with components/summaries
- ✅ `handleHaplogroupReconciliation` - Create, Update, Delete with status mapping

**Atmosphere Records (`AtmosphereRecords.scala`):**
- ✅ `PopulationComponent` with `superPopulation`, `rank`, `confidenceInterval`
- ✅ `SuperPopulationSummary` with continental aggregation
- ✅ `PopulationBreakdownRecord` with full field set
- ✅ `GenotypeRecord` with multi-test-type support
- ✅ `HaplogroupReconciliationRecord` with all supporting types
- ✅ `ReconciliationStatus`, `RunHaplogroupCall`, `StrHaplogroupPrediction`
- ✅ `SnpConflict`, `HeteroplasmyObservation`, `IdentityVerification`

### Pending

**REST API Endpoints:**
- 📋 `GenotypeDataController` - CRUD endpoints
- 📋 `PopulationBreakdownController` - CRUD + components
- 📋 `HaplogroupReconciliationController` - CRUD + status queries

**Testing:**
- 📋 Repository unit tests
- 📋 Event handler integration tests
- 📋 End-to-end firehose event tests

---

## Record Status Overview

| Record Type | Schema | DAL | Repository | Handler | API | Notes |
|:------------|:-------|:----|:-----------|:--------|:----|:------|
| `biosample` | ✅ | ✅ | ✅ | ✅ | ✅ | Core record |
| `sequencerun` | ✅ | ✅ | ✅ | ✅ | ✅ | Core record |
| `alignment` | ✅ | ✅ | ✅ | ✅ | ✅ | Core record |
| `project` | ✅ | ✅ | ✅ | ✅ | ✅ | Core record |
| `genotype` | ✅ | ✅ | ✅ | ✅ | 📋 | Multi-test-type |
| `populationBreakdown` | ✅ | ✅ | ✅ | ✅ | 📋 | PCA + GMM |
| `haplogroupReconciliation` | ✅ | ✅ | ✅ | ✅ | 📋 | Multi-run consensus |
| `strProfile` | ✅ | 📋 | 📋 | 📋 | 📋 | Future scope |
| `matchConsent` | ✅ | 📋 | 📋 | 📋 | 📋 | Future scope |
| `matchList` | ✅ | 📋 | 📋 | 📋 | 📋 | Future scope |
| `instrumentObservation` | ✅ | 📋 | 📋 | 📋 | 📋 | Future scope |
| `imputation` | ✅ | 📋 | 📋 | 📋 | 📋 | Future scope |

---

## Integration Phases

### Phase 1: MVP (Current)
- BGS Node → REST API → DecodingUs
- Subset of Lexicon (biosample, sequencerun, alignment)
- No PDS integration yet

### Phase 2: Hybrid (Kafka)
- BGS Node → Kafka → DecodingUs
- Navigator → Kafka → DecodingUs
- Expanded record types (genotype, populationBreakdown, reconciliation)

### Phase 3: Full Atmosphere (AppView)
- All clients write directly to user's PDS
- DecodingUs subscribes to AT Protocol Firehose
- Full record compliance with this Lexicon

---

## Key Schema Changes (v1.5 - v1.8)

| Version | Date | Changes |
|:--------|:-----|:--------|
| 1.5 | 2025-12-08 | Multi-run reconciliation (`haplogroupReconciliation`), reconciliation definitions |
| 1.6 | 2025-12-08 | Enhanced ancestry: 33 populations, 9 super-populations, `superPopulationSummary`, `pcaCoordinates` |
| 1.7 | 2025-12-08 | Multi-test-type: `testTypeCode` taxonomy, detailed marker statistics, derived haplogroups |
| 1.8 | 2025-12-09 | AppView implementation complete: DAL, repositories, event handlers |

---

## Reference Documents

| Document | Location | Purpose |
|:---------|:---------|:--------|
| Atmosphere Lexicon | `documents/atmosphere/` | Full schema specification |
| Multi-Test-Type Roadmap | `documents/multi-test-type-roadmap.md` | Genotype support planning |
| Ancestry Analysis | `documents/AncestryAnalysis.md` | PCA/GMM algorithm details |
| Multi-Run Reconciliation | `documents/MultiRunReconciliation.md` | Haplogroup consensus planning |
| IBD Matching System | `documents/ibd-matching-system.md` | Match system planning |
| Edge Client Status | `documents/Edge_Client_Implementation_Status.md` | Navigator implementation tracking |

---

## Contact

- **DecodingUs Backend:** [Backend Team]
- **Navigator Workbench:** [Navigator Team]
- **Nexus BGS Node:** [Nexus Team]
