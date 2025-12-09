# Core Records

The fundamental record types that form the backbone of the Atmosphere Lexicon.

## 1. Workspace Record

This record serves as the root container for a Researcher's PDS, aggregating biosample references and defined research projects.

**NSID:** `com.decodingus.atmosphere.workspace`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.workspace",
  "defs": {
    "main": {
      "type": "record",
      "description": "The root container for a Researcher's workspace, holding references to biosamples and projects.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "sampleRefs", "projectRefs"],
        "properties": {
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "sampleRefs": {
            "type": "array",
            "description": "AT URIs of biosample records in this workspace.",
            "items": {
              "type": "string",
              "description": "AT URI of a com.decodingus.atmosphere.biosample record."
            }
          },
          "projectRefs": {
            "type": "array",
            "description": "AT URIs of project records in this workspace.",
            "items": {
              "type": "string",
              "description": "AT URI of a com.decodingus.atmosphere.project record."
            }
          }
        }
      }
    }
  }
}
```

---

## 2. Biosample Record

This record represents a single biological sample. It contains donor metadata and haplogroup assignments, but references sequence runs and genotype data rather than embedding them.

**NSID:** `com.decodingus.atmosphere.biosample`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.biosample",
  "defs": {
    "main": {
      "type": "record",
      "description": "A record representing a biological sample and its donor metadata. Sequence and genotype data is referenced, not embedded.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "sampleAccession", "donorIdentifier", "centerName", "citizenDid", "atUri"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI (at://did/collection/rkey) of this biosample record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "sampleAccession": {
            "type": "string",
            "description": "Native identifier provided by the client for the biosample."
          },
          "donorIdentifier": {
            "type": "string",
            "description": "Identifier for the specimen donor within the user's context."
          },
          "citizenDid": {
            "type": "string",
            "description": "The Decentralized Identifier (DID) of the citizen/researcher who owns this biosample record."
          },
          "description": {
            "type": "string",
            "description": "Human-readable description of the sample."
          },
          "centerName": {
            "type": "string",
            "description": "The name of the Sequencing Center or BGS Node."
          },
          "sex": {
            "type": "string",
            "description": "Biological sex of the donor.",
            "knownValues": ["Male", "Female", "Other", "Unknown"]
          },
          "haplogroups": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#haplogroupAssignments",
            "description": "Y-DNA and mtDNA haplogroup assignments derived from the sequencing data."
          },
          "sequenceRunRefs": {
            "type": "array",
            "description": "AT URIs of sequence run records associated with this biosample.",
            "items": {
              "type": "string",
              "description": "AT URI of a com.decodingus.atmosphere.sequencerun record."
            }
          },
          "genotypeRefs": {
            "type": "array",
            "description": "AT URIs of genotype data records (chip/array data) associated with this biosample.",
            "items": {
              "type": "string",
              "description": "AT URI of a com.decodingus.atmosphere.genotype record."
            }
          },
          "populationBreakdownRef": {
            "type": "string",
            "description": "AT URI of the population/ancestry breakdown for this biosample."
          },
          "strProfileRef": {
            "type": "string",
            "description": "AT URI of the Y-STR profile for this biosample."
          }
        }
      }
    }
  }
}
```

---

## 3. Sequence Run Record

This record represents a single sequencing run (e.g., one library preparation and sequencing session). It is a first-class record that can be created, updated, or deleted independently.

