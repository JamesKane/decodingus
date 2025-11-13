# --- !Ups

ALTER TABLE alignment_metadata
    ADD COLUMN mapped_reads BIGINT,
    ADD COLUMN properly_paired_reads BIGINT;

ALTER TABLE alignment_coverage
    ADD COLUMN num_reads BIGINT,
    ADD COLUMN cov_bases BIGINT,
    ADD COLUMN coverage_pct DOUBLE PRECISION,
    ADD COLUMN mean_baseq DOUBLE PRECISION,
    ADD COLUMN low_coverage BIGINT;

# --- !Downs

ALTER TABLE alignment_metadata
    DROP COLUMN mapped_reads,
    DROP COLUMN properly_paired_reads;

ALTER TABLE alignment_coverage
    DROP COLUMN num_reads,
    DROP COLUMN cov_bases,
    DROP COLUMN coverage_pct,
    DROP COLUMN mean_baseq,
    DROP COLUMN low_coverage;
