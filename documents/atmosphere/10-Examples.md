# Examples

Mock data examples and CRUD event flow scenarios.

---

## Mock Data Examples

### Biosample Record

```json
{
  "$type": "com.decodingus.atmosphere.biosample",
  "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
  "meta": {
    "version": 2,
    "createdAt": "2025-12-05T14:30:00Z",
    "updatedAt": "2025-12-06T09:15:00Z",
    "lastModifiedField": "haplogroups.yDna"
  },
  "sampleAccession": "BGS-UUID-98765-XYZ",
  "donorIdentifier": "Subject-001",
  "citizenDid": "did:plc:alice123",
  "description": "Deep WGS of Proband from Smith Family Trio",
  "centerName": "DecodingUs Reference Lab",
  "sex": "Male",
  "haplogroups": {
    "yDna": {
      "haplogroupName": "R-M269",
      "score": 0.998,
      "matchingSnps": 145,
      "mismatchingSnps": 2,
      "ancestralMatches": 3000,
      "treeDepth": 25,
      "lineagePath": ["R", "R1", "R1b", "R-M269"],
      "privateVariants": {
        "variants": [
          {
            "contigAccession": "NC_000024.10",
            "position": 12345678,
            "referenceAllele": "C",
            "alternateAllele": "T",
            "genotype": "T",
            "quality": 99.5,
            "depth": 45
          }
        ],
        "analysisVersion": "decodingus-1.2.0",
        "referenceTree": "ISOGG-2024"
      }
    },
    "mtDna": {
      "haplogroupName": "H1a",
      "score": 0.995,
      "matchingSnps": 42,
      "mismatchingSnps": 0,
      "ancestralMatches": 800,
      "treeDepth": 18,
      "lineagePath": ["L3", "N", "R", "HV", "H", "H1", "H1a"]
    }
  },
  "sequenceRunRefs": [
    "at://did:plc:alice123/com.decodingus.atmosphere.sequencerun/3jui7q2ly"
  ],
  "genotypeRefs": [],
  "populationBreakdownRef": "at://did:plc:alice123/com.decodingus.atmosphere.populationBreakdown/3jui7q2m1"
}
```

### Sequence Run Record

```json
{
  "$type": "com.decodingus.atmosphere.sequencerun",
  "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.sequencerun/3jui7q2ly",
  "meta": {
    "version": 1,
    "createdAt": "2025-12-05T14:35:00Z"
  },
  "biosampleRef": "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
  "platformName": "ILLUMINA",
  "instrumentModel": "NovaSeq 6000",
  "instrumentId": "A00123",
  "testType": "WGS",
  "libraryLayout": "PAIRED",
  "totalReads": 850000000,
  "readLength": 150,
  "meanInsertSize": 450.0,
  "flowcellId": "HXXYYZZAA",
  "runDate": "2025-11-20T00:00:00Z",
  "files": [
    {
      "fileName": "Sample001_R1.fastq.gz",
      "fileSizeBytes": 15000000000,
      "fileFormat": "FASTQ",
      "checksum": "sha256-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "checksumAlgorithm": "SHA-256",
      "location": "s3://lab-data-bucket/raw/Sample001_R1.fastq.gz"
    }
  ],
  "alignmentRefs": [
    "at://did:plc:alice123/com.decodingus.atmosphere.alignment/3jui7q2lz"
  ]
}
```

### Genotype Record (In Development)

```json
{
  "$type": "com.decodingus.atmosphere.genotype",
  "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.genotype/3jui7q3aa",
  "meta": {
    "version": 1,
    "createdAt": "2025-12-08T10:00:00Z"
  },
  "biosampleRef": "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
  "testTypeCode": "ARRAY_23ANDME_V5",
  "provider": "23andMe",
  "chipVersion": "v5",
  "totalMarkersCalled": 638234,
  "totalMarkersPossible": 640000,
  "noCallRate": 0.0028,
  "yMarkersCalled": 2847,
  "yMarkersTotal": 3000,
  "mtMarkersCalled": 4123,
  "mtMarkersTotal": 4200,
  "autosomalMarkersCalled": 631264,
  "hetRate": 0.32,
  "testDate": "2023-06-15T00:00:00Z",
  "processedAt": "2025-12-08T10:00:00Z",
  "buildVersion": "GRCh37",
  "sourceFileHash": "sha256-e3b0c44298fc1c149afbf4c8996fb924...",
  "files": [
    {
      "fileName": "genome_Alice_v5_Full_20230615.txt",
      "fileSizeBytes": 25000000,
      "fileFormat": "23ANDME_RAW",
      "checksum": "sha256-e3b0c44298fc1c149afbf4c8996fb924...",
      "checksumAlgorithm": "SHA-256",
      "location": "/local/genotypes/23andme/genome_Alice_v5_Full_20230615.txt"
    }
  ],
  "derivedHaplogroups": {
    "yDna": {
      "haplogroupName": "R-M269",
      "score": 0.92,
      "matchingSnps": 45,
      "mismatchingSnps": 2,
      "treeDepth": 8,
      "lineagePath": ["R", "R1", "R1b", "R-M269"]
    },
    "mtDna": {
      "haplogroupName": "H1a",
      "score": 0.95,
      "matchingSnps": 38,
      "mismatchingSnps": 0,
      "treeDepth": 12,
      "lineagePath": ["L3", "N", "R", "HV", "H", "H1", "H1a"]
    }
  },
  "populationBreakdownRef": "at://did:plc:alice123/com.decodingus.atmosphere.populationBreakdown/3jui7q2m1"
}
```

