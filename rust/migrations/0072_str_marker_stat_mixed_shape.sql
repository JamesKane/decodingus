-- Record observations reported in a marker's minority value shape.
--
-- Some markers are reported inconsistently by the vendor export: DYS413 has 866
-- multi-copy calls (23-23, 21-23, …) and exactly one bare `23`. The report picked
-- whichever shape was non-null with the scalar winning ties, so that single
-- observation replaced the whole copy-vector range — the page showed
-- min=modal=max=23 for a marker with 25 distinct observed values, presenting one
-- sample's value as the corpus range.
--
-- The range is now taken from whichever shape the majority of observations use
-- (which also fixes the mirror case: a mostly-simple marker with a stray vector),
-- and the minority count is kept here so the discrepancy is visible in the report
-- rather than silently discarded — the same reasoning as null_alleles and
-- complex_count.

ALTER TABLE genomics.str_marker_stat
    ADD COLUMN mixed_shape_count BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN genomics.str_marker_stat.mixed_shape_count IS
    'Observations in the marker''s minority value shape (single count vs copy vector); excluded from the reported range';

-- Existing rows carry the old shape-precedence result; the next
-- `du-jobs run-once str-marker-stats` recomputes them.
