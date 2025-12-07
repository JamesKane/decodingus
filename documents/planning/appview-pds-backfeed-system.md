# AppView-to-PDS Backfeed System

## Executive Summary

This document describes the **backfeed methodology** for keeping researcher/citizen PDS records synchronized with computed and curated data from the DecodingUs AppView. When DecodingUs refines haplogroup assignments, discovers new branches, identifies potential matches, or updates any derived data, these changes must flow back to the user's PDS so they always have the most current metadata.

---

## Problem Statement

The current Atmosphere architecture is primarily **unidirectional**:

```
Researcher/Citizen PDS → Firehose → DecodingUs AppView → Database
```

However, DecodingUs performs significant post-ingestion processing on **metadata only**:

1. **Haplogroup Refinement**: Tree updates may refine `R-L21` to `R-L21>FT54321`
2. **Branch Discovery**: Private variants may be promoted to official branches
3. **Ancestral STR Reconstruction**: Compute modal STR haplotypes for tree branches using submitted STR profiles
4. **TMRCA Estimation**: Age estimates computed from STR variance across the network
5. **Potential Match Discovery**: Identify potential genetic matches across the network for user exploration
6. **Confirmed Match Stamping**: Record when both parties agree on a match result
7. **Lab Inference**: Sequencer instrument-to-lab mappings from metadata

### Edge Computing Model

**Critical Architecture Principle**: Raw genomic data (BAM/CRAM/VCF/genotype files) **never** flows to DecodingUs. All raw data analysis happens locally in the Navigator Workbench:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     EDGE COMPUTING ARCHITECTURE                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    Navigator Workbench (Edge)                    │    │
│  │                                                                  │    │
│  │  Raw Data Analysis (LOCAL ONLY - never transmitted):            │    │
│  │  • BAM/CRAM alignment and coverage metrics                      │    │
│  │  • Variant calling from sequence data                           │    │
│  │  • Haplogroup determination (Y-DNA, mtDNA)                      │    │
│  │  • STR extraction from WGS                                      │    │
│  │  • Ancestry composition / admixture analysis                    │    │
│  │  • IBD segment detection (autosomal)                            │    │
│  │                                                                  │    │
│  │  Output → Summary metadata synced to PDS                        │    │
│  │                                                                  │    │
│  └──────────────────────────────┬───────────────────────────────────┘    │
│                                 │                                        │
│                                 ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    User's PDS (Metadata Only)                    │    │
│  │                                                                  │    │
│  │  • biosample (haplogroup assignments, coverage stats)           │    │
│  │  • strProfile (STR marker values - needed for tree building)    │    │
│  │  • alignment (metrics summary, not raw alignments)              │    │
│  │  • populationBreakdown (admixture percentages)                  │    │
│  │  • Private Y-DNA/mtDNA SNPs (for branch discovery)              │    │
│  │                                                                  │    │
│  └──────────────────────────────┬───────────────────────────────────┘    │
│                                 │                                        │
│                                 ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    DecodingUs AppView                            │    │
│  │                                                                  │    │
│  │  Aggregation & Network Intelligence:                            │    │
│  │  • Haplogroup tree refinement (from network-wide SNP data)      │    │
│  │  • Ancestral STR reconstruction (from submitted STR profiles)   │    │
│  │  • TMRCA estimation (from STR variance across samples)          │    │
│  │  • Potential match identification (metadata comparison)         │    │
│  │  • Branch discovery consensus (aggregate private variants)       │    │
│  │                                                                  │    │
│  │  NEVER receives: BAM, CRAM, VCF, FASTQ, raw genotype files      │    │
│  │                                                                  │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Data That DOES Flow to DecodingUs (via PDS)

| Data Type | Purpose | Why Needed |
|:---|:---|:---|
| Haplogroup assignments | Tree placement | Network-wide refinement |
| Private Y-DNA SNPs | Branch discovery | Consensus detection for new branches |
| Private mtDNA SNPs | Branch discovery | Consensus detection for new branches |
| STR marker values | Ancestral reconstruction | Modal haplotype & TMRCA calculation |
| Coverage/quality metrics | Sample characterization | Match quality assessment |
| Ancestry percentages | Population context | Computed locally, shared as summary |

### Data That NEVER Flows to DecodingUs

| Data Type | Why Excluded |
|:---|:---|
| BAM/CRAM files | Raw sequence data - analyzed locally |
| VCF files | Full variant calls - only private SNPs shared |
| FASTQ files | Raw reads - never leave the workbench |
| Genotype chip data | Raw calls - ancestry computed locally |
| IBD segments | Sensitive relationship data - only match confirmation shared |

Without backfeed, user PDS records become stale and diverge from the AppView's refined understanding.

### Current Gap

The Atmosphere Lexicon defines records that the AppView **writes to user PDS** (e.g., `matchList`, `haplogroupAncestralStr`), but lacks:

1. A systematic enumeration of all backfeed scenarios
2. New record types for AppView-computed updates
3. Authorization model for AppView writing to user PDS
4. Conflict resolution when local and remote changes collide
5. Notification mechanism for users to see what changed
6. Audit trail for all AppView-initiated updates

