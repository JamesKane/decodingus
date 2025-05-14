# --- !Ups
--- Add author and abstract columns to publications

ALTER TABLE publication ADD COLUMN authors VARCHAR(1000) NULL;
ALTER TABLE publication ADD COLUMN abstract_summary TEXT NULL;

# --- !Downs

ALTER TABLE publication DROP COLUMN abstract_summary;
ALTER TABLE publication DROP COLUMN authors;