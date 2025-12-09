-- !Ups

-- Move user_pds_info from public schema to auth schema
-- This table stores where each user's AT Protocol identity lives (their home PDS)

-- Step 1: Create the new table in auth schema
CREATE TABLE auth.user_pds_info
(
    id         UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    user_id    UUID UNIQUE         NOT NULL,
    pds_url    VARCHAR(512)        NOT NULL,  -- Increased length for longer PDS URLs
    did        VARCHAR(255) UNIQUE NOT NULL,
    handle     VARCHAR(255),                  -- Cache the resolved handle
    created_at TIMESTAMP           NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP           NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_auth_user_pds_info_user_id FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE
);

-- Step 2: Migrate existing data
INSERT INTO auth.user_pds_info (id, user_id, pds_url, did, created_at, updated_at)
SELECT id, user_id, pds_url, did, created_at, updated_at
FROM public.user_pds_info;

-- Step 3: Drop the old table
DROP TABLE public.user_pds_info;

-- Step 4: Add indexes for common lookups
CREATE INDEX idx_auth_user_pds_info_did ON auth.user_pds_info(did);
CREATE INDEX idx_auth_user_pds_info_handle ON auth.user_pds_info(handle) WHERE handle IS NOT NULL;

COMMENT ON TABLE auth.user_pds_info IS 'Stores the home PDS URL for each user - where their AT Protocol identity lives';
COMMENT ON COLUMN auth.user_pds_info.pds_url IS 'The resolved PDS endpoint URL (e.g., https://bsky.social or https://pds.decodingus.com)';
COMMENT ON COLUMN auth.user_pds_info.handle IS 'Cached handle for quick lookups without re-resolution';

-- !Downs

-- Recreate the table in public schema
CREATE TABLE public.user_pds_info
(
    id         UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    user_id    UUID UNIQUE         NOT NULL,
    pds_url    VARCHAR(255)        NOT NULL,
    did        VARCHAR(255) UNIQUE NOT NULL,
    created_at TIMESTAMP           NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP           NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_pds_info_user_id FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_pds_info_did FOREIGN KEY (did) REFERENCES public.users (did) ON DELETE CASCADE
);

-- Migrate data back
INSERT INTO public.user_pds_info (id, user_id, pds_url, did, created_at, updated_at)
SELECT id, user_id, pds_url, did, created_at, updated_at
FROM auth.user_pds_info;

-- Drop the auth table
DROP TABLE auth.user_pds_info;
