# Variant Schema Simplification with Universal Coordinates

**Status:** Backlog
**Priority:** Medium
**Complexity:** Large
**Author:** DecodingUs Team
**Created:** 2025-12-10

---

## Key Design Decisions

| Decision | Rationale                                                                                             |
|----------|-------------------------------------------------------------------------------------------------------|
| **Name is the primary identifier** | Coordinates can have parallel mutations; strand orientation varies                                    |
| **No reference-agnostic mutation field** | G>A in GRCh38 may be C>T in hs1 (reverse complement)                                                  |
| **JSONB for coordinates** | Each assembly needs its own position AND alleles; structure varies by type                            |
| **JSONB for aliases** | Flexible, no joins, supports multiple sources                                                         |
| **`defining_haplogroup_id` FK** | Distinguishes parallel mutations without .1/.2 suffixes                                               |
| **Haplogroup context = implicit suffix** | Display "L21 (R-L21)" vs "L21 (I-L21)" instead of "L21.1" vs "L21.2"                                  |
| **1 row per named variant per lineage** | Parallel mutations at same position = separate rows, same name allowed                                |
| **Unified `variant_v2` for SNPs, STRs, SVs** | All phylogenetic characters in one table; `mutation_type` differentiates                              |
| **ASR character state tables** | `haplogroup_character_state` + `branch_mutation` support all variant types                            |
| **STRs typically don't define haplogroups** | Most STRs have `defining_haplogroup_id = NULL`; NULL alleles (e.g., R-U106>L1) can be branch-defining |
| **SVs can define branches** | Deletions, inversions, etc. are phylogenetically informative markers                                  |

---

## Related Documents

| Document | Relationship |
|----------|-------------|
| `../planning/haplogroup-discovery-system.md` | **Blocked by this proposal.** Discovery system requires new variant schema for parallel mutation handling. Added as Phase -1 prerequisite. |
| `branch-age-estimation.md` | **Depends on this proposal.** Branch age estimation uses ASR tables defined here. The `haplogroup_character_state` table replaces the originally-proposed `haplogroup_ancestral_str` table, providing unified modal/ancestral values for all variant types via ASR. |

---

## Problem Statement

The current variant storage model has several issues:

1. **Row explosion**: Each variant is stored N times (once per reference genome: GRCh37, GRCh38, hs1)
2. **Alias table complexity**: Separate `variant_alias` table requires joins and has duplication issues
3. **Liftover coupling**: Variants are tightly coupled to specific linear reference coordinates
4. **Future incompatibility**: Pangenome references (graph-based) will break the linear coordinate model

### Current Schema

```
variant (N rows per logical variant - one per reference genome)
├── variant_id (PK)
├── genbank_contig_id (FK) → ties to specific reference
├── position
├── reference_allele
├── alternate_allele
├── variant_type
├── rs_id
└── common_name

variant_alias (M rows per variant)
├── alias_id (PK)
├── variant_id (FK)
├── alias_type
├── alias_value
├── source
└── is_primary
```

**Example**: SNP "M269" currently creates:
- 3 variant rows (GRCh38, GRCh37, hs1)
- Multiple alias rows per variant_id

> **Note on Reference Genome Naming**: We use UCSC's naming convention `hs1` for the T2T-CHM13v2.0 assembly, following established precedent.

---

## Proposed Solution: Universal Variant Model

### Core Concept: Variant Identity vs. Variant Coordinates

A genetic variant's **identity** is independent of its **coordinates**, but the **allele representation** depends on strand orientation:

| Aspect | Description | Example |
|--------|-------------|---------|
| **Identity** | The assigned name + haplogroup context | M269, or L21 in R-L21 vs L21 in I-L21 (parallel mutations) |
| **Coordinates** | Where it maps | GRCh38: chrY:2,887,824 / GRCh37: chrY:2,793,009 |
| **Alleles** | Reference-specific | GRCh38: G→A / hs1: C→T (if reverse complemented) |

The **name** is stable; coordinates AND allele representations change with each reference assembly.

**Important**: hs1 (T2T-CHM13) reverse complements large sections relative to GRCh38. A G>A mutation in GRCh38 may appear as C>T in hs1. Each coordinate entry MUST store its own ref/alt alleles.

### Variant Equivalence Across Assemblies

When matching variants across assemblies, you CANNOT compare alleles directly:

```
GRCh38:  chrY:2887824  G → A  (forward strand)
hs1:     chrY:2912345  C → T  (reverse complemented region)
```

These are the SAME variant (M269), but allele comparison would fail.

The JSONB coordinates structure handles this by storing assembly-specific alleles:

### The Parallel Mutation Problem (Homoplasy)

**Critical**: The same genomic mutation can occur INDEPENDENTLY in unrelated lineages. These are NOT the same variant phylogenetically:

```
L21 (R-L21) = G→A at chrY:12345678 in R1b lineage
L21 (I-L21) = G→A at chrY:12345678 in I2 lineage (independent occurrence!)
```

Same position, same alleles, DIFFERENT variants. ISOGG historically used `.1`, `.2` suffixes (e.g., "L21.1", "L21.2") to distinguish parallel mutations, but has moved away from this due to complexity (see "Curator Workflow Changes" section below).

**Our approach**: Instead of explicit suffixes, we use `defining_haplogroup_id` to distinguish parallel mutations. Display shows "L21 (R-L21)" vs "L21 (I-L21)".

**Implications for variant identity**:

| What seems unique | Actually unique? |
|-------------------|------------------|
| Position + alleles | NO - parallel mutations exist at same position |
| Name alone (e.g., "L21") | NO - same name can occur in different lineages |
| Name + haplogroup context | YES - this is the true identity |

A variant's identity is: **name + phylogenetic lineage**

```
Variant identity = (canonical_name, defining_haplogroup_id)

L21 in R-L21  ≠  L21 in I-L21
(same name, same position, same mutation, but DIFFERENT variant rows due to different defining_haplogroup_id)
```

### Schema Implications

The schema must support multiple variants at the same genomic position:

```sql
-- These are TWO DIFFERENT rows in variant_v2:
-- Row 1: L21 defining R-L21 (defining_haplogroup_id = 12345)
-- Row 2: L21 defining I-L21 (defining_haplogroup_id = 67890, hypothetical parallel)

-- The coordinates JSONB may be identical, and canonical_name is the same, but:
-- - defining_haplogroup_id differs (this is what makes them distinct rows)
-- - UNIQUE constraint is on (canonical_name, defining_haplogroup_id)
```

This is why **name + haplogroup context is the primary identifier**, not coordinates alone.

### Universal Coordinate Design

For Y-DNA variants, the **assigned name + defining haplogroup** is the true identifier. Coordinates are just one representation of where that named mutation occurs.

