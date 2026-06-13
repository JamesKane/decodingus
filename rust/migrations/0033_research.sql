-- D2: the PII-free ResearchSubject registry. A vendor-neutral pseudonymous "person"
-- node co-admins attach project memberships + merge-links to. Identity resolution is
-- Edge-to-Edge over D1 (id-list exchange) / genetic (D3) — the AppView learns no name,
-- kit number, or hash of one. INVARIANT: every column here is pseudonymous (UUID/DID).

CREATE SCHEMA IF NOT EXISTS research;

-- The pseudonymous person node — almost empty by design.
CREATE TABLE research.research_subject (
    research_subject_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    custody_did   TEXT,                            -- null = admin-stewarded; set on member claim
    retired_into  UUID REFERENCES research.research_subject(research_subject_id), -- tombstone → kept id
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Project membership: which subject is in which group-project, and who stewards the
-- (local, clear-text) identity for it.
CREATE TABLE research.subject_membership (
    research_subject_id UUID NOT NULL REFERENCES research.research_subject(research_subject_id) ON DELETE CASCADE,
    project_id    BIGINT NOT NULL REFERENCES social.group_project(id) ON DELETE CASCADE,
    steward_did   TEXT NOT NULL,                   -- the admin holding the local clear-text identity
    added_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (research_subject_id, project_id)
);
CREATE INDEX subject_membership_project_idx ON research.subject_membership (project_id);
CREATE INDEX subject_membership_steward_idx ON research.subject_membership (steward_did);

-- Audit of merges (4.1 id-exchange / 4.2 genetic / 4.3 assertion / claim) — pseudonymous.
CREATE TABLE research.subject_link (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_a       UUID NOT NULL,                 -- kept
    subject_b       UUID NOT NULL,                 -- retired into a
    method          TEXT NOT NULL,                 -- ID_EXCHANGE/GENETIC/ASSERTION/CLAIM
    asserted_by_did TEXT NOT NULL,
    confidence      DOUBLE PRECISION,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Optional, sparse: link to a federated sample IF the person published anonymized data.
CREATE TABLE research.subject_biosample (
    research_subject_id UUID REFERENCES research.research_subject(research_subject_id) ON DELETE CASCADE,
    sample_guid   UUID REFERENCES core.biosample(sample_guid),
    PRIMARY KEY (research_subject_id, sample_guid)
);