---

## Backfeed Categories

### Category 1: AppView-Authored Records

Records created entirely by the AppView and pushed to user PDS. User cannot create these directly.

| Record Type | Trigger | Content |
|:---|:---|:---|
| `potentialMatchList` | Network analysis identifies candidates | List of potential matches for user to explore |
| `confirmedMatch` | Both parties agree on match result | Stamped match record with agreed details |
| `haplogroupAncestralStr` | STR reconstruction runs | Ancestral modal haplotype for haplogroup branch |

**Note**: `populationBreakdown` is computed locally in the Workbench and synced to PDS by the user, NOT authored by AppView.

### Category 2: AppView-Updated Records

Records created by the user (via Workbench) but updated by the AppView when network intelligence provides new information.

| Record Type | Field(s) Updated | Trigger |
|:---|:---|:---|
| `biosample` | `haplogroups.yDna.haplogroupName` | Tree update refines terminal haplogroup |
| `biosample` | `haplogroups.mtDna.haplogroupName` | Tree update refines terminal haplogroup |
| `biosample` | `haplogroups.*.privateVariants` | Private variants reclassified as known branch |
| `biosample` | `haplogroups.*.lineagePath` | Tree restructuring changes ancestry path |

**Note**: `alignment.metrics`, `strProfile`, and `populationBreakdown` are computed locally and NOT updated by AppView.

### Category 3: AppView-Notification Records

New record types to notify users of changes without modifying their source records.

| Record Type | Purpose |
|:---|:---|
| `haplogroupUpdate` | Notify of haplogroup refinement from tree update |
| `branchDiscovery` | Notify that user's private variants became official branch |
| `treeVersionUpdate` | Notify that reference tree version changed (may affect assignments) |

---

## New Lexicon Records for Backfeed

### 1. Haplogroup Update Notification (`com.decodingus.atmosphere.haplogroupUpdate`)

Sent to user's PDS when their biosample's haplogroup assignment changes.

**NSID:** `com.decodingus.atmosphere.haplogroupUpdate`

**Author:** AppView (DecodingUs)

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.haplogroupUpdate",
  "defs": {
    "main": {
      "type": "record",
      "description": "Notification that a biosample's haplogroup assignment has been refined or corrected.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "biosampleRef", "updateType", "lineage", "previous", "current"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this update notification."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the biosample that was updated."
          },
          "updateType": {
            "type": "string",
            "description": "Type of haplogroup update.",
            "knownValues": ["REFINEMENT", "CORRECTION", "BRANCH_DISCOVERY", "TREE_UPDATE", "RECLASSIFICATION"]
          },
          "lineage": {
            "type": "string",
            "description": "Which lineage was updated.",
            "knownValues": ["Y_DNA", "MT_DNA"]
          },
          "previous": {
            "type": "ref",
            "ref": "#haplogroupState",
            "description": "The previous haplogroup assignment."
          },
          "current": {
            "type": "ref",
            "ref": "#haplogroupState",
            "description": "The new haplogroup assignment."
          },
          "reason": {
            "type": "string",
            "description": "Human-readable explanation of why the change occurred."
          },
          "treeVersion": {
            "type": "string",
            "description": "Haplogroup tree version that triggered the update (e.g., 'ISOGG-2025.1')."
          },
          "effectiveAt": {
            "type": "string",
            "format": "datetime",
            "description": "When this update took effect."
          },
          "acknowledgement": {
            "type": "ref",
            "ref": "#updateAcknowledgement",
            "description": "User's acknowledgement of the update (optional)."
          }
        }
      }
    },
    "haplogroupState": {
      "type": "object",
      "description": "Snapshot of a haplogroup assignment at a point in time.",
      "required": ["haplogroupName"],
      "properties": {
        "haplogroupName": {
          "type": "string"
        },
        "score": {
          "type": "float"
        },
        "treeDepth": {
          "type": "integer"
        },
        "lineagePath": {
          "type": "array",
          "items": { "type": "string" }
        }
      }
    },
    "updateAcknowledgement": {
      "type": "object",
      "description": "User's acknowledgement of an update.",
      "properties": {
        "acknowledgedAt": {
          "type": "string",
          "format": "datetime"
        },
        "accepted": {
          "type": "boolean",
          "description": "True if user accepts, false if they dispute."
        },
        "disputeReason": {
          "type": "string",
          "description": "Reason for disputing (if accepted=false)."
        }
      }
    }
  }
}
```

### 2. Branch Discovery Notification (`com.decodingus.atmosphere.branchDiscovery`)

Sent when a user's private variants have been promoted to an official haplogroup branch.

**NSID:** `com.decodingus.atmosphere.branchDiscovery`

**Author:** AppView (DecodingUs)

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.branchDiscovery",
  "defs": {
    "main": {
      "type": "record",
      "description": "Notification that private variants from a biosample have been promoted to an official branch.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "biosampleRef", "newBranchName", "definingVariants", "discoveredAt"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this discovery notification."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the biosample that contributed to the discovery."
          },
          "lineage": {
            "type": "string",
            "description": "Which lineage (Y-DNA or mtDNA).",
            "knownValues": ["Y_DNA", "MT_DNA"]
          },
          "parentBranch": {
            "type": "string",
            "description": "The parent haplogroup from which the new branch descends."
          },
          "newBranchName": {
            "type": "string",
            "description": "Name of the newly discovered branch (e.g., 'R-FT54321')."
          },
          "definingVariants": {
            "type": "array",
            "description": "The variants that define this new branch.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#variantCall"
            }
          },
          "contributingSamples": {
            "type": "integer",
            "description": "Number of biosamples that share these variants."
          },
          "discoveredAt": {
            "type": "string",
            "format": "datetime",
            "description": "When the branch was officially added to the tree."
          },
          "curatorNotes": {
            "type": "string",
            "description": "Optional notes from the curator who approved the branch."
          },
          "citationDoi": {
            "type": "string",
            "description": "DOI of publication if branch was discovered through academic research."
          }
        }
      }
    }
  }
}
```

