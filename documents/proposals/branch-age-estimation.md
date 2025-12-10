# Branch Age Estimation System

**Reference:** McDonald, I. (2021). "Improved Models of Coalescence Ages of Y-DNA Haplogroups." *Genes*, 12(6), 862. https://doi.org/10.3390/genes12060862

**Status:** Backlog
**Priority:** High
**Complexity:** Large
**Author:** DecodingUs Team
**Created:** 2025-12-10

---

## Related Planning Documents

This proposal integrates with other planning documents:

| Document | Relationship |
|----------|-------------|
| `../planning/haplogroup-discovery-system.md` | **Primary integration point.** SNP counts come from `tree.haplogroup_variant`. Private variants from `tree.biosample_private_variant` provide per-sample data for individual TMRCA calculations. Age recalculation should trigger when branches are promoted. |
| `../planning/multi-test-type-roadmap.md` | **Test type coverage data.** Callable loci vary by test type (WGS ~3Gbp, BigY-700 ~15Mbp, Chip ~2000 SNPs). Uses `test_type_definition` table for platform characteristics. |
| `../planning/appview-pds-backfeed-system.md` | **PDS data flow.** STR profiles and private SNP counts flow from user PDS via firehose. Age estimates are NOT backfed (computed results, not user data). |
| `group-project-system.md` | **Group TMRCA.** Group projects display TMRCA estimates in `projectTreeView`. Project-level modal haplotypes feed into STR-based age estimation. |

**Schema Note:** All haplogroup-related tables reside in the `tree` schema. Branch age fields (`formed_ybp`, `tmrca_ybp`, etc.) were added to `tree.haplogroup` in evolution 48.

---

## Overview

Implement automated branch age estimation (TMRCA calculation) for Y-DNA haplogroups using a probabilistic model that combines:
1. Y-SNP mutation counting
2. Y-STR genetic distance analysis
3. Historical/genealogical constraints
4. Ancient DNA calibration points

The system will calculate `formedYbp` and `tmrcaYbp` values with 95% confidence intervals for haplogroups in our tree, populating the fields already added to the data model.

---

## Methodology Summary

### Core Mathematical Principle

The age between any two nodes is the combination of evidence as probability distributions:

```
P(t|e) = k ∏ P(t|eᵢ)
```

Where evidence falls into three categories:
- **Y-SNPs** (including ancient DNA)
- **Y-STRs** (short tandem repeats)
- **Historical information** (genealogies, surnames, autosomal DNA)

### SNP-Based Age Calculation

**Key formula (Poisson distribution):**
```
P(t|m) = Poisson(m, tbµ) = (tbµ)^m × exp(-tbµ) / m!
```

Where:
- `t` = time in years
- `b` = callable loci (base pairs of coverage)
- `µ` = mutation rate (~8 × 10⁻¹⁰ SNPs/bp/year)
- `m` = observed mutations

**Temporal resolution:** `1/bµ ≈ 83 years per SNP` (for 15 Mbp coverage)

### STR-Based Age Calculation

**Key formula:**
```
P(t|g_STRs) = ∏ P(t|mₛ) × P(gₛ|mₛ)
```

Must account for:
- **Back mutations** (STR reverts to ancestral allele)
- **Parallel mutations** (independent lines mutate to same value)
- **Multi-step mutations** (+2, -2, +3, etc.)

**Multi-step frequencies:**
- ω±1 ≈ 0.962 (single-step)
- ω±2 ≈ 0.032 (two-step)
- ω±3 ≈ 0.004 (three-step)

### Confidence Intervals

95% CIs derived from the probability distributions, accounting for:
- Poisson noise (dominates for small mutation counts)
- Mutation rate uncertainty (~±8%)
- Convergent mutations in STRs

---

## Data Requirements

### 1. Reference Data (System-Level)

#### SNP Mutation Rate Table
| Region | Rate (SNPs/bp/yr) | 95% CI | Source |
|--------|-------------------|--------|--------|
| MSY Combined | 8.33 × 10⁻¹⁰ | 7.57–9.17 × 10⁻¹⁰ | Helgason 2015 |
| X-degenerate + Ampliconic | 8.71 × 10⁻¹⁰ | 8.03–9.43 × 10⁻¹⁰ | Helgason 2015 |
| Palindromic | 7.37 × 10⁻¹⁰ | 6.41–8.48 × 10⁻¹⁰ | Helgason 2015 |

