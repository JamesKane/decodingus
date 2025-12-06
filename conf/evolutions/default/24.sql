# --- !Ups
ALTER TABLE citizen_biosample ADD COLUMN specimen_donor_id INT REFERENCES specimen_donor(id);
CREATE INDEX citizen_biosample_specimen_donor_id_idx ON citizen_biosample(specimen_donor_id);

# --- !Downs
DROP INDEX citizen_biosample_specimen_donor_id_idx;
ALTER TABLE citizen_biosample DROP COLUMN specimen_donor_id;
