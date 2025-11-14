# --- !Ups

ALTER TABLE alignment_metadata
    DROP COLUMN mapped_reads,
    DROP COLUMN properly_paired_reads;

# --- !Downs

ALTER TABLE alignment_metadata
    ADD COLUMN mapped_reads BIGINT,
    ADD COLUMN properly_paired_reads BIGINT;
