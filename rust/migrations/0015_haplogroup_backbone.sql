-- Backbone flag for the haplogroup tree. ISOGG's "backbone" is the established
-- trunk: the single-letter major clades (A, B, … T) and every ancestor on the
-- path from them up to the root. It is a *role*, distinct from `source` (which
-- records data provenance, e.g. 'ISOGG'), so we store it as its own column
-- rather than overloading `source == 'backbone'` as the legacy Scala app did.
--
-- The tree view renders backbone nodes green (the established spine) vs. amber
-- for recently-updated and grey for the rest. Recomputed by
-- `du_db::haplogroup::recompute_backbone` after each tree load; curators may
-- also set it directly.
ALTER TABLE tree.haplogroup
    ADD COLUMN is_backbone BOOLEAN NOT NULL DEFAULT false;

-- Partial index: backbone is a small subset, and the tree view filters on it.
CREATE INDEX haplogroup_backbone_idx ON tree.haplogroup (haplogroup_type)
    WHERE is_backbone;
