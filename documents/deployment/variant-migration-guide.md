# Variant Schema Migration Guide

**Context:** Migrating from row-per-reference `variant` table to unified `variant_v2` table.

## Implementation Status

| Component | Status | Notes |
|-----------|--------|-------|
| **Schema Definition** | ✅ Done | `variant_v2` created via evolution 53.sql |
| **Code Updates** | ✅ Done | Repositories, Services, and Controllers updated to use `VariantV2` |
| **Data Migration** | ⏳ Pending | Scripts ready, pending execution in production |
| **Cleanup** | ⏳ Pending | Dropping old tables (`variant`, `variant_alias`, `str_marker`) |

## Migration Strategy

### Phase 1: Dual Schema (Completed)
The `variant_v2` table exists alongside the legacy `variant` table. New code writes to `variant_v2` (via the new repository), but the old tables are preserved for rollback safety.

### Phase 2: Data Migration (Action Required)
Run the migration query to consolidate existing data into the new JSONB structure.

#### Migration Query
This query groups legacy rows by common identity (name/rsID) and aggregates their coordinates into the JSONB format.

```sql
INSERT INTO variant_v2 (canonical_name, mutation_type, naming_status, aliases, coordinates)
SELECT
    COALESCE(v.common_name, v.rs_id) as canonical_name,
    v.variant_type as mutation_type,
    CASE
        WHEN v.common_name IS NOT NULL OR v.rs_id IS NOT NULL THEN 'NAMED'
        ELSE 'UNNAMED'
    END as naming_status,
    jsonb_build_object(
        'common_names', COALESCE(
            (SELECT jsonb_agg(DISTINCT alias_value) FROM variant_alias WHERE variant_id = v.variant_id AND alias_type = 'common_name'), '[]'::jsonb
        ),
        'rs_ids', COALESCE(
            (SELECT jsonb_agg(DISTINCT alias_value) FROM variant_alias WHERE variant_id = v.variant_id AND alias_type = 'rs_id'), '[]'::jsonb
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
-- Grouping logic handles deduplication of coordinate rows
GROUP BY v.common_name, v.rs_id, v.variant_id, v.variant_type, gc.reference_genome, gc.common_name, v.position, v.reference_allele, v.alternate_allele;
```

**Post-Migration Step:** Run a second pass to merge rows that have the same `canonical_name` but different reference genomes (e.g., merge the GRCh38 row and the GRCh37 row for "M269" into a single row).

### Phase 3: Verification & Cleanup
1.  Verify counts match between `variant` (grouped by name) and `variant_v2`.
2.  Verify application functionality using the new repository.
3.  Drop `variant`, `variant_alias`, and `str_marker` tables.

## Special Cases

### STR Migration
STRs from `str_marker` are migrated into `variant_v2` with `mutation_type = 'STR'`.

```sql
INSERT INTO variant_v2 (canonical_name, mutation_type, naming_status, coordinates)
SELECT
    sm.name, 'STR', 'NAMED',
    jsonb_build_object(gc.reference_genome, jsonb_build_object('contig', gc.common_name, 'start', sm.start_pos, 'end', sm.end_pos, 'period', sm.period))
FROM str_marker sm
JOIN genbank_contig gc ON sm.genbank_contig_id = gc.genbank_contig_id;
```

### Parallel Mutations
If existing data contains the same named variant linked to multiple haplogroups (parallel mutations), the migration will initially create separate rows. Curators should review these to determine if they are truly parallel (homoplasy) or shared ancestral nodes.