```
Universal Variant Identifier (UVI):
├── canonical_name: "M269" or "L21" (primary name; may be NULL for unnamed variants)
├── mutation_type: "SNP" | "INDEL" | "MNP"
├── defining_haplogroup_id: FK to haplogroup (distinguishes parallel mutations with same name)
├── aliases: {...} (JSONB - all known names from all sources)
└── coordinates: {...} (JSONB - positions WITH ref/alt per assembly)
```

**Notes**:
- No reference-agnostic mutation field - alleles stored per-coordinate (strand orientation varies)
- No phylo_position text field - use `defining_haplogroup_id` FK to avoid stale data

### Proposed Schema

```sql
CREATE TABLE variant_v2 (
    variant_id      SERIAL PRIMARY KEY,

    -- Identity (stable across references)
    canonical_name  TEXT,                    -- Primary name (e.g., "M269", "DYS456"), NULL for unnamed/novel variants
    mutation_type   TEXT NOT NULL,           -- Point: "SNP", "INDEL", "MNP"
                                             -- Repeat: "STR"
                                             -- Structural: "DEL", "DUP", "INS", "INV", "CNV", "TRANS"
    naming_status   TEXT NOT NULL DEFAULT 'UNNAMED',  -- UNNAMED, PENDING_REVIEW, NAMED
    -- NOTE: No mutation field - alleles stored per-coordinate due to strand differences

    -- Aliases (JSONB - all known names)
    aliases JSONB DEFAULT '{}',
    -- Example:
    -- {
    --   "common_names": ["M269", "S21", "S312"],
    --   "rs_ids": ["rs9786076"],
    --   "sources": {
    --     "ybrowse": ["M269"],
    --     "isogg": ["M269", "S21"],
    --     "ftdna": ["S312"]
    --   }
    -- }

    -- Coordinates (JSONB - all reference positions)
    -- Keys use short reference names without patch versions (e.g., "GRCh38" not "GRCh38.p14")
    -- Structure varies by mutation_type (see "Coordinate JSONB Structure by mutation_type" below)
    coordinates JSONB DEFAULT '{}',
    -- SNP/INDEL/MNP example:
    -- {
    --   "GRCh38": {"contig": "chrY", "position": 2887824, "ref": "G", "alt": "A"},
    --   "hs1":    {"contig": "chrY", "position": 2912345, "ref": "C", "alt": "T"}  -- reverse complemented
    -- }
    --
    -- STR example:
    -- {
    --   "GRCh38": {"contig": "chrY", "start": 12997923, "end": 12998019, "repeat_motif": "GATA", "period": 4}
    -- }
    --
    -- DEL/DUP/INS example:
    -- {
    --   "GRCh38": {"contig": "chrY", "start": 58819361, "end": 58913456, "length": 94095}
    -- }

    -- Phylogenetic context
    -- For SNPs/SVs: the haplogroup this variant defines
    -- For STRs: usually NULL (repeat counts don't define haplogroups), but NULL alleles CAN define branches
    defining_haplogroup_id INTEGER REFERENCES tree.haplogroup(haplogroup_id),

    -- Additional metadata (optional, for GFF/evidence enrichment)
    evidence JSONB DEFAULT '{}',             -- {"yseq": {"tested": 5, "derived": 1}}
    primers JSONB DEFAULT '{}',              -- {"yseq": {"forward": "A1069_F", "reverse": "A1069_R"}}
    notes TEXT,                              -- "Downstream of S1121."

    -- Timestamps
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Unique constraint: same name can exist for different haplogroups (parallel mutations)
-- NULL canonical_name = unnamed/novel variant (identified by coordinates only)
-- NULL defining_haplogroup_id means "not yet assigned to a branch"
CREATE UNIQUE INDEX idx_variant_v2_name_haplogroup
    ON variant_v2(canonical_name, COALESCE(defining_haplogroup_id, -1))
    WHERE canonical_name IS NOT NULL;

-- For unnamed variants, uniqueness is based on coordinates within the primary reference
-- (prevents duplicate unnamed variants at same position)
-- Uses a functional index on JSONB to extract GRCh38 coordinates
CREATE UNIQUE INDEX idx_variant_v2_unnamed_coordinates
    ON variant_v2(
        (coordinates->'GRCh38'->>'contig'),
        ((coordinates->'GRCh38'->>'position')::int),
        (coordinates->'GRCh38'->>'ref'),
        (coordinates->'GRCh38'->>'alt')
    )
    WHERE canonical_name IS NULL;

-- Indexes for efficient querying
CREATE INDEX idx_variant_v2_canonical ON variant_v2(canonical_name);
CREATE INDEX idx_variant_v2_aliases ON variant_v2 USING GIN(aliases);
CREATE INDEX idx_variant_v2_coordinates ON variant_v2 USING GIN(coordinates);

-- Search by any alias
CREATE INDEX idx_variant_v2_alias_search ON variant_v2
    USING GIN((aliases->'common_names') jsonb_path_ops);
```

**Note on uniqueness**: The combination `(canonical_name, defining_haplogroup_id)` is unique, allowing:
- `L21` for `R-L21` (one row)
- `L21` for `I-L21` (another row - parallel mutation)
- But NOT two `L21` entries both pointing to `R-L21`

---

## Pangenome Compatibility

### The Pangenome Challenge

Linear references (GRCh37, GRCh38) use simple coordinates: `chromosome:position`.

Pangenome references use **graph coordinates**:
- Nodes represent sequence segments
- Edges represent connections (including structural variants)
- A position is: `node_id:offset` or `path:position`

### Graph Coordinate Support

The JSONB `coordinates` field naturally supports graph coordinates:

```json
{
  "GRCh38": {
    "type": "linear",
    "contig": "chrY",
    "position": 2887824,
    "ref": "G",
    "alt": "A"
  },
  "HPRC_v1": {
    "type": "pangenome",
    "graph": "minigraph-cactus",
    "node": "s12345",
    "offset": 42,
    "ref": "G",
    "alt": "A",
    "paths": ["GRCh38#chrY", "HG002#chrY"],
    "note": "hs1 path may have different alleles if reverse complemented"
  }
}
```

### Query Patterns

```sql
-- Find variant by any name
SELECT * FROM variant_v2
WHERE canonical_name = 'M269'
   OR aliases->'common_names' ? 'M269';

-- Find variant at GRCh38 position
SELECT * FROM variant_v2
WHERE coordinates->'GRCh38'->>'position' = '2887824'
  AND coordinates->'GRCh38'->>'contig' = 'chrY';

-- Find all variants with pangenome coordinates
SELECT * FROM variant_v2
WHERE coordinates ? 'HPRC_v1';

-- Get position in specific reference
SELECT
    canonical_name,
    coordinates->'GRCh38'->>'position' as grch38_pos,
    coordinates->'GRCh37'->>'position' as grch37_pos
FROM variant_v2
WHERE canonical_name = 'M269';
```

---

## Migration Strategy

### Phase 1: Create New Schema (Non-breaking)

1. Create `variant_v2` table alongside existing tables
2. Add migration job to populate from existing data
3. Update ingestion services to write to both schemas

