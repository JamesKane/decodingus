-- The Variant Browser listed the same physical variant several times: a template
-- (defining_haplogroup_id IS NULL — the base identity) plus one branch-placement row
-- per branch the SNP defines (defining_haplogroup_id set). Those rows share the same
-- (location, ancestral, derived) — they are one variant under the (variant, branch)
-- identity model — so the public catalog should show ONE representative per physical
-- variant.
--
-- Maintain that choice as a flag (recomputed by ybrowse::reconcile and the
-- `variant-representatives` job) so the browser filter stays a fast indexed lookup
-- rather than an on-the-fly collapse of 3M rows. IF NOT EXISTS: a constant-default
-- column add is metadata-only, and the flag is pre-built where the backfill already ran.
ALTER TABLE core.variant
  ADD COLUMN IF NOT EXISTS catalog_representative boolean NOT NULL DEFAULT false;

-- The browser lists `WHERE catalog_representative ORDER BY canonical_name`; a partial
-- index on the name over just the ~2.9M representatives serves both the filter and the
-- ordering (and the unfiltered-browse first page) as an index scan.
CREATE INDEX IF NOT EXISTS variant_catalog_representative_idx
  ON core.variant (canonical_name) WHERE catalog_representative;
