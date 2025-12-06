# PDS Registrations schema
# --- !Ups

CREATE TABLE pds_registrations (
    did TEXT PRIMARY KEY,
    pds_url TEXT NOT NULL,
    handle TEXT NOT NULL,
    last_commit_cid TEXT,
    last_commit_seq BIGINT DEFAULT 0,
    cursor BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX pds_registrations_handle_idx ON pds_registrations (handle);
CREATE INDEX pds_registrations_last_commit_cid_idx ON pds_registrations (last_commit_cid);

# --- !Downs

DROP TABLE IF EXISTS pds_registrations;