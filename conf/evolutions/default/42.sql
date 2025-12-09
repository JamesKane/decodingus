-- !Ups

-- Rename email_encrypted back to email and ensure CITEXT for case-insensitive uniqueness
-- Drop any existing constraints on the column (both possible names)
ALTER TABLE public.users DROP CONSTRAINT IF EXISTS users_email_encrypted_key;
ALTER TABLE public.users DROP CONSTRAINT IF EXISTS users_email_key;

-- Rename the column
ALTER TABLE public.users RENAME COLUMN email_encrypted TO email;

-- Change type to CITEXT for case-insensitive comparison (if not already)
ALTER TABLE public.users ALTER COLUMN email TYPE CITEXT USING email::CITEXT;

-- Add unique constraint (case-insensitive via CITEXT)
ALTER TABLE public.users ADD CONSTRAINT users_email_key UNIQUE (email);

-- !Downs

-- Drop the unique constraint
ALTER TABLE public.users DROP CONSTRAINT IF EXISTS users_email_key;

-- Change type back to VARCHAR (stored encrypted values were text)
ALTER TABLE public.users ALTER COLUMN email TYPE VARCHAR(255);

-- Rename back to email_encrypted
ALTER TABLE public.users RENAME COLUMN email TO email_encrypted;

-- Re-add the original constraint
ALTER TABLE public.users ADD CONSTRAINT users_email_encrypted_key UNIQUE (email_encrypted);
