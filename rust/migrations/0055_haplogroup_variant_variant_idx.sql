-- Index tree.haplogroup_variant by variant_id for the *current* (valid_until IS NULL) links.
--
-- The existing haplogroup_variant_current_key is UNIQUE (haplogroup_id, variant_id) — it leads
-- with haplogroup_id, so the reverse lookup "which branch(es) is this variant assigned to?" has
-- no supporting index and seq-scans the whole table. That lookup backs the Variant Browser's
-- tree-branch panel (site-matched, so it runs per variant-detail view), plus any future
-- variant->branch surfacing. Partial (valid_until IS NULL) to match the current-links workload.
CREATE INDEX IF NOT EXISTS haplogroup_variant_variant_idx
    ON tree.haplogroup_variant (variant_id) WHERE valid_until IS NULL;
