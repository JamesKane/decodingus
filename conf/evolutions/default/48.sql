-- # --- !Ups

-- Add branch age estimate columns to haplogroup table
-- Dates stored as years before present (YBP) with optional confidence intervals

ALTER TABLE tree.haplogroup
    ADD COLUMN formed_ybp INTEGER,
    ADD COLUMN formed_ybp_lower INTEGER,
    ADD COLUMN formed_ybp_upper INTEGER,
    ADD COLUMN tmrca_ybp INTEGER,
    ADD COLUMN tmrca_ybp_lower INTEGER,
    ADD COLUMN tmrca_ybp_upper INTEGER,
    ADD COLUMN age_estimate_source VARCHAR(100);

COMMENT ON COLUMN tree.haplogroup.formed_ybp IS 'Estimated years before present when branch formed (mutation occurred)';
COMMENT ON COLUMN tree.haplogroup.formed_ybp_lower IS 'Lower bound of 95% confidence interval for formed date';
COMMENT ON COLUMN tree.haplogroup.formed_ybp_upper IS 'Upper bound of 95% confidence interval for formed date';
COMMENT ON COLUMN tree.haplogroup.tmrca_ybp IS 'Estimated years before present for Time to Most Recent Common Ancestor';
COMMENT ON COLUMN tree.haplogroup.tmrca_ybp_lower IS 'Lower bound of 95% confidence interval for TMRCA';
COMMENT ON COLUMN tree.haplogroup.tmrca_ybp_upper IS 'Upper bound of 95% confidence interval for TMRCA';
COMMENT ON COLUMN tree.haplogroup.age_estimate_source IS 'Source of age estimates (e.g., YFull, internal calculation)';

-- # --- !Downs

ALTER TABLE tree.haplogroup
    DROP COLUMN IF EXISTS formed_ybp,
    DROP COLUMN IF EXISTS formed_ybp_lower,
    DROP COLUMN IF EXISTS formed_ybp_upper,
    DROP COLUMN IF EXISTS tmrca_ybp,
    DROP COLUMN IF EXISTS tmrca_ybp_lower,
    DROP COLUMN IF EXISTS tmrca_ybp_upper,
    DROP COLUMN IF EXISTS age_estimate_source;