#### STR Mutation Rate Database
Per-marker mutation rates needed for ~700+ Y-STR markers:
- Source: Ballantyne et al. 2010 (186 markers), Willems et al. 2016 (702 markers)
- Include per-marker confidence intervals
- Track directional bias (ω+ vs ω-)

**New table: `genomics.str_mutation_rate`**
```sql
CREATE TABLE genomics.str_mutation_rate (
    id SERIAL PRIMARY KEY,
    marker_name VARCHAR(50) NOT NULL UNIQUE,
    panel_names TEXT[],                    -- PowerPlex, YHRD, BigY, etc.
    mutation_rate DECIMAL(12,10) NOT NULL, -- per generation
    mutation_rate_lower DECIMAL(12,10),    -- 95% CI lower
    mutation_rate_upper DECIMAL(12,10),    -- 95% CI upper
    omega_plus DECIMAL(5,4) DEFAULT 0.5,   -- directional bias +
    omega_minus DECIMAL(5,4) DEFAULT 0.5,  -- directional bias -
    multi_step_rate DECIMAL(5,4),          -- ω±2 + ω±3 + ...
    source VARCHAR(200),
    created_at TIMESTAMP DEFAULT NOW()
);
```

#### Generation Length Parameters
| Parameter | Value | 95% CI | Notes |
|-----------|-------|--------|-------|
| Mean generation length | 33 years | 29–37 years | Pre-industrial average |
| Per-generation std dev | 8 years | — | Random scatter |

### 2. Haplogroup Reference Data

#### Ancestral STR Motifs
For each haplogroup, need modal/ancestral Y-STR values:

**New table: `tree.haplogroup_ancestral_str`**
```sql
CREATE TABLE tree.haplogroup_ancestral_str (
    id SERIAL PRIMARY KEY,
    haplogroup_id INTEGER REFERENCES tree.haplogroup(id),
    marker_name VARCHAR(50) NOT NULL,
    ancestral_value INTEGER,               -- Modal repeat count
    ancestral_value_alt INTEGER[],         -- Multi-modal alternatives
    confidence DECIMAL(3,2),               -- 0.0-1.0
    supporting_samples INTEGER,
    variance DECIMAL(8,4),
    computed_at TIMESTAMP,
    method VARCHAR(50),                    -- MODAL, PHYLOGENETIC, MANUAL
    UNIQUE(haplogroup_id, marker_name)
);
```

#### SNP Coverage by Test Type
Track callable loci per test platform.

**Integration with `test_type_definition`** (from `multi-test-type-roadmap.md`):

The `test_type_definition` table already captures platform characteristics. We need to add callable loci fields for age estimation:

```sql
-- Enhancement to existing test_type_definition table
ALTER TABLE test_type_definition ADD COLUMN
    callable_loci_bp INTEGER,              -- e.g., 15000000 for BigY-700
    callable_loci_regions JSONB;           -- Region breakdown by Y-chr region

-- Update existing test types with callable loci
UPDATE test_type_definition SET callable_loci_bp = 3000000000 WHERE code = 'WGS';       -- ~3 Gbp genome
UPDATE test_type_definition SET callable_loci_bp = 15000000 WHERE code = 'BIG_Y_700';   -- ~15 Mbp combbed region
UPDATE test_type_definition SET callable_loci_bp = 23000000 WHERE code = 'Y_ELITE';     -- ~23 Mbp Y chromosome
```

**Note:** Chip tests (23andMe, Ancestry) have limited Y-SNP coverage (~2000 markers) insufficient for SNP-based age calculation. Only STR-based estimation may be possible for chip users.

### 3. User Data from PDS (Atmosphere Records)

#### Required from CitizenBiosample/GenotypeData:
- [x] Y-haplogroup assignment (existing)
- [x] Test type/platform (existing)
- [x] Private variant details (flows to `tree.biosample_private_variant` via Discovery System)
- [ ] **NEW: Y-STR profile with marker values**
- [ ] **NEW: Callable loci summary** (from `callable_loci.bed`)

