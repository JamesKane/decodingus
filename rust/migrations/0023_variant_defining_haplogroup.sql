-- Recurrence-aware variant identity (universal-variant design, Decision 5).
--
-- A recurrent/homoplasic SNP is the SAME physical site (one coordinate) arising
-- independently on more than one lineage. ISOGG encodes this textually with a
-- `.1`/`.2` suffix on the SNP name (e.g. `A10822` on E, `A10822.2` on A000b1) and
-- a `^^` "below-criteria-but-stable" marker. Those decorations are NOT distinct
-- identities — baking them into `canonical_name` fragments one physical SNP into
-- several coordless rows.
--
-- Per the design, identity is `(canonical_name, defining_haplogroup_id)`: one name,
-- one coordinate, distinguished by the lineage each occurrence defines. The primary
-- occurrence keeps `defining_haplogroup_id IS NULL` (the COALESCE(-1) slot); each
-- additional recurrence is a sibling row sharing the name + coordinate, scoped to
-- its branch. Because every row then defines exactly ONE lineage,
-- `scrub_recurrent_links` (which prunes a variant whose links scatter across
-- unrelated lineages) leaves genuine ISOGG-curated recurrence alone by construction.

ALTER TABLE core.variant
    ADD COLUMN defining_haplogroup_id bigint REFERENCES tree.haplogroup(id);

COMMENT ON COLUMN core.variant.defining_haplogroup_id IS
    'Lineage this variant identity is a defining mutation for. NULL = the primary '
    'occurrence (unique by name). A non-NULL value marks an additional recurrence '
    'of the same physical SNP (same coordinate) on a different branch — the '
    'universal-variant way to model homoplasy (ISOGG .1/.2), instead of a name suffix.';

-- Replace the name-only uniqueness with name + defining-lineage. NULL collapses to
-- -1 so the primary occurrence stays globally unique by name, while recurrences are
-- unique per (name, branch). Matches the design index.
DROP INDEX core.variant_canonical_name_key;
CREATE UNIQUE INDEX variant_canonical_name_key
    ON core.variant (canonical_name, COALESCE(defining_haplogroup_id, -1))
    WHERE canonical_name IS NOT NULL;

-- Lookups of recurrences for a given branch (backfill + tree reads).
CREATE INDEX variant_defining_haplogroup_idx
    ON core.variant (defining_haplogroup_id)
    WHERE defining_haplogroup_id IS NOT NULL;
