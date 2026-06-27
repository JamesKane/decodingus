-- Per-defining-variant confidence from the ytree Fitch v2 pass (bin/86_flag_confidence.py).
--
-- A defining-variant link is LOW-CONFIDENCE when its SNP is either not
-- joint-confirmed (an FTDNA AEngine positive-only DP>10 call, not joint-verifiable)
-- or not monophyletic (a homoplasic / recurrent placement). Such calls are
-- indistinguishable from real defining SNPs to the per-SNP guards, but correlated
-- over-calls land as AC=6-8 "defining" SNPs that uniformly inflate corroborated-
-- looking deep branches (e.g. R-M222 read ~2.5x its true age). du_db::age excludes
-- low-confidence links from branch SNP counts; the child-outlier rejection then
-- finishes the residual on the now-clean counts.
--
-- Populated by the de-novo loader from the ingest JSON's definingVariants
-- (jointConfirmed + monophyletic). Default false so legacy/non-de-novo links count.

ALTER TABLE tree.haplogroup_variant ADD COLUMN low_confidence BOOLEAN NOT NULL DEFAULT false;
