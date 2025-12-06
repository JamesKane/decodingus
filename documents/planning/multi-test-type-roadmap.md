# Multi-Test-Type Support Roadmap

## Executive Summary

This document outlines the roadmap for extending DecodingUs beyond Whole Genome Sequencing (WGS) to support:

1. **Targeted Chromosome Testing**: Big Y-700, Y Elite, FamilyTreeDNA mtDNA Full Sequence
2. **SNP Array/Chip Data**: 23andMe, AncestryDNA, MyHeritage, LivingDNA raw data exports

The existing architecture is well-suited for this expansion with targeted enhancements to test type modeling, coverage expectations, and variant interpretation.

---

## Related Planning Documents

This roadmap integrates with other planning documents:

| Document | Relationship |
|----------|-------------|
| `haplogroup-discovery-system.md` | **Primary integration point.** Y/mtDNA variants from all test types feed into the discovery system for tree building. This roadmap's chip and targeted sequencing services delegate to the discovery system's `PrivateVariantExtractionService`. |
| `ibd-matching-system.md` | IBD comparisons happen Edge-to-Edge using autosomal data. This roadmap's test type metadata helps determine comparison compatibility. |
| `jsonb-consolidation-analysis.md` | Some tables in this roadmap may be candidates for JSONB consolidation. |

**Schema Note:** All haplogroup-related tables reside in the `tree` schema as defined in `haplogroup-discovery-system.md`. This includes:
- `tree.haplogroup`, `tree.haplogroup_variant`, `tree.haplogroup_relationship`
- `tree.biosample_private_variant`, `tree.proposed_branch`, `tree.proposed_branch_evidence`

---

## Data Indexing Philosophy

### What We Index vs. What Stays on Edge

**Critical Principle:** DecodingUs does NOT index raw autosomal variant/genotype data from users. Raw genetic data remains on the user's Edge App and PDS.

| Data Type | Indexed by DecodingUs | Reason |
|-----------|----------------------|--------|
| Y-DNA variants (private/novel) | **Yes** | Required for tree building and branch proposals |
| mtDNA variants (private/novel) | **Yes** | Required for tree building and branch proposals |
| Autosomal variants | **No** | IBD analysis done Edge-to-Edge; only match summaries indexed |
| Chip genotype calls | **No** | Remains on Edge App |
| Coverage/quality statistics | **Yes** | Metadata for quality assessment |
| Haplogroup assignments | **Yes** | Derived results, not raw data |
| Population breakdown percentages | **Yes** | Statistical summary, not raw data |
| Match list summaries | **Yes** | Relationship metadata, not genetic data |

### Why This Matters

1. **Privacy**: Raw genetic data never leaves user control
2. **Storage**: DecodingUs doesn't become a variant warehouse
3. **Performance**: Index only what's needed for tree building
4. **Compliance**: Minimizes data protection obligations

### Tree Building Data Requirements

For the Haplogroup Discovery System (see `haplogroup-discovery-system.md`), we need:

```
Y-DNA/mtDNA Private Variants:
├── Variant position and alleles (for tree.biosample_private_variant)
├── Terminal haplogroup context
├── Matching/mismatching SNP counts
└── Evidence for proposed branches

Statistical Metadata:
├── Test type and quality tier
├── Coverage statistics (mean depth, callable bases)
├── Marker counts (for chip data)
└── Haplogroup confidence scores
```

---

## Current State Analysis

### What Exists (WGS-Centric)

| Component | Current State | Adaptability |
|-----------|--------------|--------------|
| `SequenceLibrary.testType` | Unconstrained `String` | Ready for enum migration |
| `SequenceFile` | Generic file tracking | Works for all types |
| `reported_variant_pangenome` | Comprehensive variant types | Works for SNP chips |
| `AlignmentCoverage` | Coverage metrics | Needs test-type context |
| `CoverageBenchmark` | Grouped by testType | Ready for multi-type |
| Haplogroup assignment | `HaplogroupResult` in JSONB | Works for all types |

### Gaps to Address

1. **Test Type Taxonomy**: No formal definition of valid test types
2. **Target Regions**: No modeling of what regions each test covers (for Y/mtDNA)
3. **Quality Expectations**: Coverage thresholds are WGS-assumed (30x)
4. **Chip Metadata Model**: No way to record chip test metadata (marker counts, no-call rates)
5. **Haplogroup Confidence**: No test-type-aware confidence scoring
6. **Platform Capabilities**: No mapping of platforms to test types and their Y/mtDNA coverage

---

## Test Type Taxonomy

### Proposed Test Type Hierarchy

```
TestType
├── SEQUENCING
│   ├── WGS                    # Whole Genome Sequencing (current focus)
│   ├── WES                    # Whole Exome Sequencing
│   ├── TARGETED_Y             # Y-chromosome targeted sequencing
│   │   ├── BIG_Y_500          # FTDNA Big Y-500 (legacy)
│   │   ├── BIG_Y_700          # FTDNA Big Y-700
│   │   ├── Y_ELITE            # Full Genomes Y Elite
│   │   └── Y_PRIME            # YSEQ Y-Prime
│   ├── TARGETED_MT            # mtDNA sequencing
│   │   ├── MT_FULL_SEQUENCE   # Full mitochondrial genome
│   │   ├── MT_PLUS            # FTDNA mtDNA Plus
│   │   └── MT_CR_ONLY         # Control Region only (HVR1/HVR2)
│   └── PANEL                  # Custom gene panels
│
└── GENOTYPING
    ├── SNP_ARRAY              # Generic SNP chip
    │   ├── ARRAY_23ANDME_V5   # 23andMe v5 chip
    │   ├── ARRAY_23ANDME_V4   # 23andMe v4 chip
    │   ├── ARRAY_ANCESTRY_V2  # AncestryDNA v2
    │   ├── ARRAY_ANCESTRY_V1  # AncestryDNA v1
    │   ├── ARRAY_MYHERITAGE   # MyHeritage chip
    │   ├── ARRAY_LIVINGDNA    # LivingDNA chip
    │   ├── ARRAY_FTDNA_FF     # FTDNA Family Finder
    │   └── ARRAY_CUSTOM       # Other/custom arrays
    └── MICROARRAY             # Expression arrays (future)
```

### Test Type Characteristics

