# Variant Schema Simplification with Universal Coordinates

**Status:** Backlog
**Priority:** Medium
**Complexity:** Large
**Author:** DecodingUs Team
**Created:** 2025-12-10

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Name is the primary identifier** | Coordinates can have parallel mutations; strand orientation varies |
| **No reference-agnostic mutation field** | G>A in GRCh38 may be C>T in CHM13 (reverse complement) |
| **JSONB for coordinates** | Each assembly needs its own position AND alleles |
| **JSONB for aliases** | Flexible, no joins, supports multiple sources |
| **`defining_haplogroup_id` FK** | Distinguishes parallel mutations without .1/.2 suffixes |
| **Haplogroup context = implicit suffix** | Display "L21 (R-L21)" vs "L21 (I-L21)" instead of "L21.1" vs "L21.2" |
| **1 row per named variant per lineage** | Parallel mutations at same position = separate rows, same name allowed |

---

## Related Documents

| Document | Relationship |
|----------|-------------|
| `../planning/haplogroup-discovery-system.md` | **Blocked by this proposal.** Discovery system requires new variant schema for parallel mutation handling. Added as Phase -1 prerequisite. |

---

## Problem Statement

The current variant storage model has several issues:

1. **Row explosion**: Each variant is stored N times (once per reference genome: GRCh37.p13, GRCh38.p14, T2T-CHM13v2.0)
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
- 3 variant rows (GRCh38.p14, GRCh37.p13, T2T-CHM13v2.0)
- Multiple alias rows per variant_id

---

## Proposed Solution: Universal Variant Model

### Core Concept: Variant Identity vs. Variant Coordinates

A genetic variant's **identity** is independent of its **coordinates**, but the **allele representation** depends on strand orientation:

| Aspect | Description | Example |
|--------|-------------|---------|
| **Identity** | The assigned name + haplogroup context | M269, or L21 in R-L21 vs L21 in I-L21 (parallel mutations) |
| **Coordinates** | Where it maps | GRCh38: chrY:2,887,824 / GRCh37: chrY:2,793,009 |
| **Alleles** | Reference-specific | GRCh38: G→A / CHM13: C→T (if reverse complemented) |

The **name** is stable; coordinates AND allele representations change with each reference assembly.

**Important**: T2T-CHM13v2.0 reverse complements large sections relative to GRCh38. A G>A mutation in GRCh38 may appear as C>T in CHM13. Each coordinate entry MUST store its own ref/alt alleles.

### Variant Equivalence Across Assemblies

When matching variants across assemblies, you CANNOT compare alleles directly:

