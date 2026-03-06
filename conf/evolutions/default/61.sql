-- !Ups

-- ============================================================================
-- Evolution 61: Haplogroup Discovery System Tables
-- Creates the discovery pipeline tables in the tree schema for tracking
-- private variants, proposed branches, evidence, and curator actions.
-- ============================================================================

-- Private variants discovered in biosamples (unified across both Citizen and External)
CREATE TABLE tree.biosample_private_variant (
    id                       SERIAL PRIMARY KEY,
    sample_type              VARCHAR(20) NOT NULL CHECK (sample_type IN ('CITIZEN', 'EXTERNAL')),
    sample_id                INTEGER NOT NULL,
    sample_guid              UUID NOT NULL,
    variant_id               INTEGER NOT NULL,
    haplogroup_type          VARCHAR(10) NOT NULL CHECK (haplogroup_type IN ('Y', 'MT')),
    terminal_haplogroup_id   INTEGER NOT NULL REFERENCES tree.haplogroup(haplogroup_id),
    discovered_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('ACTIVE', 'PROMOTED', 'INVALIDATED')),
    UNIQUE(sample_type, sample_id, variant_id, haplogroup_type)
);

CREATE INDEX idx_bpv_sample ON tree.biosample_private_variant(sample_type, sample_id);
CREATE INDEX idx_bpv_guid ON tree.biosample_private_variant(sample_guid);
CREATE INDEX idx_bpv_variant ON tree.biosample_private_variant(variant_id);
CREATE INDEX idx_bpv_terminal ON tree.biosample_private_variant(terminal_haplogroup_id);
CREATE INDEX idx_bpv_status ON tree.biosample_private_variant(status);

COMMENT ON TABLE tree.biosample_private_variant IS 'Tracks private (mismatching) variants discovered in biosamples that extend beyond the current terminal haplogroup.';

-- Proposed branches awaiting consensus/review
CREATE TABLE tree.proposed_branch (
    id                       SERIAL PRIMARY KEY,
    parent_haplogroup_id     INTEGER NOT NULL REFERENCES tree.haplogroup(haplogroup_id),
    proposed_name            VARCHAR(100),
    haplogroup_type          VARCHAR(10) NOT NULL CHECK (haplogroup_type IN ('Y', 'MT')),
    status                   VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING', 'READY_FOR_REVIEW', 'UNDER_REVIEW',
                                               'ACCEPTED', 'PROMOTED', 'REJECTED', 'SPLIT')),
    consensus_count          INTEGER NOT NULL DEFAULT 0,
    confidence_score         DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    created_at               TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP NOT NULL DEFAULT NOW(),
    reviewed_at              TIMESTAMP,
    reviewed_by              VARCHAR(255),
    notes                    TEXT,
    promoted_haplogroup_id   INTEGER REFERENCES tree.haplogroup(haplogroup_id)
);

CREATE INDEX idx_pb_parent ON tree.proposed_branch(parent_haplogroup_id);
CREATE INDEX idx_pb_status ON tree.proposed_branch(status);
CREATE INDEX idx_pb_type ON tree.proposed_branch(haplogroup_type);

COMMENT ON TABLE tree.proposed_branch IS 'Candidate branches proposed by the discovery system when shared private variants are detected across multiple biosamples.';

-- Variants associated with proposed branches
CREATE TABLE tree.proposed_branch_variant (
    id                       SERIAL PRIMARY KEY,
    proposed_branch_id       INTEGER NOT NULL REFERENCES tree.proposed_branch(id) ON DELETE CASCADE,
    variant_id               INTEGER NOT NULL,
    is_defining              BOOLEAN NOT NULL DEFAULT TRUE,
    evidence_count           INTEGER NOT NULL DEFAULT 1,
    first_observed_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    last_observed_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(proposed_branch_id, variant_id)
);

CREATE INDEX idx_pbv_variant ON tree.proposed_branch_variant(variant_id);

COMMENT ON TABLE tree.proposed_branch_variant IS 'Links proposed branches to their defining variants with evidence tracking.';

