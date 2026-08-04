-- One-off: fill missing GRCh38 (and GRCh37) coordinates on Y **tree-linked** variant rows by
-- copying them from the same marker's catalog row.
--
-- WHY. The de-novo loader reuses a catalog row only when it matches on `coordinates @> {'hs1': …}`.
-- Markers whose hs1 coordinate had not been lifted yet missed that match, so the loader minted a
-- fresh hs1-only row — and *that* row is what `tree.haplogroup_variant` points at. The marker's
-- real coordinates stayed on the unlinked catalog row. `variant-name-reconcile` later adopted the
-- name onto the branch row but never touches `coordinates`, which is why the tree has correct
-- names and, on the 2026-08-04 prod dump, GRCh38 for only 44,181 of 203,983 branch SNPs (21.7%).
--
-- WHY THIS MATTERS. The Navigator places each source in its *native* build with no liftover
-- (`place_y_consensus_decodingus`), and `parse_decodingus_json(json, build_key)` drops every locus
-- lacking that build's coordinate. So a GRCh38 subject saw only 3,413 of 11,421 Y nodes. All 33
-- backbone nodes survived — what vanished was the terminal tree (21% node visibility at depth
-- 31-40), so GRCh38 subjects placed plausibly but SHALLOW rather than failing outright.
--
-- WHY COPY RATHER THAN CHAIN-LIFT. The catalog row carries YBrowse's own GRCh38/GRCh37 values.
-- A chain lift would re-derive them and can mismap in the inverted / ampliconic Y blocks. On the
-- prod dump every one of the 132,183 candidate rows agreed with its twin on hs1 position AND
-- alleles (0 swapped, 0 other) and 132,181 had exactly one twin — so this copy is exact, not a
-- best guess. The match is enforced in the join below: a row whose twin disagrees on the hs1 site
-- simply is not filled. There is no unsafe write available to this script.
--
-- SCOPE. Only rows linked into the *current* Y tree. mtDNA is untouched (the mt tree is
-- CP068254.1/hs1-native by design and carries no GRCh38 — that is not a defect). Rows with no
-- named twin are left alone and reported as the residual; they need `variant-coord-lift`, which
-- needs the REVERSE hs1->GRCh38 chain staged to establish its pivot (prod stages only the forward
-- hg38ToHs1 — without the reverse chain that job finishes clean with everything `no_source` and
-- looks like it worked).
--
-- Idempotent: a filled row no longer matches `NOT coordinates ? 'GRCh38'`, so a re-run fills 0.
--
-- Run:
--   PGPASSWORD=… psql -h localhost -U decoding_us_user -d decodingus_db \
--     -v ON_ERROR_STOP=1 -f scripts/fill-y-tree-build-coords.sql
--
-- AFTER (both matter):
--   decodingus-jobs run-once variant-representatives   -- newly-shared builds let twins collapse
--   psql … -c 'ANALYZE core.variant;'
-- This script bumps tree.tree_revision itself — without that bump the Navigator keeps serving its
-- cached tree and none of this reaches a client.

\set ON_ERROR_STOP on
\timing on

BEGIN;

\echo '--- before: Y tree-linked variants by build ---'
SELECT count(*) AS tree_variants,
       count(*) FILTER (WHERE v.coordinates ? 'hs1')    AS hs1,
       count(*) FILTER (WHERE v.coordinates ? 'GRCh38') AS grch38,
       count(*) FILTER (WHERE v.coordinates ? 'GRCh37') AS grch37
FROM tree.haplogroup_variant hv
JOIN core.variant v    ON v.id = hv.variant_id
JOIN tree.haplogroup h ON h.id = hv.haplogroup_id
WHERE hv.valid_until IS NULL AND h.valid_until IS NULL AND h.haplogroup_type = 'Y_DNA';

-- Candidate set: one twin per row. `DISTINCT ON` + the ORDER BY prefers the catalog
-- representative, then the lowest id, so the choice is deterministic across runs.
CREATE TEMP TABLE twin_fill ON COMMIT DROP AS
SELECT DISTINCT ON (v.id) v.id, o.id AS twin_id, o.coordinates AS src
FROM core.variant v
JOIN tree.haplogroup_variant hv ON hv.variant_id = v.id AND hv.valid_until IS NULL
JOIN tree.haplogroup h          ON h.id = hv.haplogroup_id
                               AND h.haplogroup_type = 'Y_DNA' AND h.valid_until IS NULL
