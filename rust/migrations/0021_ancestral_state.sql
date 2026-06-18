-- Ancestral-state reconstruction (ASR) modeling + allele-semantics fix.
--
-- (1) core.variant.coordinates stored `reference_allele`/`alternate_allele`, but the
--     reference genome is not the phylogenetic root (genetic Adam) — so reference does
--     NOT map to ancestral. Every source already provides ancestral/derived; relabel
--     the JSONB keys per build. Values are unchanged (they were already anc/der).
-- (2) Recurrent SNPs (homoplasy) and back-mutations: a SNP changes state on multiple
--     branches, forward (anc->der) or reverse (der->anc). Record each branch's exact
--     transition on the link so forward/defining vs back-mutation vs recurrent is
--     representable. Existing links stay NULL (treated as forward/defining, using the
--     variant's coordinate alleles).

-- (1) Rename coordinate keys per build (no-op on an empty/fresh DB).
UPDATE core.variant v
SET coordinates = (
    SELECT jsonb_object_agg(
        build,
        (entry - 'reference_allele' - 'alternate_allele')
          || (CASE WHEN entry ? 'reference_allele'
                   THEN jsonb_build_object('ancestral', entry->'reference_allele') ELSE '{}'::jsonb END)
          || (CASE WHEN entry ? 'alternate_allele'
                   THEN jsonb_build_object('derived', entry->'alternate_allele') ELSE '{}'::jsonb END)
    )
    FROM jsonb_each(v.coordinates) AS e(build, entry)
)
WHERE EXISTS (
    SELECT 1 FROM jsonb_each(v.coordinates) AS e(build, entry)
    WHERE entry ? 'reference_allele' OR entry ? 'alternate_allele'
);

-- (2) Per-branch mutation direction on the link (the exact transition observed on
--     that branch). NULL = legacy/defining forward link (use the variant's anc/der).
ALTER TABLE tree.haplogroup_variant
    ADD COLUMN ancestral_allele text,
    ADD COLUMN derived_allele   text;

COMMENT ON COLUMN tree.haplogroup_variant.ancestral_allele IS
    'Ancestral allele of this SNP on this branch (per-branch ASR transition; NULL = forward/defining, use variant coordinates).';
COMMENT ON COLUMN tree.haplogroup_variant.derived_allele IS
    'Derived allele acquired on this branch. derived == variant ancestral => back-mutation; same SNP on multiple branches => recurrent (homoplasy).';