| Test Type | Target Region | Expected Variants | Coverage Model | Primary Use |
|-----------|--------------|-------------------|----------------|-------------|
| WGS | Full genome | 4-5M | Depth-based (30x) | Complete analysis |
| WES | Exons (~2%) | 30-50K | Depth-based (100x) | Clinical |
| BIG_Y_700 | Y-chr targeted | 100K+ Y-SNPs | Depth-based (50-100x) | Y-DNA haplogroups |
| Y_ELITE | Y-chr full | 200K+ Y-SNPs | Depth-based (30x) | Deep Y-DNA |
| MT_FULL_SEQUENCE | mtDNA (16.5kb) | 50-200 variants | Depth-based (1000x+) | mtDNA haplogroups |
| ARRAY_23ANDME_V5 | Genome-wide SNPs | ~640K markers | Presence/absence | Ancestry, relatives |
| ARRAY_ANCESTRY_V2 | Genome-wide SNPs | ~700K markers | Presence/absence | Ancestry, relatives |

---

## Data Model Extensions

### 1. Test Type Definition Table

```sql
-- Evolution XX: Test Type Definitions

CREATE TYPE data_generation_method AS ENUM ('SEQUENCING', 'GENOTYPING');

CREATE TABLE test_type_definition (
    id SERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,          -- e.g., 'BIG_Y_700'
    display_name VARCHAR(100) NOT NULL,        -- e.g., 'FTDNA Big Y-700'
    category data_generation_method NOT NULL,
    vendor VARCHAR(100),                        -- e.g., 'FamilyTreeDNA', '23andMe'

    -- Target specification
    target_type VARCHAR(50) NOT NULL           -- WHOLE_GENOME, Y_CHROMOSOME, MT_DNA, AUTOSOMAL, MIXED
        CHECK (target_type IN ('WHOLE_GENOME', 'Y_CHROMOSOME', 'MT_DNA',
                               'AUTOSOMAL', 'X_CHROMOSOME', 'MIXED')),

    -- Coverage expectations (for sequencing)
    expected_min_depth DOUBLE PRECISION,       -- NULL for genotyping
    expected_target_depth DOUBLE PRECISION,

    -- Marker counts (for genotyping)
    expected_marker_count INTEGER,             -- e.g., 640000 for 23andMe v5

    -- Capabilities
    supports_haplogroup_y BOOLEAN NOT NULL DEFAULT FALSE,
    supports_haplogroup_mt BOOLEAN NOT NULL DEFAULT FALSE,
    supports_autosomal_ibd BOOLEAN NOT NULL DEFAULT FALSE,
    supports_ancestry BOOLEAN NOT NULL DEFAULT FALSE,

    -- File format expectations
    typical_file_formats TEXT[],               -- e.g., {'BAM', 'VCF'} or {'TXT', 'CSV'}

    -- Metadata
    version VARCHAR(20),                       -- Chip version, test version
    release_date DATE,
    deprecated_at DATE,
    successor_test_type_id INTEGER REFERENCES test_type_definition(id),

    description TEXT,
    documentation_url VARCHAR(500)
);

-- Seed with initial test types
INSERT INTO test_type_definition
(code, display_name, category, vendor, target_type, expected_target_depth,
 supports_haplogroup_y, supports_haplogroup_mt, supports_autosomal_ibd, supports_ancestry,
 typical_file_formats)
VALUES
-- Sequencing types
('WGS', 'Whole Genome Sequencing', 'SEQUENCING', NULL, 'WHOLE_GENOME', 30,
 TRUE, TRUE, TRUE, TRUE, ARRAY['BAM', 'CRAM', 'VCF']),

('WES', 'Whole Exome Sequencing', 'SEQUENCING', NULL, 'AUTOSOMAL', 100,
 FALSE, FALSE, FALSE, FALSE, ARRAY['BAM', 'VCF']),

('BIG_Y_700', 'FTDNA Big Y-700', 'SEQUENCING', 'FamilyTreeDNA', 'Y_CHROMOSOME', 50,
 TRUE, FALSE, FALSE, FALSE, ARRAY['BAM', 'VCF', 'BED']),

('Y_ELITE', 'Full Genomes Y Elite', 'SEQUENCING', 'Full Genomes', 'Y_CHROMOSOME', 30,
 TRUE, FALSE, FALSE, FALSE, ARRAY['BAM', 'CRAM', 'VCF']),

('MT_FULL_SEQUENCE', 'mtDNA Full Sequence', 'SEQUENCING', NULL, 'MT_DNA', 1000,
 FALSE, TRUE, FALSE, FALSE, ARRAY['BAM', 'FASTA', 'VCF']),

-- Genotyping arrays
('ARRAY_23ANDME_V5', '23andMe v5 Chip', 'GENOTYPING', '23andMe', 'MIXED', NULL,
 TRUE, TRUE, TRUE, TRUE, ARRAY['TXT', 'CSV']),

('ARRAY_ANCESTRY_V2', 'AncestryDNA v2', 'GENOTYPING', 'AncestryDNA', 'MIXED', NULL,
 TRUE, TRUE, TRUE, TRUE, ARRAY['TXT', 'CSV']),

('ARRAY_FTDNA_FF', 'FTDNA Family Finder', 'GENOTYPING', 'FamilyTreeDNA', 'AUTOSOMAL', NULL,
 FALSE, FALSE, TRUE, TRUE, ARRAY['CSV']);
```

### 2. Target Region Definitions

```sql
-- Target regions for each test type (what regions are covered)

CREATE TABLE test_type_target_region (
    id SERIAL PRIMARY KEY,
    test_type_id INTEGER NOT NULL REFERENCES test_type_definition(id),

    -- Region specification (one of these should be set)
    genbank_contig_id INTEGER REFERENCES genbank_contig(genbank_contig_id),
    pangenome_path_id INTEGER REFERENCES pangenome_path(id),

    -- Boundaries (NULL means entire contig/path)
    start_position INTEGER,
    end_position INTEGER,

    -- For named regions
    region_name VARCHAR(100),                  -- e.g., 'CDS', 'HVR1', 'Combbed Region'
    region_type VARCHAR(50)                    -- FULL, PARTIAL, TARGETED_SNPS
        CHECK (region_type IN ('FULL', 'PARTIAL', 'TARGETED_SNPS')),

    -- Expected performance in this region
    expected_coverage_pct DOUBLE PRECISION,    -- What % of region is typically covered
    expected_min_depth DOUBLE PRECISION,

    UNIQUE(test_type_id, genbank_contig_id, start_position, end_position)
);

-- Example: Big Y-700 targets Y chromosome with specific regions
INSERT INTO test_type_target_region (test_type_id, genbank_contig_id, region_name, region_type, expected_coverage_pct)
SELECT ttd.id, gc.genbank_contig_id, 'Y Chromosome - Combbed Region', 'TARGETED_SNPS', 0.95
FROM test_type_definition ttd, genbank_contig gc
WHERE ttd.code = 'BIG_Y_700' AND gc.common_name = 'chrY';
```

