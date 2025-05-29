-- !Ups
ALTER TABLE genomic_studies ALTER COLUMN source TYPE varchar(20);

-- !Downs
ALTER TABLE genomic_studies ALTER COLUMN source TYPE varchar(10);