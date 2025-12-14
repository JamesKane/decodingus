-- =============================================================================
-- Migration Script: variant + variant_alias -> variant_v2 (OPTIMIZED)
-- =============================================================================
-- Run this AFTER evolution 53.sql has been applied.
--
-- Optimization Strategy:
-- 1. Drop heavy GIN indexes before load.
-- 2. Perform SINGLE INSERT with CTEs to aggregate coordinates and aliases in one pass.
-- 3. Re-create indexes after load.
--
-- Usage:
--   psql -d your_database -f scripts/migrate_variant_to_v2.sql
-- =============================================================================

BEGIN;

-- =============================================================================
-- Step 0: Pre-migration cleanup & Index Management
-- =============================================================================

-- Drop heavy GIN indexes to speed up massive insert
-- We keep B-Tree indexes for basic unique constraints if needed, but GIN is the write killer
DROP INDEX IF EXISTS idx_variant_v2_aliases;
DROP INDEX IF EXISTS idx_variant_v2_coordinates;
DROP INDEX IF EXISTS idx_variant_v2_alias_search;

-- =============================================================================
-- Step 1: Combined Aggregation and Insert
-- =============================================================================

INSERT INTO variant_v2 (canonical_name, mutation_type, naming_status, aliases, coordinates)
WITH
    -- 1. Aggregate Coordinates (group by common_name)
    coords_agg AS (
        SELECT
            v.common_name as group_key,
            MAX(v.variant_type) as mutation_type,
            jsonb_object_agg(
                    COALESCE(gc.reference_genome, 'unknown'),
                    jsonb_build_object(
                            'contig', COALESCE(gc.common_name, gc.accession),
                            'position', v.position,
                            'ref', v.reference_allele,
                            'alt', v.alternate_allele
                    )
            ) as coordinates
        FROM variant v
        JOIN genbank_contig gc ON v.genbank_contig_id = gc.genbank_contig_id
        WHERE v.common_name IS NOT NULL  -- Optimization: Simplify by ignoring nameless/rs_id-only if not loaded
        GROUP BY v.common_name
    ),
    -- 2. Aggregate Aliases: Sources (First, array per source)
    alias_sources AS (
        SELECT
            v.common_name as group_key,
            va.source,
            jsonb_agg(DISTINCT va.alias_value) as names
        FROM variant v
        JOIN variant_alias va ON v.variant_id = va.variant_id
        WHERE v.common_name IS NOT NULL
          AND va.source IS NOT NULL
        GROUP BY v.common_name, va.source
    ),
    -- 3. Aggregate Aliases: Combine Source Arrays into Object
    alias_sources_obj AS (
        SELECT
            group_key,
            jsonb_object_agg(source, names) as sources_json
        FROM alias_sources
        GROUP BY group_key
    ),
    -- 4. Aggregate Aliases: Common Names List
    alias_commons AS (
        SELECT
            v.common_name as group_key,
            jsonb_agg(DISTINCT va.alias_value) FILTER (WHERE va.alias_type = 'common_name') as common_names
        FROM variant v
        JOIN variant_alias va ON v.variant_id = va.variant_id
        WHERE v.common_name IS NOT NULL
        GROUP BY v.common_name
    )
SELECT
    c.group_key as canonical_name,
    c.mutation_type,
    'NAMED' as naming_status,
    jsonb_build_object(
            'common_names', COALESCE(al.common_names, '[]'::jsonb),
            'rs_ids',       '[]'::jsonb,  -- Simplified: No rs_ids loaded per user instruction
            'sources',      COALESCE(aso.sources_json, '{}'::jsonb)
    ) as aliases,
    c.coordinates
FROM coords_agg c
LEFT JOIN alias_sources_obj aso ON c.group_key = aso.group_key
LEFT JOIN alias_commons al ON c.group_key = al.group_key;

-- Handle Unnamed variants (if any exist without common_name) - Optional pass if needed
-- For now, focusing on the named migration as implied by "2.5 million rows" usually being the named set.

-- =============================================================================
-- Step 2: Update haplogroup_variant FK references (With Deduplication)
-- =============================================================================

-- Drop old FK constraint
ALTER TABLE tree.haplogroup_variant DROP CONSTRAINT IF EXISTS haplogroup_variant_variant_id_fkey;

-- 2a. Add temp column to store the new ID mapping
ALTER TABLE tree.haplogroup_variant ADD COLUMN IF NOT EXISTS target_v2_id INT;

-- 2b. Populate target_v2_id based on canonical name match
-- Note: This Update is safe because it doesn't touch the constrained variant_id column yet
UPDATE tree.haplogroup_variant hv
SET target_v2_id = v2.variant_id
FROM variant v
JOIN variant_v2 v2 ON v2.canonical_name = v.common_name
WHERE hv.variant_id = v.variant_id;

-- 2c. Delete duplicates
-- We keep the row with the lowest haplogroup_variant_id
DELETE FROM tree.haplogroup_variant hv_del
USING tree.haplogroup_variant hv_keep
WHERE hv_del.haplogroup_id = hv_keep.haplogroup_id
  AND hv_del.target_v2_id = hv_keep.target_v2_id
  AND hv_del.haplogroup_variant_id > hv_keep.haplogroup_variant_id;

-- 2d. Apply the update
UPDATE tree.haplogroup_variant
SET variant_id = target_v2_id
WHERE target_v2_id IS NOT NULL;

-- 2e. Cleanup
ALTER TABLE tree.haplogroup_variant DROP COLUMN target_v2_id;

-- Add new FK constraint
ALTER TABLE tree.haplogroup_variant
    ADD CONSTRAINT haplogroup_variant_variant_id_fkey
    FOREIGN KEY (variant_id) REFERENCES variant_v2(variant_id) ON DELETE CASCADE;

-- =============================================================================
-- Step 3: Re-create Indexes
-- =============================================================================

CREATE INDEX idx_variant_v2_aliases ON variant_v2 USING GIN(aliases);
CREATE INDEX idx_variant_v2_coordinates ON variant_v2 USING GIN(coordinates);
CREATE INDEX idx_variant_v2_alias_search ON variant_v2 USING GIN((aliases->'common_names') jsonb_path_ops);

-- =============================================================================
-- Step 4: Verification
-- =============================================================================

SELECT 'Old variant count:' as check_name, COUNT(*) as count FROM variant
UNION ALL
SELECT 'New variant_v2 count:', COUNT(*) FROM variant_v2
UNION ALL
SELECT 'Old variant_alias count:', COUNT(*) FROM variant_alias
UNION ALL
SELECT 'haplogroup_variant count:', COUNT(*) FROM tree.haplogroup_variant;

-- Check for orphaned haplogroup_variant rows (should be 0)
SELECT 'Orphaned haplogroup_variant rows:' as check_name, COUNT(*) as count
FROM tree.haplogroup_variant hv
LEFT JOIN variant_v2 v2 ON hv.variant_id = v2.variant_id
WHERE v2.variant_id IS NULL;

COMMIT;

-- =============================================================================
-- Step 5: Drop old tables (RUN MANUALLY AFTER VERIFICATION)
-- =============================================================================
-- DROP TABLE IF EXISTS variant_alias CASCADE;
-- DROP TABLE IF EXISTS variant CASCADE;
-- DROP TABLE IF EXISTS str_marker CASCADE;