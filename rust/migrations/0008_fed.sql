-- fed schema: PDS fleet + firehose. This collapses the legacy second "metadata"
-- database into the single DB (plan §2) — one database, one pool.

-- Firehose cursor/lease tracking per registered PDS (distributed consumers).
CREATE TABLE fed.pds_registration (
    did             TEXT PRIMARY KEY,
    pds_url         TEXT NOT NULL,
    handle          TEXT,
    last_commit_cid TEXT,
    cursor          BIGINT,
    leased_by_instance_id TEXT,
    lease_expires_at TIMESTAMPTZ,
    processing_status TEXT NOT NULL DEFAULT 'IDLE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX pds_registration_lease_idx ON fed.pds_registration (lease_expires_at);

CREATE TABLE fed.pds_node (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    did             TEXT NOT NULL UNIQUE,
    pds_url         TEXT,
    handle          TEXT,
    node_name       TEXT,
    software_version TEXT,
    status          TEXT NOT NULL DEFAULT 'UNKNOWN',   -- ONLINE/OFFLINE/BUSY/ERROR/UNKNOWN
    capabilities    JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_heartbeat  TIMESTAMPTZ,
    last_commit_cid TEXT,
    ip_address      TEXT,
    os_info         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX pds_node_status_idx ON fed.pds_node (status);

CREATE TABLE fed.pds_heartbeat_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pds_node_id     BIGINT NOT NULL REFERENCES fed.pds_node(id) ON DELETE CASCADE,
    status          TEXT,
    software_version TEXT,
    load_metrics    JSONB NOT NULL DEFAULT '{}'::jsonb,
    processing_queue_size INTEGER,
    error_message   TEXT,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX pds_heartbeat_node_time_idx ON fed.pds_heartbeat_log (pds_node_id, recorded_at DESC);

CREATE TABLE fed.pds_fleet_config (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    config_key      TEXT NOT NULL UNIQUE,
    config_value    TEXT,
    description     TEXT,
    updated_by      TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Distributed variant/haplogroup/STR proposals submitted by edge nodes.
CREATE TABLE fed.pds_submission (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pds_node_id     BIGINT REFERENCES fed.pds_node(id) ON DELETE SET NULL,
    submission_type TEXT NOT NULL,            -- HAPLOGROUP_CALL/VARIANT_CALL/BRANCH_PROPOSAL/PRIVATE_VARIANT/STR_PROFILE
    biosample_guid  UUID REFERENCES core.biosample(sample_guid),
    proposed_value  TEXT,
    confidence_score NUMERIC(5,4),
    algorithm_version TEXT,
    software_version TEXT,
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    status          TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING/ACCEPTED/REJECTED/SUPERSEDED
    reviewed_by     TEXT,
    reviewed_at     TIMESTAMPTZ,
    atproto         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX pds_submission_status_type_idx ON fed.pds_submission (status, submission_type);