-- Biosamples supporting proposed branches (unified across both types)
CREATE TABLE tree.proposed_branch_evidence (
    id                       SERIAL PRIMARY KEY,
    proposed_branch_id       INTEGER NOT NULL REFERENCES tree.proposed_branch(id) ON DELETE CASCADE,
    sample_type              VARCHAR(20) NOT NULL CHECK (sample_type IN ('CITIZEN', 'EXTERNAL')),
    sample_id                INTEGER NOT NULL,
    sample_guid              UUID NOT NULL,
    added_at                 TIMESTAMP NOT NULL DEFAULT NOW(),
    variant_match_count      INTEGER NOT NULL DEFAULT 0,
    variant_mismatch_count   INTEGER NOT NULL DEFAULT 0,
    UNIQUE(proposed_branch_id, sample_type, sample_id)
);

CREATE INDEX idx_pbe_sample ON tree.proposed_branch_evidence(sample_type, sample_id);
CREATE INDEX idx_pbe_guid ON tree.proposed_branch_evidence(sample_guid);

COMMENT ON TABLE tree.proposed_branch_evidence IS 'Links biosamples (Citizen or External) to the proposed branches they support.';

-- Curator audit trail
CREATE TABLE tree.curator_action (
    id                       SERIAL PRIMARY KEY,
    curator_id               VARCHAR(255) NOT NULL,
    action_type              VARCHAR(50) NOT NULL
                             CHECK (action_type IN ('REVIEW', 'ACCEPT', 'REJECT', 'MODIFY',
                                                    'SPLIT', 'MERGE', 'CREATE', 'DELETE',
                                                    'REASSIGN', 'NAME_VARIANT')),
    target_type              VARCHAR(50) NOT NULL
                             CHECK (target_type IN ('PROPOSED_BRANCH', 'HAPLOGROUP',
                                                    'HAPLOGROUP_RELATIONSHIP', 'VARIANT', 'BIOSAMPLE')),
    target_id                INTEGER NOT NULL,
    previous_state           JSONB,
    new_state                JSONB,
    reason                   TEXT,
    created_at               TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ca_curator ON tree.curator_action(curator_id);
CREATE INDEX idx_ca_timestamp ON tree.curator_action(created_at);
CREATE INDEX idx_ca_target ON tree.curator_action(target_type, target_id);

COMMENT ON TABLE tree.curator_action IS 'Immutable audit trail of all curator operations on proposed branches, haplogroups, and variants.';

-- Configuration for consensus thresholds
CREATE TABLE tree.discovery_config (
    id                       SERIAL PRIMARY KEY,
    haplogroup_type          VARCHAR(10) NOT NULL CHECK (haplogroup_type IN ('Y', 'MT')),
    config_key               VARCHAR(100) NOT NULL,
    config_value             TEXT NOT NULL,
    description              TEXT,
    updated_at               TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by               VARCHAR(255),
    UNIQUE(haplogroup_type, config_key)
);

INSERT INTO tree.discovery_config (haplogroup_type, config_key, config_value, description) VALUES
('Y', 'consensus_threshold', '3', 'Minimum biosamples required to reach ReadyForReview'),
('Y', 'auto_promote_threshold', '10', 'Biosamples required for automatic promotion consideration'),
('Y', 'confidence_threshold', '0.95', 'Minimum confidence score for promotion'),
('Y', 'jaccard_match_threshold', '0.8', 'Minimum Jaccard similarity to match an existing proposal'),
('MT', 'consensus_threshold', '3', 'Minimum biosamples required to reach ReadyForReview'),
('MT', 'auto_promote_threshold', '10', 'Biosamples required for automatic promotion consideration'),
('MT', 'confidence_threshold', '0.95', 'Minimum confidence score for promotion'),
('MT', 'jaccard_match_threshold', '0.8', 'Minimum Jaccard similarity to match an existing proposal');

COMMENT ON TABLE tree.discovery_config IS 'Per-haplogroup-type configuration for discovery thresholds and scoring parameters.';

-- !Downs

DROP TABLE IF EXISTS tree.discovery_config;
DROP TABLE IF EXISTS tree.curator_action;
DROP TABLE IF EXISTS tree.proposed_branch_evidence;
DROP TABLE IF EXISTS tree.proposed_branch_variant;
DROP TABLE IF EXISTS tree.proposed_branch;
DROP TABLE IF EXISTS tree.biosample_private_variant;