**NSID:** `com.decodingus.atmosphere.sequencerun`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.sequencerun",
  "defs": {
    "main": {
      "type": "record",
      "description": "A sequencing run representing one library preparation and sequencing session. Can be independently managed.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "biosampleRef", "platformName", "testType", "files"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this sequence run record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the parent biosample record."
          },
          "platformName": {
            "type": "string",
            "description": "Sequencing platform (e.g., ILLUMINA, PACBIO, NANOPORE).",
            "knownValues": ["ILLUMINA", "PACBIO", "NANOPORE", "ION_TORRENT", "BGI", "ELEMENT", "ULTIMA"]
          },
          "instrumentModel": {
            "type": "string",
            "description": "Specific instrument model (e.g., NovaSeq 6000, Sequel II)."
          },
          "instrumentId": {
            "type": "string",
            "description": "Unique instrument identifier extracted from @RG headers (for lab inference)."
          },
          "testType": {
            "type": "string",
            "description": "Type of test (e.g., WGS, EXOME, TARGETED).",
            "knownValues": ["WGS", "EXOME", "TARGETED", "RNA_SEQ", "AMPLICON"]
          },
          "libraryLayout": {
            "type": "string",
            "description": "Paired-end or Single-end.",
            "knownValues": ["PAIRED", "SINGLE"]
          },
          "totalReads": {
            "type": "integer",
            "description": "Total number of reads."
          },
          "readLength": {
            "type": "integer",
            "description": "Average read length."
          },
          "meanInsertSize": {
            "type": "float",
            "description": "Mean insert size of the library."
          },
          "flowcellId": {
            "type": "string",
            "description": "Flowcell identifier if available from headers."
          },
          "runDate": {
            "type": "string",
            "format": "datetime",
            "description": "Date of the sequencing run if extractable."
          },
          "files": {
            "type": "array",
            "description": "Metadata about raw data files (e.g., FASTQs). Files remain local; only metadata (name, size, checksum) is stored here for provenance tracking.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#fileInfo"
            }
          },
          "alignmentRefs": {
            "type": "array",
            "description": "AT URIs of alignment records derived from this sequence run.",
            "items": {
              "type": "string",
              "description": "AT URI of a com.decodingus.atmosphere.alignment record."
            }
          }
        }
      }
    }
  }
}
```

---

## 4. Alignment Record

This record represents a single alignment of sequence data to a reference genome. It is independently managed, allowing metrics updates without touching parent records.

**NSID:** `com.decodingus.atmosphere.alignment`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.alignment",
  "defs": {
    "main": {
      "type": "record",
      "description": "An alignment of sequence data to a reference genome. Independently managed for granular updates.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "sequenceRunRef", "referenceBuild", "aligner"],
        "properties": {
          "atUri": {
            "type": "string",
            "description": "The AT URI of this alignment record."
          },
          "meta": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#recordMeta"
          },
          "sequenceRunRef": {
            "type": "string",
            "description": "AT URI of the parent sequence run record."
          },
          "biosampleRef": {
            "type": "string",
            "description": "AT URI of the grandparent biosample (denormalized for query efficiency)."
          },
          "referenceBuild": {
            "type": "string",
            "description": "Reference genome build (e.g., GRCh38, GRCh37, T2T-CHM13).",
            "knownValues": ["GRCh38", "GRCh37", "T2T-CHM13", "hg19", "hg38"]
          },
          "aligner": {
            "type": "string",
            "description": "Tool and version used for alignment (e.g., BWA-MEM 0.7.17)."
          },
          "variantCaller": {
            "type": "string",
            "description": "Tool used for variant calling (e.g., GATK HaplotypeCaller 4.2)."
          },
          "files": {
            "type": "array",
            "description": "Metadata about aligned data files (e.g., BAM, CRAM, VCF). Files remain local; only metadata is stored for provenance tracking.",
            "items": {
              "type": "ref",
              "ref": "com.decodingus.atmosphere.defs#fileInfo"
            }
          },
          "metrics": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.defs#alignmentMetrics"
          }
        }
      }
    }
  }
}
```

---

## 5. Project Record

This record defines a research project that aggregates multiple biosamples within a Researcher's PDS.

**NSID:** `com.decodingus.atmosphere.project`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.project",
  "defs": {
    "main": {
      "type": "record",
      "description": "A genealogy or research project that aggregates multiple biosamples.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["meta", "atUri", "projectName", "administrator", "memberRefs"],
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
            "description": "Name of the project (e.g., 'Smith Surname Project')."
          },
          "description": {
            "type": "string",
            "description": "Goals and scope of the research."
          },
          "administrator": {
            "type": "string",
            "description": "The DID of the researcher managing this project."
          },
          "memberRefs": {
            "type": "array",
            "description": "AT URIs of biosample records associated with this project.",
            "items": {
              "type": "string",
              "description": "AT URI of a com.decodingus.atmosphere.biosample record."
            }
          }
        }
      }
    }
  }
}
```

---

## Mapping to `decodingus` Backend

To fully leverage these records, `decodingus` will evolve its internal data model:

* **`Biosample`:** Fields like `description`, `centerName`, `sex`, `sampleAccession`, `donorIdentifier` map directly. The `meta.createdAt` becomes the creation timestamp.
* **`SequenceLibrary` → `SequenceRun`:** Now a separate record. `platformName`, `instrumentModel`, `testType`, `libraryLayout`, `totalReads`, `readLength`, `meanInsertSize`.
* **`SequenceFile`:** `fileInfo` maps directly (`fileName`, `fileSizeBytes`, `fileFormat`, `checksum`, `location`).
* **`Alignment` (New Entity):** `alignment` is now a first-class record requiring new tables/models to store `referenceBuild`, `aligner`, `variantCaller`, and the associated `files`.
* **`AlignmentMetrics`:** Stored as part of the `alignment` record, with detailed QC statistics.
* **`Haplogroups` (Enhanced):** The detailed `haplogroupResult` (score, SNPs, lineage path) can replace or enrich our existing `BiosampleOriginalHaplogroup` model.
* **`Project`:** Requires new tables (`projects`) with AT URI references to member biosamples.
* **`Workspace`:** A PDS-level container; we index its `sampleRefs` and `projectRefs` but may not store it directly.
