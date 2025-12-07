# Proposal: Privacy-First Group Project System

**Status:** Draft
**Author:** DecodingUs Team
**Created:** 2025-12-07
**Related:** [Atmosphere Lexicon](../Atmosphere_Lexicon.md), [IBD Matching System](../planning/ibd-matching-system.md)

## Overview

This proposal defines a Group Project system for collaborative haplogroup research, inspired by FTDNA's Group Project model but redesigned with privacy-first principles using the AT Protocol.

### Goals

1. Enable collaborative haplogroup research (surname projects, haplogroup projects, geographic studies)
2. Preserve member privacy - no raw STR/SNP exposure without explicit consent
3. Maintain research value through privacy-preserving aggregations
4. Give members full control over their data visibility
5. Support decentralized governance and membership

### Non-Goals

- Replacing FTDNA for users who want full public STR sharing
- Real-time chat/forum features (defer to AT Protocol social apps)
- Payment processing (integrate with external systems)

---

## Background: FTDNA's Model and Privacy Concerns

FTDNA's Group Project system offers valuable collaborative research features:

- **Haplogroup trees** showing member placement
- **STR result tables** for genetic distance analysis
- **Earliest Known Ancestor** information
- **Administrator hierarchy** for project governance
- **Group Time Tree** for TMRCA visualization

However, it has significant privacy issues:

| Issue | Description |
|:---|:---|
| **Raw STR exposure** | Full STR marker values published on public project pages |
| **All-or-nothing sharing** | Members must share with all project members or leave entirely |
| **Admin data access** | Administrators see contact info and full results for all members |
| **Forced pseudonymization** | Kit numbers are "pseudonymous" but linked to real results |
| **Centralized control** | FTDNA owns all data; users cannot truly revoke access |
| **Ancestor info exposure** | Earliest Known Ancestor details visible to other members |

