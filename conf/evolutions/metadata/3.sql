# PDS fleet management — status tracking, heartbeat, software versions, capabilities

# --- !Ups

CREATE TABLE pds_node (
    id              SERIAL PRIMARY KEY,
    did             TEXT NOT NULL UNIQUE,
    pds_url         TEXT NOT NULL,
    handle          TEXT,
    node_name       TEXT,
    software_version TEXT,
    status          TEXT NOT NULL DEFAULT 'UNKNOWN'
                        CHECK (status IN ('ONLINE', 'OFFLINE', 'BUSY', 'ERROR', 'UNKNOWN')),
    capabilities    JSONB NOT NULL DEFAULT '{}',
    last_heartbeat  TIMESTAMPTZ,
    last_commit_cid TEXT,
    last_commit_rev TEXT,
    ip_address      TEXT,
    os_info         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pds_node_status ON pds_node(status);
CREATE INDEX idx_pds_node_last_heartbeat ON pds_node(last_heartbeat);
CREATE INDEX idx_pds_node_software_version ON pds_node(software_version);

CREATE TABLE pds_heartbeat_log (
    id              SERIAL PRIMARY KEY,
    pds_node_id     INTEGER NOT NULL REFERENCES pds_node(id),
    status          TEXT NOT NULL
                        CHECK (status IN ('ONLINE', 'OFFLINE', 'BUSY', 'ERROR', 'UNKNOWN')),
    software_version TEXT,
    load_metrics    JSONB,
    processing_queue_size INTEGER DEFAULT 0,
    error_message   TEXT,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pds_heartbeat_log_node ON pds_heartbeat_log(pds_node_id);
CREATE INDEX idx_pds_heartbeat_log_recorded_at ON pds_heartbeat_log(recorded_at);

CREATE TABLE pds_fleet_config (
    id              SERIAL PRIMARY KEY,
    config_key      TEXT NOT NULL UNIQUE,
    config_value    TEXT NOT NULL,
    description     TEXT,
    updated_by      TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO pds_fleet_config (config_key, config_value, description)
VALUES
    ('target_software_version', '0.1.0', 'Target PDS software version for fleet'),
    ('heartbeat_interval_seconds', '300', 'Expected heartbeat interval in seconds'),
    ('offline_threshold_seconds', '900', 'Seconds without heartbeat before marking OFFLINE');

# --- !Downs

DROP TABLE IF EXISTS pds_fleet_config;
DROP TABLE IF EXISTS pds_heartbeat_log;
DROP TABLE IF EXISTS pds_node;