### 3. Chip/Array Test Metadata (Statistics Only)

**Note:** We do NOT store individual genotype calls or marker-level data. Raw chip data remains on the user's Edge App. We only store statistical metadata for quality assessment and haplogroup confidence scoring.

```sql
-- Metadata about a chip/array test result (NOT the genotypes themselves)

CREATE TABLE genotyping_test_summary (
    id SERIAL PRIMARY KEY,
    sample_guid UUID NOT NULL,
    test_type_id INTEGER NOT NULL REFERENCES test_type_definition(id),

    -- Statistical summary
    total_markers_called INTEGER NOT NULL,     -- How many markers had calls
    total_markers_possible INTEGER NOT NULL,   -- Total markers on chip
    no_call_count INTEGER NOT NULL,            -- Markers with no call
    no_call_rate DOUBLE PRECISION NOT NULL,    -- no_call_count / total_markers_possible

    -- Y-DNA marker coverage (for haplogroup confidence)
    y_markers_called INTEGER,                  -- Y-chr markers with calls
    y_markers_total INTEGER,                   -- Total Y-chr markers on chip
    y_coverage_rate DOUBLE PRECISION,

    -- mtDNA marker coverage (for haplogroup confidence)
    mt_markers_called INTEGER,
    mt_markers_total INTEGER,
    mt_coverage_rate DOUBLE PRECISION,

    -- Quality indicators
    average_confidence DOUBLE PRECISION,       -- Mean confidence across calls
    het_rate DOUBLE PRECISION,                 -- Heterozygosity rate (quality check)

    -- Source tracking
    chip_version VARCHAR(50),                  -- Specific chip version detected
    processed_at TIMESTAMP NOT NULL,
    source_file_hash VARCHAR(64),              -- SHA-256 of source file for deduplication

    -- AT Protocol reference
    at_uri VARCHAR(500),                       -- Reference to PDS record
    at_cid VARCHAR(100),

    UNIQUE(sample_guid, test_type_id, source_file_hash)
);

CREATE INDEX idx_gts_sample ON genotyping_test_summary(sample_guid);
CREATE INDEX idx_gts_test_type ON genotyping_test_summary(test_type_id);
```

### 4. Y/mtDNA Marker Coverage Reference (For Haplogroup Confidence)

We need to know which haplogroup-defining SNPs each chip covers, but this is **reference data**, not user data:

```sql
-- Reference: Which Y/mtDNA haplogroup markers are covered by each chip type
-- This is loaded once per chip version, not per user

CREATE TABLE test_type_haplogroup_marker_coverage (
    id SERIAL PRIMARY KEY,
    test_type_id INTEGER NOT NULL REFERENCES test_type_definition(id),
    haplogroup_type VARCHAR(10) NOT NULL CHECK (haplogroup_type IN ('Y', 'MT')),

    -- Summary statistics
    total_tree_defining_snps INTEGER NOT NULL,    -- Total SNPs that define tree branches
    covered_snps INTEGER NOT NULL,                 -- SNPs included on this chip
    coverage_rate DOUBLE PRECISION NOT NULL,       -- covered / total

    -- Depth analysis
    max_resolvable_depth INTEGER,                  -- Deepest tree level resolvable
    backbone_coverage_rate DOUBLE PRECISION,       -- Coverage of major branch SNPs

    -- Computed at
    computed_at TIMESTAMP NOT NULL DEFAULT NOW(),

    UNIQUE(test_type_id, haplogroup_type)
);

-- Example: 23andMe v5 covers ~2000 Y-SNPs out of ~200K known, resolving to major branches
INSERT INTO test_type_haplogroup_marker_coverage
(test_type_id, haplogroup_type, total_tree_defining_snps, covered_snps, coverage_rate, max_resolvable_depth, backbone_coverage_rate)
SELECT id, 'Y', 200000, 2000, 0.01, 8, 0.85
FROM test_type_definition WHERE code = 'ARRAY_23ANDME_V5';
```

### 5. Enhanced Sequence Library

```sql
-- Extend sequence_library with test type reference

ALTER TABLE sequence_library
    ADD COLUMN test_type_id INTEGER REFERENCES test_type_definition(id);

-- Migrate existing data
UPDATE sequence_library sl
SET test_type_id = ttd.id
FROM test_type_definition ttd
WHERE UPPER(sl.test_type) = ttd.code;

-- Add constraint after migration (allow NULL for legacy)
-- Future: ALTER TABLE sequence_library ALTER COLUMN test_type_id SET NOT NULL;

-- Index for efficient joins
CREATE INDEX idx_sl_test_type ON sequence_library(test_type_id);
```

---

## Domain Model Extensions

### TestTypeDefinition

```scala
package models.domain.genomics

case class TestTypeDefinition(
  id: Option[Int],
  code: String,
  displayName: String,
  category: DataGenerationMethod,
  vendor: Option[String],
  targetType: TargetType,
  expectedMinDepth: Option[Double],
  expectedTargetDepth: Option[Double],
  expectedMarkerCount: Option[Int],
  supportsHaplogroupY: Boolean,
  supportsHaplogroupMt: Boolean,
  supportsAutosomalIbd: Boolean,
  supportsAncestry: Boolean,
  typicalFileFormats: Seq[String],
  version: Option[String],
  releaseDate: Option[LocalDate],
  deprecatedAt: Option[LocalDate],
  successorTestTypeId: Option[Int],
  description: Option[String],
  documentationUrl: Option[String]
)

enum DataGenerationMethod:
  case Sequencing, Genotyping

enum TargetType:
  case WholeGenome, YChromosome, MtDna, Autosomal, XChromosome, Mixed
```

### GenotypingTestSummary (Metadata Only)

**Note:** We do NOT store individual genotype calls. This model captures statistical metadata only.