**Sources:**
- [Introduction to Group Projects – FTDNA Help](https://help.familytreedna.com/hc/en-us/articles/4503173806351-Introduction-to-Group-Projects)
- [The Group Time Tree](https://blog.familytreedna.com/group-time-tree/)

---

## Design Principles

### 1. Data Sovereignty

Member data lives in their PDS, not the project's. Projects aggregate *references*, not copies.

### 2. Consent-Based Visibility

Every data field has explicit visibility controls. Members opt-in to sharing, not opt-out.

### 3. Privacy-Preserving Research

Research value is maintained through aggregated statistics, not individual exposure:
- Show **genetic distance**, not raw STR values
- Show **member counts per branch**, not member lists
- Show **geographic distributions**, not individual locations

### 4. Minimum Necessary Access

Administrators see only what's needed for project governance, not full member data.

### 5. Revocable Participation

Members can leave or adjust visibility instantly. Changes take effect immediately.

---

## Lexicon Additions

### Namespace: `com.decodingus.atmosphere`

These records extend the existing Atmosphere Lexicon.

---

### 1. Group Project Record (`com.decodingus.atmosphere.groupProject`)

The project definition record, owned by the project administrator.

**NSID:** `com.decodingus.atmosphere.groupProject`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.groupProject",
  "defs": {
    "main": {
      "type": "record",
      "description": "A haplogroup research project with tiered membership and privacy controls.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "projectName", "projectType", "governance"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this project record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "projectName": {
            "type": "string",
            "description": "Display name of the project.",
            "minLength": 3,
            "maxLength": 100
          },
          "projectType": {
            "type": "string",
            "description": "The focus of this project.",
            "knownValues": ["HAPLOGROUP", "SURNAME", "GEOGRAPHIC", "ETHNIC", "RESEARCH", "CUSTOM"]
          },
          "targetHaplogroup": {
            "type": "string",
            "description": "The haplogroup this project focuses on (e.g., 'R-CTS4466'). Optional for surname/geographic projects."
          },
          "targetLineage": {
            "type": "string",
            "description": "Y-DNA or MT-DNA focus.",
            "knownValues": ["Y_DNA", "MT_DNA", "BOTH"]
          },
          "description": {
            "type": "string",
            "description": "Project description, goals, and scope.",
            "maxLength": 5000
          },
          "backgroundInfo": {
            "type": "string",
            "description": "Historical/genealogical background for the project.",
            "maxLength": 10000
          },
          "governance": {
            "type": "ref",
            "ref": "#projectGovernance"
          },
          "visibilityPolicy": {
            "type": "ref",
            "ref": "#visibilityPolicy"
          },
          "joinPolicy": {
            "type": "string",
            "description": "How new members can join.",
            "knownValues": ["OPEN", "APPROVAL_REQUIRED", "INVITE_ONLY", "HAPLOGROUP_VERIFIED"]
          },
          "haplogroupRequirement": {
            "type": "string",
            "description": "Regex or prefix for required haplogroup (e.g., 'R-CTS4466.*'). Enforced if joinPolicy is HAPLOGROUP_VERIFIED."
          },
          "subgroups": {
            "type": "array",
            "description": "Named subgroups for organizing members (e.g., by sub-haplogroup or geographic origin).",
            "items": {
              "type": "ref",
              "ref": "#projectSubgroup"
            }
          },
          "links": {
            "type": "array",
            "description": "External links (websites, papers, etc.).",
            "items": {
              "type": "ref",
              "ref": "#projectLink"
            }
          },
          "createdAt": {
            "type": "string",
            "format": "datetime"
          }
        }
      }
    },
    "projectGovernance": {
      "type": "object",
      "description": "Leadership structure and permissions.",
      "required": ["administrators"],
      "properties": {
        "administrators": {
          "type": "array",
          "description": "Primary administrators with full control.",
          "items": {
            "type": "ref",
            "ref": "#projectRole"
          },
          "minItems": 1
        },
        "coAdministrators": {
          "type": "array",
          "description": "Co-administrators with delegated permissions.",
          "items": {
            "type": "ref",
            "ref": "#projectRole"
          }
        },
        "moderators": {
          "type": "array",
          "description": "Moderators for community management.",
          "items": {
            "type": "ref",
            "ref": "#projectRole"
          }
        },
        "curators": {
          "type": "array",
          "description": "Curators for data quality and haplogroup assignments.",
          "items": {
            "type": "ref",
            "ref": "#projectRole"
          }
        },
        "successionPolicy": {
          "type": "string",
          "description": "What happens if primary admin becomes inactive.",
          "knownValues": ["CO_ADMIN_INHERITS", "MEMBER_VOTE", "DECODINGUS_APPOINTS", "PROJECT_CLOSES"]
        }
      }
    },
    "projectRole": {
      "type": "object",
      "description": "A role assignment for a project member.",
      "required": ["citizenDid", "role"],
      "properties": {
        "citizenDid": {
          "type": "string",
          "description": "DID of the citizen holding this role."
        },
        "role": {
          "type": "string",
          "knownValues": ["ADMIN", "CO_ADMIN", "MODERATOR", "CURATOR"]
        },
        "permissions": {
          "type": "array",
          "description": "Specific permissions granted.",
          "items": {
            "type": "string",
            "knownValues": [
              "APPROVE_MEMBERS",
              "REMOVE_MEMBERS",
              "EDIT_PROJECT",
              "MANAGE_SUBGROUPS",
              "VIEW_CONTACT_INFO",
              "SEND_ANNOUNCEMENTS",
              "MANAGE_ROLES"
            ]
          }
        },
        "appointedAt": {
          "type": "string",
          "format": "datetime"
        },
        "appointedBy": {
          "type": "string",
          "description": "DID of the admin who appointed this role."
        }
      }
    },
    "visibilityPolicy": {
      "type": "object",
      "description": "Default visibility settings for the project. Members can be MORE restrictive, never less.",
      "properties": {
        "publicTreeView": {
          "type": "boolean",
          "description": "Show aggregated haplogroup tree to non-members."
        },
        "memberListVisibility": {
          "type": "string",
          "description": "Who can see the member list.",
          "knownValues": ["PUBLIC", "MEMBERS_ONLY", "ADMINS_ONLY", "HIDDEN"]
        },
        "strPolicy": {
          "type": "string",
          "description": "How STR data can be displayed.",
          "knownValues": ["HIDDEN", "DISTANCE_ONLY", "MODAL_COMPARISON", "MEMBERS_ONLY_RAW", "PUBLIC_RAW"]
        },
        "snpPolicy": {
          "type": "string",
          "description": "How SNP/haplogroup data can be displayed.",
          "knownValues": ["HIDDEN", "TERMINAL_ONLY", "FULL_PATH", "WITH_PRIVATE_VARIANTS"]
        },
        "ancestorPolicy": {
          "type": "string",
          "description": "How ancestor information can be displayed.",
          "knownValues": ["HIDDEN", "CENTURY_ONLY", "REGION_ONLY", "SURNAME_ONLY", "FULL"]
        },
        "defaultMemberVisibility": {
          "type": "ref",
          "ref": "#memberVisibilityPrefs",
          "description": "Default visibility for new members (they can customize)."
        }
      }
    },
    "projectSubgroup": {
      "type": "object",
      "description": "A named subgroup for organizing project members.",
      "required": ["subgroupId", "name"],
      "properties": {
        "subgroupId": {
          "type": "string",
          "description": "Unique identifier within the project."
        },
        "name": {
          "type": "string",
          "description": "Display name (e.g., 'Irish Branch', 'Pre-1700 Ancestors')."
        },
        "description": {
          "type": "string"
        },
        "haplogroupFilter": {
          "type": "string",
          "description": "Auto-assign members matching this haplogroup pattern."
        },
        "color": {
          "type": "string",
          "description": "Display color for tree visualization (hex code)."
        },
        "sortOrder": {
          "type": "integer"
        }
      }
    },
    "projectLink": {
      "type": "object",
      "properties": {
        "title": { "type": "string" },
        "url": { "type": "string", "format": "uri" },
        "category": {
          "type": "string",
          "knownValues": ["WEBSITE", "PAPER", "FORUM", "SOCIAL", "OTHER"]
        }
      }
    },
    "memberVisibilityPrefs": {
      "type": "object",
      "description": "Granular visibility preferences for a member.",
      "properties": {
        "showInMemberList": {
          "type": "boolean",
          "description": "Appear in project member listings."
        },
        "showInTree": {
          "type": "boolean",
          "description": "Appear in haplogroup tree visualizations."
        },
        "shareTerminalHaplogroup": {
          "type": "boolean",
          "description": "Share terminal haplogroup assignment."
        },
        "shareFullLineagePath": {
          "type": "boolean",
          "description": "Share full haplogroup path from root."
        },
        "sharePrivateVariants": {
          "type": "boolean",
          "description": "Share private/novel variants for haplogroup discovery."
        },
        "ancestorVisibility": {
          "type": "string",
          "description": "How much ancestor info to share.",
          "knownValues": ["NONE", "CENTURY_ONLY", "REGION_ONLY", "COUNTRY_ONLY", "SURNAME_ONLY", "FULL"]
        },
        "strVisibility": {
          "type": "string",
          "description": "How STR data can be used.",
          "knownValues": ["NONE", "DISTANCE_CALCULATION_ONLY", "MODAL_COMPARISON_ONLY", "FULL_TO_MEMBERS", "FULL_PUBLIC"]
        },
        "allowDirectContact": {
          "type": "boolean",
          "description": "Allow other members to request contact."
        },
        "showDisplayName": {
          "type": "boolean",
          "description": "Show display name (vs anonymous kit ID)."
        }
      }
    }
  }
}
```

---

### 2. Project Membership Record (`com.decodingus.atmosphere.projectMembership`)

**Critical privacy feature:** This record lives in the **member's PDS**, not the project's. The member controls it completely.

**NSID:** `com.decodingus.atmosphere.projectMembership`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.projectMembership",
  "defs": {
    "main": {
      "type": "record",
      "description": "A citizen's membership in a group project. Lives in the MEMBER'S PDS for full control.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "projectRef", "biosampleRef", "status"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this membership record (in member's PDS)."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "projectRef": {
            "type": "string",
            "description": "AT URI of the groupProject being joined."
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the biosample enrolled in this project."
          },
          "status": {
            "type": "string",
            "description": "Current membership status.",
            "knownValues": ["PENDING_APPROVAL", "ACTIVE", "SUSPENDED", "LEFT", "REMOVED"]
          },
          "displayName": {
            "type": "string",
            "description": "Pseudonym for this project (can be different per project).",
            "maxLength": 50
          },
          "kitId": {
            "type": "string",
            "description": "Anonymous kit identifier for this project (auto-generated if not set)."
          },
          "visibility": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.groupProject#memberVisibilityPrefs",
            "description": "Personal visibility preferences. Merged with project policy (more restrictive wins)."
          },
          "subgroupAssignments": {
            "type": "array",
            "description": "Subgroups this member belongs to.",
            "items": {
              "type": "string",
              "description": "Subgroup ID from the project."
            }
          },
          "earliestKnownAncestor": {
            "type": "ref",
            "ref": "#ancestorInfo",
            "description": "Earliest known ancestor information (shared per visibility settings)."
          },
          "contributionLevel": {
            "type": "string",
            "description": "Self-declared participation level.",
            "knownValues": ["OBSERVER", "CONTRIBUTOR", "ACTIVE_RESEARCHER"]
          },
          "joinedAt": {
            "type": "string",
            "format": "datetime"
          },
          "notes": {
            "type": "string",
            "description": "Private notes (only visible to member and admins if permitted).",
            "maxLength": 1000
          }
        }
      }
    },
    "ancestorInfo": {
      "type": "object",
      "description": "Earliest known ancestor information with granular fields for selective sharing.",
      "properties": {
        "name": {
          "type": "string",
          "description": "Full name of ancestor."
        },
        "surname": {
          "type": "string",
          "description": "Surname only (for surname projects)."
        },
        "birthYear": {
          "type": "integer",
          "description": "Exact birth year if known."
        },
        "birthCentury": {
          "type": "string",
          "description": "Birth century (e.g., '18th century')."
        },
        "birthDecade": {
          "type": "string",
          "description": "Birth decade (e.g., '1750s')."
        },
        "birthCountry": {
          "type": "string",
          "description": "Country of origin."
        },
        "birthRegion": {
          "type": "string",
          "description": "Region/state/province."
        },
        "birthPlace": {
          "type": "string",
          "description": "Specific town/village."
        },
        "additionalInfo": {
          "type": "string",
          "description": "Additional genealogical notes.",
          "maxLength": 500
        }
      }
    }
  }
}
```

