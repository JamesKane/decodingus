-- Per-branch reconstructed ancestral Y-STR motif (phylogenetic ASR).
--
-- du_db::ystr already reconstructs each node's ancestral simple-marker motif
-- (McDonald §2.5.2 parsimony up-/down-pass over the SNP-defined tree) as an
-- in-memory input to the STR-variance age model. This table persists that motif
-- so it can be surfaced per branch: the inferred ancestral haplotype, and — by
-- diffing a node against its parent — the per-marker STR mutations along the
-- branch leading into it (the STR analogue of the SNP anc→der transitions in the
-- node sidebar).
--
-- Distinct from tree.haplogroup_ancestral_str (which holds the per-branch MODAL
-- of *direct testers* + curator MANUAL overrides, for the signature display and
-- STR→branch prediction). This is the reconstructed ancestral state, simple
-- markers only (multi-copy/complex aren't reconstructed in v1). Full-refreshed
-- by ystr::recompute_signatures on each `branch-age` run.

CREATE TABLE tree.haplogroup_str_asr (
    haplogroup_id   BIGINT NOT NULL REFERENCES tree.haplogroup(id) ON DELETE CASCADE,
    marker_name     TEXT NOT NULL,
    ancestral_value INTEGER NOT NULL,   -- reconstructed repeat count (simple markers)
    recomputed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (haplogroup_id, marker_name)
);
CREATE INDEX haplogroup_str_asr_hg_idx ON tree.haplogroup_str_asr (haplogroup_id);