### 3. Tree Version Update Notification (`com.decodingus.atmosphere.treeVersionUpdate`)

Sent when the haplogroup reference tree is updated, which may affect user's assignments.

**NSID:** `com.decodingus.atmosphere.treeVersionUpdate`

**Author:** AppView (DecodingUs)

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.treeVersionUpdate",
  "defs": {
    "main": {
      "type": "record",
      "description": "Notification that the haplogroup reference tree has been updated.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "lineage", "previousVersion", "newVersion", "effectiveAt"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this tree update notification."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "lineage": {
            "type": "string",
            "description": "Which lineage tree was updated.",
            "knownValues": ["Y_DNA", "MT_DNA"]
          },
          "previousVersion": {
            "type": "string",
            "description": "Previous tree version (e.g., 'ISOGG-2024.12')."
          },
          "newVersion": {
            "type": "string",
            "description": "New tree version (e.g., 'ISOGG-2025.01')."
          },
          "effectiveAt": {
            "type": "string",
            "format": "datetime",
            "description": "When the new tree version became active."
          },
          "affectedBiosamples": {
            "type": "array",
            "description": "List of user's biosamples that may be affected.",
            "items": {
              "type": "ref",
              "ref": "#affectedBiosample"
            }
          },
          "changelogUrl": {
            "type": "string",
            "format": "uri",
            "description": "URL to the tree changelog/release notes."
          },
          "summary": {
            "type": "string",
            "description": "Human-readable summary of changes relevant to user."
          }
        }
      }
    },
    "affectedBiosample": {
      "type": "object",
      "description": "A biosample potentially affected by tree changes.",
      "required": ["biosampleRef", "currentHaplogroup"],
      "properties": {
        "biosampleRef": {
          "type": "string",
          "description": "AT URI of the affected biosample."
        },
        "currentHaplogroup": {
          "type": "string",
          "description": "Current haplogroup assignment."
        },
        "mayChange": {
          "type": "boolean",
          "description": "True if this biosample's assignment may change."
        },
        "suggestedAction": {
          "type": "string",
          "description": "Recommended action (e.g., 'Re-analyze in Workbench').",
          "knownValues": ["NONE", "REVIEW", "REANALYZE"]
        }
      }
    }
  }
}
```

---

## Collaborative Matching Model

Unlike centralized DNA matching services that compute matches server-side, DecodingUs uses a **collaborative discovery** model where:

1. **AppView identifies potential matches** across the network based on shared haplogroups, STR similarity, or other criteria
2. **Users explore candidates** in their Workbench (Navigator), choosing which to investigate
3. **Both parties must agree** on the match result before it's stamped as confirmed
4. **Confirmed matches** are written to both users' PDS as permanent records

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     COLLABORATIVE MATCHING FLOW                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─────────────┐                              ┌─────────────┐           │
│  │  Alice's    │                              │   Bob's     │           │
│  │  Workbench  │                              │  Workbench  │           │
│  └──────┬──────┘                              └──────┬──────┘           │
│         │                                           │                    │
│         │  1. AppView identifies potential match    │                    │
│         │◀─────────────────────────────────────────▶│                    │
│         │     (written to both PDS as candidates)   │                    │
│         │                                           │                    │
│         │  2. Alice explores match in Workbench    │                    │
│         │─────▶ Reviews STR comparison             │                    │
│         │─────▶ Compares haplogroup branches        │                    │
│         │─────▶ Initiates match confirmation       │                    │
│         │                                           │                    │
│         │  3. AppView notifies Bob of request      │                    │
│         │──────────────────────────────────────────▶│                    │
│         │                                           │                    │
│         │  4. Bob reviews and confirms match       │                    │
│         │◀──────────────────────────────────────────│                    │
│         │                                           │                    │
│         │  5. AppView stamps confirmed match       │                    │
│         │◀─────────────────────────────────────────▶│                    │
│         │     (written to BOTH PDS)                 │                    │
│         │                                           │                    │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4. Potential Match List Record (`com.decodingus.atmosphere.potentialMatchList`)

List of potential matches identified by the AppView for user exploration.

**NSID:** `com.decodingus.atmosphere.potentialMatchList`

**Author:** AppView (DecodingUs)

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.potentialMatchList",
  "defs": {
    "main": {
      "type": "record",
      "description": "List of potential genetic matches for user to explore in Workbench.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "biosampleRef", "candidates"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this potential match list."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the biosample these candidates relate to."
          },
          "candidateCount": {
            "type": "integer",
            "description": "Total number of potential matches."
          },
          "lastUpdatedAt": {
            "type": "string",
            "format": "datetime",
            "description": "When candidate list was last refreshed."
          },
          "candidates": {
            "type": "array",
            "description": "List of potential match candidates.",
            "items": {
              "type": "ref",
              "ref": "#matchCandidate"
            }
          }
        }
      }
    },
    "matchCandidate": {
      "type": "object",
      "description": "A potential match candidate for user exploration.",
      "required": ["candidateBiosampleRef", "matchType", "similarity"],
      "properties": {
        "candidateBiosampleRef": {
          "type": "string",
          "description": "AT URI of the potential match's biosample."
        },
        "candidateDid": {
          "type": "string",
          "description": "DID of the potential match (if they consent to visibility)."
        },
        "matchType": {
          "type": "string",
          "description": "Type of potential match.",
          "knownValues": ["Y_STR", "Y_SNP_HAPLOGROUP", "MT_HAPLOGROUP", "AUTOSOMAL_IBD"]
        },
        "similarity": {
          "type": "float",
          "description": "Similarity score (0.0-1.0) for ranking candidates."
        },
        "sharedHaplogroup": {
          "type": "string",
          "description": "Common haplogroup if Y-DNA or mtDNA match."
        },
        "geneticDistance": {
          "type": "integer",
          "description": "STR genetic distance if Y-STR match."
        },
        "estimatedRelationship": {
          "type": "string",
          "description": "Rough relationship estimate based on match type."
        },
        "identifiedAt": {
          "type": "string",
          "format": "datetime",
          "description": "When this candidate was identified."
        },
        "status": {
          "type": "string",
          "description": "Current status of this candidate.",
          "knownValues": ["NEW", "VIEWED", "EXPLORING", "PENDING_CONFIRMATION", "CONFIRMED", "DECLINED"]
        }
      }
    }
  }
}
```