#### Y-STR Profile Record (Atmosphere)
Already defined in `AtmosphereRecords.scala`:
```scala
case class StrMarkerValue(
  marker: String,         // DYS19, DYS389I, etc.
  value: StrValue,        // SimpleStrValue, MultiCopyStrValue, ComplexStrValue
  panel: Option[String],
  quality: Option[String],
  readDepth: Option[Int]
)
```

**Gap:** Need to ensure STR profiles are being captured from PDS firehose.

### Edge Computing Model

**Critical Architecture Principle** (from `appview-pds-backfeed-system.md`): Raw genomic data (BAM/CRAM/VCF) **never** flows to DecodingUs. All raw data analysis happens locally in the Navigator Workbench.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                 AGE ESTIMATION DATA FLOW                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Navigator Workbench (Edge) - LOCAL PROCESSING:                         │
│  • Haplogroup calling (determines terminal haplogroup)                  │
│  • Private SNP counting (novel variants below terminal)                 │
│  • STR extraction from WGS/BAM files                                    │
│  • Callable loci calculation → produces callable_loci.bed               │
│                                                                          │
│  Output → Summary metadata synced to user's PDS:                        │
│  • biosample.haplogroups (terminal, path, private SNP count)            │
│  • strProfile (marker values for Y-STRs)                                │
│  • callable_loci.bed (anonymous genomic regions - can be shared)        │
│                                                                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  DecodingUs AppView - AGGREGATION:                                      │
│  • Collects private SNP counts across all samples                       │
│  • Aggregates STR profiles for modal haplotype computation              │
│  • Integrates per-sample callable loci for precise age calculation      │
│  • Calculates branch ages using network-wide data                       │
│  • Stores age estimates in tree.haplogroup                              │
│                                                                          │
│  DOES receive: Private SNP counts, STR profiles, callable_loci.bed,     │
│                haplogroup assignments                                    │
│  NEVER receives: BAM, CRAM, VCF, raw genotype files                     │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

**Note:** Age estimates are AppView-computed values stored in `tree.haplogroup`. They are NOT backfed to user PDS - users see them via the public tree API.

### Per-Sample Callable Loci Integration

The Edge Client produces `callable_loci.bed` files containing genomic regions with sufficient coverage for variant calling. This is **anonymous data** (just coordinates, no personal information) that can safely flow to DecodingUs.

**Why per-sample callable loci matters:**
- The Poisson formula `P(t|m) = Poisson(m, tbµ)` requires accurate `b` (callable loci)
- Using test-type averages (e.g., "BigY-700 = 15 Mbp") introduces systematic error
- Actual callable loci varies significantly between samples due to:
  - Sequencing depth variation
  - DNA quality differences
  - Lab-specific protocols
  - Region-specific coverage dropouts

**BED file integration:**

```sql
-- Per-biosample callable loci storage
-- Uses polymorphic reference pattern (consistent with tree.biosample_private_variant)
CREATE TABLE genomics.biosample_callable_loci (
    id SERIAL PRIMARY KEY,
    sample_type VARCHAR(20) NOT NULL,           -- 'citizen' or 'external'
    sample_id INTEGER NOT NULL,                 -- FK to citizen.biosample or external.biosample
    sample_guid UUID,                           -- For citizen samples, enables PDS correlation
    chromosome VARCHAR(20) NOT NULL,            -- chrY, chrM, etc.
    total_callable_bp BIGINT NOT NULL,          -- Sum of all callable regions
    region_count INTEGER,                       -- Number of discrete regions
    bed_file_hash VARCHAR(64),                  -- SHA-256 for deduplication
    computed_at TIMESTAMP NOT NULL,
    source_test_type_id INTEGER REFERENCES test_type_definition(id),

    -- Optional: Y-chromosome region breakdown for SNP age calculation
    y_xdegen_callable_bp BIGINT,                -- X-degenerate regions
    y_ampliconic_callable_bp BIGINT,            -- Ampliconic regions
    y_palindromic_callable_bp BIGINT,           -- Palindromic regions

    UNIQUE(sample_type, sample_id, chromosome),
    CHECK (sample_type IN ('citizen', 'external'))
);

CREATE INDEX idx_bcl_sample ON genomics.biosample_callable_loci(sample_type, sample_id);
CREATE INDEX idx_bcl_guid ON genomics.biosample_callable_loci(sample_guid) WHERE sample_guid IS NOT NULL;
```