```
GRCh38:  chrY:2887824  G → A  (forward strand)
CHM13:   chrY:2912345  C → T  (reverse complemented region)
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
    canonical_name  TEXT,                    -- Primary name (e.g., "M269"), NULL for unnamed/novel variants
    mutation_type   TEXT NOT NULL,           -- "SNP", "INDEL", "MNP"
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
    coordinates JSONB DEFAULT '{}',
    -- Example:
    -- {
    --   "GRCh38.p14": {
    --     "contig": "chrY",
    --     "position": 2887824,
    --     "ref": "G",
    --     "alt": "A"
    --   },
    --   "GRCh37.p13": {
    --     "contig": "chrY",
    --     "position": 2793009,
    --     "ref": "G",
    --     "alt": "A"
    --   },
    --   "T2T-CHM13v2.0": {
    --     "contig": "chrY",
    --     "position": 2912345,
    --     "ref": "C",              -- Reverse complemented!
    --     "alt": "T"               -- G→A becomes C→T
    --   },
    --   "pangenome_v1": {
    --     "node": "chrY.segment.12345",
    --     "offset": 42,
    --     "ref": "G",
    --     "alt": "A"
    --   }
    -- }

    -- Phylogenetic context (for haplogroup-defining variants)
    defining_haplogroup_id INTEGER REFERENCES tree.haplogroup(haplogroup_id),

    -- Metadata
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
        (coordinates->'GRCh38.p14'->>'contig'),
        ((coordinates->'GRCh38.p14'->>'position')::int),
        (coordinates->'GRCh38.p14'->>'ref'),
        (coordinates->'GRCh38.p14'->>'alt')
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
  "GRCh38.p14": {
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
    "note": "CHM13 path may have different alleles if reverse complemented"
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
WHERE coordinates->'GRCh38.p14'->>'position' = '2887824'
  AND coordinates->'GRCh38.p14'->>'contig' = 'chrY';

-- Find all variants with pangenome coordinates
SELECT * FROM variant_v2
WHERE coordinates ? 'HPRC_v1';

-- Get position in specific reference
SELECT
    canonical_name,
    coordinates->'GRCh38.p14'->>'position' as grch38_pos,
    coordinates->'GRCh37.p13'->>'position' as grch37_pos
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
│              → Submit to ISOGG for registration                          │
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

### ISOGG Submission Workflow

When assigning DU names, system should:

1. **Log the assignment** with coordinates, haplogroup context, evidence count
2. **Generate submission record** for ISOGG registration
3. **Track submission status**: PENDING → SUBMITTED → ACCEPTED/REJECTED
4. **Handle ISOGG feedback**: If ISOGG assigns different name, add as alias

```scala
case class IsoggSubmission(
  id: Option[Int],
  variantId: Int,
  duName: String,                    // Our assigned name (e.g., "DU00042")
  coordinates: JsonObject,           // GRCh38 coordinates
  definingHaplogroup: String,        // e.g., "R-DU00042"
  evidenceCount: Int,                // Supporting biosamples
  submittedAt: Option[LocalDateTime],
  submissionStatus: SubmissionStatus,
  isoggAssignedName: Option[String], // If ISOGG gives different name
  isoggResponse: Option[String],     // Notes from ISOGG
  resolvedAt: Option[LocalDateTime]
)

enum SubmissionStatus:
  case Pending     // Ready to submit
  case Submitted   // Sent to ISOGG
  case Accepted    // ISOGG accepted our name
  case Renamed     // ISOGG assigned different name (added as alias)
  case Rejected    // ISOGG rejected (duplicate, invalid, etc.)
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

ISOGG recognizes variant naming prefixes from authorized organizations:

| Prefix | Organization |
|--------|--------------|
| BY | BigY (FTDNA) |
| FGC | Full Genomes Corp |
| Y | YFull |
| FT | FTDNA (legacy) |
| S | ScotlandsDNA (now deprecated) |
| **DU** | **DecodingUs (proposed)** |

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
    "common_names": ["DU12345"],
    "external_names": ["BYxxxx", "Axxx"],
    "sources": {
      "decodingus": ["DU12345"],
      "ybrowse": ["Axxx"],
      "ftdna": ["BYxxxx"]
    }
  }
}
```

### Requirements for ISOGG Recognition

1. **Consistent naming scheme**: Sequential numbering with prefix
2. **Public variant database**: Our tree already provides this
3. **Submission process**: Contribute discoveries to ISOGG
4. **Quality standards**: Validated variants with evidence

### Action Items

- [ ] Contact ISOGG about registering "DU" prefix
- [ ] Define variant discovery/validation pipeline
- [ ] Create variant submission workflow to ISOGG
- [ ] Add `discovered_by` field to track DecodingUs-discovered variants

---

## References

- [GFA Format Specification](https://github.com/GFA-spec/GFA-spec) - Graph assembly format
- [VG Toolkit](https://github.com/vgteam/vg) - Pangenome tools
- [Human Pangenome Reference Consortium](https://humanpangenome.org/)
- [PostgreSQL JSONB Documentation](https://www.postgresql.org/docs/current/datatype-json.html)
- [ISOGG Y-DNA SNP Index](https://isogg.org/tree/) - Naming conventions