```scala
package models.domain.genomics

case class GenotypingTestSummary(
  id: Option[Int],
  sampleGuid: UUID,
  testTypeId: Int,

  // Statistical summary
  totalMarkersCalled: Int,
  totalMarkersPossible: Int,
  noCallCount: Int,
  noCallRate: Double,

  // Y-DNA marker coverage (for haplogroup confidence)
  yMarkersCalled: Option[Int],
  yMarkersTotal: Option[Int],
  yCoverageRate: Option[Double],

  // mtDNA marker coverage (for haplogroup confidence)
  mtMarkersCalled: Option[Int],
  mtMarkersTotal: Option[Int],
  mtCoverageRate: Option[Double],

  // Quality indicators
  averageConfidence: Option[Double],
  hetRate: Option[Double],

  // Source tracking
  chipVersion: Option[String],
  processedAt: LocalDateTime,
  sourceFileHash: Option[String],

  // AT Protocol reference
  atUri: Option[String],
  atCid: Option[String]
)
```

### Y/mtDNA Private Variants (From Edge App)

For tree building, the Edge App extracts and submits only Y-DNA and mtDNA variants. This integrates with the Haplogroup Discovery System (see `haplogroup-discovery-system.md`).

**Important:** The data structures here align with the discovery system's `PrivateVariantData` and `VariantCall` types.

```scala
/**
 * Submitted by Edge App after processing chip data locally.
 * Only Y-DNA and mtDNA variants needed for haplogroup tree building.
 *
 * This data feeds into the PrivateVariantExtractionService defined in
 * haplogroup-discovery-system.md, which creates BiosamplePrivateVariant records.
 */
case class ChipDerivedHaplogroupData(
  sampleGuid: UUID,
  testTypeCode: String,

  // Haplogroup assignment (used by discovery system)
  yHaplogroupResult: Option[HaplogroupResult],
  mtHaplogroupResult: Option[HaplogroupResult],

  // Detailed private variant calls (aligns with PrivateVariantData in discovery system)
  privateVariantData: Option[PrivateVariantData],

  // Quality metrics specific to chip data
  chipQualityMetrics: ChipQualityMetrics
)

/**
 * Shared structure from haplogroup-discovery-system.md
 * Re-used here for chip-derived submissions.
 */
case class PrivateVariantData(
  yDna: Option[Seq[VariantCall]],
  mtDna: Option[Seq[VariantCall]]
)

case class VariantCall(
  contigAccession: String,       // e.g., "NC_000024.10" for chrY
  position: Int,
  referenceAllele: String,
  alternateAllele: String,
  rsId: Option[String],
  variantName: Option[String]    // Common name if known (e.g., "M269")
)

case class ChipQualityMetrics(
  coverageOfKnownSnps: Double,
  ySnpsCovered: Option[Int],
  mtSnpsCovered: Option[Int],
  processedAt: LocalDateTime
)
```

---

## Lexicon Extensions

### Extended Biosample Record

Update `com.decodingus.atmosphere.biosample` to support multiple test types:

```json
{
  "sequenceData": {
    "type": "array",
    "items": {
      "type": "ref",
      "ref": "#sequenceData"
    }
  },
  "genotypeData": {
    "type": "array",
    "description": "SNP array/chip genotyping data.",
    "items": {
      "type": "ref",
      "ref": "#genotypeData"
    }
  }
}
```

### New Genotype Data Structure

```json
"genotypeData": {
  "type": "object",
  "description": "SNP array genotyping results.",
  "required": ["testType", "vendor", "processedAt"],
  "properties": {
    "testType": {
      "type": "string",
      "description": "Test type code (e.g., 'ARRAY_23ANDME_V5')."
    },
    "vendor": {
      "type": "string",
      "description": "Genotyping vendor (e.g., '23andMe', 'AncestryDNA')."
    },
    "chipVersion": {
      "type": "string",
      "description": "Chip/array version identifier."
    },
    "totalMarkersGenotyped": {
      "type": "integer",
      "description": "Total number of markers with calls."
    },
    "noCallRate": {
      "type": "float",
      "description": "Percentage of markers with no call (0-1)."
    },
    "processedAt": {
      "type": "string",
      "format": "datetime"
    },
    "rawDataFile": {
      "type": "ref",
      "ref": "#fileInfo",
      "description": "Reference to the raw data export file."
    },
    "derivedHaplogroups": {
      "type": "ref",
      "ref": "#haplogroupAssignments",
      "description": "Haplogroups derived from chip data."
    }
  }
}
```

---

## Service Layer Extensions

### 1. TestTypeService

```scala
trait TestTypeService {
  /**
   * Get test type definition by code.
   */
  def getByCode(code: String): Future[Option[TestTypeDefinition]]

  /**
   * Get all active test types in a category.
   */
  def getByCategory(category: DataGenerationMethod): Future[Seq[TestTypeDefinition]]

  /**
   * Get test types that support a specific capability.
   */
  def getByCapability(
    supportsY: Option[Boolean] = None,
    supportsMt: Option[Boolean] = None,
    supportsIbd: Option[Boolean] = None
  ): Future[Seq[TestTypeDefinition]]

  /**
   * Validate that a test type code is valid.
   */
  def isValidCode(code: String): Future[Boolean]

  /**
   * Get target regions for a test type.
   */
  def getTargetRegions(testTypeId: Int): Future[Seq[TestTypeTargetRegion]]
}
```

### 2. ChipDataExtractionService

**Note:** Raw chip data is NOT uploaded to DecodingUs. The Edge App processes chip files locally and includes results in the biosample PDS record. This service extracts chip-related data from Firehose events.

**Integration with Haplogroup Discovery System:** This service delegates private variant processing to the `PrivateVariantExtractionService` defined in `haplogroup-discovery-system.md`. The discovery system handles:
- Creating `tree.biosample_private_variant` records
- Updating/creating `tree.proposed_branch` proposals
- Tracking consensus across all sample sources (Citizen + External)

