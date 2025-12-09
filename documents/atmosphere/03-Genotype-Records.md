# Genotype Records

Record types for genotyping array/chip data and imputation results.

---

## 1. Genotype Record

This record represents genotyping array/chip data (e.g., 23andMe, AncestryDNA, FTDNA). It is a first-class record separate from sequencing data. Raw genotype calls remain local on the Edge App; only metadata and derived results (haplogroups, ancestry percentages) flow to DecodingUs.

**NSID:** `com.decodingus.atmosphere.genotype`

**Status:** 🚧 In Development (Navigator Desktop Implementation)

**Supported Vendors**: 23andMe, AncestryDNA, FamilyTreeDNA, MyHeritage, LivingDNA

**Test Type Codes** (per multi-test-type-roadmap.md):

- `ARRAY_23ANDME_V5` - 23andMe v5 chip (~640K markers)
- `ARRAY_23ANDME_V4` - 23andMe v4 chip (~570K markers)
- `ARRAY_ANCESTRY_V2` - AncestryDNA v2 (~700K markers)
- `ARRAY_FTDNA_FF` - FTDNA Family Finder (~700K markers)
- `ARRAY_MYHERITAGE` - MyHeritage DNA (~700K markers)
- `ARRAY_LIVINGDNA` - LivingDNA (~630K markers)

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.genotype",
  "defs": {
    "main": {
      "type": "record",
      "description": "Genotyping array/chip data from DTC providers. Raw genotypes stay local; only metadata flows to DecodingUs.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "biosampleRef", "testTypeCode", "provider"],
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
          "testTypeCode": {
            "type": "string",
            "description": "Test type code from the taxonomy.",
            "knownValues": ["ARRAY_23ANDME_V5", "ARRAY_23ANDME_V4", "ARRAY_ANCESTRY_V2", "ARRAY_FTDNA_FF", "ARRAY_MYHERITAGE", "ARRAY_LIVINGDNA", "ARRAY_CUSTOM"]
          },
          "provider": {
            "type": "string",
            "description": "The genotyping provider or company.",
            "knownValues": ["23andMe", "AncestryDNA", "FamilyTreeDNA", "MyHeritage", "LivingDNA", "Nebula", "Custom"]
          },
          "chipVersion": {
            "type": "string",
            "description": "Specific chip version identifier (e.g., 'v5', 'v2')."
          },
          "totalMarkersCalled": {
            "type": "integer",
            "description": "Number of markers with valid genotype calls."
          },
          "totalMarkersPossible": {
            "type": "integer",
            "description": "Total markers on the chip/array."
          },
          "noCallRate": {
            "type": "float",
            "description": "Percentage of markers with no call (0.0-1.0)."
          },
          "yMarkersCalled": {
            "type": "integer",
            "description": "Number of Y-DNA markers with calls (for haplogroup confidence)."
          },
          "yMarkersTotal": {
            "type": "integer",
            "description": "Total Y-DNA markers on chip."
          },
          "mtMarkersCalled": {
            "type": "integer",
            "description": "Number of mtDNA markers with calls."
          },
          "mtMarkersTotal": {
            "type": "integer",
            "description": "Total mtDNA markers on chip."
          },
          "autosomalMarkersCalled": {
            "type": "integer",
            "description": "Number of autosomal markers with calls (for ancestry/IBD)."
          },
          "hetRate": {
            "type": "float",
            "description": "Heterozygosity rate across autosomal markers (quality check)."
          },
          "testDate": {
            "type": "string",
            "format": "datetime",
            "description": "Date the genotyping was performed."
          },
          "processedAt": {
            "type": "string",
            "format": "datetime",
            "description": "When the file was processed by Navigator."
          },
          "buildVersion": {
            "type": "string",
            "description": "Reference genome build for coordinates.",
            "knownValues": ["GRCh37", "GRCh38"]
          },
          "sourceFileHash": {
            "type": "string",
            "description": "SHA-256 hash of source file for deduplication."
          },
          "files": {
            "type": "array",
            "description": "Metadata about genotype data files. Files remain local; only metadata is stored for provenance tracking.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#fileInfo"
            }
          },
          "derivedHaplogroups": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#haplogroupAssignments",
            "description": "Haplogroups derived from chip Y/mtDNA markers."
          },
          "populationBreakdownRef": {
            "type": "string",
            "description": "AT URI of the population breakdown derived from this genotype data."
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

**Edge App Processing**: Navigator Desktop processes chip files locally:

1. Auto-detects vendor format from file header
2. Parses genotype calls (stays local, never uploaded)
3. Computes summary statistics (marker counts, call rates)
4. Extracts Y/mtDNA markers for haplogroup analysis
5. Runs ancestry analysis using autosomal markers
6. Syncs metadata and derived results to PDS

---

## 2. Imputation Record

This record represents imputed genotype data derived from array data.

**NSID:** `com.decodingus.atmosphere.imputation`

**Status:** 🔮 Future Scope (Multi-Test Type Support)

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