### Phase 2: Migrate Read Operations

1. Update `VariantRepository` to read from new schema
2. Add compatibility layer for existing queries
3. Validate data consistency

### Phase 3: Deprecate Old Schema

1. Remove writes to old tables
2. Drop `variant_alias` table
3. Rename `variant_v2` to `variant`

### Data Migration Query

```sql
-- Consolidate existing variants into new schema
-- NOTE: Preserves NULL for truly unnamed variants (no common_name, no rs_id)
INSERT INTO variant_v2 (canonical_name, mutation_type, naming_status, aliases, coordinates)
SELECT
    -- Prefer common_name, then rs_id; leave NULL if neither exists
    COALESCE(v.common_name, v.rs_id) as canonical_name,
    v.variant_type as mutation_type,
    -- Set naming_status based on whether we have a name
    CASE
        WHEN v.common_name IS NOT NULL OR v.rs_id IS NOT NULL THEN 'NAMED'
        ELSE 'UNNAMED'
    END as naming_status,
    jsonb_build_object(
        'common_names', COALESCE(
            (SELECT jsonb_agg(DISTINCT alias_value)
             FROM variant_alias
             WHERE variant_id = v.variant_id AND alias_type = 'common_name'),
            '[]'::jsonb
        ),
        'rs_ids', COALESCE(
            (SELECT jsonb_agg(DISTINCT alias_value)
             FROM variant_alias
             WHERE variant_id = v.variant_id AND alias_type = 'rs_id'),
            '[]'::jsonb
        )
    ) as aliases,
    jsonb_build_object(
        gc.reference_genome, jsonb_build_object(
            'contig', gc.common_name,
            'position', v.position,
            'ref', v.reference_allele,
            'alt', v.alternate_allele
        )
    ) as coordinates
FROM variant v
JOIN genbank_contig gc ON v.genbank_contig_id = gc.genbank_contig_id
GROUP BY v.common_name, v.rs_id, v.variant_id, v.variant_type,
         gc.reference_genome, gc.common_name, v.position,
         v.reference_allele, v.alternate_allele;
```

**Note on migration**: The original `variant` table row-per-reference model means each named variant may have multiple rows (one per assembly). This migration inserts one row per source variant; a follow-up query should merge coordinates for variants with the same `(common_name, rs_id)` across different reference genomes.

---

## Benefits

| Current | Proposed |
|---------|----------|
| N rows per variant (one per reference) | 1 row per **named** variant (parallel mutations = separate rows) |
| Separate alias table with joins | Inline JSONB aliases |
| Liftover creates new rows | Liftover adds to coordinates JSONB |
| Linear coordinates only | Supports linear + graph coordinates |
| Hard to find "all names for variant" | Single query on JSONB |
| Schema changes for new references | Just add to JSONB |
| Alleles assumed same across refs | Per-assembly alleles (handles strand differences) |

---

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| JSONB query performance | GIN indexes on JSONB fields |
| Data consistency (no FK on coordinates) | Application-level validation |
| Complex migration | Phased approach, dual-write period |
| Breaking existing code | Compatibility layer in repository |
| Parallel mutations sharing coordinates | Name is PK, not coordinates; `defining_haplogroup_id` distinguishes |
| Strand differences between assemblies | Each coordinate entry stores its own ref/alt alleles |

---

## Impact on Curator Workflows

### Current Problem

The current curator workflow has no safeguards for parallel mutations:

```
Curator A: Search "L21" → Create variant → Link to R-L21
Curator B: Search "L21" → Find existing → Link to I-L21 (different branch!)
Result: ONE variant_id linked to TWO unrelated haplogroups
```

This conflates two distinct scenarios:

| Scenario | What happened | Correct handling |
|----------|---------------|------------------|
| **Shared ancestral** | Mutation occurred once, defines common ancestor of both branches | One variant, linked to ancestor haplogroup |
| **Parallel mutation** | Same mutation occurred independently in both lineages | TWO variant rows (L21 for R-L21, L21 for I-L21), each with different `defining_haplogroup_id` |

### ISOGG Naming Complexity

ISOGG moved away from `.1/.2` suffixes due to:
- **Order of discovery**: Which one is `.1`? First discovered? Most common?
- **Renaming churn**: As tree evolves, suffixes may need to change
- **User confusion**: People don't understand the suffixes

But without suffixes, parallel mutations are **invisible** in the naming system.

### Curator Workflow Changes Needed

**1. Parallel mutation detection warning:**
```
⚠️ Warning: Variant "L21" is already associated with haplogroup R-L21.
   You're adding it to I-L21 which is in a different branch.

   Options:
   [ ] This is a shared ancestral mutation (use existing variant)
   [ ] This is a parallel mutation (create new variant "L21" for I-L21)
```

**2. Schema support for same-name variants:**
```sql
-- Current: unique on (contig_id, position, ref, alt)
-- Allows same name but fails on same coordinates

-- Proposed: unique on (canonical_name, defining_haplogroup_id)
-- Allows same coordinates for parallel mutations with different names/haplogroups
UNIQUE (canonical_name, defining_haplogroup_id)
```

**3. Display disambiguation:**
When showing variants that have parallel occurrences:
```
L21 (R-L21)     -- displayed as "L21" in R1b context
L21 (I-L21)     -- displayed as "L21" in I2 context
```

The haplogroup context serves as implicit `.1/.2` without explicit suffixes.

### Backward Compatibility

Existing data may have parallel mutations incorrectly stored as:
- One variant linked to multiple unrelated haplogroups, OR
- Multiple variants with same name (ambiguous which is which)

**Migration approach:**
1. Identify variants linked to multiple haplogroups
2. Check if haplogroups share a common ancestor → keep as shared
3. If no common ancestor → flag for curator review to split into parallel mutations

---

## Novel Variant Naming Workflow

### The Lifecycle of an Unnamed Variant

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Novel Variant Lifecycle                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  1. DISCOVERY                                                            │
│     Biosample arrives with private variants (mismatching SNPs)           │
│     Variant has: coordinates, ref/alt alleles, NO NAME                   │
│     naming_status = 'UNNAMED'                                            │
│     canonical_name = NULL                                                │
│                                                                          │
│  2. PROPOSAL BUILDING                                                    │
│     Multiple biosamples share the unnamed variant                        │
│     Variant linked to ProposedBranch                                     │
│     Still unnamed, but gaining evidence                                  │
│                                                                          │
│  3. CURATOR REVIEW                                                       │
│     Proposal reaches threshold, curator reviews                          │
│     naming_status = 'PENDING_REVIEW'                                     │
│     Curator can assign name from external source OR request DU name      │
│                                                                          │
│  4. NAMING (on promotion)                                                │
│     Option A: External name known (e.g., "BY12345" from FTDNA)           │
│              → canonical_name = "BY12345", naming_status = 'NAMED'       │
│                                                                          │
│     Option B: Novel discovery, no external name                          │
│              → Request DU sequence number                                │
│              → canonical_name = "DU00001", naming_status = 'NAMED'       │
│              → Publish in VCF/GFF for YBrowse aggregation                │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Temporary Identifiers for Unnamed Variants