**Atmosphere record extension:**

```scala
// In biosample record - callable loci summary
case class CallableLociSummary(
  chromosome: String,              // "chrY", "chrM"
  totalCallableBp: Long,           // Total callable base pairs
  regionCount: Option[Int],        // Number of discrete callable regions
  bedFileRef: Option[String]       // AT URI to full BED file if stored
)
```

**Age calculation improvement:**
- Phase 1 (SNP-only): Use per-sample `total_callable_bp` (from `genomics.biosample_callable_loci`) instead of test-type average
- Intersection calculation: When combining samples for haplogroup age, use intersection of callable regions
- Uncertainty reduction: Per-sample callable loci reduces `b` uncertainty from ~±20% to ~±2%
- Lookup: Join via `sample_type` + `sample_id` (polymorphic) or `sample_guid` for citizen samples

#### Private SNP Information

Private SNP counts for age estimation come from `tree.biosample_private_variant` (managed by the Haplogroup Discovery System), **not** directly from the PDS `mismatchingSnps` field.

**Why this matters:**
- The PDS `mismatchingSnps` count reflects the state at time of haplogroup calling
- The Discovery System may have since promoted some "private" variants to official branches
- Age estimation should use the **current** tree state, not the original assignment
- Only variants with `status = 'ACTIVE'` are truly private for age calculation purposes

**Data flow:**
```
PDS biosample.mismatchingSnps (original count)
        │
        ▼
tree.biosample_private_variant (detailed records)
        │
        ├── status = 'PROMOTED' → Now part of official tree, not counted
        ├── status = 'INVALIDATED' → Artifact/error, not counted
        └── status = 'ACTIVE' → True private SNPs for age estimation
```

**Query for age estimation:**
```sql
-- Get remaining private SNP count for a sample
SELECT COUNT(*) AS private_snp_count
FROM tree.biosample_private_variant
WHERE sample_guid = :sampleGuid
  AND haplogroup_type = 'Y'
  AND status = 'ACTIVE';
```

**Integration with `BranchAgeEstimationService`:**
```scala
// Get private SNP count from discovery system, not PDS
def getPrivateSnpCount(sampleGuid: UUID, haplogroupType: HaplogroupType): Future[Int] =
  biosamplePrivateVariantRepository.countActive(sampleGuid, haplogroupType)
```

This ensures age estimates automatically improve as the tree evolves - when a user's private variant is promoted to an official branch, their individual TMRCA calculation updates accordingly.

### 4. Historical/Genealogical Data

#### Paper Genealogies (Optional Enhancement)
For surname projects and known genealogies:

**New table: `tree.genealogical_anchor`**
```sql
CREATE TABLE tree.genealogical_anchor (
    id SERIAL PRIMARY KEY,
    haplogroup_id INTEGER REFERENCES tree.haplogroup(id),
    anchor_type VARCHAR(50),               -- KNOWN_MRCA, MDKA, ANCIENT_DNA
    date_ce INTEGER,                       -- Calendar year (negative for BC)
    date_uncertainty_years INTEGER,        -- ± years
    confidence DECIMAL(3,2),
    description TEXT,
    source VARCHAR(500),
    carbon_date_bp INTEGER,                -- For ancient DNA
    carbon_date_sigma INTEGER,
    created_at TIMESTAMP DEFAULT NOW()
);
```

### 5. Group Project Integration

The Group Project system (`group-project-system.md`) displays TMRCA estimates in its tree visualization. Integration points:

#### Project Tree View TMRCA
The `projectTreeView` record includes TMRCA estimates per node:
```json
"tmrcaEstimate": {
  "fromHaplogroup": "R-CTS4466",
  "toHaplogroup": "R-FT54321",
  "yearsBeforePresent": 750,
  "confidenceInterval": { "lower": 550, "upper": 1000 },
  "method": "COMBINED",
  "sampleSize": 45
}
```

#### Project Modal Haplotypes
Group projects compute modal STR haplotypes (`projectModal`). These can feed into:
- **Ancestral motif reconstruction** for the haplogroup tree
- **STR variance calculation** for project-specific TMRCA
- **Subgroup affinity** calculations

