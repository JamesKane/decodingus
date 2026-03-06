# Submission provenance — audit which PDS proposed specific variants and haplogroup calls

# --- !Ups

CREATE TABLE pds_submission (
    id                  SERIAL PRIMARY KEY,
    pds_node_id         INTEGER NOT NULL REFERENCES pds_node(id),
    submission_type     TEXT NOT NULL
                            CHECK (submission_type IN ('HAPLOGROUP_CALL', 'VARIANT_CALL', 'BRANCH_PROPOSAL', 'PRIVATE_VARIANT', 'STR_PROFILE')),
    biosample_id        INTEGER,
    biosample_guid      UUID,
    proposed_value      TEXT NOT NULL,
    confidence_score    DOUBLE PRECISION,
    algorithm_version   TEXT,
    software_version    TEXT,
    payload             JSONB,
    status              TEXT NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'SUPERSEDED')),
    reviewed_by         TEXT,
    reviewed_at         TIMESTAMPTZ,
    review_notes        TEXT,
    at_uri              TEXT,
    at_cid              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pds_submission_node ON pds_submission(pds_node_id);
CREATE INDEX idx_pds_submission_type ON pds_submission(submission_type);
CREATE INDEX idx_pds_submission_biosample ON pds_submission(biosample_id);
CREATE INDEX idx_pds_submission_biosample_guid ON pds_submission(biosample_guid);
CREATE INDEX idx_pds_submission_status ON pds_submission(status);
CREATE INDEX idx_pds_submission_created ON pds_submission(created_at);

# --- !Downs

DROP TABLE IF EXISTS pds_submission;