While unnamed, variants need a way to be referenced. Options:

| Approach | Example | Pros | Cons |
|----------|---------|------|------|
| **Coordinate-based** | `chrY:2887824:G>A` | Unambiguous | Long, assembly-specific |
| **Hash-based** | `var_a7f3b2c1` | Short, unique | Not human-friendly |
| **Sequence-based** | `NOVEL_00001` | Sortable by discovery order | Needs sequence management |

**Recommendation**: Use coordinate-based display (`chrY:2887824:G>A`) with internal `variant_id` for tracking. Store primary reference (GRCh38.p14) coordinates in a generated display field.

### DU Naming Sequence

```sql
-- Sequence for DecodingUs variant names
CREATE SEQUENCE du_variant_name_seq START WITH 1;

-- Function to get next DU name
CREATE FUNCTION next_du_name() RETURNS TEXT AS $$
BEGIN
    RETURN 'DU' || LPAD(nextval('du_variant_name_seq')::TEXT, 5, '0');
END;
$$ LANGUAGE plpgsql;

-- Example: SELECT next_du_name(); → 'DU00001'
```

### Curator Naming Interface

When promoting a proposal, curator sees:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Assign Names to Defining Variants                                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Variant 1: chrY:2887824:G>A                                            │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │ ○ External name: [____________]  (e.g., BY12345)                │    │
│  │ ● Assign DU name: DU00042 (next available)                      │    │
│  │ ○ Leave unnamed (not recommended)                               │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  Variant 2: chrY:2889100:C>T                                            │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │ ● External name: [FGC98765____]  (known from FTDNA)             │    │
│  │ ○ Assign DU name: DU00043 (next available)                      │    │
│  │ ○ Leave unnamed (not recommended)                               │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  [ Cancel ]                              [ Promote with Names ]          │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### YBrowse Integration

YBrowse aggregates variant names from all research organizations into a central database administered by YSEQ's chief officer. Each organization uses their own prefix:

| Prefix | Organization | Status |
|--------|--------------|--------|
| A | FTDNA (legacy naming) | Active |
| BY | BigY (FTDNA) | Active |
| CTS | Chris Tyler-Smith | Active |
| DF | ScotlandsDNA | Deprecated |
| DU | **DecodingUs** | **Proposed** |
| F | Li Jin | Active |
| FGC | Full Genomes Corp | Active |
| FT | FTDNA (legacy) | Deprecated |
| L | FTDNA (legacy) | Active |
| M | Stanford (Underhill/Jobling) | Active |
| P | AFDIL (Hammer) | Active |
| PF | Paolo Francalacci | Active |
| S | ScotlandsDNA | Deprecated |
| U | Uppsala | Active |
| V | Valencia | Active |
| Y | YFull | Active |
| Z | FTDNA (legacy) | Active |

To have DU variants included in YBrowse:

1. **Publish variants** with DU names in our public tree
2. **Provide VCF/GFF export** in standard format for aggregation
3. **Document coordinates** with GRCh38 positions (YBrowse standard)

YBrowse will aggregate our DU-prefixed names alongside names from other sources. A variant may have multiple names from different organizations (e.g., DU00042 = BY98765 = FGC54321).

```scala
// Track when variants are published and any cross-references discovered
case class VariantPublication(
  id: Option[Int],
  variantId: Int,
  duName: String,                    // Our assigned name (e.g., "DU00042")
  coordinates: JsonObject,           // GRCh38 coordinates
  definingHaplogroup: String,        // e.g., "R-DU00042"
  evidenceCount: Int,                // Supporting biosamples
  publishedAt: LocalDateTime,
  crossReferences: Map[String, String]  // Other names: {"ybrowse": "A12345", "ftdna": "BY98765"}
)
```

### YBrowse GFF Data Enrichment

YBrowse provides data in both VCF and GFF formats. The GFF format contains significantly richer metadata that should be ingested when implementing the new schema:

**GFF Example (FGC29071):**
```
Name:              FGC29071
allele_anc:        A
allele_der:        C
comment:           Downstream of S1121.
count_derived:     1
count_tested:      5
isogg_haplogroup:  R1b1a1a2a1a2c1c1b1a1b4a2a
mutation:          A to C
primer_f:          A1069_F
primer_r:          A1069_R
ref:               Full Genomes Corp. (2014)
ycc_haplogroup:    R1b-CTS4466 (not listed)
yfull_node:        Not found on the YFull Ytree
primary_id:        171175
```

**Field Mapping for variant_v2:**

| GFF Field | Schema Field / Use | Notes |
|-----------|-------------------|-------|
| `ref` | `aliases.sources` key | Full source attribution: "Full Genomes Corp. (2014)" |
| `allele_anc` / `allele_der` | `coordinates.*.ref/alt` | Clearer than VCF ref/alt terminology |
| `count_derived` / `count_tested` | New: `evidence` JSONB | YSEQ testing evidence (see below) |
| `comment` | New: `notes` field | Contextual info like "Downstream of S1121" |
| `isogg_haplogroup` | Cross-ref to tree | Full ISOGG path for validation |
| `primer_f` / `primer_r` | New: `primers` JSONB | YSEQ Sanger sequencing primers |
| `yfull_node` | `aliases.sources.yfull` | YFull tree cross-reference |
| `ycc_haplogroup` | Historical reference | YCC nomenclature (deprecated but useful) |
| `primary_id` | `aliases.sources.ybrowse_id` | YBrowse internal ID for back-linking |

**YSEQ Evidence Fields:**

