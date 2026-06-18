-- ibd schema: population/ancestry analysis + privacy-preserving IBD matching,
-- attestations, suggestions, and match request/consent tracking.

-- ── Population & ancestry ────────────────────────────────────────────────────
CREATE TABLE ibd.population (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    population_name TEXT NOT NULL UNIQUE
);

CREATE TABLE ibd.analysis_method (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    method_name     TEXT NOT NULL UNIQUE
);

CREATE TABLE ibd.ancestry_analysis (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_guid     UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    analysis_method_id BIGINT NOT NULL REFERENCES ibd.analysis_method(id),
    population_id   BIGINT NOT NULL REFERENCES ibd.population(id),
    probability     NUMERIC(5,4) NOT NULL,
    UNIQUE (sample_guid, analysis_method_id, population_id)
);

-- Full ADMIXTURE/PCA breakdown per sample (pca_coordinates as JSONB).
CREATE TABLE ibd.population_breakdown (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_guid     UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    analysis_method TEXT,
    panel_type      TEXT,
    pca_coordinates JSONB NOT NULL DEFAULT '{}'::jsonb,
    analysis_date   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX population_breakdown_sample_idx ON ibd.population_breakdown (sample_guid);

CREATE TABLE ibd.population_breakdown_cache (
    sample_guid     UUID PRIMARY KEY REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    breakdown       JSONB NOT NULL DEFAULT '{}'::jsonb,
    breakdown_hash  VARCHAR(64),
    cached_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Cached O(N^2) pairwise overlap scores (order-independent pair key).
CREATE TABLE ibd.population_overlap_score (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_guid_1   UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    sample_guid_2   UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    score           DOUBLE PRECISION NOT NULL,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX population_overlap_pair_key
    ON ibd.population_overlap_score (LEAST(sample_guid_1, sample_guid_2), GREATEST(sample_guid_1, sample_guid_2));

-- ── IBD matching ─────────────────────────────────────────────────────────────
CREATE TABLE ibd.validation_service (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    guid            UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    trust_level     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE ibd.ibd_discovery_index (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_guid_1   UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    sample_guid_2   UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    pangenome_graph_id BIGINT REFERENCES genomics.pangenome_graph(id),
    match_region_type TEXT NOT NULL,         -- AUTOSOMAL/X/Y/MT
    total_shared_cm_approx DOUBLE PRECISION,
    num_shared_segments_approx INTEGER,
    is_publicly_discoverable BOOLEAN NOT NULL DEFAULT false,
    consensus_status TEXT,
    validation_service_id BIGINT REFERENCES ibd.validation_service(id),
    indexed_date    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Order-independent pair uniqueness per region type.
CREATE UNIQUE INDEX ibd_discovery_pair_key
    ON ibd.ibd_discovery_index (LEAST(sample_guid_1, sample_guid_2), GREATEST(sample_guid_1, sample_guid_2), match_region_type);

CREATE TABLE ibd.ibd_pds_attestation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ibd_discovery_index_id BIGINT NOT NULL REFERENCES ibd.ibd_discovery_index(id) ON DELETE CASCADE,
    attesting_pds_guid UUID NOT NULL,
    attestation_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    attestation_signature TEXT NOT NULL,
    attestation_type TEXT NOT NULL,          -- INITIAL_REPORT/CONFIRMATION/DISPUTE/REVOCATION
    attestation_notes TEXT
);
CREATE INDEX ibd_attestation_index_idx ON ibd.ibd_pds_attestation (ibd_discovery_index_id);

CREATE TABLE ibd.match_suggestion (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_sample_guid UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    suggested_sample_guid UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    suggestion_type TEXT NOT NULL,           -- SHARED_MATCH/POPULATION_OVERLAP/HAPLOGROUP
    score           DOUBLE PRECISION,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE/DISMISSED/EXPIRED/CONVERTED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ
);
CREATE INDEX match_suggestion_target_idx ON ibd.match_suggestion (target_sample_guid, status);

-- ── Match request & consent (AT Protocol records keyed by at:// URI) ─────────
CREATE TABLE ibd.match_request (
    request_uri     TEXT PRIMARY KEY,
    requester_did   TEXT NOT NULL,
    target_did      TEXT NOT NULL,
    requester_sample_guid UUID REFERENCES core.biosample(sample_guid),
    target_sample_guid    UUID REFERENCES core.biosample(sample_guid),
    status          TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING/CANCELLED/CONSENTED/DECLINED/EXPIRED
    details         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX match_request_target_did_idx ON ibd.match_request (target_did, status);
CREATE INDEX match_request_requester_did_idx ON ibd.match_request (requester_did, status);

CREATE TABLE ibd.match_consent (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_uri     TEXT NOT NULL REFERENCES ibd.match_request(request_uri) ON DELETE CASCADE,
    consenting_did  TEXT NOT NULL,
    consent_given   BOOLEAN NOT NULL,
    consent_uri     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX match_consent_request_idx ON ibd.match_consent (request_uri);
