# Genotype Records

Record types for genotyping array/chip data and imputation results.

**Status:** Future Scope (Multi-Test Type Support)

---

## 1. Genotype Record

This record represents genotyping array/chip data (e.g., 23andMe, AncestryDNA, FTDNA). It is a first-class record separate from sequencing data.

**NSID:** `com.decodingus.atmosphere.genotype`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.genotype",
  "defs": {
    "main": {
      "type": "record",
      "description": "Genotyping array/chip data from DTC providers or research arrays.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "biosampleRef", "chipType", "provider"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this genotype record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the parent biosample record."
          },
          "provider": {
            "type": "string",
            "description": "The genotyping provider or company.",
            "knownValues": ["23ANDME", "ANCESTRY", "FTDNA", "MYHERITAGE", "LIVINGDNA", "NEBULA", "CUSTOM"]
          },
          "chipType": {
            "type": "string",
            "description": "The array/chip version used.",
            "knownValues": ["GSA_V3", "GSA_V2", "OMNI_EXPRESS", "ILLUMINA_CORE", "CUSTOM"]
          },
          "chipVersion": {
            "type": "string",
            "description": "Specific version identifier (e.g., 'v5.2', '2024Q1')."
          },
          "snpCount": {
            "type": "integer",
            "description": "Total number of SNPs genotyped."
          },
          "callRate": {
            "type": "float",
            "description": "Percentage of SNPs successfully called (0.0-1.0)."
          },
          "testDate": {
            "type": "string",
            "format": "datetime",
            "description": "Date the genotyping was performed."
          },
          "buildVersion": {
            "type": "string",
            "description": "Reference genome build for coordinates.",
            "knownValues": ["GRCh37", "GRCh38", "hg19", "hg38"]
          },
          "files": {
            "type": "array",
            "description": "Metadata about genotype data files. Files remain local; only metadata is stored for provenance tracking.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#fileInfo"
            }
          },
          "imputationRef": {
            "type": "string",
            "description": "AT URI of imputation results if available."
          }
        }
      }
    }
  }
}
```

---

## 2. Imputation Record

This record represents imputed genotype data derived from array data.

**NSID:** `com.decodingus.atmosphere.imputation`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.imputation",
  "defs": {
    "main": {
      "type": "record",
      "description": "Imputed genotype data derived from array genotyping.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "genotypeRef", "referencePanel", "imputationTool"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this imputation record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "genotypeRef": {
            "type": "string",
            "description": "AT URI of the source genotype record."
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the grandparent biosample (denormalized)."
          },
          "referencePanel": {
            "type": "string",
            "description": "Reference panel used for imputation.",
            "knownValues": ["TOPMED", "HRC", "1000G_PHASE3", "CUSTOM"]
          },
          "imputationTool": {
            "type": "string",
            "description": "Tool used for imputation (e.g., 'Minimac4', 'IMPUTE5')."
          },
          "imputedVariantCount": {
            "type": "integer",
            "description": "Number of variants imputed."
          },
          "averageInfoScore": {
            "type": "float",
            "description": "Average imputation quality score (INFO/R2)."
          },
          "files": {
            "type": "array",
            "description": "Metadata about imputed VCF files. Files remain local; only metadata is stored for provenance tracking.",
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

## Backend Mapping

* **`Genotype` (New Entity):** Maps to new `genotype_data` table for chip/array results.
* **`Imputation` (New Entity):** Maps to new `imputation_result` table.

See [multi-test-type-roadmap.md](../multi-test-type-roadmap.md) for implementation planning.
