-- !Ups

-- Create enum types
CREATE TYPE biological_sex AS ENUM ('male', 'female', 'intersex');
CREATE TYPE biosample_type AS ENUM ('Standard', 'PGP', 'Citizen', 'Ancient');

-- Add new columns to specimen_donor with temporary nullability
ALTER TABLE specimen_donor
    ADD COLUMN sex biological_sex,
    ADD COLUMN geocoord geometry(Point, 4326),
    ADD COLUMN date_range_start integer,
    ADD COLUMN date_range_end integer,
    ADD COLUMN donor_type biosample_type,
    ADD COLUMN pgp_participant_id varchar(50),
    ADD COLUMN citizen_biosample_did varchar(255);

-- Migrate data from biosample to specimen_donor
UPDATE specimen_donor sd
SET sex = CASE
              WHEN b.sex = 'male' THEN 'male'::biological_sex
              WHEN b.sex = 'female' THEN 'female'::biological_sex
              WHEN b.sex = 'intersex' THEN 'intersex'::biological_sex
              ELSE NULL
    END,
    geocoord = b.geocoord,
    date_range_start = b.date_range_start,
    date_range_end = b.date_range_end,
    donor_type = b.sample_type::biosample_type,
    pgp_participant_id = b.pgp_participant_id,
    citizen_biosample_did = b.citizen_biosample_did
FROM biosample b
WHERE b.specimen_donor_id = sd.id;

-- Set default value and not null constraint for donor_type after data migration
ALTER TABLE specimen_donor
    ALTER COLUMN donor_type SET NOT NULL,
    ALTER COLUMN donor_type SET DEFAULT 'Standard',
    ADD CONSTRAINT pgp_participant_id_required
        CHECK (donor_type != 'PGP' OR (donor_type = 'PGP' AND pgp_participant_id IS NOT NULL)),
    ADD CONSTRAINT citizen_did_required
        CHECK (donor_type != 'Citizen' OR (donor_type = 'Citizen' AND citizen_biosample_did IS NOT NULL));

-- Remove migrated columns from biosample
ALTER TABLE biosample
    DROP COLUMN sex,
    DROP COLUMN geocoord,
    DROP COLUMN sample_type,
    DROP COLUMN pgp_participant_id,
    DROP COLUMN citizen_biosample_did,
    DROP COLUMN date_range_start,
    DROP COLUMN date_range_end,
    DROP CONSTRAINT IF EXISTS pgp_participant_id_required,
    DROP CONSTRAINT IF EXISTS citizen_did_required,
    DROP CONSTRAINT IF EXISTS biosample_sex_check,
    DROP CONSTRAINT IF EXISTS biosample_type_check;

-- !Downs

-- Add columns back to biosample
ALTER TABLE biosample
    ADD COLUMN sex varchar(15),
    ADD COLUMN geocoord geometry(Point, 4326),
    ADD COLUMN sample_type varchar(10),
    ADD COLUMN pgp_participant_id varchar(50),
    ADD COLUMN citizen_biosample_did varchar(255),
    ADD COLUMN date_range_start integer,
    ADD COLUMN date_range_end integer;

-- Migrate data back from specimen_donor to biosample
UPDATE biosample b
SET sex = sd.sex::text,
    geocoord = sd.geocoord,
    sample_type = sd.donor_type::text,
    pgp_participant_id = sd.pgp_participant_id,
    citizen_biosample_did = sd.citizen_biosample_did,
    date_range_start = sd.date_range_start,
    date_range_end = sd.date_range_end
FROM specimen_donor sd
WHERE b.specimen_donor_id = sd.id;

-- Add constraints back to biosample
ALTER TABLE biosample
    ADD CONSTRAINT biosample_sex_check
        CHECK (sex IN ('male', 'female', 'intersex')),
    ADD CONSTRAINT biosample_type_check
        CHECK (sample_type IN ('Standard', 'PGP', 'Citizen', 'Ancient')),
    ADD CONSTRAINT pgp_participant_id_required
        CHECK (sample_type != 'PGP' OR (sample_type = 'PGP' AND pgp_participant_id IS NOT NULL)),
    ADD CONSTRAINT citizen_did_required
        CHECK (sample_type != 'Citizen' OR (sample_type = 'Citizen' AND citizen_biosample_did IS NOT NULL));

-- Remove added columns from specimen_donor
ALTER TABLE specimen_donor
    DROP COLUMN sex,
    DROP COLUMN geocoord,
    DROP COLUMN date_range_start,
    DROP COLUMN date_range_end,
    DROP COLUMN donor_type,
    DROP COLUMN pgp_participant_id,
    DROP COLUMN citizen_biosample_did;

-- Drop the enum types (need to drop them last since columns depend on them)
DROP TYPE biological_sex;
DROP TYPE biosample_type;