#### Integration Points
| Component | How It Uses Age Estimation |
|-----------|---------------------------|
| `projectTreeView.tmrcaEstimates` | Displays age estimates from `tree.haplogroup` |
| `projectModal` computation | Contributes to `tree.haplogroup_ancestral_str` |
| Project-specific TMRCA | Uses project member STRs for finer resolution |
| Genealogical anchors | Project admins can add known MRCAs for calibration |

---

## System Architecture

### Service Components

```
┌─────────────────────────────────────────────────────────────────┐
│                    BranchAgeEstimationService                    │
├─────────────────────────────────────────────────────────────────┤
│  - calculateHaplogroupAge(haplogroupId): AgeEstimateResult      │
│  - recalculateTreeAges(): BatchResult                           │
│  - validateAgeConsistency(): ValidationResult                   │
└───────────────────────────┬─────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
┌───────────────┐  ┌───────────────┐  ┌───────────────┐
│ SnpAgeService │  │ StrAgeService │  │HistoricalAge  │
│               │  │               │  │    Service    │
├───────────────┤  ├───────────────┤  ├───────────────┤
│ - countSnps() │  │ - getGeneticDist│ │ - applyAnchors│
│ - calcPoisson │  │ - calcP(g|m)  │  │ - applySurname│
│ - mergeCoverage │ │ - getAncestral│  │ - applyNRR()  │
└───────────────┘  └───────────────┘  └───────────────┘
        │                   │                   │
        └───────────────────┼───────────────────┘
                            ▼
                ┌───────────────────────┐
                │ ProbabilityDistribution│
                │        Service        │
                ├───────────────────────┤
                │ - multiply(pdf1, pdf2)│
                │ - convolve(pdf1, pdf2)│
                │ - get95CI()           │
                │ - getMedian()         │
                └───────────────────────┘
```

### Data Flow

```
1. COLLECT EVIDENCE
   ├── SNP Evidence
   │   ├── Count SNPs defining haplogroup
   │   ├── Count private SNPs per sample
   │   └── Get coverage intersection (b̄)
   │
   ├── STR Evidence
   │   ├── Get ancestral STR motif
   │   ├── Calculate genetic distance per marker
   │   └── Apply P(g|m) conversion
   │
   └── Historical Evidence
       ├── Genealogical anchors
       ├── Surname constraints
       └── Ancient DNA calibration

2. CALCULATE PDFs
   ├── P(t|SNPs) = ∏ Poisson(mₖ, tb̄µ)
   ├── P(t|STRs) = ∏ P(t|mₛ) × P(gₛ|mₛ)
   └── P(t|historical) = anchors × priors

3. COMBINE & PROPAGATE
   ├── P(t|all) = P(t|SNPs) × P(t|STRs) × P(t|historical)
   ├── Apply parent constraint (causality fix)
   └── Propagate up tree from leaves to root

4. OUTPUT
   ├── formedYbp (median)
   ├── formedYbpLower (2.5th percentile)
   ├── formedYbpUpper (97.5th percentile)
   ├── tmrcaYbp, tmrcaYbpLower, tmrcaYbpUpper
   └── ageEstimateSource
```

---

## Implementation Phases

### Phase 1: Foundation (SNP-Only Calculation)

**Goal:** Calculate basic age estimates using SNP counts only.

**Tasks:**
1. [ ] Create `BranchAgeEstimationService` with SNP-only calculation
2. [ ] Create `ProbabilityDistributionService` for PDF operations
3. [ ] Add SNP mutation rate configuration
4. [ ] Implement Poisson-based P(t|m) calculation
5. [ ] Add tree traversal to propagate ages bottom-up
6. [ ] Implement causality constraint (parent > child)
7. [ ] Create scheduled job to recalculate ages
8. [ ] Add curator tool to trigger recalculation

**Data needed:**
- SNP counts per haplogroup (already have via `haplogroup_variant`)
- Estimated coverage per haplogroup

### Phase 2: STR Integration

**Goal:** Add Y-STR data to improve precision.

**Tasks:**
1. [ ] Create `genomics.str_mutation_rate` table
2. [ ] Import mutation rates from Ballantyne/Willems studies
3. [ ] Create `tree.haplogroup_ancestral_str` table
4. [ ] Implement ancestral STR motif calculation (modal values)
5. [ ] Implement P(g|m) mapping with multi-step mutations
6. [ ] Create `StrAgeService` for STR-based age calculation
7. [ ] Integrate STR PDFs into combined calculation

