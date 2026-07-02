-- One-time cutover backfill: populate core.variant.defining_haplogroup_id from the
-- de-novo tree links so the (canonical_name + defining branch) naming model is
-- realized. Row-per-(name,branch): recurrent variants are split into a copy per
-- branch. Run after decodingus-tree-init, e.g.:
--   cat scripts/backfill-defining-haplogroup.sql | psql "$DATABASE_URL"
-- NOTE: interim step until du_db::denovo::load sets defining_haplogroup_id itself
-- (and clear_dna deletes the recurrence copies) — see the naming follow-up.

-- Backfill core.variant.defining_haplogroup_id so the (canonical_name, branch)
-- naming model is actually populated. Row-per-(name,branch): a variant that
-- defines exactly one branch gets that branch; a recurrent variant keeps its
-- lowest branch and is split into a copy per additional branch, with the extra
-- tree links re-pointed onto the copies.
DO $$
DECLARE r RECORD; new_id BIGINT; i INT; n_simple INT; n_rec INT := 0; n_copies INT := 0;
BEGIN
  -- 1. Variants defining exactly one branch → set it directly.
  WITH one AS (
    SELECT variant_id, min(haplogroup_id) AS hg
    FROM tree.haplogroup_variant WHERE valid_until IS NULL
    GROUP BY variant_id HAVING count(DISTINCT haplogroup_id) = 1)
  UPDATE core.variant v SET defining_haplogroup_id = one.hg, updated_at = now()
  FROM one WHERE v.id = one.variant_id AND v.defining_haplogroup_id IS NULL;
  GET DIAGNOSTICS n_simple = ROW_COUNT;
  RAISE NOTICE 'simple (1-branch) variants set: %', n_simple;

  -- 2. Recurrent variants: original keeps branches[1]; copy per branches[2..].
  FOR r IN
    SELECT variant_id,
           array_agg(DISTINCT haplogroup_id ORDER BY haplogroup_id) AS branches
    FROM tree.haplogroup_variant WHERE valid_until IS NULL
    GROUP BY variant_id HAVING count(DISTINCT haplogroup_id) > 1
  LOOP
    n_rec := n_rec + 1;
    UPDATE core.variant SET defining_haplogroup_id = r.branches[1], updated_at = now()
    WHERE id = r.variant_id AND defining_haplogroup_id IS NULL;

    FOR i IN 2 .. array_length(r.branches, 1) LOOP
      INSERT INTO core.variant
        (canonical_name, mutation_type, naming_status, coordinates, aliases, annotations, evidence, defining_haplogroup_id)
      SELECT canonical_name, mutation_type, naming_status, coordinates, aliases, annotations, evidence, r.branches[i]
      FROM core.variant WHERE id = r.variant_id
      RETURNING id INTO new_id;
      n_copies := n_copies + 1;

      UPDATE tree.haplogroup_variant SET variant_id = new_id
      WHERE variant_id = r.variant_id AND haplogroup_id = r.branches[i] AND valid_until IS NULL;
    END LOOP;
  END LOOP;
  RAISE NOTICE 'recurrent variants split: %, copies created: %', n_rec, n_copies;
END $$;

-- Verify: every current tree link now resolves to a variant scoped to that branch.
SELECT
  (SELECT count(*) FROM core.variant WHERE defining_haplogroup_id IS NOT NULL) AS variants_with_branch,
  (SELECT count(*) FROM tree.haplogroup_variant hv JOIN core.variant v ON v.id = hv.variant_id
     WHERE hv.valid_until IS NULL AND v.defining_haplogroup_id = hv.haplogroup_id) AS links_matched_to_scoped_variant,
  (SELECT count(*) FROM tree.haplogroup_variant WHERE valid_until IS NULL) AS total_links;
