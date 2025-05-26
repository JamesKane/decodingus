# --- !Ups

-- Add new columns to the public.publication table from OpenAlex integration
ALTER TABLE public.publication
    ADD COLUMN open_alex_id VARCHAR(255),
    ADD COLUMN citation_normalized_percentile REAL,
    ADD COLUMN cited_by_count INTEGER,
    ADD COLUMN open_access_status VARCHAR(50),
    ADD COLUMN open_access_url VARCHAR(2048),
    ADD COLUMN primary_topic VARCHAR(255),
    ADD COLUMN publication_type VARCHAR(50),
    ADD COLUMN publisher VARCHAR(255);

-- Add unique constraint for open_alex_id if it's guaranteed to be unique
-- ALTER TABLE public.publication ADD CONSTRAINT publication_open_alex_id_uk UNIQUE (open_alex_id);
-- Note: It's often safer to add unique constraints after populating existing NULLs
-- if you have existing rows where open_alex_id would be NULL, as NULL is not unique.
-- If you insert new data, you might add this in a later evolution or use a unique index
-- that allows NULLs (e.g., CREATE UNIQUE INDEX ON table (col) WHERE col IS NOT NULL; in Postgres).
-- For now, if your model allows Option[String], the database column should allow NULL.

# --- !Downs

-- Revert changes: Drop the columns added in this evolution
ALTER TABLE public.publication
    DROP COLUMN publisher,
    DROP COLUMN publication_type,
    DROP COLUMN primary_topic,
    DROP COLUMN open_access_url,
    DROP COLUMN open_access_status,
    DROP COLUMN cited_by_count,
    DROP COLUMN citation_normalized_percentile,
    DROP COLUMN open_alex_id;