# --- !Ups
ALTER TABLE biosample_original_haplogroup ADD COLUMN y_haplogroup_result JSONB;
ALTER TABLE biosample_original_haplogroup ADD COLUMN mt_haplogroup_result JSONB;

# --- !Downs
ALTER TABLE biosample_original_haplogroup DROP COLUMN mt_haplogroup_result;
ALTER TABLE biosample_original_haplogroup DROP COLUMN y_haplogroup_result;