```scala
trait ChipDataExtractionService {
  /**
   * Extract and process chip test metadata from a biosample Firehose event.
   * Called by CitizenBiosampleEventHandler when processing biosample records.
   */
  def extractFromBiosample(
    biosample: CitizenBiosample,
    sequenceData: SequenceData
  ): Future[Option[ChipDataExtractionResult]]

  /**
   * Get haplogroup confidence based on chip marker coverage.
   */
  def getHaplogroupConfidence(
    testTypeId: Int,
    haplogroupType: HaplogroupType
  ): Future[HaplogroupConfidenceFactors]
}

case class ChipDataExtractionResult(
  sampleGuid: UUID,
  testTypeCode: String,
  qualityAssessment: QualityAssessment,

  // Y-DNA results (delegated to PrivateVariantExtractionService)
  yHaplogroupAssigned: Option[String],
  yPrivateVariantsRecorded: Int,
  yProposalsUpdated: Seq[Int],

  // mtDNA results
  mtHaplogroupAssigned: Option[String],
  mtPrivateVariantsRecorded: Int,
  mtProposalsUpdated: Seq[Int],

  // Overall confidence
  confidenceFactors: HaplogroupConfidenceFactors
)

case class QualityAssessment(
  overallQuality: String,           // "HIGH", "MEDIUM", "LOW"
  noCallRateAcceptable: Boolean,
  yDnaCoverage: Option[String],     // "SUFFICIENT", "LIMITED", "NONE"
  mtDnaCoverage: Option[String],
  warnings: Seq[String]
)
```

### 3. TargetedSequencingExtractionService

**Note:** Targeted sequencing data (Big Y-700, Y Elite, mtDNA Full Sequence) is processed by the Edge App and included in the biosample PDS record. This service extracts targeted sequencing data from Firehose events.

```scala
trait TargetedSequencingExtractionService {
  /**
   * Extract and process targeted sequencing data from a biosample Firehose event.
   * Called by CitizenBiosampleEventHandler when processing biosample records.
   */
  def extractFromBiosample(
    biosample: CitizenBiosample,
    sequenceData: SequenceData
  ): Future[Option[TargetedExtractionResult]]

  /**
   * Calculate coverage for targeted regions based on extracted metrics.
   */
  def calculateTargetedCoverage(
    sampleGuid: UUID,
    testTypeId: Int,
    alignmentMetrics: AlignmentMetrics
  ): Future[TargetedCoverageResult]
}

case class TargetedExtractionResult(
  sampleGuid: UUID,
  testTypeCode: String,
  variantsExtracted: Int,
  coverageStats: TargetedCoverageResult,
  haplogroupResult: Option[HaplogroupResult],
  privateVariantsRecorded: Int,
  proposalsUpdated: Seq[Int]
)

case class TargetedCoverageResult(
  testTypeCode: String,
  targetRegions: Seq[RegionCoverage],
  overallCoveragePct: Double,
  meanDepth: Option[Double]
)

case class RegionCoverage(
  regionName: String,
  contigName: String,
  startPosition: Int,
  endPosition: Int,
  coveredBases: Int,
  totalBases: Int,
  coveragePct: Double,
  meanDepth: Option[Double]
)
```

### 4. HaplogroupVariantQueryService

**Note:** This is a READ-ONLY query service for Y-DNA and mtDNA variants. The data is managed by the Haplogroup Discovery System (`haplogroup-discovery-system.md`).

**Data Sources:**
- `tree.biosample_private_variant` - Private variants per sample (from discovery system)
- `tree.haplogroup_variant` - Known tree-defining variants
- `tree.proposed_branch_variant` - Variants in pending proposals

Autosomal variant comparisons are performed Edge-to-Edge and are not managed here.

```scala
trait HaplogroupVariantQueryService {
  /**
   * Get Y/mtDNA variants for a sample that are relevant to haplogroup assignment.
   * Queries tree.biosample_private_variant and tree.haplogroup_variant.
   */
  def getHaplogroupVariantsForSample(
    sampleGuid: UUID,
    haplogroupType: HaplogroupType
  ): Future[Seq[IndexedHaplogroupVariant]]

  /**
   * Get private variants for a sample (not yet in the tree).
   * Queries tree.biosample_private_variant where status = 'ACTIVE'.
   *
   * @see BiosamplePrivateVariant in haplogroup-discovery-system.md
   */
  def getPrivateVariants(
    sampleGuid: UUID,
    haplogroupType: HaplogroupType
  ): Future[Seq[BiosamplePrivateVariantView]]

  /**
   * Get proposals this sample is contributing evidence to.
   * Queries tree.proposed_branch_evidence.
   */
  def getProposalsForSample(
    sampleGuid: UUID,
    haplogroupType: HaplogroupType
  ): Future[Seq[ProposedBranchSummary]]

  /**
   * Compare Y/mtDNA variants between samples for tree building purposes.
   * Uses Jaccard similarity algorithm from haplogroup-discovery-system.md.
   */
  def compareHaplogroupVariants(
    sampleGuid1: UUID,
    sampleGuid2: UUID,
    haplogroupType: HaplogroupType
  ): Future[HaplogroupVariantComparison]
}

case class IndexedHaplogroupVariant(
  sampleGuid: UUID,
  haplogroupType: HaplogroupType,
  variantId: Int,                  // FK to public.variant
  position: Int,
  referenceAllele: String,
  alternateAllele: String,
  isTreeDefining: Boolean,         // In tree.haplogroup_variant
  isPrivate: Boolean,              // In tree.biosample_private_variant with status=ACTIVE
  snpName: Option[String],         // e.g., "M269" if known
  sourceTestType: String,
  quality: Option[Double]
)

/**
 * View model for private variant display.
 * Aligns with BiosamplePrivateVariant from haplogroup-discovery-system.md.
 */
case class BiosamplePrivateVariantView(
  variantId: Int,
  position: Int,
  referenceAllele: String,
  alternateAllele: String,
  terminalHaplogroupName: String,  // Terminal haplogroup at time of discovery
  status: String,                  // ACTIVE, PROMOTED, INVALIDATED
  discoveredAt: LocalDateTime,
  inProposalIds: Seq[Int]          // tree.proposed_branch IDs where this appears
)

case class ProposedBranchSummary(
  proposalId: Int,
  parentHaplogroupName: String,
  proposedName: Option[String],
  status: String,
  consensusCount: Int,
  confidenceScore: Double
)

case class HaplogroupVariantComparison(
  haplogroupType: HaplogroupType,
  sharedKnownSnps: Seq[String],
  sharedPrivateVariantIds: Seq[Int],
  uniqueToSample1: Int,
  uniqueToSample2: Int,
  jaccardSimilarity: Double        // Using algorithm from discovery system
)
```

