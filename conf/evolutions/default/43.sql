-- !Ups

-- Cookie consent tracking for GDPR compliance
-- Tracks when users accept the cookie policy

CREATE TABLE auth.cookie_consents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES public.users(id) ON DELETE CASCADE,
    session_id VARCHAR(255),  -- For anonymous users before login
    ip_address_hash VARCHAR(64),  -- Hashed for privacy, used for anonymous consent
    consent_given BOOLEAN NOT NULL DEFAULT FALSE,
    consent_timestamp TIMESTAMP NOT NULL DEFAULT NOW(),
    policy_version VARCHAR(20) NOT NULL DEFAULT '1.0',  -- Track which version they accepted
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Either user_id or session_id must be present
    CONSTRAINT chk_consent_identity CHECK (user_id IS NOT NULL OR session_id IS NOT NULL)
);

-- Index for quick lookups
CREATE INDEX idx_cookie_consents_user_id ON auth.cookie_consents(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_cookie_consents_session_id ON auth.cookie_consents(session_id) WHERE session_id IS NOT NULL;

COMMENT ON TABLE auth.cookie_consents IS 'Tracks user acceptance of cookie policy for GDPR compliance';
COMMENT ON COLUMN auth.cookie_consents.policy_version IS 'Version of the cookie policy the user accepted';
COMMENT ON COLUMN auth.cookie_consents.ip_address_hash IS 'SHA-256 hash of IP address for anonymous consent tracking';

-- !Downs

DROP TABLE auth.cookie_consents;