### Match Consent Record (Future)

```json
{
  "$type": "com.decodingus.atmosphere.matchConsent",
  "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.matchConsent/3jui7q3bb",
  "meta": {
    "version": 1,
    "createdAt": "2025-12-01T11:00:00Z"
  },
  "biosampleRef": "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
  "consentLevel": "FULL",
  "allowedMatchTypes": ["IBD", "Y_STR", "AUTOSOMAL"],
  "minimumSegmentCm": 7.0,
  "shareContactInfo": true,
  "consentedAt": "2025-12-01T11:00:00Z"
}
```

### Match List Record (Future)

```json
{
  "$type": "com.decodingus.atmosphere.matchList",
  "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.matchList/3jui7q3cc",
  "meta": {
    "version": 5,
    "createdAt": "2025-12-01T12:00:00Z",
    "updatedAt": "2025-12-06T08:00:00Z"
  },
  "biosampleRef": "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
  "matchCount": 127,
  "lastCalculatedAt": "2025-12-06T08:00:00Z",
  "matches": [
    {
      "matchedBiosampleRef": "at://did:plc:bob456/com.decodingus.atmosphere.biosample/3abc123",
      "matchedCitizenDid": "did:plc:bob456",
      "relationshipEstimate": "2ND_COUSIN",
      "totalSharedCm": 215.5,
      "longestSegmentCm": 45.2,
      "segmentCount": 8,
      "matchedAt": "2025-12-02T14:30:00Z",
      "sharedSegments": [
        {
          "chromosome": "7",
          "startPosition": 50000000,
          "endPosition": 75000000,
          "lengthCm": 45.2,
          "snpCount": 12500,
          "isHalfIdentical": true
        }
      ]
    }
  ]
}
```

### Instrument Observation Record (Future)

```json
{
  "$type": "com.decodingus.atmosphere.instrumentObservation",
  "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.instrumentObservation/3jui7q3dd",
  "meta": {
    "version": 1,
    "createdAt": "2025-12-05T14:40:00Z"
  },
  "instrumentId": "A00123",
  "labName": "Nebula Genomics",
  "biosampleRef": "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
  "sequenceRunRef": "at://did:plc:alice123/com.decodingus.atmosphere.sequencerun/3jui7q2ly",
  "platform": "ILLUMINA",
  "instrumentModel": "NovaSeq 6000",
  "flowcellId": "HXXYYZZAA",
  "runDate": "2025-11-20T00:00:00Z",
  "confidence": "KNOWN"
}
```

### Population Breakdown Record (In Development)

```json
{
  "$type": "com.decodingus.atmosphere.populationBreakdown",
  "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.populationBreakdown/3jui7q2m1",
  "meta": {
    "version": 1,
    "createdAt": "2025-12-08T16:00:00Z"
  },
  "biosampleRef": "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
  "analysisMethod": "PCA_PROJECTION_GMM",
  "panelType": "aims",
  "referencePopulations": "1000G_HGDP_v1",
  "snpsAnalyzed": 5000,
  "snpsWithGenotype": 4823,
  "snpsMissing": 177,
  "confidenceLevel": 0.92,
  "components": [
    {
      "populationCode": "CEU",
      "populationName": "Northwestern European",
      "superPopulation": "European",
      "percentage": 48.2,
      "confidenceInterval": { "lower": 45.1, "upper": 51.3 },
      "rank": 1
    },
    {
      "populationCode": "GBR",
      "populationName": "British",
      "superPopulation": "European",
      "percentage": 22.5,
      "confidenceInterval": { "lower": 19.8, "upper": 25.2 },
      "rank": 2
    },
    {
      "populationCode": "IBS",
      "populationName": "Iberian",
      "superPopulation": "European",
      "percentage": 15.3,
      "confidenceInterval": { "lower": 12.1, "upper": 18.5 },
      "rank": 3
    },
    {
      "populationCode": "FIN",
      "populationName": "Finnish",
      "superPopulation": "European",
      "percentage": 8.7,
      "confidenceInterval": { "lower": 6.2, "upper": 11.2 },
      "rank": 4
    },
    {
      "populationCode": "YRI",
      "populationName": "Yoruba",
      "superPopulation": "African",
      "percentage": 3.2,
      "confidenceInterval": { "lower": 1.5, "upper": 4.9 },
      "rank": 5
    }
  ],
  "superPopulationSummary": [
    {
      "superPopulation": "European",
      "percentage": 94.7,
      "populations": ["CEU", "GBR", "IBS", "FIN", "TSI"]
    },
    {
      "superPopulation": "African",
      "percentage": 3.2,
      "populations": ["YRI", "LWK", "ESN", "MSL", "GWD"]
    }
  ],
  "pcaCoordinates": [0.0234, -0.0156, 0.0089],
  "analysisDate": "2025-12-08T16:00:00Z",
  "pipelineVersion": "1.0.0",
  "referenceVersion": "v1"
}
```

