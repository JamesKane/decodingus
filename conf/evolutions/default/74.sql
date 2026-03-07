# --- !Ups

-- Match Discovery Engine tables (IBD-AV-1)

CREATE TABLE match_suggestion (
    id              BIGSERIAL PRIMARY KEY,
    target_sample_guid UUID NOT NULL,
    suggested_sample_guid UUID NOT NULL,
    suggestion_type VARCHAR(30) NOT NULL CHECK (suggestion_type IN ('SHARED_MATCH', 'POPULATION_OVERLAP', 'HAPLOGROUP')),
    score           DOUBLE PRECISION NOT NULL,
    metadata        JSONB,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISMISSED', 'EXPIRED', 'CONVERTED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ,
    UNIQUE (target_sample_guid, suggested_sample_guid, suggestion_type)
);

CREATE INDEX idx_match_suggestion_target ON match_suggestion(target_sample_guid, status);
CREATE INDEX idx_match_suggestion_expires ON match_suggestion(expires_at) WHERE status = 'ACTIVE';

CREATE TABLE population_breakdown_cache (
    id              BIGSERIAL PRIMARY KEY,
    sample_guid     UUID NOT NULL UNIQUE,
    breakdown       JSONB NOT NULL,
    breakdown_hash  VARCHAR(64) NOT NULL,
    cached_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source_at_uri   VARCHAR(500)
);

CREATE INDEX idx_pop_cache_sample ON population_breakdown_cache(sample_guid);

CREATE TABLE population_overlap_score (
    id              BIGSERIAL PRIMARY KEY,
    sample_guid_1   UUID NOT NULL,
    sample_guid_2   UUID NOT NULL,
    overlap_score   DOUBLE PRECISION NOT NULL,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (sample_guid_1, sample_guid_2),
    CHECK (sample_guid_1 < sample_guid_2)
);

CREATE INDEX idx_pop_overlap_sample1 ON population_overlap_score(sample_guid_1);
CREATE INDEX idx_pop_overlap_sample2 ON population_overlap_score(sample_guid_2);

-- Match Request & Consent Tracking tables (IBD-AV-2)

CREATE TABLE match_request_tracking (
    id              BIGSERIAL PRIMARY KEY,
    at_uri          VARCHAR(500) NOT NULL UNIQUE,
    requester_did   VARCHAR(255) NOT NULL,
    from_sample_guid UUID NOT NULL,
    to_sample_guid  UUID NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'WITHDRAWN', 'CANCELLED')),
    message         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ
);

CREATE INDEX idx_match_req_requester ON match_request_tracking(requester_did, status);
CREATE INDEX idx_match_req_to_sample ON match_request_tracking(to_sample_guid, status);
CREATE INDEX idx_match_req_from_sample ON match_request_tracking(from_sample_guid, status);

CREATE TABLE match_consent_tracking (
    id              BIGSERIAL PRIMARY KEY,
    at_uri          VARCHAR(500) NOT NULL UNIQUE,
    consenting_did  VARCHAR(255) NOT NULL,
    sample_guid     UUID NOT NULL,
    consent_level   VARCHAR(20) NOT NULL CHECK (consent_level IN ('FULL', 'ANONYMOUS', 'PROJECT_ONLY')),
    allowed_match_types JSONB,
    share_contact_info BOOLEAN NOT NULL DEFAULT FALSE,
    consented_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ
);

CREATE INDEX idx_match_consent_did ON match_consent_tracking(consenting_did);
CREATE INDEX idx_match_consent_sample ON match_consent_tracking(sample_guid);

-- Extend ibd_discovery_index for IBD-AV-4 linkage
ALTER TABLE ibd_discovery_index
    ADD COLUMN IF NOT EXISTS match_request_at_uri VARCHAR(500),
    ADD COLUMN IF NOT EXISTS requester_did VARCHAR(255),
    ADD COLUMN IF NOT EXISTS target_did VARCHAR(255);

# --- !Downs

ALTER TABLE ibd_discovery_index
    DROP COLUMN IF EXISTS match_request_at_uri,
    DROP COLUMN IF EXISTS requester_did,
    DROP COLUMN IF EXISTS target_did;

DROP TABLE IF EXISTS match_consent_tracking;
DROP TABLE IF EXISTS match_request_tracking;
DROP TABLE IF EXISTS population_overlap_score;
DROP TABLE IF EXISTS population_breakdown_cache;
DROP TABLE IF EXISTS match_suggestion;
