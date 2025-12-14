# Universal Variant Schema Design

**Abstract:** A database schema design for handling genetic variants (SNPs, STRs, SVs) across multiple reference genomes (linear and graph-based) using a unified table structure with JSONB coordinates.

---

## Problem Statement

Traditional variant storage models often suffer from:
1. **Row explosion**: Storing copies of the same variant for each reference genome (GRCh37, GRCh38, hs1).
2. **Alias complexity**: Separate tables for aliases lead to complex joins.
3. **Liftover coupling**: Variants tightly coupled to specific linear coordinates break when reference genomes update.
4. **Graph incompatibility**: Linear integer coordinates don't map well to pangenome graph structures.

## Solution: Universal Variant Model

### Core Concept: Variant Identity vs. Variant Coordinates

A variant's **identity** is distinct from its **coordinates**.

| Aspect | Description | Example |
|--------|-------------|---------|
| **Identity** | The assigned name + phylogenetic context | `M269`, or `L21` (in R-L21 lineage) |
| **Coordinates** | Where it maps on a specific assembly | GRCh38: `chrY:2,887,824` |
| **Alleles** | Assembly-specific representation | GRCh38: `G→A` / hs1: `C→T` (reverse complemented) |

### Key Design Decisions

1.  **Name is the primary identifier**: Coordinates change; names are stable.
2.  **No reference-agnostic mutation field**: Alleles are stored per-coordinate because strand orientation differs between assemblies (e.g., GRCh38 vs T2T-CHM13/hs1).
3.  **JSONB for coordinates**: Stores mappings for multiple assemblies in a single row.
4.  **JSONB for aliases**: Stores common names, rsIDs, and source-specific IDs flexibly.
5.  **`defining_haplogroup_id` FK**: Distinguishes parallel mutations (homoplasy) where the same variant occurs in different lineages (e.g., L21 in R vs L21 in I).

## Schema Definition (`variant_v2`)

```sql
CREATE TABLE variant_v2 (
    variant_id      SERIAL PRIMARY KEY,

    -- Identity
    canonical_name  TEXT,                    -- e.g., "M269", NULL for novel variants
    mutation_type   TEXT NOT NULL,           -- "SNP", "STR", "DEL", "INS", "INV", etc.
    naming_status   TEXT NOT NULL DEFAULT 'UNNAMED',

    -- Aliases (JSONB)
    -- { "common_names": ["M269", "S21"], "rs_ids": ["rs9786076"], "sources": {...} }
    aliases JSONB DEFAULT '{}',

    -- Coordinates (JSONB)
    -- {
    --   "GRCh38": {"contig": "chrY", "position": 2887824, "ref": "G", "alt": "A"},
    --   "hs1":    {"contig": "chrY", "position": 2912345, "ref": "C", "alt": "T"}
    -- }
    coordinates JSONB DEFAULT '{}',

    -- Phylogenetic context (distinguishes parallel mutations)
    defining_haplogroup_id INTEGER REFERENCES tree.haplogroup(haplogroup_id),

    -- Metadata
    evidence JSONB DEFAULT '{}',
    primers JSONB DEFAULT '{}',
    notes TEXT,

    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes
CREATE UNIQUE INDEX idx_variant_v2_name_haplogroup
    ON variant_v2(canonical_name, COALESCE(defining_haplogroup_id, -1))
    WHERE canonical_name IS NOT NULL;

CREATE INDEX idx_variant_v2_coordinates ON variant_v2 USING GIN(coordinates);
CREATE INDEX idx_variant_v2_aliases ON variant_v2 USING GIN(aliases);
```

### Unified Coordinate Patterns

The `coordinates` JSONB field adapts to the mutation type:

| Mutation Type | JSON Structure |
|---------------|----------------|
| **SNP/INDEL** | `{"contig": "chrY", "position": 123, "ref": "A", "alt": "T"}` |
| **STR** | `{"contig": "chrY", "start": 100, "end": 200, "period": 4, "motif": "GATA"}` |
| **SV (DEL/INV)** | `{"contig": "chrY", "start": 100, "end": 5000, "length": 4900}` |

## Pangenome Compatibility

The schema supports graph coordinates alongside linear ones:

```json
{
  "GRCh38": { "type": "linear", "contig": "chrY", "position": 2887824 },
  "HPRC_v1": {
    "type": "pangenome",
    "graph": "minigraph-cactus",
    "node": "s12345",
    "offset": 42
  }
}
```

## Extensions

### STRs and Ancestral State Reconstruction (ASR)

STRs are treated as phylogenetic characters.
*   **Reference Data**: `str_mutation_rate` table stores per-marker mutation rates.
*   **ASR**: `haplogroup_character_state` stores inferred repeat counts at tree nodes.

### Genome Region Annotations

Non-variant annotations (Centromeres, PARs, Cytobands) use a similar JSONB pattern in a separate `genome_region_v2` table to handle multi-reference coordinates.

## References

*   [ISOGG Y-DNA SNP Index](https://isogg.org/tree/)
*   [Human Pangenome Reference Consortium](https://humanpangenome.org/)
*   [PostgreSQL JSONB Documentation](https://www.postgresql.org/docs/current/datatype-json.html)
