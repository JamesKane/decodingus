-- YFull-style sample placement: attach non-D2C biosamples as leaves under the tree node
-- their published haplogroup call resolves to. The call lives in
-- core.biosample.original_haplogroups (a paper's stated Y/mt haplogroup); a recompute
-- resolves it to a tree.haplogroup node (via du_db::haplogroup::resolve_name_or_variant).
-- D2C (source='CITIZEN') samples are excluded. An unresolvable call is kept UNPLACED
-- (haplogroup_id NULL + the raw text) so a curator can triage it — no silent loss.
--
-- dna_type-partitioned (PK includes it) so a sample gets one Y placement now + one mt
-- placement when the mt tree lands. Placements feed the cached tree's per-node sample
-- count, so a recompute bumps tree.tree_revision (the ETag marker).

CREATE TABLE tree.haplogroup_sample (
    sample_guid   UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    dna_type      core.dna_type NOT NULL,
    haplogroup_id BIGINT REFERENCES tree.haplogroup(id),  -- NULL when UNPLACED
    call_text     TEXT NOT NULL,                          -- the raw published call resolved from
    status        TEXT NOT NULL,                          -- PLACED | UNPLACED
    refreshed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (sample_guid, dna_type)
);
CREATE INDEX haplogroup_sample_node_idx ON tree.haplogroup_sample (haplogroup_id) WHERE status = 'PLACED';
CREATE INDEX haplogroup_sample_status_idx ON tree.haplogroup_sample (status);
