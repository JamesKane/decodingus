-- D6: discovery consensus engine support. Adds the constraints the engine's
-- declarative recompute relies on (idempotent upsert arbiters + a stable cluster
-- key for proposal identity), and seeds tree.discovery_config (previously unused).

-- 1. Natural identity for a materialized private variant: one sample asserting one
--    variant under one DNA arm (the materialize ON CONFLICT arbiter). De-dup any
--    pre-existing fixture rows first so the unique index can be built.
DELETE FROM tree.biosample_private_variant a
  USING tree.biosample_private_variant b
  WHERE a.id > b.id AND a.sample_guid = b.sample_guid
    AND a.variant_id = b.variant_id AND a.haplogroup_type = b.haplogroup_type;
ALTER TABLE tree.biosample_private_variant
  ADD CONSTRAINT bpv_sample_variant_type_key UNIQUE (sample_guid, variant_id, haplogroup_type);

-- 2. One row per (proposal, variant) so the engine can rebuild a proposal's
--    defining-variant set with DELETE-then-insert safely.
ALTER TABLE tree.proposed_branch_variant
  ADD CONSTRAINT pbv_branch_variant_key UNIQUE (proposed_branch_id, variant_id);

-- 3. Stable cluster identity for the engine's proposal UPSERT. cluster_key = the
--    sorted defining variant-id set (as text). haplogroup_type scopes the partial
--    unique index. submit()-created proposals leave cluster_key NULL and are
--    excluded, so the two intake paths coexist.
ALTER TABLE tree.proposed_branch ADD COLUMN cluster_key TEXT;
ALTER TABLE tree.proposed_branch ADD COLUMN haplogroup_type core.dna_type;
UPDATE tree.proposed_branch pb
  SET haplogroup_type = h.haplogroup_type
  FROM tree.haplogroup h WHERE h.id = pb.parent_haplogroup_id;
CREATE UNIQUE INDEX proposed_branch_open_cluster_key
  ON tree.proposed_branch (parent_haplogroup_id, haplogroup_type, cluster_key)
  WHERE status IN ('PROPOSED','UNDER_REVIEW','READY_FOR_REVIEW','SPLIT_CANDIDATE')
    AND cluster_key IS NOT NULL;
CREATE INDEX proposed_branch_status_idx ON tree.proposed_branch (status);

-- 4. Seed discovery thresholds + engine flags (read by du_db::discovery::load_config).
INSERT INTO tree.discovery_config (config_key, config_value, description) VALUES
 ('thresholds_Y_DNA',
  '{"consensus_threshold":3,"auto_promote_threshold":10,"confidence_threshold":0.95,"similarity_match_threshold":0.80,"similarity_split_threshold":0.50}',
  'Y-DNA discovery consensus thresholds'),
 ('thresholds_MT_DNA',
  '{"consensus_threshold":3,"auto_promote_threshold":10,"confidence_threshold":0.95,"similarity_match_threshold":0.80,"similarity_split_threshold":0.50}',
  'mtDNA discovery consensus thresholds'),
 ('confidence_weights',
  '{"w_count":0.4,"w_submitters":0.3,"w_consistency":0.3}',
  'discovery confidence blend weights'),
 ('engine',
  '{"auto_promote":false}',
  'discovery engine flags (auto_promote off = curator-gated)')
ON CONFLICT (config_key) DO NOTHING;
