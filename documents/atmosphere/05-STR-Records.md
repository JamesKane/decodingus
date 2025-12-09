# STR Records

Record types for Y-STR profiles and ancestral STR reconstruction.

---

## 1. STR Profile Record

This record contains a biosample's Y-STR profile data. STRs may come from dedicated STR tests (FTDNA Y-37, Y-111, etc.) or be derived from WGS/Big Y data.

**NSID:** `com.decodingus.atmosphere.strProfile`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.strProfile",
  "defs": {
    "main": {
      "type": "record",
      "description": "Y-STR profile for a biosample. Can contain multiple panels from different sources.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "biosampleRef", "markers"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this STR profile record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the parent biosample."
          },
          "sequenceRunRef": {
            "type": "string",
            "description": "AT URI of the sequence run if STRs were derived from WGS (optional)."
          },
          "panels": {
            "type": "array",
            "description": "Panels/tests that contributed to this profile.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#strPanel"
            }
          },
          "markers": {
            "type": "array",
            "description": "The STR marker values.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#strMarkerValue"
            }
          },
          "totalMarkers": {
            "type": "integer",
            "description": "Total number of markers in this profile."
          },
          "source": {
            "type": "string",
            "description": "How these STRs were obtained.",
            "knownValues": ["DIRECT_TEST", "WGS_DERIVED", "BIG_Y_DERIVED", "IMPORTED", "MANUAL_ENTRY"]
          },
          "importedFrom": {
            "type": "string",
            "description": "If imported, the original source (e.g., 'FTDNA', 'YSEQ', 'YFULL')."
          },
          "derivationMethod": {
            "type": "string",
            "description": "For WGS-derived STRs, the tool/method used.",
            "knownValues": ["HIPSTR", "GANGSTR", "EXPANSION_HUNTER", "LOBSTR", "CUSTOM"]
          },
          "files": {
            "type": "array",
            "description": "Source CSV/TSV files if available.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#fileInfo"
            }
          }
        }
      }
    }
  }
}
```

---

## 2. Haplogroup Ancestral STR Record

This record contains the reconstructed ancestral STR state for a haplogroup branch node in the Y-DNA tree. Computed by the AppView using phylogenetic reconstruction methods.

**NSID:** `com.decodingus.atmosphere.haplogroupAncestralStr`

**Status:** Future Scope (Y-DNA Tree Enhancement)

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.haplogroupAncestralStr",
  "defs": {
    "main": {
      "type": "record",
      "description": "Reconstructed ancestral STR haplotype for a haplogroup branch node.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "haplogroup", "ancestralMarkers", "computedAt"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this ancestral STR record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "haplogroup": {
            "type": "string",
            "description": "The haplogroup this ancestral state represents (e.g., 'R-M269', 'I-M253')."
          },
          "haplogroupTreeRef": {
            "type": "string",
            "description": "Reference to the haplogroup tree version used."
          },
          "parentHaplogroup": {
            "type": "string",
            "description": "Parent haplogroup in the tree (for computing mutations along branch)."
          },
          "ancestralMarkers": {
            "type": "array",
            "description": "Reconstructed ancestral STR values with confidence.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#ancestralStrState"
            }
          },
          "sampleCount": {
            "type": "integer",
            "description": "Number of descendant samples used in reconstruction."
          },
          "computedAt": {
            "type": "string",
            "format": "datetime",
            "description": "When this reconstruction was computed."
          },
          "method": {
            "type": "string",
            "description": "Overall reconstruction method.",
            "knownValues": ["MODAL", "MEDIAN", "PARSIMONY", "BAYESIAN", "ML_PHYLOGENETIC"]
          },
          "softwareVersion": {
            "type": "string",
            "description": "Version of reconstruction software used."
          },
          "mutationRateModel": {
            "type": "string",
            "description": "Mutation rate model used (if applicable).",
            "knownValues": ["FTDNA_INFINITE_ALLELES", "BALLANTYNE_2010", "BURGARELLA_2012", "CUSTOM"]
          },
          "tmrcaEstimate": {
            "type": "object",
            "description": "Time to Most Recent Common Ancestor based on STR variance.",
            "properties": {
              "yearsBeforePresent": {
                "type": "integer"
              },
              "confidenceInterval": {
                "type": "object",
                "properties": {
                  "lower": { "type": "integer" },
                  "upper": { "type": "integer" }
                }
              },
              "generationTime": {
                "type": "integer",
                "description": "Generation time assumption used (years)."
              }
            }
          },
          "branchMutations": {
            "type": "array",
            "description": "Inferred STR mutations along the branch from parent haplogroup.",
            "items": {
              "type": "ref",
              "ref": "#strBranchMutation"
            }
          }
        }
      }
    },
    "strBranchMutation": {
      "type": "object",
      "description": "An inferred STR mutation along a haplogroup branch.",
      "required": ["marker", "fromValue", "toValue"],
      "properties": {
        "marker": {
          "type": "string",
          "description": "STR marker that mutated."
        },
        "fromValue": {
          "type": "ref",
          "ref": "com.decodingus.atmosphere.defs#strValue",
          "description": "Ancestral value (from parent haplogroup)."
        },
        "toValue": {
          "type": "ref",
          "ref": "com.decodingus.atmosphere.defs#strValue",
          "description": "Derived value (at this haplogroup)."
        },
        "stepChange": {
          "type": "integer",
          "description": "Net change in repeat count (positive = gain, negative = loss)."
        },
        "confidence": {
          "type": "float",
          "description": "Confidence in this mutation inference (0.0-1.0)."
        }
      }
    }
  }
}
```

---

## STR Value Types

STR markers can have three different value structures, defined in [01-Common-Definitions.md](./01-Common-Definitions.md):

1. **Simple** (`simpleStrValue`): Single repeat count (e.g., DYS393 = 13)
2. **Multi-copy** (`multiCopyStrValue`): Ordered values for multi-copy markers (e.g., DYS385a/b = 11-14)
3. **Complex** (`complexStrValue`): Multi-allelic with allele counts for palindromic markers (e.g., DYF399X = 22t-25c-26.1t)

---

## Backend Mapping

* **`strProfile`:** Maps to new `str_profiles` table with marker values stored as JSON array.
* **`haplogroupAncestralStr`:** Maps to new `haplogroup_ancestral_str` table for tree enhancement features.