---

## API Endpoints

### Data Flow Pattern

**Important:** All citizen data flows through the PDS/Firehose pattern. There are NO direct submission APIs for citizen data.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              Data Flow                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Edge App                                                                │
│     │                                                                    │
│     ├──► GET /api/v1/test-types/* (Reference data - READ ONLY)          │
│     │                                                                    │
│     └──► Creates/updates biosample record in User's PDS                  │
│          (includes testType, haplogroup data, private variants)          │
│              │                                                           │
│              ▼                                                           │
│         AT Protocol Firehose                                             │
│              │                                                           │
│              ▼                                                           │
│         DecodingUs App View ingests biosample                            │
│              │                                                           │
│              ├──► Extracts test type metadata                            │
│              ├──► Extracts Y/mtDNA variants for tree building            │
│              └──► Triggers PrivateVariantExtractionService               │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Reference Data APIs (Read-Only)

These are the only public endpoints - they provide reference data for Edge Apps.

```
# Get marker coverage reference for a test type (helps Edge App know what to extract)
GET  /api/v1/test-types/{code}/haplogroup-marker-coverage
     ?haplogroupType={Y|MT}
     → TestTypeHaplogroupMarkerCoverage
```

### Removed APIs

The following APIs are NOT needed because data flows via PDS/Firehose:

| Removed | Reason |
|---------|--------|
| ~~`POST /api/v1/chip/register-summary`~~ | Metadata flows via biosample in PDS |
| ~~`POST /api/v1/chip/submit-haplogroup-data`~~ | Haplogroup data flows via biosample in PDS |
| ~~`POST /api/v1/import/targeted/y-dna`~~ | Targeted data flows via biosample in PDS |
| ~~`POST /api/v1/import/targeted/mt-dna`~~ | Targeted data flows via biosample in PDS |

**Note:** All test results (chip summaries, Y/mtDNA variants, haplogroup assignments) are included in the `com.decodingus.atmosphere.biosample` record in the user's PDS. The Firehose ingestion extracts this data and processes it through the appropriate services.

### Test Type Queries

```
# Test Type Definitions
GET  /api/v1/test-types
     ?category={SEQUENCING|GENOTYPING}
     ?supportsY={bool}
     ?supportsMt={bool}
     → Seq[TestTypeDefinition]

GET  /api/v1/test-types/{code}
     → TestTypeDefinition

GET  /api/v1/test-types/{code}/target-regions
     → Seq[TestTypeTargetRegion]

GET  /api/v1/test-types/{code}/capabilities
     → TestTypeCapabilities
```

### Haplogroup Variant Queries (Y/mtDNA Only)

**Note:** These endpoints query data managed by the Haplogroup Discovery System. The underlying tables are in the `tree` schema.

```
# Get indexed Y/mtDNA variants for a sample
# Queries: tree.biosample_private_variant, tree.haplogroup_variant
GET  /api/v1/haplogroup-variants/{sampleGuid}
     ?type={Y|MT}
     → Seq[IndexedHaplogroupVariant]

# Get private variants (not yet in tree)
# Queries: tree.biosample_private_variant WHERE status='ACTIVE'
GET  /api/v1/haplogroup-variants/{sampleGuid}/private
     ?type={Y|MT}
     → Seq[BiosamplePrivateVariantView]

# Get proposals this sample contributes to
# Queries: tree.proposed_branch_evidence
GET  /api/v1/haplogroup-variants/{sampleGuid}/proposals
     ?type={Y|MT}
     → Seq[ProposedBranchSummary]

# Compare Y/mtDNA variants between samples (for tree building)
# Uses Jaccard similarity from haplogroup-discovery-system.md
GET  /api/v1/haplogroup-variants/compare/{sampleGuid1}/{sampleGuid2}
     ?type={Y|MT}
     → HaplogroupVariantComparison
```

**See Also:** For full discovery/proposal management, use the Curator API defined in `haplogroup-discovery-system.md`:
- `GET /api/v1/discovery/proposals` - Query proposals
- `POST /api/v1/curator/proposals/{id}/accept` - Accept a proposal

---

## Haplogroup Assignment Considerations

### Test Type Limitations

| Test Type | Y-DNA Depth | mtDNA Depth | Haplogroup Accuracy |
|-----------|-------------|-------------|---------------------|
| WGS (30x) | Terminal | Terminal | Highest |
| Big Y-700 | Terminal+ | N/A | Highest for Y |
| Y Elite | Terminal+ | N/A | Highest for Y |
| MT Full Seq | N/A | Terminal | Highest for mt |
| 23andMe v5 | ~2000 SNPs | ~3000 SNPs | Major branch |
| AncestryDNA | ~2000 SNPs | ~3000 SNPs | Major branch |

### Haplogroup Confidence Scoring

```scala
case class HaplogroupConfidenceFactors(
  testType: String,
  snpsCovered: Int,
  snpsTotal: Int,
  coverageRatio: Double,
  depthAtDefiningSnps: Option[Double],
  estimatedAccuracy: HaplogroupAccuracy
)

enum HaplogroupAccuracy:
  case Terminal      // Can resolve to terminal branch
  case NearTerminal  // Within 1-2 levels of terminal
  case MajorBranch   // Can resolve major branch (e.g., R1b, H)
  case Uncertain     // Insufficient data
```

### Adjusted Haplogroup Assignment

For chip data, the haplogroup assignment algorithm must:

1. **Identify covered SNPs**: Query which defining SNPs are in the marker set
2. **Score against available data**: Only use markers present in the chip
3. **Report confidence**: Indicate depth limitation
4. **Flag uncertainty**: When missing key branch-defining SNPs

```scala
trait HaplogroupAssignmentService {
  /**
   * Assign haplogroup with test-type-aware confidence.
   */
  def assignHaplogroup(
    sampleGuid: UUID,
    haplogroupType: HaplogroupType,
    testType: String
  ): Future[HaplogroupAssignmentResult]
}

