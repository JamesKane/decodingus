-- Canonical contig-name normalization for the per-chromosome coverage benchmark.
--
-- Reference builds disagree on contig naming: GRCh38 and T2T-CHM13 (hs1) prefix the
-- primary chromosomes with `chr` (chr1…chr22, chrX, chrY, chrM), while GRCh37 does not
-- (1…22, X, Y, MT). Grouping/filtering the benchmark on the raw `metrics.contigs[].contig`
-- string therefore lists the SAME chromosome twice (chrY and Y) and a filter pick on one
-- form silently hides the other build's rows.
--
-- Fold both spellings to a single canonical, `chr`-prefixed token so each chromosome
-- appears once and a filter matches every build. Primary chromosomes only — unplaced /
-- alt / random / decoy contigs (chr1_KI270706v1_random, chrUn_*, GL000*, …) are left
-- EXACTLY as published so they stay distinct. `MT`/`chrMT` fold to `chrM`; the CHM13
-- `chrY_hs1` spelling folds to `chrY`.
CREATE OR REPLACE FUNCTION fed.canonical_contig(raw text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT CASE
    WHEN core IN ('1','2','3','4','5','6','7','8','9','10','11','12',
                  '13','14','15','16','17','18','19','20','21','22','X','Y')
      THEN 'chr' || core
    WHEN core IN ('M','MT')
      THEN 'chrM'
    ELSE raw   -- unrecognized (alt / unplaced / decoy): keep the published name verbatim
  END
  FROM (
    -- strip a leading 'chr' and a trailing '_hs1' build tag, case-insensitively
    SELECT upper(regexp_replace(regexp_replace(raw, '^chr', '', 'i'), '_hs1$', '', 'i')) AS core
  ) s
$$;