### 5. Confirmed Match Record (`com.decodingus.atmosphere.confirmedMatch`)

A confirmed match stamped by the AppView after both parties agree.

**NSID:** `com.decodingus.atmosphere.confirmedMatch`

**Author:** AppView (DecodingUs)

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.confirmedMatch",
  "defs": {
    "main": {
      "type": "record",
      "description": "A confirmed genetic match agreed upon by both parties.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "biosampleRef", "matchedBiosampleRef", "matchType", "confirmedAt"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this confirmed match record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of this user's biosample."
          },
          "matchedBiosampleRef": {
            "type": "string",
            "description": "AT URI of the matched biosample."
          },
          "matchedCitizenDid": {
            "type": "string",
            "description": "DID of the matched citizen."
          },
          "matchType": {
            "type": "string",
            "description": "Type of confirmed match.",
            "knownValues": ["Y_STR", "Y_SNP_HAPLOGROUP", "MT_HAPLOGROUP", "AUTOSOMAL_IBD"]
          },
          "matchDetails": {
            "type": "ref",
            "ref": "#confirmedMatchDetails",
            "description": "Detailed match information based on match type."
          },
          "confirmedAt": {
            "type": "string",
            "format": "datetime",
            "description": "When both parties confirmed the match."
          },
          "initiatedBy": {
            "type": "string",
            "description": "DID of the party who initiated confirmation."
          },
          "confirmedBy": {
            "type": "string",
            "description": "DID of the party who accepted confirmation."
          },
          "notes": {
            "type": "string",
            "description": "Optional notes about the match relationship."
          }
        }
      }
    },
    "confirmedMatchDetails": {
      "type": "object",
      "description": "Detailed match metrics based on match type.",
      "properties": {
        "sharedHaplogroup": {
          "type": "string",
          "description": "Common haplogroup (Y-DNA or mtDNA matches)."
        },
        "geneticDistance": {
          "type": "integer",
          "description": "STR genetic distance (Y-STR matches)."
        },
        "tmrcaEstimate": {
          "type": "object",
          "description": "Estimated time to most recent common ancestor.",
          "properties": {
            "generations": { "type": "integer" },
            "yearsBeforePresent": { "type": "integer" },
            "confidenceInterval": {
              "type": "object",
              "properties": {
                "lower": { "type": "integer" },
                "upper": { "type": "integer" }
              }
            }
          }
        },
        "sharedCm": {
          "type": "float",
          "description": "Total shared centiMorgans (autosomal IBD matches)."
        },
        "segmentCount": {
          "type": "integer",
          "description": "Number of shared segments (autosomal IBD matches)."
        },
        "relationshipEstimate": {
          "type": "string",
          "description": "Estimated relationship based on match data.",
          "knownValues": ["PARENT_CHILD", "SIBLING", "GRANDPARENT", "AUNT_UNCLE", "1ST_COUSIN",
                         "2ND_COUSIN", "3RD_COUSIN", "4TH_COUSIN", "DISTANT", "UNKNOWN"]
        }
      }
    }
  }
}
```

### 6. Sync Status Record (`com.decodingus.atmosphere.syncStatus`)

A record in the user's PDS tracking the sync state with the AppView.

**NSID:** `com.decodingus.atmosphere.syncStatus`

**Author:** AppView (DecodingUs)

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.syncStatus",
  "defs": {
    "main": {
      "type": "record",
      "description": "Tracks synchronization status between user's PDS and the AppView.",
      "key": "literal:self",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "lastSyncAt", "appViewVersion"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this sync status record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "lastSyncAt": {
            "type": "string",
            "format": "datetime",
            "description": "Last successful sync with AppView."
          },
          "appViewVersion": {
            "type": "string",
            "description": "Version of the DecodingUs AppView."
          },
          "treeVersions": {
            "type": "object",
            "description": "Current haplogroup tree versions used.",
            "properties": {
              "yDna": { "type": "string" },
              "mtDna": { "type": "string" }
            }
          },
          "pendingUpdates": {
            "type": "integer",
            "description": "Number of pending updates to be applied."
          },
          "unacknowledgedNotifications": {
            "type": "integer",
            "description": "Number of notifications user hasn't acknowledged."
          },
          "biosampleSyncStates": {
            "type": "array",
            "description": "Per-biosample sync status.",
            "items": {
              "type": "ref",
              "ref": "#biosampleSyncState"
            }
          }
        }
      }
    },
    "biosampleSyncState": {
      "type": "object",
      "description": "Sync state for a single biosample.",
      "required": ["biosampleRef", "status"],
      "properties": {
        "biosampleRef": {
          "type": "string",
          "description": "AT URI of the biosample."
        },
        "status": {
          "type": "string",
          "description": "Current sync status.",
          "knownValues": ["SYNCED", "PENDING_UPDATE", "UPDATE_AVAILABLE", "CONFLICT", "ERROR"]
        },
        "lastUpdatedAt": {
          "type": "string",
          "format": "datetime"
        },
        "pendingFields": {
          "type": "array",
          "description": "Fields with pending updates.",
          "items": { "type": "string" }
        }
      }
    }
  }
}
```