### STR Profile Record

```json
{
  "$type": "com.decodingus.atmosphere.strProfile",
  "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.strProfile/3jui7q3ee",
  "meta": {
    "version": 1,
    "createdAt": "2025-12-05T15:30:00Z"
  },
  "biosampleRef": "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
  "sequenceRunRef": "at://did:plc:alice123/com.decodingus.atmosphere.sequencerun/3jui7q2ly",
  "panels": [
    {
      "panelName": "Y111",
      "markerCount": 111,
      "provider": "WGS_DERIVED",
      "testDate": "2025-12-05T15:30:00Z"
    }
  ],
  "markers": [
    {
      "marker": "DYS393",
      "value": { "type": "simple", "repeats": 13 },
      "panel": "Y12",
      "quality": "HIGH"
    },
    {
      "marker": "DYS390",
      "value": { "type": "simple", "repeats": 24 },
      "panel": "Y12",
      "quality": "HIGH"
    },
    {
      "marker": "DYS385a",
      "value": { "type": "multiCopy", "copies": [11, 14] },
      "panel": "Y12",
      "quality": "HIGH"
    },
    {
      "marker": "DYS385b",
      "value": { "type": "multiCopy", "copies": [11, 14] },
      "panel": "Y12",
      "quality": "HIGH"
    },
    {
      "marker": "DYF399X",
      "value": {
        "type": "complex",
        "alleles": [
          { "repeats": 22, "count": 1, "designation": "t" },
          { "repeats": 25, "count": 2, "designation": "c" },
          { "repeats": 26.1, "count": 1, "designation": "t" }
        ],
        "rawNotation": "22t-25c-26.1t"
      },
      "panel": "Y500",
      "quality": "HIGH"
    },
    {
      "marker": "DYS19",
      "value": { "type": "simple", "repeats": 14 },
      "panel": "Y12",
      "quality": "HIGH"
    },
    {
      "marker": "DYS391",
      "value": { "type": "simple", "repeats": 11 },
      "panel": "Y12",
      "quality": "HIGH"
    },
    {
      "marker": "DYS439",
      "value": { "type": "simple", "repeats": 12 },
      "panel": "Y25",
      "quality": "HIGH"
    },
    {
      "marker": "DYS389i",
      "value": { "type": "simple", "repeats": 13 },
      "panel": "Y12",
      "quality": "HIGH"
    },
    {
      "marker": "DYS389ii",
      "value": { "type": "simple", "repeats": 29 },
      "panel": "Y12",
      "quality": "HIGH"
    },
    {
      "marker": "DYS458",
      "value": { "type": "simple", "repeats": 17 },
      "panel": "Y37",
      "quality": "HIGH"
    },
    {
      "marker": "DYS459a",
      "value": { "type": "multiCopy", "copies": [9, 10] },
      "panel": "Y37",
      "quality": "HIGH"
    }
  ],
  "totalMarkers": 111,
  "source": "WGS_DERIVED",
  "derivationMethod": "HIPSTR"
}
```

### Haplogroup Ancestral STR Record (Future)

