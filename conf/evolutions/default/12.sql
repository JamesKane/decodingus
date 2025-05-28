-- !Ups

ALTER TABLE ena_study RENAME TO genomic_studies;

ALTER TABLE genomic_studies
    ADD COLUMN source VARCHAR(10),
    ADD COLUMN submission_date DATE,
    ADD COLUMN last_update DATE,
    ADD COLUMN bio_project_id VARCHAR(50),
    ADD COLUMN molecule VARCHAR(50),
    ADD COLUMN topology VARCHAR(50),
    ADD COLUMN taxonomy_id INTEGER,
    ADD COLUMN version VARCHAR(10);

-- Update existing source values
UPDATE genomic_studies
SET source = 'ENA';

-- Now add NOT NULL constraint after the update
ALTER TABLE genomic_studies
    ALTER COLUMN source SET NOT NULL;

-- Add enum constraint to ensure only valid values
ALTER TABLE genomic_studies
    ADD CONSTRAINT valid_source CHECK (source IN ('ENA', 'NCBI_BIOPROJECT', 'NCBI_GENBANK'));

-- Rename the column
ALTER TABLE publication_ena_study
    RENAME COLUMN ena_study_id TO genomic_study_id;

-- Rename the foreign key constraint (if it exists)
ALTER TABLE publication_ena_study
    RENAME CONSTRAINT publication_ena_study_ena_study_id_fkey
        TO publication_ena_study_genomic_study_id_fkey;


-- !Downs

-- Revert the foreign key constraint rename
ALTER TABLE publication_ena_study
    RENAME CONSTRAINT publication_ena_study_genomic_study_id_fkey
        TO publication_ena_study_ena_study_id_fkey;

-- Revert the column rename
ALTER TABLE publication_ena_study
    RENAME COLUMN genomic_study_id TO ena_study_id;

ALTER TABLE genomic_studies
    DROP COLUMN source,
    DROP COLUMN submission_date,
    DROP COLUMN last_update,
    DROP COLUMN bio_project_id,
    DROP COLUMN molecule,
    DROP COLUMN topology,
    DROP COLUMN taxonomy_id,
    DROP COLUMN version;

ALTER TABLE genomic_studies RENAME TO ena_study;

