-- !Ups

-- Add haplogroup reconciliation references to specimen_donor
-- Reconciliation is at the donor level since a donor may have multiple biosamples/runs
-- These link to HaplogroupReconciliation records for multi-run consensus
ALTER TABLE specimen_donor
    ADD COLUMN y_dna_reconciliation_ref VARCHAR,
    ADD COLUMN mt_dna_reconciliation_ref VARCHAR;

COMMENT ON COLUMN specimen_donor.y_dna_reconciliation_ref IS 'AT URI reference to Y-DNA haplogroup reconciliation record';
COMMENT ON COLUMN specimen_donor.mt_dna_reconciliation_ref IS 'AT URI reference to MT-DNA haplogroup reconciliation record';

-- !Downs

ALTER TABLE specimen_donor
    DROP COLUMN IF EXISTS y_dna_reconciliation_ref,
    DROP COLUMN IF EXISTS mt_dna_reconciliation_ref;