case class HaplogroupAssignmentResult(
  haplogroupResult: HaplogroupResult,
  confidenceFactors: HaplogroupConfidenceFactors,
  limitingFactors: Seq[String],       // e.g., "Missing SNP M269 coverage"
  suggestedUpgrade: Option[String]    // e.g., "Consider Big Y-700 for deeper Y-DNA"
)
```

---

## IBD Matching Considerations

### Cross-Test-Type Matching

When comparing samples from different test types:

```
WGS ↔ WGS:           Full comparison (~640K+ shared SNPs possible)
WGS ↔ Chip:          Limited to chip markers (~640K max)
Chip ↔ Chip (same):  Full chip comparison
Chip ↔ Chip (diff):  Intersection of marker sets
```

### Intersection Set Management

```sql
-- Pre-computed marker intersections between test types
CREATE TABLE test_type_marker_intersection (
    id SERIAL PRIMARY KEY,
    test_type_id_1 INTEGER NOT NULL REFERENCES test_type_definition(id),
    test_type_id_2 INTEGER NOT NULL REFERENCES test_type_definition(id),
    shared_marker_count INTEGER NOT NULL,
    computed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(LEAST(test_type_id_1, test_type_id_2), GREATEST(test_type_id_1, test_type_id_2))
);

-- Shared markers detail (for filtering during comparison)
CREATE TABLE test_type_shared_marker (
    intersection_id INTEGER NOT NULL REFERENCES test_type_marker_intersection(id),
    variant_id INTEGER NOT NULL REFERENCES variant(variant_id),
    PRIMARY KEY (intersection_id, variant_id)
);
```

### IBD Comparison Adjustments

```scala
trait IbdComparisonService {
  /**
   * Get comparable marker set between two samples.
   */
  def getComparableMarkers(
    sampleGuid1: UUID,
    testType1: String,
    sampleGuid2: UUID,
    testType2: String
  ): Future[ComparableMarkerSet]

  /**
   * Perform IBD comparison with test-type awareness.
   */
  def compareWithTestTypeAwareness(
    sampleGuid1: UUID,
    sampleGuid2: UUID
  ): Future[TestTypeAwareIbdResult]
}

case class ComparableMarkerSet(
  testType1: String,
  testType2: String,
  totalComparableMarkers: Int,
  chromosomeBreakdown: Map[String, Int],
  estimatedIbdResolution: String  // "High", "Medium", "Low"
)

case class TestTypeAwareIbdResult(
  ibdResult: IbdMatchDetails,
  comparableMarkers: Int,
  testTypeLimitation: Option[String],
  confidenceAdjustment: Double  // Factor to apply to relationship estimates
)
```

---

## File Format Support

### Sequencing Formats (Existing)

| Format | Extension | Current Support | Notes |
|--------|-----------|-----------------|-------|
| BAM | .bam | Yes | Aligned reads |
| CRAM | .cram | Yes | Compressed BAM |
| VCF | .vcf, .vcf.gz | Yes | Variants |
| FASTQ | .fastq, .fq | Yes | Raw reads |
| BED | .bed | Partial | Target regions |

### Chip Data Formats (Edge App Parsing)

**Note:** These parsers run in the Edge App, NOT on DecodingUs servers. The Edge App parses these files locally, stores raw data in the user's PDS, and only submits metadata + Y/mtDNA variants to DecodingUs.

| Vendor | Format | Extension | Structure |
|--------|--------|-----------|-----------|
| 23andMe | TSV | .txt | rsid, chromosome, position, genotype |
| AncestryDNA | TSV | .txt | rsid, chromosome, position, allele1, allele2 |
| FTDNA | CSV | .csv | RSID, CHROMOSOME, POSITION, RESULT |
| MyHeritage | CSV | .csv | RSID, CHROMOSOME, POSITION, RESULT |
| LivingDNA | CSV | .csv | Similar to 23andMe |

### Edge App Parser Interface

These interfaces are implemented in the Edge App, not in DecodingUs:

```scala
// Edge App implementation (TypeScript/JavaScript equivalent)
trait ChipDataParser {
  def detect(firstLines: Seq[String]): Boolean
  def parse(source: Source): Iterator[RawGenotypeCall]
  def vendor: String
  def inferVersion(header: String): Option[String]

  // Extract statistics for DecodingUs registration
  def extractSummary(calls: Seq[RawGenotypeCall]): GenotypingTestSummary

  // Extract Y/mtDNA variants for tree building
  def extractHaplogroupVariants(
    calls: Seq[RawGenotypeCall],
    markerCoverage: TestTypeHaplogroupMarkerCoverage
  ): ChipDerivedHaplogroupVariants
}

case class RawGenotypeCall(
  markerId: String,      // rsID or vendor marker name
  chromosome: String,
  position: Int,
  allele1: Char,
  allele2: Char,
  rawLine: String        // For debugging
)