---

### 3. Project Tree View Record (`com.decodingus.atmosphere.projectTreeView`)

Aggregated, privacy-preserving tree visualization computed by the AppView.

**NSID:** `com.decodingus.atmosphere.projectTreeView`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.projectTreeView",
  "defs": {
    "main": {
      "type": "record",
      "description": "Aggregated haplogroup tree view for a project. Computed by DecodingUs AppView.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "projectRef", "rootNode", "computedAt"],
        "properties": {
          "atUri": {
            "type": "string"
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "projectRef": {
            "type": "string",
            "description": "AT URI of the groupProject."
          },
          "computedAt": {
            "type": "string",
            "format": "datetime",
            "description": "When this view was last computed."
          },
          "totalMembers": {
            "type": "integer",
            "description": "Total project members (including those hidden from tree)."
          },
          "membersInTree": {
            "type": "integer",
            "description": "Members visible in this tree view."
          },
          "rootNode": {
            "type": "ref",
            "ref": "#treeNode"
          },
          "tmrcaEstimates": {
            "type": "array",
            "description": "TMRCA estimates for key branch points.",
            "items": {
              "type": "ref",
              "ref": "#tmrcaEstimate"
            }
          },
          "statistics": {
            "type": "ref",
            "ref": "#projectStatistics"
          }
        }
      }
    },
    "treeNode": {
      "type": "object",
      "description": "A node in the aggregated tree. Shows COUNTS, not individual members.",
      "required": ["haplogroup", "memberCount"],
      "properties": {
        "haplogroup": {
          "type": "string",
          "description": "Haplogroup at this node."
        },
        "haplogroupName": {
          "type": "string",
          "description": "Human-readable name if different from ID."
        },
        "memberCount": {
          "type": "integer",
          "description": "Number of members at or below this node."
        },
        "directMemberCount": {
          "type": "integer",
          "description": "Members terminal at exactly this haplogroup."
        },
        "tmrcaYbp": {
          "type": "integer",
          "description": "Estimated years before present for this node."
        },
        "tmrcaRange": {
          "type": "object",
          "properties": {
            "lower": { "type": "integer" },
            "upper": { "type": "integer" }
          }
        },
        "children": {
          "type": "array",
          "items": {
            "type": "ref",
            "ref": "#treeNode"
          }
        },
        "geographicSummary": {
          "type": "array",
          "description": "Anonymized geographic distribution.",
          "items": {
            "type": "ref",
            "ref": "#regionCount"
          }
        },
        "centurySummary": {
          "type": "array",
          "description": "Distribution of earliest known ancestors by century.",
          "items": {
            "type": "ref",
            "ref": "#centuryCount"
          }
        },
        "subgroupBreakdown": {
          "type": "array",
          "description": "Member counts by project subgroup.",
          "items": {
            "type": "object",
            "properties": {
              "subgroupId": { "type": "string" },
              "count": { "type": "integer" }
            }
          }
        }
      }
    },
    "regionCount": {
      "type": "object",
      "properties": {
        "region": { "type": "string" },
        "country": { "type": "string" },
        "count": { "type": "integer" }
      }
    },
    "centuryCount": {
      "type": "object",
      "properties": {
        "century": { "type": "string" },
        "count": { "type": "integer" }
      }
    },
    "tmrcaEstimate": {
      "type": "object",
      "description": "Time to Most Recent Common Ancestor estimate.",
      "properties": {
        "fromHaplogroup": { "type": "string" },
        "toHaplogroup": { "type": "string" },
        "yearsBeforePresent": { "type": "integer" },
        "confidenceInterval": {
          "type": "object",
          "properties": {
            "lower": { "type": "integer" },
            "upper": { "type": "integer" }
          }
        },
        "method": {
          "type": "string",
          "description": "Calculation method used.",
          "knownValues": ["SNP_COUNTING", "STR_VARIANCE", "COMBINED"]
        },
        "sampleSize": {
          "type": "integer",
          "description": "Number of samples used in calculation."
        }
      }
    },
    "projectStatistics": {
      "type": "object",
      "description": "Aggregate statistics for the project.",
      "properties": {
        "totalBranches": { "type": "integer" },
        "deepestBranch": { "type": "string" },
        "averageTreeDepth": { "type": "float" },
        "mostCommonTerminal": { "type": "string" },
        "geographicDiversity": { "type": "integer", "description": "Number of distinct countries" },
        "oldestAncestorCentury": { "type": "string" },
        "newestAncestorCentury": { "type": "string" }
      }
    }
  }
}
```

---

### 4. STR Comparison Record (`com.decodingus.atmosphere.strComparison`)

Privacy-preserving STR analysis - shows **distance metrics**, not raw values.

**NSID:** `com.decodingus.atmosphere.strComparison`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.strComparison",
  "defs": {
    "main": {
      "type": "record",
      "description": "Privacy-preserving STR comparison. Shows genetic distance, not raw marker values.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "membershipRef", "projectRef", "comparison"],
        "properties": {
          "atUri": {
            "type": "string"
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "membershipRef": {
            "type": "string",
            "description": "AT URI of the projectMembership this comparison belongs to."
          },
          "projectRef": {
            "type": "string",
            "description": "AT URI of the groupProject."
          },
          "comparison": {
            "type": "ref",
            "ref": "#modalComparison"
          },
          "computedAt": {
            "type": "string",
            "format": "datetime"
          }
        }
      }
    },
    "modalComparison": {
      "type": "object",
      "description": "Comparison against project modal haplotype.",
      "properties": {
        "modalVersion": {
          "type": "string",
          "description": "Version/date of the modal haplotype used."
        },
        "panelsTested": {
          "type": "array",
          "description": "Which STR panels are included.",
          "items": {
            "type": "string",
            "knownValues": ["Y12", "Y25", "Y37", "Y67", "Y111", "Y500", "Y700"]
          }
        },
        "markersCovered": {
          "type": "integer",
          "description": "Total markers compared."
        },
        "geneticDistance": {
          "type": "integer",
          "description": "Total step distance from project modal."
        },
        "matchPercentage": {
          "type": "float",
          "description": "Percentage exact match to modal (0.0-1.0)."
        },
        "distanceBreakdown": {
          "type": "object",
          "description": "Distance by panel for users with partial testing.",
          "properties": {
            "y12Distance": { "type": "integer" },
            "y25Distance": { "type": "integer" },
            "y37Distance": { "type": "integer" },
            "y67Distance": { "type": "integer" },
            "y111Distance": { "type": "integer" }
          }
        },
        "subgroupAffinities": {
          "type": "array",
          "description": "How close this member is to each subgroup's modal.",
          "items": {
            "type": "ref",
            "ref": "#subgroupAffinity"
          }
        },
        "nearestNeighborCount": {
          "type": "integer",
          "description": "Number of members within GD=3 (without identifying them)."
        },
        "clusterAssignment": {
          "type": "string",
          "description": "Auto-assigned STR cluster if project uses clustering."
        }
      }
    },
    "subgroupAffinity": {
      "type": "object",
      "properties": {
        "subgroupId": { "type": "string" },
        "subgroupName": { "type": "string" },
        "distance": { "type": "integer" },
        "rank": { "type": "integer", "description": "1 = closest subgroup" },
        "withinTypicalRange": { "type": "boolean" }
      }
    }
  }
}
```

