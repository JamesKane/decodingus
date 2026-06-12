-- D6 reliability: gate + weight the discovery consensus by the contributor's
-- cross-technology consensus reliability (fed.haplogroup_reconciliation). Extend
-- the existing tree.discovery_config seed (0029) in place — `jsonb ||` merge so the
-- prior keys are preserved.

-- Exclusion floor: a contributor whose consensus confidence is below this (or whose
-- reconciliation is INCOMPATIBLE) can't drive a branch proposal.
UPDATE tree.discovery_config
   SET config_value = config_value || '{"min_consensus_confidence": 0.5}'::jsonb
 WHERE config_key = 'engine';

-- Confidence weights renormalized to include the reliability term (sum = 1.0).
UPDATE tree.discovery_config
   SET config_value = '{"w_count":0.35,"w_submitters":0.2,"w_consistency":0.25,"w_reliability":0.2}'::jsonb
 WHERE config_key = 'confidence_weights';
