# Reconciliation Records

Record types for multi-run haplogroup reconciliation and conflict resolution.

---

## Haplogroup Reconciliation Record

This record contains the reconciliation results when a specimen donor has multiple biosamples or sequencing runs with potentially different haplogroup calls. It tracks per-run calls, conflicts, and the consensus result. Reconciliation is at the donor level since a donor may have multiple biosamples from different testing companies or labs.

**NSID:** `com.decodingus.atmosphere.haplogroupReconciliation`

**Status:** ✅ AppView Complete

**AppView Implementation (2025-12-09):**
- Database: `haplogroup_reconciliation` table with `dna_type` enum (Migration 40)
- Domain Models: `HaplogroupReconciliation`, `ReconciliationStatus`, `DnaType` enum
- Repository: `HaplogroupReconciliationRepository` with donor/DNA type uniqueness
- Event Handler: `handleHaplogroupReconciliation` in `AtmosphereEventHandler`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.haplogroupReconciliation",
  "defs": {
    "main": {
      "type": "record",
      "description": "Reconciliation of haplogroup calls across multiple biosamples/runs for a single specimen donor.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "specimenDonorRef", "dnaType", "status", "runCalls"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this reconciliation record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "specimenDonorRef": {
            "type": "string",
            "description": "AT URI or identifier of the specimen donor this reconciliation belongs to."
          },
          "dnaType": {
            "type": "string",
            "description": "Whether this reconciliation is for Y-DNA or MT-DNA.",
            "knownValues": ["Y_DNA", "MT_DNA"]
          },
          "status": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#reconciliationStatus",
            "description": "Summary reconciliation status."
          },
          "runCalls": {
            "type": "array",
            "description": "Individual haplogroup calls from each run.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#runHaplogroupCall"
            },
            "minItems": 1
          },
          "snpConflicts": {
            "type": "array",
            "description": "List of SNP-level conflicts between runs.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#snpConflict"
            }
          },
          "heteroplasmyObservations": {
            "type": "array",
            "description": "Heteroplasmy observations (mtDNA only).",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#heteroplasmyObservation"
            }
          },
          "identityVerification": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#identityVerification",
            "description": "Identity verification metrics if multiple runs were compared."
          },
          "lastReconciliationAt": {
            "type": "string",
            "format": "datetime",
            "description": "When reconciliation was last performed."
          },
          "manualOverride": {
            "type": "object",
            "description": "If a user manually overrode the consensus.",
            "properties": {
              "overriddenHaplogroup": {
                "type": "string",
                "description": "The user-specified haplogroup."
              },
              "reason": {
                "type": "string",
                "description": "Reason for the override."
              },
              "overriddenAt": {
                "type": "string",
                "format": "datetime"
              },
              "overriddenBy": {
                "type": "string",
                "description": "DID of the user who made the override."
              }
            }
          },
          "auditLog": {
            "type": "array",
            "description": "History of reconciliation decisions.",
            "items": {
              "type": "ref",
              "ref": "#auditEntry"
            }
          }
        }
      }
    },
    "auditEntry": {
      "type": "object",
      "description": "An entry in the reconciliation audit log.",
      "required": ["timestamp", "action"],
      "properties": {
        "timestamp": {
          "type": "string",
          "format": "datetime"
        },
        "action": {
          "type": "string",
          "description": "Action performed.",
          "knownValues": ["INITIAL_RECONCILIATION", "RUN_ADDED", "RUN_REMOVED", "MANUAL_OVERRIDE", "CONFLICT_RESOLVED", "RECOMPUTED"]
        },
        "previousConsensus": {
          "type": "string",
          "description": "Previous consensus haplogroup before this action."
        },
        "newConsensus": {
          "type": "string",
          "description": "New consensus haplogroup after this action."
        },
        "runRef": {
          "type": "string",
          "description": "AT URI of run involved (for RUN_ADDED/RUN_REMOVED)."
        },
        "notes": {
          "type": "string",
          "description": "Additional notes about this action."
        }
      }
    }
  }
}
```

---

## Compatibility Levels

The reconciliation system categorizes multi-run results into four compatibility levels:

| Level | Description | Example |
|:------|:------------|:--------|
| **COMPATIBLE** | Same branch, different depths | Run A: R-BY18291, Run B: R-CTS4466 - both valid, accept deepest |
| **MINOR_DIVERGENCE** | Tip-level differences, sibling terminal branches | Runs disagree at terminal SNP level |
| **MAJOR_DIVERGENCE** | Branch-level split | R-DF13 children diverge at major branch |
| **INCOMPATIBLE** | Different individuals | R1b vs I2a - likely sample mix-up |

---

## Reconciliation Algorithm

1. **Collect Calls**: Gather haplogroup calls from all sequence runs, alignments, and STR profiles
2. **Find Common Ancestor**: Determine the lowest common ancestor (LCA) of all calls in the haplogroup tree
3. **Calculate Branch Compatibility Score**: `LCA_depth / max(depth_A, depth_B)`
   - 1.0 = fully compatible
   - <0.5 = likely different individuals
4. **Compare SNP Calls**: Calculate SNP concordance across overlapping positions
   - 0.99+ = same individual
5. **Resolve Conflicts**: Apply resolution rules based on:
   - Majority voting
   - Higher quality scores
   - Higher coverage
   - Heteroplasmy detection (mtDNA)
6. **Generate Consensus**: Output the deepest supported haplogroup

---

## Backend Mapping

* **`HaplogroupReconciliation`:** Maps to new `haplogroup_reconciliation` table with related conflict/audit tables.

See [MultiRunReconciliation.md](../MultiRunReconciliation.md) for implementation planning.
