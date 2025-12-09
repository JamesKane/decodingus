-- !Ups

-- Haplogroup reconciliation table for multi-run/multi-biosample consensus
-- Stored at specimen_donor level since a donor may have multiple biosamples
-- from different testing companies or labs that need reconciliation

CREATE TYPE dna_type AS ENUM ('Y_DNA', 'MT_DNA');

CREATE TABLE haplogroup_reconciliation (
    id SERIAL PRIMARY KEY,
    at_uri VARCHAR UNIQUE,
    at_cid VARCHAR,
    specimen_donor_id INT NOT NULL REFERENCES specimen_donor(id),
    dna_type dna_type NOT NULL,

    -- Reconciliation status metrics consolidated into JSONB
    -- Contains: compatibilityLevel, consensusHaplogroup, statusConfidence,
    --           branchCompatibilityScore, snpConcordance, runCount, warnings
    status JSONB NOT NULL DEFAULT '{}',

    -- Run calls stored as JSONB array of RunHaplogroupCall objects
    -- Each call: { sourceRef, haplogroup, confidence, callMethod, score,
    --              supportingSnps, conflictingSnps, noCalls, technology,
    --              meanCoverage, treeVersion, strPrediction }
    run_calls JSONB NOT NULL,

    -- Optional conflict/heteroplasmy data
    -- Each conflict: { position, snpName, contigAccession, calls[], resolution, resolvedValue }
    snp_conflicts JSONB,

    -- Each observation: { position, majorAllele, minorAllele, majorAlleleFrequency,
    --                     depth, isDefiningSnp, affectedHaplogroup }
    heteroplasmy_observations JSONB,

    -- Identity verification metrics
    -- { kinshipCoefficient, fingerprintSnpConcordance, yStrDistance,
    --   verificationStatus, verificationMethod }
    identity_verification JSONB,

    -- Manual override if user corrected the consensus
    -- { overriddenHaplogroup, reason, overriddenAt, overriddenBy }
    manual_override JSONB,

    -- Audit log of reconciliation changes
    -- Each entry: { timestamp, action, previousConsensus, newConsensus, runRef, notes }
    audit_log JSONB,

    last_reconciliation_at TIMESTAMP,
    deleted BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Unique constraint: one reconciliation per donor per DNA type
CREATE UNIQUE INDEX idx_reconciliation_donor_dna_type
    ON haplogroup_reconciliation(specimen_donor_id, dna_type)
    WHERE deleted = FALSE;

CREATE INDEX idx_reconciliation_specimen_donor ON haplogroup_reconciliation(specimen_donor_id);
CREATE INDEX idx_reconciliation_at_uri ON haplogroup_reconciliation(at_uri) WHERE at_uri IS NOT NULL;
-- Index on JSONB field for consensus haplogroup queries
CREATE INDEX idx_reconciliation_consensus ON haplogroup_reconciliation((status->>'consensusHaplogroup'));

COMMENT ON TABLE haplogroup_reconciliation IS 'Multi-run haplogroup reconciliation at specimen donor level';
COMMENT ON COLUMN haplogroup_reconciliation.run_calls IS 'Array of RunHaplogroupCall objects from each source (runs, alignments, STR profiles)';
COMMENT ON COLUMN haplogroup_reconciliation.branch_compatibility_score IS 'LCA_depth / max(depth_A, depth_B) - 1.0 = fully compatible';

-- !Downs

DROP TABLE IF EXISTS haplogroup_reconciliation;
DROP TYPE IF EXISTS dna_type;
