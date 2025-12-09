# Atmosphere Lexicon - Executive Summary

Current status and milestones for the DecodingUs Atmosphere Lexicon implementation across all teams.

**Last Updated:** 2025-12-09

---

## Overview

The Atmosphere Lexicon defines decentralized, user-owned genomic records for the AT Protocol (Bluesky) ecosystem. This enables citizens to own their genomic data in Personal Data Stores (PDS) while DecodingUs operates as an AppView for network-wide aggregation and analysis.

**Core Principle:** Raw genomic data (BAM, CRAM, VCF, FASTQ, genotype files) **never** leaves the user's device. All analysis is performed locally in Navigator Workbench. Only computed summaries and metadata flow through the PDS to DecodingUs.

---

## MVP Status

### **AppView MVP: SHIPPABLE**

**Status Date:** 2025-12-09

The DecodingUs AppView backend has reached MVP status for Phases 1 and 2. All core record types are fully implemented with event handlers ready to process inbound firehose events.

| Component | Status | Notes |
|:----------|:-------|:------|
| Lexicon Schema Definitions | ✅ Complete | All record types defined in v1.8 |
| Database Migrations | ✅ Complete | Migrations 37-40 applied |
| Domain Models (Scala) | ✅ Complete | JSONB consolidation for Slick 22-tuple limit |
| DAL Tables (Slick) | ✅ Complete | All tables with nested projections |
| Repositories | ✅ Complete | Full CRUD for all MVP entities |
| Event Handlers | ✅ Complete | All core + extended record types |
| Firehose Controller | ✅ Complete | JSON discriminator-based routing |

### MVP Scope

**Included:**
- Inbound event processing (Create/Update/Delete) for all core record types
- Full data persistence with AT URI/CID tracking
- Optimistic locking and conflict detection
- Soft deletes with orphan handling

**Post-MVP (Backlog):**
- REST API query endpoints (back-flow channels)
- Integration test suite expansion
- Phase 3 AT Protocol Firehose subscription

---

## Record Implementation Status

| Record Type | Schema | DAL | Repository | Handler | Status |
|:------------|:-------|:----|:-----------|:--------|:-------|
| `biosample` | ✅ | ✅ | ✅ | ✅ | **MVP** |
| `sequencerun` | ✅ | ✅ | ✅ | ✅ | **MVP** |
| `alignment` | ✅ | ✅ | ✅ | ✅ | **MVP** |
| `project` | ✅ | ✅ | ✅ | ✅ | **MVP** |
| `genotype` | ✅ | ✅ | ✅ | ✅ | **MVP** |
| `populationBreakdown` | ✅ | ✅ | ✅ | ✅ | **MVP** |
| `haplogroupReconciliation` | ✅ | ✅ | ✅ | ✅ | **MVP** |
| `strProfile` | ✅ | 📋 | 📋 | 📋 | Future |
| `matchConsent` | ✅ | 📋 | 📋 | 📋 | Future |
| `matchList` | ✅ | 📋 | 📋 | 📋 | Future |
| `instrumentObservation` | ✅ | 📋 | 📋 | 📋 | Future |
| `imputation` | ✅ | 📋 | 📋 | 📋 | Future |

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
| Repositories | ✅ Complete | Full CRUD for all MVP entities |
| Event Handler Routing | ✅ Complete | `AtmosphereEventHandler` routes all events |
| **MVP Release** | ✅ **SHIPPABLE** | Ready for Phase 1/2 integration |

**Next Focus:** Integration testing, then Phase 3 AT Protocol Firehose subscription.

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

## Integration Phases

### Phase 1: MVP (Current) - READY

- BGS Node → REST API → DecodingUs
- Navigator → REST API → DecodingUs
- Full Lexicon support for core records
- No PDS integration yet

### Phase 2: Hybrid (Kafka) - READY

- BGS Node → Kafka → DecodingUs
- Navigator → Kafka → DecodingUs
- Same event handler infrastructure
- Expanded record types (genotype, populationBreakdown, reconciliation)

### Phase 3: Full Atmosphere (AppView) - Planned

- All clients write directly to user's PDS
- DecodingUs subscribes to AT Protocol Firehose
- Full record compliance with this Lexicon
- Requires Bluesky relay infrastructure integration

---

## Key Schema Versions

| Version | Date | Changes |
|:--------|:-----|:--------|
| 1.5 | 2025-12-08 | Multi-run reconciliation (`haplogroupReconciliation`) |
| 1.6 | 2025-12-08 | Enhanced ancestry: 33 populations, 9 super-populations |
| 1.7 | 2025-12-08 | Multi-test-type: `testTypeCode` taxonomy |
| 1.8 | 2025-12-09 | AppView implementation complete |
| **1.9** | **2025-12-09** | **MVP marked shippable** |

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