// Edge App implementations (conceptual Scala - actual code is TypeScript)
class Parser23andMe extends ChipDataParser { ... }
class ParserAncestryDna extends ChipDataParser { ... }
class ParserFtdna extends ChipDataParser { ... }
```

---

## Implementation Phases

### Phase 1: Foundation

**Scope:**
- Test type taxonomy and database schema
- Domain models for test types
- Basic test type service

**Deliverables:**
- [ ] Database evolution for `test_type_definition` and related tables
- [ ] `TestTypeDefinition` domain model
- [ ] `TestTypeRepository`
- [ ] `TestTypeService`
- [ ] Seed data for initial test types
- [ ] Migrate existing `sequence_library.test_type` to new FK

### Phase 2: Chip Data Metadata Support

**Scope:**
- Chip test summary/metadata registration (NOT raw genotypes)
- Reference data for haplogroup marker coverage per chip type
- Edge App parsing libraries (runs client-side, not on DecodingUs)

**Deliverables:**
- [ ] `genotyping_test_summary` table (metadata only)
- [ ] `test_type_haplogroup_marker_coverage` reference table
- [ ] `GenotypingTestSummary` domain model
- [ ] `ChipDataRegistrationService`
- [ ] Registration API endpoints (`/api/v1/chip/register-summary`)
- [ ] Reference data for Y/mtDNA marker coverage per chip type
- [ ] Documentation for Edge App chip parsing requirements

### Phase 3: Targeted Sequencing Support

**Scope:**
- Target region definitions
- Coverage calculation for targeted tests
- Import support for Big Y, Y Elite, mtDNA

**Deliverables:**
- [ ] `test_type_target_region` table
- [ ] Target region data for Big Y-700, Y Elite, MT Full Sequence
- [ ] `TargetedSequencingService`
- [ ] Coverage calculation for targeted regions
- [ ] Import API endpoints

### Phase 4: Haplogroup Variant Query Layer

**Scope:**
- Query services for Y/mtDNA variants indexed by the Haplogroup Discovery System
- API endpoints for variant retrieval and comparison
- Integration testing with discovery system data

**Prerequisites:** Haplogroup Discovery System Phase 1-2 must be complete (see `haplogroup-discovery-system.md`)

**Deliverables:**
- [ ] `HaplogroupVariantQueryService` (read-only queries against `tree` schema)
- [ ] Haplogroup variant API endpoints (`/api/v1/haplogroup-variants/*`)
- [ ] Integration with `tree.biosample_private_variant`, `tree.proposed_branch_evidence`
- [ ] Test type-aware confidence scoring using `test_type_haplogroup_marker_coverage`

### Phase 5: Test-Type-Aware Haplogroup Confidence

**Scope:**
- Extend haplogroup assignment with test-type-aware confidence scoring
- Chip marker coverage impacts confidence levels
- Recommendations for deeper testing

**Prerequisites:** Haplogroup Discovery System must be operational

**Deliverables:**
- [ ] Enhanced `HaplogroupAssignmentService` with test type context
- [ ] `HaplogroupConfidenceFactors` calculation using `test_type_haplogroup_marker_coverage`
- [ ] Integration with discovery system's proposal visibility rules
- [ ] API updates for confidence reporting and upgrade recommendations

### Phase 6: IBD Integration

**Scope:**
- Cross-test-type IBD comparison
- Marker intersection management
- Confidence adjustments

**Deliverables:**
- [ ] `test_type_marker_intersection` table
- [ ] Intersection computation job
- [ ] Enhanced IBD comparison service
- [ ] Cross-test-type matching in discovery

---

## Edge App Coordination

### Edge App is the Primary Chip Data Processor

**Critical:** Raw chip genotype data NEVER leaves the Edge App. The Edge App is responsible for:

| Responsibility | Description |
|----------------|-------------|
| Chip file parsing | Parses raw chip exports from 23andMe, AncestryDNA, etc. |
| Format detection | Auto-detects vendor and version from file header |
| Data validation | Validates genotype calls and quality |
| Privacy filtering | User can exclude any markers before processing |
| Statistical extraction | Computes marker counts, no-call rates, coverage statistics |
| Y/mtDNA extraction | Extracts only Y and mtDNA variants for tree building |
| Haplogroup analysis | May perform preliminary haplogroup assignment locally |
| PDS storage | Stores full genotype data in user's PDS |

### What Edge App Submits to DecodingUs

| Data Type | Submitted | Purpose |
|-----------|-----------|---------|
| `GenotypingTestSummary` | Yes | Metadata for quality assessment |
| `ChipDerivedHaplogroupVariants` | Yes | Y/mtDNA variants for tree building |
| Raw genotype calls | **No** | Stays on Edge App and PDS |
| Autosomal variants | **No** | Used for Edge-to-Edge IBD only |

### Coordination Points

1. **Reference Data Download**: Edge App fetches marker coverage reference from DecodingUs to know which Y/mtDNA SNPs to extract
2. **Metadata Registration**: Edge App submits `GenotypingTestSummary` after local processing
3. **Haplogroup Variant Submission**: Edge App submits Y/mtDNA variants for tree building
4. **IBD Coordination**: Autosomal comparisons happen Edge-to-Edge per `ibd-matching-system.md`

---

## Configuration

```hocon
decodingus.test-types {
  # Default test type for legacy data without explicit type
  default-sequencing-type = "WGS"

  # Chip metadata registration settings
  chip-registration {
    # Minimum marker count to accept a chip registration
    min-marker-count = 100000
    # Maximum acceptable no-call rate
    max-no-call-rate = 0.05
  }

  # Haplogroup assignment
  haplogroup {
    min-snps-for-assignment = 10
    min-confidence-to-report = 0.7
    suggest-upgrade-threshold = 0.85
  }

  # Y/mtDNA variant submission
  haplogroup-variants {
    # Require minimum Y-DNA markers for branch proposal participation
    min-y-markers-for-proposals = 50
    # Require minimum mtDNA markers for branch proposal participation
    min-mt-markers-for-proposals = 20
  }

  # IBD comparison (Edge-to-Edge coordination)
  ibd {
    min-shared-markers-for-comparison = 100000
    low-density-warning-threshold = 200000
  }
}
```

---

## Testing Strategy

### Unit Tests
- Test type definition queries and capabilities
- `GenotypingTestSummary` validation
- Haplogroup confidence factor calculation
- Y/mtDNA variant submission validation

### Integration Tests
- Chip metadata registration workflow
- Haplogroup variant submission and indexing
- Targeted sequencing import (Y/mtDNA)
- Cross-test-type haplogroup comparison

### Data Validation Tests
- Verify marker coverage reference data accuracy
- Validate haplogroup confidence scoring
- Test coverage calculations for targeted regions

### Edge App Contract Tests
- Verify Edge App can parse reference data format
- Validate submission payload formats
- Test error handling for invalid submissions
- Verify round-trip data integrity
- Test marker intersection calculations

---

## Future Considerations

1. **Additional Vendors**: Support for more chip vendors as needed
2. **Imputation**: Impute missing genotypes for chip data
3. **Liftover**: Support multiple reference genome builds
4. **Custom Panels**: Support for clinical gene panels
5. **RNA-Seq**: Expression data (separate feature track)
6. **Methylation Arrays**: Epigenetic data support

---

## Appendix: Chip File Format Examples (Edge App Reference)

**Note:** These format examples are provided as reference documentation for Edge App developers. The Edge App is responsible for parsing these formats locally.

### 23andMe Format

```
# This data file generated by 23andMe at: Wed Jan 01 2025 00:00:00 GMT+0000
#
# rsid  chromosome  position  genotype
rs12345	1	12345	AA
rs23456	1	23456	AG
rs34567	1	34567	GG
...
```

### AncestryDNA Format

```
#AncestryDNA raw data download
#This file was generated by AncestryDNA
rsid	chromosome	position	allele1	allele2
rs12345	1	12345	A	A
rs23456	1	23456	A	G
rs34567	1	34567	G	G
...
```

### FTDNA Family Finder Format

```
RSID,CHROMOSOME,POSITION,RESULT
rs12345,1,12345,AA
rs23456,1,23456,AG
rs34567,1,34567,GG
...
```