---

## Backfeed Authorization Model

### AppView Service Account

The DecodingUs AppView operates as a service account with delegated write access to user PDS records.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Authorization Flow                                │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  1. User authenticates with Navigator or Web UI                         │
│  2. User grants "AppView Write" scope to DecodingUs                     │
│  3. DecodingUs receives delegated credential (DPoP-bound access token)  │
│  4. AppView uses credential to write backfeed records to user's PDS    │
│                                                                          │
│  ┌─────────────────────┐                                                │
│  │  User PDS           │                                                │
│  │                     │                                                │
│  │  Scopes granted to  │                                                │
│  │  DecodingUs:        │                                                │
│  │                     │                                                │
│  │  ✓ read:biosample   │   (read user's biosamples)                     │
│  │  ✓ write:potentialMatches │ (write potential match candidates)       │
│  │  ✓ write:confirmedMatch   │ (stamp confirmed matches)                │
│  │  ✓ write:update     │   (write update notifications)                 │
│  │  ✓ update:biosample │   (update haplogroup fields)                   │
│  │                     │                                                │
│  └─────────────────────┘                                                │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Scope Definitions

| Scope | Allows |
|:---|:---|
| `com.decodingus.atmosphere:read` | Read all Atmosphere records |
| `com.decodingus.atmosphere:write:potentialMatches` | Create/update potential match candidate lists |
| `com.decodingus.atmosphere:write:confirmedMatch` | Stamp confirmed matches when both parties agree |
| `com.decodingus.atmosphere:write:notification` | Create notification records (updates, discoveries) |
| `com.decodingus.atmosphere:update:biosample` | Update specific fields on biosample records |
| `com.decodingus.atmosphere:write:syncStatus` | Maintain sync status record |

### Consent Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│  AppView Consent Dialog (shown in Navigator or Web)                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  DecodingUs AppView is requesting access to your PDS:                  │
│                                                                         │
│  ┌─ Requested Permissions ────────────────────────────────────────────┐ │
│  │                                                                     │ │
│  │  ☑ Read your biosample records                                     │ │
│  │    Allow DecodingUs to read your genomic metadata                  │ │
│  │                                                                     │ │
│  │  ☑ Update your haplogroup assignments                              │ │
│  │    Automatically apply refined haplogroups when tree updates       │ │
│  │                                                                     │ │
│  │  ☑ Write potential match candidates                                │ │
│  │    Notify you of potential genetic matches to explore              │ │
│  │                                                                     │ │
│  │  ☑ Stamp confirmed matches                                         │ │
│  │    Record matches when both you and your match agree               │ │
│  │                                                                     │ │
│  │  ☑ Send update notifications                                       │ │
│  │    Notify you when your data is updated                            │ │
│  │                                                                     │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  [Grant Access]                                            [Deny]       │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Backfeed Processing Pipeline

### Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     DecodingUs Backend (AppView)                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    Event Sources                                 │    │
│  ├──────────────────┬──────────────────┬───────────────────────────┤    │
│  │ Tree Update Job  │ Match Discovery  │ Analysis Pipeline         │    │
│  │ (scheduled)      │ (network scan)   │ (on file upload)          │    │
│  └────────┬─────────┴────────┬─────────┴─────────────┬─────────────┘    │
│           │                  │                       │                   │
│           ▼                  ▼                       ▼                   │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    Backfeed Event Queue                          │    │
│  │  (Kafka topic: decodingus.backfeed.events)                       │    │
│  └──────────────────────────────┬──────────────────────────────────┘    │
│                                 │                                        │
│                                 ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    Backfeed Processor Service                    │    │
│  │                                                                  │    │
│  │  1. Retrieve user's delegated credential                        │    │
│  │  2. Build appropriate Lexicon record                            │    │
│  │  3. Write record to user's PDS                                  │    │
│  │  4. Update local sync tracking                                   │    │
│  │  5. Handle failures with retry                                  │    │
│  │                                                                  │    │
│  └──────────────────────────────┬──────────────────────────────────┘    │
│                                 │                                        │
│                                 ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    PDS Write Client                              │    │
│  │  (AT Protocol XRPC: com.atproto.repo.createRecord/putRecord)    │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
                    ┌──────────────────────────┐
                    │  User's PDS              │
                    │  - Updated records       │
                    │  - New notifications     │
                    │  - Sync status           │
                    └──────────────────────────┘
```

### Event Types

```scala
sealed trait BackfeedEvent {
  def citizenDid: String
  def biosampleAtUri: String
  def priority: BackfeedPriority
}

case class HaplogroupRefinementEvent(
  citizenDid: String,
  biosampleAtUri: String,
  lineage: Lineage,
  previousHaplogroup: String,
  newHaplogroup: String,
  reason: HaplogroupUpdateReason,
  treeVersion: String,
  priority: BackfeedPriority = BackfeedPriority.Normal
) extends BackfeedEvent

case class BranchDiscoveryEvent(
  citizenDid: String,
  biosampleAtUri: String,
  lineage: Lineage,
  newBranchName: String,
  parentBranch: String,
  definingVariantIds: Seq[Int],
  contributingSampleCount: Int,
  priority: BackfeedPriority = BackfeedPriority.High
) extends BackfeedEvent

case class PotentialMatchesEvent(
  citizenDid: String,
  biosampleAtUri: String,
  candidateCount: Int,
  newCandidates: Int,
  removedCandidates: Int,
  priority: BackfeedPriority = BackfeedPriority.Normal
) extends BackfeedEvent

case class ConfirmedMatchEvent(
  citizenDid: String,
  biosampleAtUri: String,
  matchedCitizenDid: String,
  matchedBiosampleAtUri: String,
  sharedCm: Float,
  segmentCount: Int,
  confirmedAt: Instant,
  priority: BackfeedPriority = BackfeedPriority.High
) extends BackfeedEvent

case class AnalysisCompleteEvent(
  citizenDid: String,
  biosampleAtUri: String,
  analysisType: AnalysisType,
  updatedRecordAtUri: String,
  pipelineVersion: String,
  priority: BackfeedPriority = BackfeedPriority.Low
) extends BackfeedEvent

enum BackfeedPriority:
  case High    // Branch discovery, major haplogroup change, confirmed match
  case Normal  // Regular updates, potential matches
  case Low     // Analysis reruns, minor updates

enum HaplogroupUpdateReason:
  case TreeUpdate       // Reference tree was updated
  case BranchDiscovery  // New branch added from consensus
  case Correction       // Manual curator correction
  case Reclassification // Nomenclature change
  case RefinedAnalysis  // Better analysis with same data
```

### Processing Logic

```scala
class BackfeedProcessorService(
  pdsClient: PdsWriteClient,
  credentialStore: DelegatedCredentialStore,
  syncTracker: SyncTracker
) {

  def processEvent(event: BackfeedEvent): Future[BackfeedResult] = {
    for {
      // 1. Get user's delegated credential
      credential <- credentialStore.getCredential(event.citizenDid)
        .flatMap {
          case Some(cred) if cred.isValid => Future.successful(cred)
          case Some(cred) => refreshCredential(cred)
          case None => Future.failed(NoCredentialException(event.citizenDid))
        }

      // 2. Build the appropriate record(s)
      records <- buildRecords(event)

      // 3. Write to user's PDS
      results <- Future.traverse(records) { record =>
        pdsClient.writeRecord(
          credential = credential,
          collection = record.collection,
          record = record.data,
          rkey = record.rkey
        )
      }

      // 4. Update local sync tracking
      _ <- syncTracker.recordBackfeed(event, results)

      // 5. Update user's syncStatus record
      _ <- updateSyncStatus(credential, event.citizenDid)

    } yield BackfeedResult.Success(results.map(_.atUri))
  }

  private def buildRecords(event: BackfeedEvent): Future[Seq[BackfeedRecord]] = {
    event match {
      case e: HaplogroupRefinementEvent =>
        for {
          // Create notification record
          notification <- buildHaplogroupUpdateNotification(e)
          // Optionally update biosample directly if user consented
          biosampleUpdate <- if (autoUpdateEnabled(e.citizenDid)) {
            buildBiosampleHaplogroupUpdate(e).map(Some(_))
          } else Future.successful(None)
        } yield Seq(notification) ++ biosampleUpdate.toSeq

      case e: BranchDiscoveryEvent =>
        buildBranchDiscoveryNotification(e).map(Seq(_))

      case e: PotentialMatchesEvent =>
        buildPotentialMatchesRecord(e).map(Seq(_))

      case e: ConfirmedMatchEvent =>
        // Stamp confirmed match in BOTH parties' PDS
        for {
          record1 <- buildConfirmedMatchRecord(e, e.citizenDid)
          record2 <- buildConfirmedMatchRecord(e, e.matchedCitizenDid)
        } yield Seq(record1, record2)

      case e: AnalysisCompleteEvent =>
        buildAnalysisUpdateNotification(e).map(Seq(_))
    }
  }
}
```

---

## Conflict Resolution

### Scenario: Local and Remote Changes

When Navigator syncs a locally-modified biosample that the AppView also updated:

```
Timeline:
─────────────────────────────────────────────────────────────────────────
  t1: User syncs biosample with haplogroup R-L21 (atCid: abc123)
  t2: AppView refines to R-L21>FT54321, writes to PDS (atCid: def456)
  t3: User edits description locally (still has atCid: abc123)
  t4: User attempts sync → CONFLICT (atCid mismatch)
─────────────────────────────────────────────────────────────────────────
```

### Resolution Strategy

```scala
enum ConflictResolutionStrategy:
  case AppViewWins     // AppView-computed fields always win
  case UserWins        // User's local changes always win
  case FieldLevel      // Merge at field level
  case Manual          // Require user decision

val fieldResolutionRules: Map[String, ConflictResolutionStrategy] = Map(
  // AppView-computed fields - AppView always wins
  "haplogroups.yDna.haplogroupName" -> ConflictResolutionStrategy.AppViewWins,
  "haplogroups.yDna.score" -> ConflictResolutionStrategy.AppViewWins,
  "haplogroups.yDna.lineagePath" -> ConflictResolutionStrategy.AppViewWins,
  "haplogroups.mtDna.haplogroupName" -> ConflictResolutionStrategy.AppViewWins,
  "haplogroups.mtDna.score" -> ConflictResolutionStrategy.AppViewWins,
  "haplogroups.mtDna.lineagePath" -> ConflictResolutionStrategy.AppViewWins,

  // User-editable fields - User wins
  "description" -> ConflictResolutionStrategy.UserWins,
  "alias" -> ConflictResolutionStrategy.UserWins,
  "donorIdentifier" -> ConflictResolutionStrategy.UserWins,

  // Complex fields - Manual resolution
  "haplogroups.yDna.privateVariants" -> ConflictResolutionStrategy.Manual
)
```

### Navigator Conflict UI

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Sync Conflict Detected                                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Biosample: VIK-003                                                    │
│                                                                         │
│  Your local version and the AppView version have both changed.         │
│                                                                         │
│  ┌─ Automatic Resolution ─────────────────────────────────────────────┐ │
│  │                                                                     │ │
│  │  ✓ Y-DNA Haplogroup: Using AppView value                          │ │
│  │    Local: R-L21     AppView: R-L21>FT54321                        │ │
│  │    (AppView-computed fields always use latest refinement)          │ │
│  │                                                                     │ │
│  │  ✓ Description: Using your local value                            │ │
│  │    Local: "Updated analysis notes"                                 │ │
│  │    AppView: "Deep WGS of Proband"                                  │ │
│  │    (User-editable fields preserve your changes)                    │ │
│  │                                                                     │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  ┌─ Requires Your Decision ───────────────────────────────────────────┐ │
│  │                                                                     │ │
│  │  ⚠ Private Variants                                                │ │
│  │                                                                     │ │
│  │  Local version has 5 private variants                              │ │
│  │  AppView version has 3 (2 were promoted to R-L21>FT54321)         │ │
│  │                                                                     │ │
│  │  ( ) Keep my 5 private variants                                    │ │
│  │  (•) Accept AppView's 3 (2 are now part of official branch)       │ │
│  │  ( ) Review each variant individually                              │ │
│  │                                                                     │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  [Apply Resolution]                                           [Cancel]  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Notification Aggregation

To avoid overwhelming users with individual notifications, the AppView aggregates updates:

### Daily Digest Record (`com.decodingus.atmosphere.updateDigest`)

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.updateDigest",
  "defs": {
    "main": {
      "type": "record",
      "description": "Daily digest of all updates for a user's biosamples.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "periodStart", "periodEnd", "summary"],
        "properties": {
          "atUri": { "type": "string" },
          "meta": { "type": "ref", "ref": "com.decodingus.atmosphere.defs#recordMeta" },
          "periodStart": { "type": "string", "format": "datetime" },
          "periodEnd": { "type": "string", "format": "datetime" },
          "summary": {
            "type": "object",
            "properties": {
              "haplogroupUpdates": { "type": "integer" },
              "branchDiscoveries": { "type": "integer" },
              "newPotentialMatches": { "type": "integer" },
              "confirmedMatches": { "type": "integer" },
              "analysisUpdates": { "type": "integer" }
            }
          },
          "updateRefs": {
            "type": "array",
            "description": "AT URIs of individual update notifications.",
            "items": { "type": "string" }
          },
          "highlights": {
            "type": "array",
            "description": "Most significant updates to call out.",
            "items": { "type": "ref", "ref": "#digestHighlight" }
          }
        }
      }
    },
    "digestHighlight": {
      "type": "object",
      "properties": {
        "type": { "type": "string", "knownValues": ["BRANCH_DISCOVERY", "CONFIRMED_MATCH", "HAPLOGROUP_REFINEMENT", "NEW_POTENTIAL_MATCH"] },
        "biosampleRef": { "type": "string" },
        "message": { "type": "string" }
      }
    }
  }
}
```

---

## Implementation Phases

### Phase 1: Notification Infrastructure
- Implement `haplogroupUpdate` and `branchDiscovery` notification records
- Build backfeed event queue and processor
- Establish delegated credential storage and management
- Create basic Navigator UI for viewing notifications

### Phase 2: Collaborative Matching
- Implement `potentialMatchList` record for match candidates
- Add `confirmedMatch` record stamping when both parties agree
- Build match exploration UI in Navigator Workbench
- Implement match confirmation workflow

### Phase 3: Direct Record Updates
- Implement `biosample.haplogroups` field updates with user consent
- Implement conflict resolution logic
- Extend Navigator sync to handle AppView-modified records

### Phase 4: Full Sync Loop
- Add `syncStatus` record management
- Implement `updateDigest` for daily summaries
- Build comprehensive Navigator sync dashboard
- Add push notifications (optional)

### Phase 5: Advanced Features
- Real-time WebSocket updates for immediate notification
- Selective sync (user can pause certain update types)
- Audit log accessible to users
- Dispute workflow for incorrect haplogroup assignments

---

## Database Schema Additions

```sql
-- Track delegated credentials for PDS write access
CREATE TABLE pds_delegated_credential (
  id SERIAL PRIMARY KEY,
  citizen_did TEXT NOT NULL UNIQUE,
  access_token TEXT NOT NULL,
  refresh_token TEXT,
  token_type TEXT DEFAULT 'DPoP',
  scopes TEXT[] NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW()
);

-- Track backfeed events sent to user PDS
CREATE TABLE backfeed_event_log (
  id SERIAL PRIMARY KEY,
  citizen_did TEXT NOT NULL,
  biosample_at_uri TEXT NOT NULL,
  event_type TEXT NOT NULL,
  event_payload JSONB NOT NULL,
  record_at_uri TEXT,
  status TEXT DEFAULT 'PENDING',  -- PENDING, SENT, FAILED, RETRYING
  attempts INT DEFAULT 0,
  last_attempt_at TIMESTAMP,
  error_message TEXT,
  created_at TIMESTAMP DEFAULT NOW(),
  sent_at TIMESTAMP
);

CREATE INDEX idx_backfeed_event_status ON backfeed_event_log(status);
CREATE INDEX idx_backfeed_event_citizen ON backfeed_event_log(citizen_did);

-- Track sync state per biosample
CREATE TABLE biosample_sync_state (
  id SERIAL PRIMARY KEY,
  citizen_did TEXT NOT NULL,
  biosample_at_uri TEXT NOT NULL,
  local_at_cid TEXT,
  appview_at_cid TEXT,
  last_user_update TIMESTAMP,
  last_appview_update TIMESTAMP,
  sync_status TEXT DEFAULT 'SYNCED',  -- SYNCED, PENDING_USER, PENDING_APPVIEW, CONFLICT
  pending_fields TEXT[],
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),

  UNIQUE(citizen_did, biosample_at_uri)
);
```

---

## Open Questions

1. **Notification Retention**: How long should notification records be kept in user's PDS?

2. **Credential Refresh**: How to handle expired credentials when user hasn't connected Navigator in months?

3. **Rate Limiting**: What limits should apply to AppView writes to prevent abuse?

4. **User Preferences**: Should users be able to opt out of specific update types?

5. **Offline Users**: How to queue updates for users whose PDS is temporarily unreachable?

6. **Multi-AppView**: If user grants access to multiple AppViews, how to coordinate?

7. **Match Confirmation Timeout**: How long should a match confirmation request remain pending before expiring?

8. **Potential Match Criteria**: What thresholds (STR distance, shared haplogroup depth) qualify someone as a potential match?

---

## Related Documents

- [Atmosphere Lexicon Design](../Atmosphere_Lexicon.md) - Base record schemas
- [PDS Workbench Biosample Flow](../proposals/pds-workbench-biosample-flow.md) - Forward flow design
- [Haplogroup Discovery System](./haplogroup-discovery-system.md) - Branch discovery triggers
- [IBD Matching System](./ibd-matching-system.md) - Potential match identification criteria
