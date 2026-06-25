-- Per-build joint-call Y callable mask: the region the de-novo ASR branch/private
-- SNP counts were ascertained over (e.g. results/chrY.callable_mask.chm13v2.bed).
--
-- du_db::age uses it two ways, region-consistently:
--   1. Denominator — the UNIFORM mask size (Σ interval bp), replacing the per-sample
--      coverage average. ASR branch counts are a single fixed count per branch over
--      the joint-call mask, so their denominator must be that mask, not per-sample bp.
--   2. Numerator — a POSITIVE filter: count only SNPs whose hs1 position falls inside
--      the mask. (The recurrent-region mask is negative-only and lets SNPs in
--      non-callable territory — X-transposed, DYZ, gaps — inflate the count.)
--
-- Replaced wholesale each de-novo build (decodingus-tree-init --callable-mask). hs1
-- (CHM13v2.0) frame, half-open [start,end) like BED. Y-only, so no contig column.

CREATE TABLE genomics.y_callable_interval (
    span int8range NOT NULL
);
CREATE INDEX y_callable_interval_gist ON genomics.y_callable_interval USING gist (span);