**Data needed:**
- Y-STR profiles from PDS (ensure Atmosphere capture)
- Per-marker mutation rates

### Phase 3: Historical Integration

**Goal:** Incorporate genealogical anchors and priors.

**Tasks:**
1. [ ] Create `tree.genealogical_anchor` table
2. [ ] Add curator interface for anchor management
3. [ ] Implement anchor-based PDF constraints
4. [ ] Add Net Reproduction Rate prior (optional)
5. [ ] Support ancient DNA calibration points

### Phase 4: PDS Data Capture

**Goal:** Automatically capture age-relevant data from user submissions.

**Tasks:**
1. [ ] Ensure callable loci BED files captured via firehose (parse `callable_loci.bed`)
2. [ ] Ensure Y-STR profiles captured in firehose processing
3. [ ] Link citizen STR data to ancestral motif calculations
4. [ ] Create sample contribution tracking per haplogroup
5. [ ] Trigger Discovery System import when new samples arrive (private SNPs flow via Discovery System)

---

## Expected Precision

Based on paper's examples:

| Timeframe | SNP-only | SNP+STR Combined |
|-----------|----------|------------------|
| 300 years | ±100-200 years | ±50-100 years |
| 1100 years | ±300-400 years | ±150-200 years |
| 4000 years | ±500-800 years | ±300-500 years |

**Fundamental limits:**
- SNP mutation rate uncertainty: ~±8%
- Minimum temporal resolution: ~83 years/SNP (15 Mbp test)
- STR convergent mutations become significant >2000 years

---

## Reference Implementation

Dr. McDonald's reference implementation available at:
https://github.com/iain-mcdonald/TMRCA

Key algorithms to port:
- `calculate_snp_age()` - Poisson-based SNP calculation
- `calculate_str_age()` - STR with convergent mutation handling
- `combine_evidence()` - PDF multiplication
- `propagate_tree()` - Bottom-up tree traversal with causality fix

---

## Configuration

```hocon
age-estimation {
  # SNP mutation rate (per bp per year)
  snp-mutation-rate = 8.33e-10
  snp-mutation-rate-sigma = 0.4e-10

  # Generation length
  generation-length-years = 33
  generation-length-sigma = 4

  # STR multi-step frequencies
  str-omega-1 = 0.962
  str-omega-2 = 0.032
  str-omega-3 = 0.004

  # Calculation settings
  pdf-resolution = 10        # years per bin
  pdf-max-age = 100000       # years
  confidence-interval = 0.95

  # Scheduling
  recalculation-cron = "0 0 4 ? * SUN"  # Weekly Sunday 4 AM
}
```

---

## Success Criteria

1. **Accuracy:** Age estimates within published ranges for well-studied haplogroups (e.g., R-S781 ≈ 1245 CE)
2. **Precision:** 95% CIs comparable to YFull/McDonald paper results
3. **Consistency:** No causality violations (parent always older than child)
4. **Performance:** Full tree recalculation < 1 hour
5. **Auditability:** All calculations logged with input parameters

---

## Dependencies

- Existing: `HaplogroupCoreRepository`, `HaplogroupVariantRepository`, `TreeLayoutService`
- New reference data: STR mutation rates, ancestral motifs
- Optional: Ancient DNA sample database for calibration

---

## Open Questions

1. **STR data availability:** How many PDS users have Y-STR profiles? Need to assess data volume.
2. **Ancestral motif bootstrapping:** Initial motifs may need manual curation for major haplogroups.
3. **Calculation triggers:** On-demand vs scheduled vs event-driven (new samples)?
4. **YFull integration:** Should we import their existing age estimates as baseline?

---

## References

1. McDonald, I. (2021). Genes, 12(6), 862. [Primary methodology]
2. Helgason et al. (2015). Nat. Genet., 47, 453-457. [SNP mutation rates]
3. Ballantyne et al. (2010). Am. J. Hum. Genet., 87, 341-353. [STR mutation rates]
4. Willems et al. (2016). Am. J. Hum. Genet., 98, 919-933. [Population-scale STR rates]
5. Adamov et al. (2015). Russ. J. Genet. Geneal., 7, 68-82. [YFull methodology]
