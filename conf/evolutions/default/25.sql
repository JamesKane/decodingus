# --- !Ups

ALTER TABLE sequence_library ADD COLUMN at_uri VARCHAR(255);
ALTER TABLE sequence_library ADD COLUMN at_cid VARCHAR(255);
CREATE INDEX idx_sequence_library_at_uri ON sequence_library(at_uri);

ALTER TABLE alignment_metadata ADD COLUMN reference_build VARCHAR(255);
ALTER TABLE alignment_metadata ADD COLUMN variant_caller VARCHAR(255);
ALTER TABLE alignment_metadata ADD COLUMN genome_territory BIGINT;
ALTER TABLE alignment_metadata ADD COLUMN mean_coverage DOUBLE PRECISION;
ALTER TABLE alignment_metadata ADD COLUMN median_coverage DOUBLE PRECISION;
ALTER TABLE alignment_metadata ADD COLUMN sd_coverage DOUBLE PRECISION;
ALTER TABLE alignment_metadata ADD COLUMN pct_exc_dupe DOUBLE PRECISION;
ALTER TABLE alignment_metadata ADD COLUMN pct_exc_mapq DOUBLE PRECISION;
ALTER TABLE alignment_metadata ADD COLUMN pct_10x DOUBLE PRECISION;
ALTER TABLE alignment_metadata ADD COLUMN pct_20x DOUBLE PRECISION;
ALTER TABLE alignment_metadata ADD COLUMN pct_30x DOUBLE PRECISION;
ALTER TABLE alignment_metadata ADD COLUMN het_snp_sensitivity DOUBLE PRECISION;

# --- !Downs

ALTER TABLE alignment_metadata DROP COLUMN het_snp_sensitivity;
ALTER TABLE alignment_metadata DROP COLUMN pct_30x;
ALTER TABLE alignment_metadata DROP COLUMN pct_20x;
ALTER TABLE alignment_metadata DROP COLUMN pct_10x;
ALTER TABLE alignment_metadata DROP COLUMN pct_exc_mapq;
ALTER TABLE alignment_metadata DROP COLUMN pct_exc_dupe;
ALTER TABLE alignment_metadata DROP COLUMN sd_coverage;
ALTER TABLE alignment_metadata DROP COLUMN median_coverage;
ALTER TABLE alignment_metadata DROP COLUMN mean_coverage;
ALTER TABLE alignment_metadata DROP COLUMN genome_territory;
ALTER TABLE alignment_metadata DROP COLUMN variant_caller;
ALTER TABLE alignment_metadata DROP COLUMN reference_build;

DROP INDEX idx_sequence_library_at_uri;
ALTER TABLE sequence_library DROP COLUMN at_cid;
ALTER TABLE sequence_library DROP COLUMN at_uri;
