# Atmosphere Lexicon - Executive Summary

Current status and milestones for the DecodingUs Atmosphere Lexicon implementation across all teams.

**Last Updated:** 2025-12-09

---

## Overview

The Atmosphere Lexicon defines decentralized, user-owned genomic records for the AT Protocol (Bluesky) ecosystem. This enables citizens to own their genomic data in Personal Data Stores (PDS) while DecodingUs operates as an AppView for network-wide aggregation and analysis.

**Core Principle:** Raw genomic data (BAM, CRAM, VCF, FASTQ, genotype files) **never** leaves the user's device. All analysis is performed locally in Navigator Workbench. Only computed summaries and metadata flow through the PDS to DecodingUs.

---

## Team Milestones

### DecodingUs (AppView Backend)

| Milestone | Status | Description |
|:----------|:-------|:------------|
| Core Record Schema | ✅ Complete | `biosample`, `sequencerun`, `alignment`, `project`, `workspace` |
| Firehose Consumer | 🚧 In Progress | AT Protocol event stream consumer |
| Haplogroup Reconciliation | ✅ Complete | Multi-run consensus algorithm, conflict resolution |
| Genotype Record Schema | ✅ Complete | Multi-test-type support with taxonomy codes |
| Population Breakdown Schema | ✅ Complete | 33 populations, 9 super-populations, PCA coordinates |
| Database Tables | 📋 Planned | `genotype_data`, `ancestry_analysis` table enhancements |

**Current Focus:** Preparing database schema for genotype and ancestry data from Navigator.

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

**Implementation Notes (per v1.7 changelog):**
- Auto-detection of vendor format from file headers
- Parsing genotype calls (stays local)
- Summary statistics computation (marker counts, call rates)
- Y/mtDNA marker extraction for haplogroup analysis
- Ancestry analysis using autosomal markers
- Metadata sync to PDS (genotypes stay local)

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

## Record Status Overview

| Record Type | DecodingUs | Navigator | Nexus | Notes |
|:------------|:-----------|:----------|:------|:------|
| `biosample` | ✅ | ✅ | ✅ | Core record |
| `sequencerun` | ✅ | ✅ | ✅ | Core record |
| `alignment` | ✅ | ✅ | ✅ | Core record |
| `genotype` | ✅ Schema | 🚧 Implementing | N/A | Multi-test-type |
| `populationBreakdown` | ✅ Schema | 🚧 Implementing | N/A | PCA + GMM |
| `haplogroupReconciliation` | ✅ Schema | 📋 Planned | N/A | Multi-run consensus |
| `strProfile` | ✅ Schema | 📋 Planned | 📋 Planned | Y-STR markers |
| `matchConsent` | ✅ Schema | 📋 Planned | N/A | IBD matching |
| `matchList` | ✅ Schema | 📋 Planned | N/A | IBD results |
| `instrumentObservation` | ✅ Schema | 📋 Planned | 📋 Planned | Lab discovery |

---

## Integration Phases

### Phase 1: MVP (Current)
- BGS Node → REST API → DecodingUs
- Subset of Lexicon (biosample, sequencerun, alignment)
- No PDS integration yet

### Phase 2: Hybrid (Kafka)
- BGS Node → Kafka → DecodingUs
- Navigator → Kafka → DecodingUs
- Expanded record types (genotype, populationBreakdown)

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
| 1.8 | 2025-12-08 | Edge Client Implementation Status reference |

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