---

### 5. Project Modal Haplotype Record (`com.decodingus.atmosphere.projectModal`)

The computed modal (ancestral) haplotype for the project.

**NSID:** `com.decodingus.atmosphere.projectModal`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.projectModal",
  "defs": {
    "main": {
      "type": "record",
      "description": "The computed modal (ancestral) STR haplotype for a project or subgroup.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "projectRef", "computedAt"],
        "properties": {
          "atUri": { "type": "string" },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "projectRef": {
            "type": "string",
            "description": "AT URI of the groupProject."
          },
          "subgroupId": {
            "type": "string",
            "description": "Subgroup ID if this is a subgroup modal (null for project-wide)."
          },
          "computedAt": {
            "type": "string",
            "format": "datetime"
          },
          "sampleSize": {
            "type": "integer",
            "description": "Number of members used to compute modal."
          },
          "method": {
            "type": "string",
            "description": "Computation method.",
            "knownValues": ["MODE", "MEDIAN", "PHYLOGENETIC_INFERENCE"]
          },
          "panels": {
            "type": "array",
            "description": "Modal values by panel. Note: This may be restricted based on project policy.",
            "items": {
              "type": "ref",
              "ref": "#modalPanel"
            }
          },
          "stability": {
            "type": "float",
            "description": "How stable/consistent the modal is (0.0-1.0). Low values indicate divergent membership."
          }
        }
      }
    },
    "modalPanel": {
      "type": "object",
      "description": "Modal values for a specific STR panel.",
      "properties": {
        "panel": {
          "type": "string",
          "knownValues": ["Y12", "Y25", "Y37", "Y67", "Y111"]
        },
        "markers": {
          "type": "array",
          "description": "Only included if project policy allows raw modal sharing.",
          "items": {
            "type": "ref",
            "ref": "#modalMarker"
          }
        },
        "varianceScore": {
          "type": "float",
          "description": "Average variance across markers in this panel."
        }
      }
    },
    "modalMarker": {
      "type": "object",
      "properties": {
        "marker": { "type": "string", "description": "Marker name (e.g., 'DYS393')." },
        "modalValue": { "type": "integer" },
        "variance": { "type": "float" },
        "membersCovered": { "type": "integer" }
      }
    }
  }
}
```

---

## Event Handling

### Firehose Collections

The AppView listens for:
- `com.decodingus.atmosphere.groupProject` - Project CRUD
- `com.decodingus.atmosphere.projectMembership` - Membership changes (in member PDSs)

### Event Flow

#### Member Joins Project

```
1. Citizen creates projectMembership in their PDS
   - Sets projectRef, biosampleRef, visibility prefs
   - Status: PENDING_APPROVAL (or ACTIVE if OPEN join policy)

