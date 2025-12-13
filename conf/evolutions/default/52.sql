# --- !Ups

-- Add provenance JSONB column to haplogroup table for multi-source attribution tracking
ALTER TABLE tree.haplogroup ADD COLUMN provenance JSONB;

-- Add GIN index for efficient querying by provenance fields
CREATE INDEX idx_haplogroup_provenance ON tree.haplogroup USING GIN (provenance);

-- Add comment for documentation
COMMENT ON COLUMN tree.haplogroup.provenance IS 'JSONB tracking node and variant provenance from multiple sources. Structure: {primaryCredit, nodeProvenance[], variantProvenance{}, lastMergedAt, lastMergedFrom}';

# --- !Downs

DROP INDEX IF EXISTS tree.idx_haplogroup_provenance;
ALTER TABLE tree.haplogroup DROP COLUMN IF EXISTS provenance;