```json
{
  "$type": "com.decodingus.atmosphere.haplogroupAncestralStr",
  "atUri": "at://did:plc:decodingus-appview/com.decodingus.atmosphere.haplogroupAncestralStr/r-m269",
  "meta": {
    "version": 12,
    "createdAt": "2025-01-15T00:00:00Z",
    "updatedAt": "2025-12-05T18:00:00Z"
  },
  "haplogroup": "R-M269",
  "haplogroupTreeRef": "decodingus-ydna-tree-2025-12",
  "parentHaplogroup": "R-L23",
  "ancestralMarkers": [
    {
      "marker": "DYS393",
      "ancestralValue": { "type": "simple", "repeats": 13 },
      "confidence": 0.98,
      "supportingSamples": 4521,
      "variance": 0.15,
      "method": "PARSIMONY"
    },
    {
      "marker": "DYS390",
      "ancestralValue": { "type": "simple", "repeats": 24 },
      "confidence": 0.95,
      "supportingSamples": 4489,
      "variance": 0.42,
      "method": "PARSIMONY"
    },
    {
      "marker": "DYS385a",
      "ancestralValue": { "type": "multiCopy", "copies": [11, 14] },
      "confidence": 0.91,
      "supportingSamples": 4320,
      "variance": 0.68,
      "method": "PARSIMONY"
    },
    {
      "marker": "DYS19",
      "ancestralValue": { "type": "simple", "repeats": 14 },
      "confidence": 0.97,
      "supportingSamples": 4510,
      "variance": 0.22,
      "method": "PARSIMONY"
    }
  ],
  "sampleCount": 4521,
  "computedAt": "2025-12-05T18:00:00Z",
  "method": "PARSIMONY",
  "softwareVersion": "decodingus-str-reconstruction-1.0.0",
  "mutationRateModel": "BALLANTYNE_2010",
  "tmrcaEstimate": {
    "yearsBeforePresent": 4800,
    "confidenceInterval": {
      "lower": 4200,
      "upper": 5600
    },
    "generationTime": 31
  },
  "branchMutations": [
    {
      "marker": "DYS458",
      "fromValue": { "type": "simple", "repeats": 16 },
      "toValue": { "type": "simple", "repeats": 17 },
      "stepChange": 1,
      "confidence": 0.82
    }
  ]
}
```

---

## CRUD Event Flow Examples

### Example 1: Adding a New WGS Result to Existing Biosample

**Scenario:** User already has a biosample with one 30x WGS. They add a new 60x deep WGS from a different platform.

**Local Analysis Flow (in Navigator Workbench):**
1. User aligns new PacBio data locally (GRCh38)
2. Navigator computes coverage metrics, haplogroups locally
3. Navigator syncs metadata to PDS

**Firehose Events (metadata only):**

1. **Create `sequencerun`** (metadata for new run with PacBio data)
   - `biosampleRef` points to existing biosample
   - `instrumentId` triggers lab lookup
   - DecodingUs: Creates new `sequence_libraries` metadata row (no raw data)

2. **Update `biosample`** (optional, to add reference)
   - `sequenceRunRefs` now includes new run's AT URI
   - `meta.version` incremented
   - `meta.lastModifiedField`: "sequenceRunRefs"
   - DecodingUs: Updates only the refs array, no other changes

3. **Create `alignment`** (metadata for GRCh38 alignment)
   - `sequenceRunRef` points to new run
   - Contains coverage metrics computed locally
   - DecodingUs: Creates new `alignments` metadata row (no BAM/CRAM content)

**Result:** Three small, targeted metadata operations. Raw data stays local.

### Example 2: Adding Genotype Data for IBD Matching (Future)

**Scenario:** User imports their 23andMe data in Navigator Workbench and opts into matching.

**Local Analysis Flow (in Navigator Workbench):**
1. User imports 23andMe file locally
2. Navigator computes haplogroups, ancestry, IBD segments locally
3. Navigator syncs metadata to PDS (haplogroups, ancestry percentages, consent)

**Firehose Events (metadata only):**

1. **Create `genotype`**
   - Contains chip details and file metadata (not the actual file)
   - DecodingUs: Creates `genotype_data` row with metadata only

2. **Create `matchConsent`**
   - User opts into FULL matching
   - DecodingUs: Includes biosample in potential match discovery

3. **AppView creates `potentialMatchList`**
   - Based on consented biosamples across network
   - DecodingUs identifies candidates; user explores matches locally in Workbench
   - Both parties must agree before `confirmedMatch` is stamped

**Result:** User sees potential matches; actual comparison done locally.

### Example 3: Contributing Lab Observation (Future)

**Scenario:** User's sequence run contains instrument ID not in database.

**Firehose Events:**

1. **Create `instrumentObservation`**
   - User provides lab name with KNOWN confidence
   - DecodingUs: Records observation, aggregates with others
   - If threshold reached, proposal created for curator

**Result:** Crowdsourced lab database improves.

### Example 4: Haplogroup Discovery with Private Variants

**Scenario:** User's haplogroup result contains novel variants.

**Firehose Events:**

1. **Create/Update `biosample`**
   - `haplogroups.yDna.privateVariants` contains novel variant calls
   - DecodingUs: Extracts private variants
   - ProposalEngine: Creates or updates ProposedBranch
   - If consensus reached, curator notified

**Result:** Novel haplogroup branch discovered from network-wide observations.