2. AppView receives event via firehose
   - Validates haplogroup if HAPLOGROUP_VERIFIED policy
   - Creates internal membership index entry
   - Notifies project admins if approval needed

3. Admin approves (if required)
   - Updates membership status to ACTIVE
   - Triggers tree recomputation

4. AppView recomputes projectTreeView
   - Aggregates new member per their visibility settings
   - Publishes updated tree
```

#### Member Updates Visibility

```
1. Citizen updates their projectMembership record
   - Changes visibility.showInTree from true to false

2. AppView receives update event
   - Immediately updates internal index
   - Triggers tree recomputation

3. New projectTreeView published
   - Member no longer appears in tree
   - Aggregated counts still include them (if they allow)
```

#### Member Leaves Project

```
1. Citizen deletes their projectMembership record
   - Or updates status to LEFT

2. AppView receives event
   - Removes from all project indexes
   - Triggers tree recomputation
   - Member data was never copied - just dereferenced
```

---

## Privacy Comparison

| Data Type | FTDNA | DecodingUs |
|:---|:---|:---|
| **Raw STR values** | Public on project page | Never public. Distance metrics only. |
| **Terminal haplogroup** | Always visible to members | Opt-in per member |
| **Full haplogroup path** | Visible to members | Opt-in per member |
| **Earliest ancestor name** | Visible to members | Granular: name/surname/region/century |
| **Contact info** | Visible to admins | Only if member enables `allowDirectContact` |
| **Kit number** | Pseudonymous but linked | Truly anonymous, can be project-specific |
| **Membership list** | Visible to all members | Configurable: public/members/admins/hidden |
| **Individual tree placement** | Always shown | Opt-in `showInTree` |
| **Data revocation** | Must leave project entirely | Granular - update visibility anytime |
| **Data location** | FTDNA servers | Member's own PDS |

---

## Research Value Preserved

Despite enhanced privacy, researchers retain:

| Capability | How It's Preserved |
|:---|:---|
| **Haplogroup tree structure** | Aggregated `projectTreeView` with counts per branch |
| **TMRCA estimates** | Computed from consenting members' data |
| **Geographic patterns** | Anonymized `regionCount` summaries |
| **STR analysis** | `strComparison` shows distance without raw values |
| **Subgroup identification** | `subgroupAffinities` show cluster membership |
| **Modal haplotype** | `projectModal` computed from participants |
| **Private variant discovery** | Aggregated via haplogroup discovery system |
| **Temporal patterns** | `centurySummary` shows ancestor distributions |

---

## Future Considerations

### Not In Scope (Yet)

1. **Project funding/patronage** - Could integrate with AT Protocol payment proposals
2. **Discussion forums** - Defer to AT Protocol social features
3. **Document sharing** - Research papers, family trees, etc.
4. **Cross-project analysis** - Comparing across multiple projects
5. **Academic collaboration** - Formal research agreements

### Open Questions

1. Should `projectModal` raw values ever be public, or always computed-on-demand?
2. How to handle members who belong to multiple overlapping projects?
3. Should there be a "verified researcher" role with elevated (but audited) access?
4. How to handle project succession when admins become inactive?

---

## Implementation Phases

### Phase 1: Basic Projects
- `groupProject` record type
- `projectMembership` record type
- Simple member list (no tree aggregation)
- Basic visibility controls

### Phase 2: Tree Visualization
- `projectTreeView` computation
- TMRCA estimates
- Geographic summaries

### Phase 3: STR Analysis
- `projectModal` computation
- `strComparison` for members
- Subgroup affinities

### Phase 4: Advanced Features
- Subgroup management
- Cross-project tools
- Research collaboration features
