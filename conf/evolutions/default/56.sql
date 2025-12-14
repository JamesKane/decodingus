# --- !Ups

ALTER TABLE variant_v2 ADD COLUMN annotations JSONB DEFAULT '{}'::jsonb;

COMMENT ON COLUMN variant_v2.annotations IS 'Computed region overlaps (e.g., Cytobands, PAR, STR overlaps). Managed by background jobs.';

CREATE INDEX idx_variant_v2_annotations ON variant_v2 USING GIN(annotations);

# --- !Downs

DROP INDEX IF EXISTS idx_variant_v2_annotations;
ALTER TABLE variant_v2 DROP COLUMN annotations;
