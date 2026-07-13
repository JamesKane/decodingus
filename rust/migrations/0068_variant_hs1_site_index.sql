-- Site lookup for the naming authority: "is there already a named variant at this
-- exact hs1 locus + mutation state?"
--
-- DEPLOY NOTE: core.variant is ~4.7 GB / 3.1M rows. Plain CREATE INDEX takes a SHARE lock
-- and blocks writes to the table for the whole build, and du-web runs migrations on boot.
-- Build these CONCURRENTLY against live prod BEFORE deploying; the IF NOT EXISTS below then
-- makes this migration a no-op. See docs/variant-name-reconcile-deploy.md.
--
-- This question is asked three ways and, until now, always by full scan of a ~3M-row
-- catalog: the curator dedup warning (du_db::naming::dedup_by_site), the Reuse-name
-- offer behind it (adoptable_name), and the placeholder reconcile job that folds
-- de-novo coordinate rows onto the name they already have
-- (naming::reconcile_placeholder_names, which probes once per placeholder row).
--
-- Partial + expression index on the *named* side only — that is the side being probed,
-- and it excludes both NULL names and the loader's coordinate placeholders (chrY:…),
-- which are not names and must never satisfy a "already named here?" lookup.
-- The expressions must stay character-identical to the query predicates or the planner
-- will not use this index.
CREATE INDEX IF NOT EXISTS variant_hs1_site_named_idx
    ON core.variant (
        (coordinates -> 'hs1' ->> 'contig'),
        (coordinates -> 'hs1' ->> 'position'),
        (coordinates -> 'hs1' ->> 'ancestral'),
        (coordinates -> 'hs1' ->> 'derived')
    )
    WHERE coordinates ? 'hs1'
      AND canonical_name IS NOT NULL
      AND canonical_name NOT LIKE 'chr%:%';

-- The probing side: the branch-defining placeholder rows the reconcile job walks in id
-- order. Scoped to defining_haplogroup_id IS NOT NULL to match the job (and the
-- needs_name queue) — the loader's branch-less coordinate rows are catalog dead weight,
-- not naming work, and are not scanned.
CREATE INDEX IF NOT EXISTS variant_placeholder_branch_idx
    ON core.variant (id)
    WHERE canonical_name LIKE 'chr%:%' AND defining_haplogroup_id IS NOT NULL;
