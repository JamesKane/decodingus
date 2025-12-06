# --- !Ups
ALTER TABLE biosample_original_haplogroup ADD COLUMN y_haplogroup_result JSONB;
ALTER TABLE biosample_original_haplogroup ADD COLUMN mt_haplogroup_result JSONB;
ALTER TABLE specimen_donor RENAME COLUMN citizen_biosample_did TO at_uri;

# --- !Downs
ALTER TABLE specimen_donor RENAME COLUMN at_uri TO citizen_biosample_did;
ALTER TABLE biosample_original_haplogroup DROP COLUMN mt_haplogroup_result;
ALTER TABLE biosample_original_haplogroup DROP COLUMN y_haplogroup_result;
