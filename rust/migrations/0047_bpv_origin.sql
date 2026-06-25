-- Origin of a materialized private variant. The discovery engine's materialize()
-- prunes ACTIVE rows no longer backed by a fed.private_variant record; that prune
-- must NOT reap rows seeded directly by the de-novo tree loader (which collapses a
-- sample's private singleton branch into the discovery substrate instead of
-- publishing it as a tree node). Scope the prune to fed-origin rows.
--
--   'FED'    — materialized from fed.private_variant (citizen-published, prunable)
--   'DENOVO' — seeded by the de-novo loader from a tip's private SNPs (prune-exempt)
ALTER TABLE tree.biosample_private_variant
  ADD COLUMN origin TEXT NOT NULL DEFAULT 'FED';