The `count_derived` and `count_tested` fields are testing results from [YSEQ.net](https://yseq.net). YSEQ's chief officer administers the YBrowse variant list and provides these evidence counts to indicate testing confidence:

- `count_tested`: Number of samples tested for this variant
- `count_derived`: Number showing the derived (mutant) allele
- Example: 1/5 = 20% derived rate in tested population

**YSEQ Primer Information:**

The `primer_f` and `primer_r` fields are YSEQ's PCR primers, provided for transparency. When ordering a Sanger sequencing test from YSEQ, these primers define exactly what region is amplified and sequenced. This enables:

- Independent verification of test methodology
- Primer design for other labs
- Understanding of test coverage/limitations

**Proposed Schema Addition:**

```sql
-- Add to variant_v2 for GFF-sourced metadata
ALTER TABLE variant_v2 ADD COLUMN evidence JSONB DEFAULT '{}';
-- Example: {"yseq": {"tested": 5, "derived": 1, "as_of": "2024-01"}}

ALTER TABLE variant_v2 ADD COLUMN primers JSONB DEFAULT '{}';
-- Example: {"yseq": {"forward": "A1069_F", "reverse": "A1069_R"}}

ALTER TABLE variant_v2 ADD COLUMN notes TEXT;
-- Example: "Downstream of S1121."
```

---

## Open Questions

1. **Canonical name selection**: When merging variants, which name becomes canonical?
   - Proposal: ISOGG name > YBrowse name > rs_id > generated ID

2. **Coordinate provenance**: Should we track WHO added each coordinate?
   - Proposal: Add `source` to each coordinate entry in JSONB

3. **Variant merging**: How to handle when two "different" variants are discovered to be the same?
   - Proposal: Soft-merge with alias preservation
   - Note: Be careful not to merge parallel mutations (L21 in R-L21 ≠ L21 in I-L21)

4. ~~**Haplogroup-variant relationship**: Should this move to the variant table?~~
   - **Resolved**: Yes, `defining_haplogroup_id` FK included in schema. Required to distinguish parallel mutations.

5. **Parallel mutation discovery**: How to detect when a "new" variant is actually a parallel occurrence of an existing one?
   - This requires phylogenetic analysis, not just coordinate comparison
   - May need human curation or algorithmic detection based on haplogroup placement

---

## Future Consideration: DecodingUs as Naming Authority

### ISOGG Naming Organization

YBrowse (administered by YSEQ) recognizes variant naming prefixes from authorized organizations. See the full prefix table in the "YBrowse Integration" section above.

### Benefits of Becoming a Naming Authority

1. **Canonical naming**: Variants discovered through DecodingUs citizen science get official names
2. **Avoid naming conflicts**: Our own namespace prevents collisions
3. **Community recognition**: Establishes DecodingUs as a legitimate research entity
4. **Data provenance**: Clear attribution for variant discoveries

### Proposed Prefix: `DU`

```
DU00001  - First DecodingUs-named variant
DU00002  - Second variant
...
```

### Schema Support

The proposed JSONB schema naturally supports this:

```json
{
  "canonical_name": "DU12345",
  "aliases": {
    "common_names": ["DU12345", "BYxxxx", "Axxx"],
    "rs_ids": [],
    "sources": {
      "decodingus": ["DU12345"],
      "ybrowse": ["Axxx"],
      "ftdna": ["BYxxxx"]
    }
  }
}
```

### Requirements for YBrowse Aggregation

YBrowse aggregates from organizations that meet these criteria:

1. **Consistent naming scheme**: Sequential numbering with unique prefix (DU)
2. **Public variant database**: Searchable tree with variant details
3. **VCF availability**: Standard format for aggregation
4. **Quality standards**: Validated variants with evidence
5. **Coordinate accuracy**: GRCh38 positions matching YBrowse conventions

### Action Items

- [ ] Establish "DU" prefix convention in our public tree
- [ ] Define variant discovery/validation pipeline
- [ ] Create VCF export endpoint for YBrowse aggregation
- [ ] Add `discovered_by` field to track DecodingUs-discovered variants
- [ ] Build cross-reference sync to track when same variant has multiple names (DU* = BY* = FGC*)

---

## Extension: STRs and Ancestral State Reconstruction

### Rethinking STRs as Phylogenetic Characters

Initially, STRs might seem fundamentally different from SNPs:

| Aspect | SNP | STR |
|--------|-----|-----|
| **Variation type** | Single nucleotide change | Repeat count variation |
| **Allele representation** | ref/alt bases (G→A) | Repeat count (e.g., 12, 13, 14) |
| **Mutation rate** | ~10⁻⁸ per generation | ~10⁻³ per generation (1000x higher) |
| **Mutation model** | Infinite sites (rarely back-mutates) | Stepwise (can increase/decrease) |

However, with **Ancestral State Reconstruction (ASR)**, both become phylogenetic characters:

| Concept | SNP | STR |
|---------|-----|-----|
| **Character** | The variant itself | The STR marker |
| **Character state** | Ancestral (G) or Derived (A) | Repeat count (12, 13, 14...) |
| **ASR output** | Inferred ancestral allele at each node | Inferred repeat count at each node |
| **Branch annotation** | "M269: G→A" | "DYS456: 15→16" |

This suggests **STRs should be unified with variants**, not kept separate.

### STR Coordinate Structure

STRs use `mutation_type = 'STR'` in the canonical `variant_v2` table (see "Proposed Schema" above). The `coordinates` JSONB for STRs includes:

```json
{
  "GRCh38": {
    "contig": "chrY",
    "start": 12997923,
    "end": 12998019,
    "repeat_motif": "GATA",
    "period": 4,
    "reference_repeats": 13
  }
}
```

**Key difference from SNPs**: Most STRs have `defining_haplogroup_id = NULL` because repeat count variation doesn't typically define haplogroups—states are reconstructed at all nodes via ASR. However, **NULL alleles** (e.g., DYS439 NULL under R-U106, also known as L1/S26) can be branch-defining and would have a `defining_haplogroup_id` set.

### Ancestral State Reconstruction Tables

ASR produces inferred character states at internal tree nodes. This applies to **both SNPs and STRs**:

```sql
-- Reconstructed character states at haplogroup nodes
CREATE TABLE haplogroup_character_state (
    id              SERIAL PRIMARY KEY,
    haplogroup_id   INT NOT NULL REFERENCES haplogroup(haplogroup_id),
    variant_id      INT NOT NULL REFERENCES variant_v2(variant_id),

    -- The inferred state at this node
    -- For SNPs: "ancestral" or "derived" (or the actual allele: "G", "A")
    -- For STRs: the repeat count as string (e.g., "15") or "NULL" for null alleles
    inferred_state  TEXT NOT NULL,

    -- Confidence/probability from ASR algorithm
    confidence      DECIMAL(5,4),         -- 0.0000 to 1.0000

    -- For STRs: probability distribution over states (optional, for uncertain reconstructions)
    state_probabilities JSONB,
    -- Example: {"13": 0.05, "14": 0.25, "15": 0.65, "16": 0.05}

    -- ASR metadata
    algorithm       TEXT,                 -- "parsimony", "ml", "bayesian"
    reconstructed_at TIMESTAMPTZ DEFAULT NOW(),

    UNIQUE(haplogroup_id, variant_id)
);

CREATE INDEX idx_character_state_haplogroup ON haplogroup_character_state(haplogroup_id);
CREATE INDEX idx_character_state_variant ON haplogroup_character_state(variant_id);
```

### Branch Mutations (State Changes)

For tree visualization and analysis, track where states change along branches:

```sql
-- State changes along tree branches
CREATE TABLE branch_mutation (
    id              SERIAL PRIMARY KEY,
    variant_id      INT NOT NULL REFERENCES variant_v2(variant_id),

    -- The branch where the mutation occurred (parent → child)
    parent_haplogroup_id INT NOT NULL REFERENCES haplogroup(haplogroup_id),
    child_haplogroup_id  INT NOT NULL REFERENCES haplogroup(haplogroup_id),

    -- State transition
    from_state      TEXT NOT NULL,        -- "G" or "15"
    to_state        TEXT NOT NULL,        -- "A" or "16"

    -- For STRs: direction of change
    -- +1 = expansion, -1 = contraction, NULL for SNPs
    step_direction  INT,

    -- Confidence from ASR
    confidence      DECIMAL(5,4),

    UNIQUE(variant_id, parent_haplogroup_id, child_haplogroup_id)
);

CREATE INDEX idx_branch_mutation_child ON branch_mutation(child_haplogroup_id);
```

### Query Examples with Unified Model

```sql
-- Get all character states at a haplogroup node (SNPs and STRs together)
SELECT
    v.canonical_name,
    v.mutation_type,
    hcs.inferred_state,
    hcs.confidence
FROM haplogroup_character_state hcs
JOIN variant_v2 v ON hcs.variant_id = v.variant_id
WHERE hcs.haplogroup_id = 12345
ORDER BY v.mutation_type, v.canonical_name;

-- Get STR mutations along a branch (useful for age estimation)
SELECT
    v.canonical_name,
    bm.from_state,
    bm.to_state,
    bm.step_direction
FROM branch_mutation bm
JOIN variant_v2 v ON bm.variant_id = v.variant_id
WHERE bm.child_haplogroup_id = 12345
  AND v.mutation_type = 'STR';

-- Reconstruct ancestral STR haplotype at a node
SELECT
    v.canonical_name,
    hcs.inferred_state as repeat_count,
    hcs.confidence
FROM haplogroup_character_state hcs
JOIN variant_v2 v ON hcs.variant_id = v.variant_id
WHERE hcs.haplogroup_id = 12345
  AND v.mutation_type = 'STR'
ORDER BY v.canonical_name;
```

### Biosample Observations

Observed values from actual samples (input to ASR):

```sql
-- Observed character states from biosamples
CREATE TABLE biosample_variant_call (
    id              SERIAL PRIMARY KEY,
    biosample_id    INT NOT NULL REFERENCES biosample(id),
    variant_id      INT NOT NULL REFERENCES variant_v2(variant_id),

    -- The observed state
    -- For SNPs: "ref", "alt", "het", or actual alleles
    -- For STRs: repeat count as string (e.g., "15")
    observed_state  TEXT NOT NULL,

    -- Call quality
    quality_score   INT,
    read_depth      INT,
    confidence      TEXT,                 -- "high", "medium", "low"

    -- Source
    source          TEXT,                 -- "ftdna", "yfull", "user_upload"
    created_at      TIMESTAMPTZ DEFAULT NOW(),

    UNIQUE(biosample_id, variant_id)
);
```

### STR Mutation Rate Reference Data

STR-based ASR and age estimation require per-marker mutation rates. This reference table supports both:

```sql
-- Per-marker STR mutation rates (reference data)
CREATE TABLE str_mutation_rate (
    id SERIAL PRIMARY KEY,
    marker_name TEXT NOT NULL UNIQUE,         -- DYS456, DYS389I, etc.
    panel_names TEXT[],                       -- PowerPlex, YHRD, BigY, etc.

    -- Mutation rate per generation
    mutation_rate DECIMAL(12,10) NOT NULL,
    mutation_rate_lower DECIMAL(12,10),       -- 95% CI lower
    mutation_rate_upper DECIMAL(12,10),       -- 95% CI upper

    -- Directional bias (for stepwise mutation model)
    omega_plus DECIMAL(5,4) DEFAULT 0.5,      -- Probability of expansion
    omega_minus DECIMAL(5,4) DEFAULT 0.5,     -- Probability of contraction

    -- Multi-step mutation frequencies
    multi_step_rate DECIMAL(5,4),             -- ω±2 + ω±3 + ...

    source TEXT,                              -- Ballantyne 2010, Willems 2016, etc.
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Link variant_v2 STR entries to their mutation rates
CREATE INDEX idx_str_mutation_rate_marker ON str_mutation_rate(marker_name);
```

**Sources**: Ballantyne et al. 2010 (186 markers), Willems et al. 2016 (702 markers)

### Why Unification Makes Sense

1. **ASR treats them the same**: Both are characters with states at nodes
2. **Tree visualization**: Show SNP and STR mutations on branches together
3. **Age estimation**: STR mutation counts inform TMRCA calculations
4. **Simpler queries**: One table for "all variants at this haplogroup"
5. **Consistent coordinate pattern**: JSONB handles the structural differences

### What Differs (handled in JSONB/metadata)

| Aspect | SNP | STR | How Handled |
|--------|-----|-----|-------------|
| Allele type | Bases | Count or NULL | `coordinates` JSONB structure differs |
| Defines haplogroup | Usually yes | Rarely (NULL alleles can) | `defining_haplogroup_id` usually NULL for STRs |
| State space | Binary (anc/der) | Integer range + NULL | `state_probabilities` JSONB |
| Mutation model | Infinite sites | Stepwise | `step_direction` in branch_mutation |
| Rate data | Fixed per region | Per-marker | `str_mutation_rate` reference table |

### Modal Haplotypes (Derived from ASR)

Modal haplotypes become a **view over ASR results**, not a separate table:

```sql
-- Modal haplotype is just the reconstructed state at the haplogroup node
CREATE VIEW haplogroup_str_modal AS
SELECT
    hcs.haplogroup_id,
    v.canonical_name as str_name,
    hcs.inferred_state::int as modal_value,
    hcs.confidence
FROM haplogroup_character_state hcs
JOIN variant_v2 v ON hcs.variant_id = v.variant_id
WHERE v.mutation_type = 'STR';
```

### Current STR Schema Migration Path

The existing `str_marker` table (from 50.sql) migrates into `variant_v2`:

```sql
INSERT INTO variant_v2 (canonical_name, mutation_type, naming_status, coordinates)
SELECT
    sm.name,
    'STR',
    'NAMED',
    jsonb_build_object(
        gc.reference_genome,
        jsonb_build_object(
            'contig', gc.common_name,
            'start', sm.start_pos,
            'end', sm.end_pos,
            'period', sm.period,
            'repeat_motif', 'UNKNOWN'  -- Will need enrichment from external data
        )
    )
FROM str_marker sm
JOIN genbank_contig gc ON sm.genbank_contig_id = gc.genbank_contig_id;
```

---

## Extension: Structural Variants (SVs)

### SVs as Branch-Defining Markers

Structural variants are phylogenetically informative and can define haplogroup branches, as demonstrated in [Poznik et al. 2016](https://www.science.org/doi/10.1126/science.aab3812) and subsequent work on Y chromosome phylogeny.

This means SVs should be **unified with SNPs and STRs** in `variant_v2`, not kept separate.

### SV Characteristics

| SV Type | Description | Size Range |
|---------|-------------|------------|
| **Deletion (DEL)** | Sequence removed | 50bp - Mb |
| **Duplication (DUP)** | Sequence copied | 50bp - Mb |
| **Insertion (INS)** | Sequence added | 50bp - Mb |
| **Inversion (INV)** | Sequence reversed | 1kb - Mb |
| **Translocation (TRANS)** | Sequence moved to different location | Variable |
| **CNV** | Copy Number Variant (special case of DUP/DEL) | Variable |

### SV Coordinate Challenges

SVs have unique coordinate considerations (handled in JSONB):

1. **Breakpoint precision**: Start/end may be imprecise (±bp) → `confidence_interval`
2. **Reference content**: May need sequence hash → `deleted_sequence_hash`
3. **Complex events**: Inversions have inner coordinates → `inner_start`, `inner_end`
4. **Size**: Length stored explicitly → `length`

### Unified Schema: SVs in variant_v2

SVs become additional `mutation_type` values in the unified table:

```sql
-- mutation_type now includes:
-- Point mutations: 'SNP', 'INDEL', 'MNP'
-- Repeat variations: 'STR'
-- Structural variants: 'DEL', 'DUP', 'INS', 'INV', 'CNV', 'TRANS'

-- SV coordinate examples in variant_v2:

-- Deletion example:
-- {
--   "GRCh38": {
--     "contig": "chrY",
--     "start": 58819361,
--     "end": 58913456,
--     "length": 94095,
--     "confidence_interval_start": [-50, 50],
--     "confidence_interval_end": [-100, 100],
--     "deleted_sequence_hash": "sha256:abc123..."
--   }
-- }

-- Inversion example:
-- {
--   "GRCh38": {
--     "contig": "chrY",
--     "start": 58819361,
--     "end": 58913456,
--     "length": 94095,
--     "inner_start": 58819500,
--     "inner_end": 58913300
--   }
-- }

-- CNV example (with copy number):
-- {
--   "GRCh38": {
--     "contig": "chrY",
--     "start": 23800000,
--     "end": 24100000,
--     "length": 300000,
--     "reference_copies": 2,
--     "copy_number_range": [0, 4]
--   }
-- }
```

### SVs in ASR Context

Like SNPs and STRs, SVs have character states at tree nodes:

| Variant Type | Character State | Example |
|--------------|-----------------|---------|
| SNP | Ancestral or Derived allele | G or A |
| STR | Repeat count or NULL | 15, NULL |
| DEL/DUP/INS | Presence/Absence | "present" or "absent" |
| INV | Orientation | "forward" or "inverted" |
| CNV | Copy number | 0, 1, 2, 3... |

The `haplogroup_character_state` and `branch_mutation` tables handle all of these:

```sql
-- SV state at a node
INSERT INTO haplogroup_character_state (haplogroup_id, variant_id, inferred_state, confidence)
VALUES (12345, 999, 'present', 0.98);  -- Deletion is present at this haplogroup

-- SV mutation on a branch
INSERT INTO branch_mutation (variant_id, parent_haplogroup_id, child_haplogroup_id, from_state, to_state)
VALUES (999, 100, 12345, 'absent', 'present');  -- Deletion arose on this branch
```

### Known Branch-Defining Y-DNA SVs

| Name | Type | Size | Defining Haplogroup | Reference |
|------|------|------|---------------------|-----------|
| **AZFa deletion** | DEL | ~800kb | Multiple independent | Medical/fertility |
| **AZFb deletion** | DEL | ~6.2Mb | Multiple independent | Medical/fertility |
| **AZFc deletion** | DEL | ~3.5Mb | Multiple independent | Medical/fertility |
| **gr/gr deletion** | DEL | ~1.6Mb | Various | Repping et al. 2006 |
| **IR2 inversion** | INV | ~300kb | Specific lineages | Poznik et al. 2016 |
| **P1-P8 palindrome variants** | Various | Variable | Various | Skaletsky et al. 2003 |

### SV Evidence Fields

SVs often require additional evidence metadata:

```sql
-- The existing 'evidence' JSONB field in variant_v2 handles this:
-- {
--   "call_method": "read_depth",       -- or "split_read", "paired_end", "assembly"
--   "supporting_reads": 45,
--   "quality_score": 99,
--   "callers_agreeing": ["manta", "delly", "lumpy"],
--   "validated": true,
--   "validation_method": "PCR"
-- }
```

### Query Examples

```sql
-- Find all SVs defining a haplogroup
SELECT v.canonical_name, v.mutation_type, v.coordinates
FROM variant_v2 v
WHERE v.defining_haplogroup_id = 12345
  AND v.mutation_type IN ('DEL', 'DUP', 'INS', 'INV', 'CNV', 'TRANS');

-- Get all branch-defining variants (SNPs + SVs) for a haplogroup
SELECT v.canonical_name, v.mutation_type,
       bm.from_state, bm.to_state
FROM branch_mutation bm
JOIN variant_v2 v ON bm.variant_id = v.variant_id
WHERE bm.child_haplogroup_id = 12345;

-- Find large deletions (>100kb)
SELECT v.canonical_name,
       (v.coordinates->'GRCh38'->>'length')::int as length_bp
FROM variant_v2 v
WHERE v.mutation_type = 'DEL'
  AND (v.coordinates->'GRCh38'->>'length')::int > 100000;
```

---

## Extension: Genome Region Annotations

### Genome Annotation Tables

The non-variant tables in evolution 50.sql share the multi-reference coordinate challenge:

| Table | Purpose | Coordinate Nature |
|-------|---------|-------------------|
| `genome_region` | Structural annotations (centromere, PAR, etc.) | Start/end positions |
| `cytoband` | Cytogenetic bands | Start/end positions |

**Note**: The `str_marker` table from 50.sql migrates into `variant_v2` (see "STR Migration" below), since STRs are phylogenetic characters used in ASR.

### Current Schema (50.sql)

```sql
-- Structural regions
CREATE TABLE genome_region (
  id SERIAL PRIMARY KEY,
  genbank_contig_id INT NOT NULL REFERENCES genbank_contig(genbank_contig_id),
  region_type VARCHAR(30) NOT NULL,   -- Centromere, Telomere_P, PAR1, etc.
  name VARCHAR(50),                   -- For named regions (P1-P8 palindromes)
  start_pos BIGINT NOT NULL,
  end_pos BIGINT NOT NULL,
  modifier DECIMAL(3,2),              -- Quality modifier
  UNIQUE(genbank_contig_id, region_type, name, start_pos)
);

-- Cytobands
CREATE TABLE cytoband (
  id SERIAL PRIMARY KEY,
  genbank_contig_id INT NOT NULL REFERENCES genbank_contig(genbank_contig_id),
  name VARCHAR(20) NOT NULL,          -- p11.32, q11.21, etc.
  start_pos BIGINT NOT NULL,
  end_pos BIGINT NOT NULL,
  stain VARCHAR(10) NOT NULL,         -- gneg, gpos25, acen, etc.
  UNIQUE(genbank_contig_id, name)
);
```

### Recommendation: Keep Regions Separate from Variants

Genome annotations differ from variants in key ways:

| Aspect | Variants (SNP/STR/SV) | Genome Annotations |
|--------|----------------------|-------------------|
| **Identity** | Name + haplogroup context | Name + region type |
| **Variability** | Varies between individuals | Fixed per reference |
| **Updates** | Continuous discovery | Per-reference updates |
| **Query pattern** | "Where is M269?" | "What region contains position X?" |

**Recommendation**: Keep `genome_region` and `cytoband` in a separate table (`genome_region_v2`), but apply the JSONB coordinate pattern for multi-reference support:

```sql
CREATE TABLE genome_region_v2 (
    region_id       SERIAL PRIMARY KEY,
    region_type     TEXT NOT NULL,        -- Centromere, Telomere_P, PAR1, XTR, etc.
    name            TEXT,                 -- For named regions (P1-P8 palindromes)

    -- Coordinates per reference (the key insight from variant_v2)
    coordinates JSONB NOT NULL,
    -- Example:
    -- {
    --   "GRCh38": {
    --     "contig": "chrY",
    --     "start": 10316944,
    --     "end": 10544039
    --   },
    --   "GRCh37": {
    --     "contig": "chrY",
    --     "start": 10246944,
    --     "end": 10474039
    --   },
    --   "hs1": {
    --     "contig": "chrY",
    --     "start": 10400000,
    --     "end": 10627095
    --   }
    -- }

    -- Region-specific metadata
    properties JSONB DEFAULT '{}',
    -- Example for regions: {"modifier": 0.5}
    -- Example for cytobands: {"stain": "gpos75"}

    UNIQUE(region_type, name)
);

CREATE INDEX idx_genome_region_v2_coords ON genome_region_v2 USING GIN(coordinates);

-- Efficient lookup: "What region contains GRCh38:chrY:15000000?"
CREATE INDEX idx_genome_region_v2_grch38_range ON genome_region_v2 (
    (coordinates->'GRCh38'->>'contig'),
    ((coordinates->'GRCh38'->>'start')::bigint),
    ((coordinates->'GRCh38'->>'end')::bigint)
);
```

---

## Unified Coordinate Pattern Summary

The key insight across all these schemas is the **JSONB coordinate pattern**:

```json
{
  "GRCh38": { "contig": "chrY", "start": 12345, "end": 12345, ... },
  "GRCh37": { "contig": "chrY", "start": 12300, "end": 12300, ... },
  "hs1":    { "contig": "chrY", "start": 12400, "end": 12400, ... }
}
```

### Schema Unification Summary

| Feature | Table | Rationale |
|---------|-------|-----------|
| **SNP/INDEL/MNP** | `variant_v2` | Core point mutations |
| **STR** | `variant_v2` | Unified - phylogenetic characters for ASR |
| **SV (DEL/DUP/INS/INV/CNV)** | `variant_v2` | Unified - branch-defining markers |
| **STR mutation rates** | `str_mutation_rate` | Reference data - per-marker rates for ASR/age estimation |
| **Genome Region** | `genome_region_v2` | Separate - fixed per reference, not variants |
| **Cytoband** | `genome_region_v2` | Merged with regions via `properties` JSONB |

**Key insight**: If it can define a branch or has states reconstructed by ASR, it belongs in `variant_v2`.

### Coordinate JSONB Structure by mutation_type

| mutation_type | Coordinate Fields | Extra Fields |
|---------------|-------------------|--------------|
| **SNP** | contig, position | ref, alt |
| **INDEL** | contig, position | ref, alt |
| **MNP** | contig, position | ref, alt |
| **STR** | contig, start, end | repeat_motif, period, reference_repeats |
| **DEL/DUP/INS** | contig, start, end, length | confidence_intervals, sequence_hash |
| **INV** | contig, start, end, length | inner_start, inner_end |
| **CNV** | contig, start, end, length | reference_copies, copy_number_range |

| Separate Table | Coordinate Fields | Extra Fields |
|----------------|-------------------|--------------|
| **genome_region_v2** | contig, start, end | (in `properties`: modifier, stain) |

### ASR Integration

With ancestral state reconstruction, all variant types share these tables:

| Table | Purpose |
|-------|---------|
| `haplogroup_character_state` | Inferred state at each tree node (replaces `haplogroup_ancestral_str` concept) |
| `branch_mutation` | State transitions along branches |
| `biosample_variant_call` | Observed values (input to ASR) |
| `str_mutation_rate` | Per-marker mutation rates for STR ASR/age estimation |

| Variant Type | State Type | Example States |
|--------------|------------|----------------|
| SNP/INDEL/MNP | Allele | "G", "A", "ancestral", "derived" |
| STR | Repeat count or NULL | "13", "14", "15", "NULL" |
| DEL/DUP/INS | Presence | "present", "absent" |
| INV | Orientation | "forward", "inverted" |
| CNV | Copy number | "0", "1", "2", "3" |

---

## Migration Considerations

### STR Migration

STRs migrate into `variant_v2` as `mutation_type = 'STR'`. See the migration query in the "STRs and Ancestral State Reconstruction" section above.

### SV Migration

SVs migrate into `variant_v2` with `mutation_type` set to the specific SV type ('DEL', 'DUP', 'INS', 'INV', 'CNV'). If there's no existing SV table, SVs will be ingested directly into the new schema.

### Genome Region Migration

```sql
INSERT INTO genome_region_v2 (region_type, name, coordinates, properties)
SELECT
    gr.region_type,
    gr.name,
    jsonb_build_object(
        gc.reference_genome,
        jsonb_build_object(
            'contig', gc.common_name,
            'start', gr.start_pos,
            'end', gr.end_pos
        )
    ) as coordinates,
    CASE WHEN gr.modifier IS NOT NULL
         THEN jsonb_build_object('modifier', gr.modifier)
         ELSE '{}'::jsonb
    END as properties
FROM genome_region gr
JOIN genbank_contig gc ON gr.genbank_contig_id = gc.genbank_contig_id;
```

---

## References

- [GFA Format Specification](https://github.com/GFA-spec/GFA-spec) - Graph assembly format
- [VG Toolkit](https://github.com/vgteam/vg) - Pangenome tools
- [Human Pangenome Reference Consortium](https://humanpangenome.org/)
- [PostgreSQL JSONB Documentation](https://www.postgresql.org/docs/current/datatype-json.html)
- [ISOGG Y-DNA SNP Index](https://isogg.org/tree/) - Naming conventions
- [YHRD STR Database](https://yhrd.org/) - Y-STR reference
- [dbVar](https://www.ncbi.nlm.nih.gov/dbvar/) - NCBI Structural Variation database
- [Hallast et al. 2021](https://www.science.org/doi/10.1126/science.abg8871) - Y chromosome structural variants and phylogeny
- [Poznik et al. 2016](https://www.science.org/doi/10.1126/science.aab3812) - Punctuated bursts in human male demography
