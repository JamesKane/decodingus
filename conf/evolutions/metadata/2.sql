# --- !Ups
ALTER TABLE pds_registrations ADD COLUMN leased_by_instance_id TEXT NULL;
ALTER TABLE pds_registrations ADD COLUMN lease_expires_at TIMESTAMPTZ NULL;
ALTER TABLE pds_registrations ADD COLUMN processing_status TEXT NOT NULL DEFAULT 'idle';

CREATE INDEX pds_registrations_lease_expires_at_idx ON pds_registrations (lease_expires_at);

# --- !Downs
DROP INDEX pds_registrations_lease_expires_at_idx;

ALTER TABLE pds_registrations DROP COLUMN processing_status;
ALTER TABLE pds_registrations DROP COLUMN lease_expires_at;
ALTER TABLE pds_registrations DROP COLUMN leased_by_instance_id;
