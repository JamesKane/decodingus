# Ancestry Records

Record types for ancestry composition and population analysis.

---

## Population Breakdown Record

This record contains ancestry composition analysis results using PCA projection onto reference populations from 1000 Genomes and HGDP/SGDP. Provides ADMIXTURE-style ancestry breakdowns at sub-continental granularity (~33 populations organized into 9 super-populations).

**NSID:** `com.decodingus.atmosphere.populationBreakdown`

**Status:** ✅ AppView Complete | 🚧 Navigator In Development

**AppView Implementation (2025-12-09):**
- Database: `population_breakdown`, `population_component`, `super_population_summary` tables (Migration 38)
- Domain Models: `PopulationBreakdown`, `PopulationComponent`, `SuperPopulationSummary`
- Repository: `PopulationBreakdownRepository` with components/summaries management
- Event Handler: `handlePopulationBreakdown` in `AtmosphereEventHandler`

**Algorithm**: PCA Projection + Gaussian Mixture Model

- Projects sample genotypes onto pre-computed PCA space from reference populations
- Calculates Mahalanobis distance to population centroids
- Converts distances to probabilities via multivariate Gaussian PDF
- Supports two panel types: AIMs (~5k SNPs, ~2-5 min) and genome-wide (~500k SNPs, ~20-30 min)

**Reference Populations (33 total)**:

- **European (5)**: CEU, FIN, GBR, IBS, TSI
- **African (5)**: YRI, LWK, ESN, MSL, GWD
- **East Asian (5)**: CHB, JPT, KHV, CHS, CDX
- **South Asian (5)**: GIH, PJL, BEB, STU, ITU
- **Americas (4)**: MXL, PUR, PEL, CLM
- **West Asian (3)**: Druze, Palestinian, Bedouin (HGDP)
- **Oceanian (2)**: Papuan, Melanesian (HGDP)
- **Central Asian (1)**: Yakut (HGDP)
- **Native American (3)**: Maya, Pima, Karitiana (HGDP)

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.populationBreakdown",
  "defs": {
    "main": {
      "type": "record",
      "description": "Ancestry composition analysis showing population percentages from atDNA analysis.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "biosampleRef", "analysisMethod", "panelType", "components"],
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
            "knownValues": ["PCA_PROJECTION_GMM", "ADMIXTURE", "FASTSTRUCTURE", "SUPERVISED_ML", "CUSTOM"]
          },
          "panelType": {
            "type": "string",
            "description": "SNP panel used for analysis.",
            "knownValues": ["aims", "genome-wide"]
          },
          "referencePopulations": {
            "type": "string",
            "description": "Reference population dataset used.",
            "knownValues": ["1000G_HGDP_v1", "1000G", "HGDP", "Custom"]
          },
          "snpsAnalyzed": {
            "type": "integer",
            "description": "Total number of SNPs in the analysis panel."
          },
          "snpsWithGenotype": {
            "type": "integer",
            "description": "Number of SNPs with valid genotype calls from the sample."
          },
          "snpsMissing": {
            "type": "integer",
            "description": "Number of SNPs with no call or missing data."
          },
          "confidenceLevel": {
            "type": "float",
            "description": "Overall confidence score (0.0-1.0) based on data quality and completeness."
          },
          "components": {
            "type": "array",
            "description": "List of sub-continental population components with percentages.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#populationComponent"
            }
          },
          "superPopulationSummary": {
            "type": "array",
            "description": "Aggregated percentages at the continental level.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#superPopulationSummary"
            }
          },
          "pcaCoordinates": {
            "type": "array",
            "description": "First 3 principal component coordinates for visualization.",
            "items": { "type": "float" },
            "maxItems": 3
          },
          "analysisDate": {
            "type": "string",
            "format": "datetime",
            "description": "When the analysis was performed."
          },
          "pipelineVersion": {
            "type": "string",
            "description": "Version of the analysis pipeline (e.g., '1.0.0')."
          },
          "referenceVersion": {
            "type": "string",
            "description": "Version of the reference panel data (e.g., 'v1')."
          }
        }
      }
    }
  }
}
```

---

## IBD Matching Integration

Population breakdown data enables:

- **Match contextualization**: Understand shared ancestry context for IBD matches
- **Endogamy detection**: Identify populations with higher background relatedness
- **Geographic correlation**: Map genetic ancestry to geographic origins
- **Match introduction text**: Generate meaningful connection descriptions (e.g., "You share 45cM with this person who has similar Northwestern European ancestry")

---

## Backend Mapping

* **`PopulationBreakdown` (New Entity):** Maps to existing `ancestry_analysis` with enhanced population components.

See [ibd-matching-system.md](../ibd-matching-system.md) and [AncestryAnalysis.md](../AncestryAnalysis.md) for implementation planning.
