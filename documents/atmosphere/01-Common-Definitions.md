# Common Definitions

Shared type definitions used across multiple Atmosphere Lexicon record types.

**NSID:** `com.decodingus.atmosphere.defs`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.defs",
  "defs": {
    "recordMeta": {
      "type": "object",
      "description": "Metadata for tracking changes and enabling efficient sync.",
      "required": ["version", "createdAt"],
      "properties": {
        "version": {
          "type": "integer",
          "description": "Monotonically increasing version number for this record. Incremented on each update."
        },
        "createdAt": {
          "type": "string",
          "format": "datetime",
          "description": "Timestamp when this record was first created."
        },
        "updatedAt": {
          "type": "string",
          "format": "datetime",
          "description": "Timestamp of the most recent update."
        },
        "lastModifiedField": {
          "type": "string",
          "description": "Hint about what field changed in the last update (e.g., 'haplogroups.yDna', 'description')."
        }
      }
    },
    "fileInfo": {
      "type": "object",
      "description": "Metadata about a data file for provenance tracking. NOTE: This is metadata ONLY - DecodingUs never accesses the actual file content. Files remain on the user's local system or personal storage.",
      "required": ["fileName", "fileFormat"],
      "properties": {
        "fileName": {
          "type": "string"
        },
        "fileSizeBytes": {
          "type": "integer"
        },
        "fileFormat": {
          "type": "string",
          "knownValues": ["FASTQ", "BAM", "CRAM", "VCF", "GVCF", "BED", "23ANDME", "ANCESTRY", "FTDNA"]
        },
        "checksum": {
          "type": "string",
          "description": "SHA-256 or similar checksum for data integrity verification."
        },
        "checksumAlgorithm": {
          "type": "string",
          "description": "Algorithm used for checksum (e.g., 'SHA-256', 'MD5').",
          "knownValues": ["SHA-256", "MD5", "CRC32"]
        },
        "location": {
          "type": "string",
          "format": "uri",
          "description": "User's personal reference to file location (local path, personal cloud). DecodingUs does NOT access this URI."
        }
      }
    },
    "haplogroupResult": {
      "type": "object",
      "description": "Detailed scoring and classification result for a haplogroup.",
      "required": ["haplogroupName", "score"],
      "properties": {
        "haplogroupName": {
          "type": "string",
          "description": "The assigned haplogroup nomenclature (e.g., R-M269, H1a)."
        },
        "score": {
          "type": "float",
          "description": "Confidence score of the assignment."
        },
        "matchingSnps": {
          "type": "integer",
          "description": "Count of SNPs matching the defining mutations for this haplogroup."
        },
        "mismatchingSnps": {
          "type": "integer",
          "description": "Count of SNPs that contradict the assignment (potential private variants)."
        },
        "ancestralMatches": {
          "type": "integer",
          "description": "Count of ancestral state matches."
        },
        "treeDepth": {
          "type": "integer",
          "description": "The depth of the assigned node in the phylogenetic tree."
        },
        "lineagePath": {
          "type": "array",
          "description": "The path from root to the assigned haplogroup (e.g., A -> ... -> R -> ... -> R-M269).",
          "items": {
            "type": "string"
          }
        },
        "privateVariants": {
          "type": "ref",
          "ref": "#privateVariantData",
          "description": "Detailed private variant calls for haplogroup discovery (optional)."
        }
      }
    },
    "privateVariantData": {
      "type": "object",
      "description": "Detailed private variant calls that extend beyond the terminal haplogroup.",
      "properties": {
        "variants": {
          "type": "array",
          "description": "List of private (novel) variant calls.",
          "items": {
            "type": "ref",
            "ref": "#variantCall"
          }
        },
        "analysisVersion": {
          "type": "string",
          "description": "Version of the haplogroup analysis pipeline that identified these variants."
        },
        "referenceTree": {
          "type": "string",
          "description": "The haplogroup tree version used (e.g., 'ISOGG-2024', 'PhyloTree-17')."
        }
      }
    },
    "variantCall": {
      "type": "object",
      "description": "A single variant call representing a mutation.",
      "required": ["contigAccession", "position", "referenceAllele", "alternateAllele"],
      "properties": {
        "contigAccession": {
          "type": "string",
          "description": "GenBank accession for the contig (e.g., 'NC_000024.10' for chrY)."
        },
        "position": {
          "type": "integer",
          "description": "1-based position on the contig."
        },
        "referenceAllele": {
          "type": "string",
          "description": "Reference allele."
        },
        "alternateAllele": {
          "type": "string",
          "description": "Alternate (mutant) allele."
        },
        "rsId": {
          "type": "string",
          "description": "dbSNP rsID if known (e.g., 'rs123456')."
        },
        "variantName": {
          "type": "string",
          "description": "Common name if known (e.g., 'M269', 'L21')."
        },
        "genotype": {
          "type": "string",
          "description": "Called genotype (e.g., 'A', 'T', 'het')."
        },
        "quality": {
          "type": "float",
          "description": "Variant call quality score."
        },
        "depth": {
          "type": "integer",
          "description": "Read depth at this position."
        }
      }
    },
    "haplogroupAssignments": {
      "type": "object",
      "description": "Container for paternal (Y-DNA) and maternal (mtDNA) haplogroup classifications.",
      "properties": {
        "yDna": {
          "type": "ref",
          "ref": "#haplogroupResult",
          "description": "The predicted Y-chromosome haplogroup (Paternal)."
        },
        "mtDna": {
          "type": "ref",
          "ref": "#haplogroupResult",
          "description": "The predicted Mitochondrial haplogroup (Maternal)."
        }
      }
    },
    "alignmentMetrics": {
      "type": "object",
      "description": "Quality control metrics for the alignment.",
      "properties": {
        "genomeTerritory": {
          "type": "integer",
          "description": "The total number of bases in the reference genome territory."
        },
        "meanCoverage": {
          "type": "float",
          "description": "The mean coverage across the genome territory."
        },
        "medianCoverage": {
          "type": "float"
        },
        "sdCoverage": {
          "type": "float",
          "description": "Standard deviation of coverage."
        },
        "pctExcDupe": {
          "type": "float",
          "description": "Percentage of reads excluded due to duplication."
        },
        "pctExcMapq": {
          "type": "float",
          "description": "Percentage of reads excluded due to low mapping quality."
        },
        "pct10x": {
          "type": "float",
          "description": "Percentage of genome with at least 10x coverage."
        },
        "pct20x": {
          "type": "float",
          "description": "Percentage of genome with at least 20x coverage."
        },
        "pct30x": {
          "type": "float",
          "description": "Percentage of genome with at least 30x coverage."
        },
        "hetSnpSensitivity": {
          "type": "float",
          "description": "Sensitivity for detecting heterozygous SNPs."
        },
        "contigs": {
          "type": "array",
          "description": "Per-contig coverage statistics.",
          "items": {
            "type": "ref",
            "ref": "#contigMetrics"
          }
        }
      }
    },
    "contigMetrics": {
      "type": "object",
      "description": "Coverage analysis for a specific contig (chromosome).",
      "required": ["contigName", "callableBases"],
      "properties": {
        "contigName": {
          "type": "string",
          "description": "Name of the contig (e.g., chr1, 1)."
        },
        "callableBases": {
          "type": "integer",
          "description": "Number of bases deemed callable."
        },
        "meanCoverage": {
          "type": "float"
        },
        "poorMappingQuality": {
          "type": "integer",
          "description": "Number of bases with poor mapping quality."
        },
        "lowCoverage": {
          "type": "integer"
        },
        "noCoverage": {
          "type": "integer"
        }
      }
    },
    "populationComponent": {
      "type": "object",
      "description": "A single ancestry component in a population breakdown. Sub-continental granularity (~33 populations from 1000 Genomes + HGDP/SGDP).",
      "required": ["populationCode", "percentage"],
      "properties": {
        "populationCode": {
          "type": "string",
          "description": "Standardized population code from reference panel (e.g., 'CEU', 'YRI', 'CHB', 'GIH')."
        },
        "populationName": {
          "type": "string",
          "description": "Human-readable population name (e.g., 'Northwestern European', 'Yoruba', 'Han Chinese')."
        },
        "superPopulation": {
          "type": "string",
          "description": "Continental-level grouping for this population.",
          "knownValues": ["European", "African", "East Asian", "South Asian", "Americas", "West Asian", "Oceanian", "Central Asian", "Native American"]
        },
        "percentage": {
          "type": "float",
          "description": "Percentage contribution (0.0-100.0)."
        },
        "confidenceInterval": {
          "type": "object",
          "description": "95% confidence interval for the percentage estimate.",
          "properties": {
            "lower": { "type": "float" },
            "upper": { "type": "float" }
          }
        },
        "rank": {
          "type": "integer",
          "description": "Rank by percentage (1 = highest). Useful for sorting display."
        }
      }
    },
    "superPopulationSummary": {
      "type": "object",
      "description": "Aggregated ancestry percentage at the continental/super-population level.",
      "required": ["superPopulation", "percentage"],
      "properties": {
        "superPopulation": {
          "type": "string",
          "description": "Continental-level population grouping.",
          "knownValues": ["European", "African", "East Asian", "South Asian", "Americas", "West Asian", "Oceanian", "Central Asian", "Native American"]
        },
        "percentage": {
          "type": "float",
          "description": "Combined percentage from all sub-populations in this group (0.0-100.0)."
        },
        "populations": {
          "type": "array",
          "description": "List of population codes contributing to this super-population.",
          "items": { "type": "string" }
        }
      }
    },
    "ancestryPanel": {
      "type": "object",
      "description": "Description of the SNP panel used for ancestry analysis.",
      "required": ["panelType", "snpCount"],
      "properties": {
        "panelType": {
          "type": "string",
          "description": "Type of ancestry panel used.",
          "knownValues": ["aims", "genome-wide"]
        },
        "snpCount": {
          "type": "integer",
          "description": "Number of SNPs in the panel (aims: ~5,000; genome-wide: ~500,000)."
        },
        "description": {
          "type": "string",
          "description": "Human-readable description of the panel."
        },
        "referenceVersion": {
          "type": "string",
          "description": "Version of the reference panel data (e.g., 'v1')."
        }
      }
    },
    "ibdSegment": {
      "type": "object",
      "description": "An identical-by-descent (IBD) segment shared between two samples.",
      "required": ["chromosome", "startPosition", "endPosition", "lengthCm"],
      "properties": {
        "chromosome": {
          "type": "string",
          "description": "Chromosome number (e.g., '1', '22', 'X')."
        },
        "startPosition": {
          "type": "integer",
          "description": "Start position in base pairs."
        },
        "endPosition": {
          "type": "integer",
          "description": "End position in base pairs."
        },
        "lengthCm": {
          "type": "float",
          "description": "Length in centiMorgans."
        },
        "snpCount": {
          "type": "integer",
          "description": "Number of SNPs in the segment."
        },
        "isHalfIdentical": {
          "type": "boolean",
          "description": "True if half-identical (one allele matches), false if fully identical."
        }
      }
    },
    "strMarkerValue": {
      "type": "object",
      "description": "A single STR marker value. Handles simple, multi-copy, and complex multi-allelic markers.",
      "required": ["marker", "value"],
      "properties": {
        "marker": {
          "type": "string",
          "description": "Standard marker name (e.g., 'DYS393', 'DYS385a', 'DYF399X')."
        },
        "value": {
          "type": "ref",
          "ref": "#strValue",
          "description": "The marker value - simple integer or complex allele structure."
        },
        "panel": {
          "type": "string",
          "description": "Which panel this marker belongs to.",
          "knownValues": ["Y12", "Y25", "Y37", "Y67", "Y111", "Y500", "Y700", "YSEQ", "FTDNA_BIG_Y", "OTHER"]
        },
        "quality": {
          "type": "string",
          "description": "Call quality if available.",
          "knownValues": ["HIGH", "MEDIUM", "LOW", "UNCERTAIN"]
        },
        "readDepth": {
          "type": "integer",
          "description": "Read depth for WGS-derived STR calls."
        }
      }
    },
    "strValue": {
      "type": "union",
      "description": "STR value - either a simple repeat count or complex multi-allelic structure.",
      "refs": ["#simpleStrValue", "#multiCopyStrValue", "#complexStrValue"]
    },
    "simpleStrValue": {
      "type": "object",
      "description": "Simple single-value STR (e.g., DYS393 = 13).",
      "required": ["type", "repeats"],
      "properties": {
        "type": {
          "type": "string",
          "const": "simple"
        },
        "repeats": {
          "type": "integer",
          "description": "Number of tandem repeats."
        }
      }
    },
    "multiCopyStrValue": {
      "type": "object",
      "description": "Multi-copy STR with ordered values (e.g., DYS385a/b = 11-14, DYS459a/b = 9-10).",
      "required": ["type", "copies"],
      "properties": {
        "type": {
          "type": "string",
          "const": "multiCopy"
        },
        "copies": {
          "type": "array",
          "description": "Ordered repeat counts for each copy (convention: ascending order).",
          "items": {
            "type": "integer"
          },
          "minItems": 2
        }
      }
    },
    "complexStrValue": {
      "type": "object",
      "description": "Complex multi-allelic STR with allele counts (e.g., DYF399X = 22t-25c-26.1t). Used for palindromic markers.",
      "required": ["type", "alleles"],
      "properties": {
        "type": {
          "type": "string",
          "const": "complex"
        },
        "alleles": {
          "type": "array",
          "description": "List of alleles with their repeat values and counts.",
          "items": {
            "type": "ref",
            "ref": "#strAllele"
          },
          "minItems": 1
        },
        "rawNotation": {
          "type": "string",
          "description": "Original notation string for reference (e.g., '22t-25c-26.1t')."
        }
      }
    },
    "strAllele": {
      "type": "object",
      "description": "A single allele in a complex STR marker.",
      "required": ["repeats", "count"],
      "properties": {
        "repeats": {
          "type": "float",
          "description": "Repeat count (float to handle partial repeats like 26.1)."
        },
        "count": {
          "type": "integer",
          "description": "Number of copies of this allele (e.g., 2 for 'c' = cis/both copies)."
        },
        "designation": {
          "type": "string",
          "description": "Allele designation letter if applicable.",
          "knownValues": ["t", "c", "q"]
        }
      }
    },
    "strPanel": {
      "type": "object",
      "description": "Metadata about an STR panel/test.",
      "required": ["panelName", "markerCount"],
      "properties": {
        "panelName": {
          "type": "string",
          "description": "Panel name (e.g., 'Y-37', 'Big Y-700 STRs').",
          "knownValues": ["Y12", "Y25", "Y37", "Y67", "Y111", "Y500", "Y700", "YSEQ_ALPHA", "CUSTOM"]
        },
        "markerCount": {
          "type": "integer",
          "description": "Number of markers in this panel."
        },
        "provider": {
          "type": "string",
          "description": "Testing company or source.",
          "knownValues": ["FTDNA", "YSEQ", "NEBULA", "DANTE", "WGS_DERIVED", "OTHER"]
        },
        "testDate": {
          "type": "string",
          "format": "datetime",
          "description": "When the test was performed."
        }
      }
    },
    "ancestralStrState": {
      "type": "object",
      "description": "Reconstructed ancestral STR state for a haplogroup branch node.",
      "required": ["marker", "ancestralValue"],
      "properties": {
        "marker": {
          "type": "string",
          "description": "STR marker name."
        },
        "ancestralValue": {
          "type": "ref",
          "ref": "#strValue",
          "description": "Reconstructed ancestral value at this branch point."
        },
        "confidence": {
          "type": "float",
          "description": "Confidence in reconstruction (0.0-1.0)."
        },
        "supportingSamples": {
          "type": "integer",
          "description": "Number of descendant samples used in reconstruction."
        },
        "variance": {
          "type": "float",
          "description": "Variance observed among descendants."
        },
        "method": {
          "type": "string",
          "description": "Reconstruction method used.",
          "knownValues": ["MODE", "MEDIAN", "PARSIMONY", "ML_PHYLOGENETIC"]
        }
      }
    },
    "reconciliationStatus": {
      "type": "object",
      "description": "Summary of multi-run reconciliation for a biosample's haplogroup assignments.",
      "required": ["compatibilityLevel", "consensusHaplogroup"],
      "properties": {
        "compatibilityLevel": {
          "type": "string",
          "description": "Overall compatibility across all runs.",
          "knownValues": ["COMPATIBLE", "MINOR_DIVERGENCE", "MAJOR_DIVERGENCE", "INCOMPATIBLE"]
        },
        "consensusHaplogroup": {
          "type": "string",
          "description": "The reconciled consensus haplogroup across all runs."
        },
        "confidence": {
          "type": "float",
          "description": "Confidence in the consensus (0.0-1.0)."
        },
        "divergencePoint": {
          "type": "string",
          "description": "The last common ancestor haplogroup if runs diverge (e.g., 'R-DF13')."
        },
        "branchCompatibilityScore": {
          "type": "float",
          "description": "LCA_depth / max(depth_A, depth_B). 1.0 = fully compatible, <0.5 = likely different individuals."
        },
        "snpConcordance": {
          "type": "float",
          "description": "matching_calls / (matching + conflicting). 0.99+ = same individual."
        },
        "runCount": {
          "type": "integer",
          "description": "Number of runs included in reconciliation."
        },
        "warnings": {
          "type": "array",
          "description": "Any warnings generated during reconciliation.",
          "items": { "type": "string" }
        }
      }
    },
    "runHaplogroupCall": {
      "type": "object",
      "description": "A haplogroup call from a single sequencing run or STR profile with quality metrics.",
      "required": ["sourceRef", "haplogroup", "confidence", "callMethod"],
      "properties": {
        "sourceRef": {
          "type": "string",
          "description": "AT URI of the data source: sequence run, alignment, or STR profile."
        },
        "haplogroup": {
          "type": "string",
          "description": "The called haplogroup."
        },
        "confidence": {
          "type": "float",
          "description": "Confidence score for this call (0.0-1.0)."
        },
        "callMethod": {
          "type": "string",
          "description": "Method used to determine haplogroup.",
          "knownValues": ["SNP_PHYLOGENETIC", "STR_PREDICTION", "VENDOR_REPORTED"]
        },
        "score": {
          "type": "float",
          "description": "Raw scoring result from haplogroup assignment."
        },
        "supportingSnps": {
          "type": "integer",
          "description": "Number of derived SNPs supporting this call (SNP-based only)."
        },
        "conflictingSnps": {
          "type": "integer",
          "description": "Number of SNPs contradicting this call (SNP-based only)."
        },
        "noCalls": {
          "type": "integer",
          "description": "Number of defining SNPs with no call (SNP-based only)."
        },
        "technology": {
          "type": "string",
          "description": "Sequencing/testing technology used.",
          "knownValues": ["WGS", "WES", "BIG_Y", "SNP_ARRAY", "AMPLICON", "STR_PANEL"]
        },
        "meanCoverage": {
          "type": "float",
          "description": "Mean coverage in the haplogroup-relevant region (sequencing only)."
        },
        "treeVersion": {
          "type": "string",
          "description": "Haplogroup tree version used for this call."
        },
        "strPrediction": {
          "type": "ref",
          "ref": "#strHaplogroupPrediction",
          "description": "Details of STR-based prediction (when callMethod is STR_PREDICTION)."
        }
      }
    },
    "strHaplogroupPrediction": {
      "type": "object",
      "description": "Haplogroup prediction based on Y-STR profile analysis.",
      "required": ["predictedHaplogroup", "probability"],
      "properties": {
        "predictedHaplogroup": {
          "type": "string",
          "description": "The predicted haplogroup from STR analysis."
        },
        "probability": {
          "type": "float",
          "description": "Probability of this prediction being correct (0.0-1.0)."
        },
        "predictionMethod": {
          "type": "string",
          "description": "Algorithm used for prediction.",
          "knownValues": ["NEVGEN", "HAPEST", "YHAPLO", "SAPP", "BAYESIAN", "CUSTOM"]
        },
        "alternativePredictions": {
          "type": "array",
          "description": "Other possible haplogroups with their probabilities.",
          "items": {
            "type": "object",
            "properties": {
              "haplogroup": { "type": "string" },
              "probability": { "type": "float" }
            }
          }
        },
        "markersUsed": {
          "type": "integer",
          "description": "Number of STR markers used in prediction."
        },
        "panelName": {
          "type": "string",
          "description": "STR panel used (e.g., Y-37, Y-111)."
        },
        "predictionDepth": {
          "type": "string",
          "description": "Expected depth/resolution of STR-based prediction.",
          "knownValues": ["MAJOR_CLADE", "SUBCLADE", "TERMINAL"]
        },
        "modalMatch": {
          "type": "object",
          "description": "Best matching modal haplotype if applicable.",
          "properties": {
            "haplogroup": { "type": "string", "description": "Haplogroup of the modal." },
            "geneticDistance": { "type": "integer", "description": "Genetic distance (step mutations) from modal." },
            "sampleCount": { "type": "integer", "description": "Number of samples in the modal reference set." }
          }
        },
        "limitations": {
          "type": "array",
          "description": "Known limitations of this prediction.",
          "items": { "type": "string" }
        }
      }
    },
    "snpConflict": {
      "type": "object",
      "description": "A conflict at a specific SNP position between runs.",
      "required": ["position", "calls"],
      "properties": {
        "position": {
          "type": "integer",
          "description": "Genomic position of the conflict."
        },
        "snpName": {
          "type": "string",
          "description": "SNP name if known (e.g., 'M269', 'L21')."
        },
        "contigAccession": {
          "type": "string",
          "description": "GenBank accession for the contig."
        },
        "calls": {
          "type": "array",
          "description": "The different calls from each run.",
          "items": { "type": "ref", "ref": "#snpCallFromRun" }
        },
        "resolution": {
          "type": "string",
          "description": "How the conflict was resolved.",
          "knownValues": ["ACCEPT_MAJORITY", "ACCEPT_HIGHER_QUALITY", "ACCEPT_HIGHER_COVERAGE", "UNRESOLVED", "HETEROPLASMY"]
        },
        "resolvedValue": {
          "type": "string",
          "description": "The resolved allele value if resolution was applied."
        }
      }
    },
    "snpCallFromRun": {
      "type": "object",
      "description": "A single SNP call from one run.",
      "required": ["runRef", "allele"],
      "properties": {
        "runRef": {
          "type": "string",
          "description": "AT URI of the run that made this call."
        },
        "allele": {
          "type": "string",
          "description": "Called allele (e.g., 'A', 'T', 'NO_CALL')."
        },
        "quality": {
          "type": "float",
          "description": "Call quality score."
        },
        "depth": {
          "type": "integer",
          "description": "Read depth at this position."
        },
        "variantAlleleFrequency": {
          "type": "float",
          "description": "VAF for heteroplasmy detection (mtDNA)."
        }
      }
    },
    "heteroplasmyObservation": {
      "type": "object",
      "description": "An observation of mtDNA heteroplasmy at a specific position.",
      "required": ["position", "majorAllele", "minorAllele", "majorAlleleFrequency"],
      "properties": {
        "position": {
          "type": "integer",
          "description": "Mitochondrial genome position."
        },
        "majorAllele": {
          "type": "string",
          "description": "The predominant allele."
        },
        "minorAllele": {
          "type": "string",
          "description": "The minor allele."
        },
        "majorAlleleFrequency": {
          "type": "float",
          "description": "Frequency of the major allele (0.5-1.0)."
        },
        "depth": {
          "type": "integer",
          "description": "Total read depth at this position."
        },
        "isDefiningSnp": {
          "type": "boolean",
          "description": "Whether this position is a haplogroup-defining SNP."
        },
        "affectedHaplogroup": {
          "type": "string",
          "description": "Haplogroup affected by this heteroplasmy if defining."
        }
      }
    },
    "identityVerification": {
      "type": "object",
      "description": "Metrics for verifying that multiple runs come from the same individual.",
      "properties": {
        "kinshipCoefficient": {
          "type": "float",
          "description": "Self-vs-self should be ~0.5, different individuals <0.05."
        },
        "fingerprintSnpConcordance": {
          "type": "float",
          "description": "Concordance on curated fingerprint SNP set (should be 1.0 for same individual)."
        },
        "yStrDistance": {
          "type": "integer",
          "description": "Genetic distance on Y-STR markers (should be 0 for same individual)."
        },
        "verificationStatus": {
          "type": "string",
          "description": "Overall verification result.",
          "knownValues": ["VERIFIED_SAME", "LIKELY_SAME", "UNCERTAIN", "LIKELY_DIFFERENT", "VERIFIED_DIFFERENT"]
        },
        "verificationMethod": {
          "type": "string",
          "description": "Method used for verification.",
          "knownValues": ["AUTOSOMAL_KINSHIP", "Y_STR", "FINGERPRINT_SNPS", "COMBINED"]
        }
      }
    }
  }
}
```