JOIN core.variant o
  ON  o.canonical_name = v.canonical_name
  AND o.id <> v.id
  AND o.coordinates ? 'GRCh38'
  -- identical hs1 site AND alleles — this is what makes the copy exact rather than inferred
  AND o.coordinates->'hs1'->>'contig'    = v.coordinates->'hs1'->>'contig'
  AND o.coordinates->'hs1'->>'position'  = v.coordinates->'hs1'->>'position'
  AND o.coordinates->'hs1'->>'ancestral' = v.coordinates->'hs1'->>'ancestral'
  AND o.coordinates->'hs1'->>'derived'   = v.coordinates->'hs1'->>'derived'
WHERE v.canonical_name IS NOT NULL
  AND v.coordinates ? 'hs1'
  AND NOT v.coordinates ? 'GRCh38'
ORDER BY v.id, o.catalog_representative DESC, o.id;

\echo '--- rows this run will fill ---'
SELECT count(*) AS rows_to_fill FROM twin_fill;

-- Fill GRCh38 always (that is the candidate predicate); ride GRCh37 along only where the row
-- lacks it and the twin has it. Existing keys are never overwritten.
UPDATE core.variant v
SET coordinates = v.coordinates
      || jsonb_build_object('GRCh38', t.src->'GRCh38')
      || CASE WHEN NOT v.coordinates ? 'GRCh37' AND t.src ? 'GRCh37'
              THEN jsonb_build_object('GRCh37', t.src->'GRCh37')
              ELSE '{}'::jsonb END,
    updated_at = now()
FROM twin_fill t
WHERE v.id = t.id;

\echo '--- after: Y tree-linked variants by build ---'
SELECT count(*) AS tree_variants,
       count(*) FILTER (WHERE v.coordinates ? 'hs1')    AS hs1,
       count(*) FILTER (WHERE v.coordinates ? 'GRCh38') AS grch38,
       count(*) FILTER (WHERE v.coordinates ? 'GRCh37') AS grch37
FROM tree.haplogroup_variant hv
JOIN core.variant v    ON v.id = hv.variant_id
JOIN tree.haplogroup h ON h.id = hv.haplogroup_id
WHERE hv.valid_until IS NULL AND h.valid_until IS NULL AND h.haplogroup_type = 'Y_DNA';

\echo '--- residual (no named twin — needs variant-coord-lift + the reverse chain) ---'
SELECT v.mutation_type::text AS mutation_type,
       (v.canonical_name LIKE 'DU%') AS du_minted,
       count(DISTINCT v.id) AS rows
FROM tree.haplogroup_variant hv
JOIN core.variant v    ON v.id = hv.variant_id
JOIN tree.haplogroup h ON h.id = hv.haplogroup_id
WHERE hv.valid_until IS NULL AND h.valid_until IS NULL AND h.haplogroup_type = 'Y_DNA'
  AND NOT v.coordinates ? 'GRCh38'
GROUP BY 1, 2 ORDER BY 3 DESC;

\echo '--- node-level build visibility (what a subject on that build can actually see) ---'
WITH n AS (
  SELECT h.id, h.is_backbone,
         count(*) FILTER (WHERE v.coordinates ? 'hs1')    AS hs1,
         count(*) FILTER (WHERE v.coordinates ? 'GRCh38') AS g38
  FROM tree.haplogroup h
  JOIN tree.haplogroup_variant hv ON hv.haplogroup_id = h.id AND hv.valid_until IS NULL
  JOIN core.variant v             ON v.id = hv.variant_id
  WHERE h.haplogroup_type = 'Y_DNA' AND h.valid_until IS NULL
  GROUP BY 1, 2)
SELECT count(*) AS nodes_with_variants,
       count(*) FILTER (WHERE hs1 > 0) AS visible_hs1,
       count(*) FILTER (WHERE g38 > 0) AS visible_grch38,
       count(*) FILTER (WHERE is_backbone AND g38 > 0) AS backbone_visible_grch38
FROM n;

-- Invalidate the served tree's ETag so clients re-fetch (tree endpoints answer 304 off this).
-- Only when this run actually changed something: the tree payload is ~60 MB, so a no-op bump
-- would make every client re-download it for nothing.
UPDATE tree.tree_revision SET revision = revision + 1, updated_at = now()
WHERE id = 1 AND EXISTS (SELECT 1 FROM twin_fill);
SELECT revision AS new_tree_revision FROM tree.tree_revision WHERE id = 1;

COMMIT;
