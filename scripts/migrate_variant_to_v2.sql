-- =============================================================================
-- Migration Script: variant + variant_alias -> variant_v2
-- =============================================================================
-- Run this AFTER evolution 53.sql has been applied.
-- This script consolidates data from the old schema into variant_v2.
--
-- Usage:
--   psql -d your_database -f scripts/migrate_variant_to_v2.sql
--
-- Steps:
--   1. Migrate variant data into variant_v2 (consolidating by name)
--   2. Migrate aliases into JSONB
--   3. Update haplogroup_variant FK references
--   4. Verify migration
--   5. Drop old tables (commented out - uncomment after verification)
-- =============================================================================

BEGIN;

-- =============================================================================
-- Optimization: Create temporary index to speed up grouping and joining
-- =============================================================================
CREATE INDEX IF NOT EXISTS idx_tmp_variant_group_key ON variant (COALESCE(common_name, rs_id));

-- =============================================================================
-- Step 1: Insert variants into variant_v2 (Name + Coordinates in one pass)
-- =============================================================================

INSERT INTO variant_v2 (canonical_name, mutation_type, naming_status, aliases, coordinates)
SELECT
    COALESCE(v.common_name, v.rs_id) as group_key,
    MAX(v.variant_type) as mutation_type,
    CASE WHEN COALESCE(v.common_name, v.rs_id) IS NOT NULL THEN 'NAMED' ELSE 'UNNAMED' END,
    '{}'::jsonb as aliases,
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
GROUP BY COALESCE(v.common_name, v.rs_id)
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Step 2: Migrate aliases into JSONB structure
-- =============================================================================

WITH raw_aliases AS (
    -- Collect all aliases linked to any variant instance in the group
    SELECT
        COALESCE(v.common_name, v.rs_id) as group_key,
        va.alias_type,
        va.alias_value,
        va.source
    FROM variant v
    JOIN variant_alias va ON v.variant_id = va.variant_id
),
grouped_aliases AS (
    -- Aggregate aliases by type for each variant group
    SELECT
        group_key,
        jsonb_build_object(
            'common_names', COALESCE(jsonb_agg(DISTINCT alias_value) FILTER (WHERE alias_type = 'common_name'), '[]'::jsonb),
            'rs_ids',       COALESCE(jsonb_agg(DISTINCT alias_value) FILTER (WHERE alias_type = 'rs_id'), '[]'::jsonb),
            'sources',      COALESCE(
                                (
                                    SELECT jsonb_object_agg(source, names)
                                    FROM (
                                             SELECT source, jsonb_agg(DISTINCT alias_value) as names
                                             FROM raw_aliases ra2
                                             WHERE ra2.group_key = ra1.group_key
                                               AND source IS NOT NULL
                                             GROUP BY source
                                         ) src
                                ),
                                '{}'::jsonb
                            )
        ) as alias_json
    FROM raw_aliases ra1
    GROUP BY group_key
)
UPDATE variant_v2 v2
SET aliases = ga.alias_json
FROM grouped_aliases ga
WHERE v2.canonical_name IS NOT DISTINCT FROM ga.group_key;

-- =============================================================================
-- Step 4: Update haplogroup_variant FK references
-- =============================================================================

-- Drop old FK constraint
ALTER TABLE tree.haplogroup_variant DROP CONSTRAINT IF EXISTS haplogroup_variant_variant_id_fkey;

-- Create mapping and update references
UPDATE tree.haplogroup_variant hv
SET variant_id = v2.variant_id
FROM variant v
JOIN variant_v2 v2 ON v2.canonical_name IS NOT DISTINCT FROM COALESCE(v.common_name, v.rs_id)
WHERE hv.variant_id = v.variant_id;

-- Remove any duplicates created by consolidation
DELETE FROM tree.haplogroup_variant hv1
USING tree.haplogroup_variant hv2
WHERE hv1.haplogroup_id = hv2.haplogroup_id
  AND hv1.variant_id = hv2.variant_id
  AND hv1.haplogroup_variant_id > hv2.haplogroup_variant_id;

-- Add new FK constraint
ALTER TABLE tree.haplogroup_variant
    ADD CONSTRAINT haplogroup_variant_variant_id_fkey
    FOREIGN KEY (variant_id) REFERENCES variant_v2(variant_id) ON DELETE CASCADE;

-- =============================================================================
-- Step 5: Verification queries
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

DROP INDEX IF EXISTS idx_tmp_variant_group_key;

COMMIT;

-- =============================================================================
-- Step 6: Drop old tables (RUN ONLY AFTER VERIFICATION!)
-- =============================================================================
-- Uncomment these lines after verifying the migration:
--
-- DROP TABLE IF EXISTS variant_alias CASCADE;
-- DROP TABLE IF EXISTS variant CASCADE;
-- DROP TABLE IF EXISTS str_marker CASCADE;
