# Ancestry Records

Record types for ancestry composition and population analysis.

**Status:** Future Scope (IBD Matching System)

---

## Population Breakdown Record

This record contains ancestry composition analysis results.

**NSID:** `com.decodingus.atmosphere.populationBreakdown`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.populationBreakdown",
  "defs": {
    "main": {
      "type": "record",
      "description": "Ancestry composition analysis showing population percentages.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "biosampleRef", "analysisMethod", "components"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this population breakdown record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the parent biosample record."
          },
          "analysisMethod": {
            "type": "string",
            "description": "The analysis method/algorithm used.",
            "knownValues": ["ADMIXTURE", "FASTSTRUCTURE", "PCA_PROJECTION", "SUPERVISED_ML", "CUSTOM"]
          },
          "referencePopulations": {
            "type": "string",
            "description": "Reference population dataset used (e.g., '1000G', 'HGDP', 'Custom')."
          },
          "kValue": {
            "type": "integer",
            "description": "Number of ancestral populations (K) in the model."
          },
          "components": {
            "type": "array",
            "description": "List of population components with percentages.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#populationComponent"
            }
          },
          "analysisDate": {
            "type": "string",
            "format": "datetime",
            "description": "When the analysis was performed."
          },
          "pipelineVersion": {
            "type": "string",
            "description": "Version of the analysis pipeline."
          }
        }
      }
    }
  }
}
```

---

## Backend Mapping

* **`PopulationBreakdown` (New Entity):** Maps to existing `ancestry_analysis` with enhanced population components.

See [ibd-matching-system.md](../ibd-matching-system.md) for implementation planning.
