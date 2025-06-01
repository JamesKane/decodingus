# --- !Ups
-- First add the column with a temporary NULL constraint
ALTER TABLE public.biosample
    ADD COLUMN sample_type varchar(10);

-- Update all existing rows to 'Standard'
UPDATE public.biosample
SET sample_type = 'Standard';

-- Now make the column NOT NULL and add the constraint
ALTER TABLE public.biosample
    ALTER COLUMN sample_type SET NOT NULL,
    ADD CONSTRAINT biosample_type_check CHECK (
        sample_type IN ('Standard', 'PGP', 'Citizen', 'Ancient')
        );

-- Add the rest of the columns
ALTER TABLE public.biosample
    ADD COLUMN pgp_participant_id varchar(50),
    ADD COLUMN citizen_biosample_did varchar(255),
    ADD COLUMN source_platform varchar(100),
    ADD COLUMN date_range_start integer,
    ADD COLUMN date_range_end integer;

-- Add constraints for PGP and Citizen samples
ALTER TABLE public.biosample
    ADD CONSTRAINT pgp_participant_id_required
        CHECK (
            (sample_type != 'PGP') OR
            (sample_type = 'PGP' AND pgp_participant_id IS NOT NULL)
            );

ALTER TABLE public.biosample
    ADD CONSTRAINT citizen_did_required
        CHECK (
            (sample_type != 'Citizen') OR
            (sample_type = 'Citizen' AND citizen_biosample_did IS NOT NULL)
            );

# --- !Downs
ALTER TABLE public.biosample
    DROP CONSTRAINT IF EXISTS citizen_did_required,
    DROP CONSTRAINT IF EXISTS pgp_participant_id_required,
    DROP CONSTRAINT IF EXISTS biosample_type_check,
    DROP COLUMN IF EXISTS date_range_end,
    DROP COLUMN IF EXISTS date_range_start,
    DROP COLUMN IF EXISTS source_platform,
    DROP COLUMN IF EXISTS citizen_biosample_did,
    DROP COLUMN IF EXISTS pgp_participant_id,
    DROP COLUMN IF EXISTS sample_type;