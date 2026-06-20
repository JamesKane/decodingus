-- Recruitment campaigns (Tier 3c) — privacy-preserving cohort outreach. A project
-- researcher defines criteria + a message; the AppView computes the matching cohort from
-- the fed.* mirrors and BROKERS invitations (it never hands the researcher the cohort).
-- Only members who opt in (ACCEPT) become visible to the researcher. This is the same
-- "never materialize everyone" stance as IBD candidate generation.

-- The campaign definition. The researcher sees aggregate counts + opt-ins, never the
-- invited/declined DIDs.
CREATE TABLE research.recruitment_campaign (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id        BIGINT NOT NULL REFERENCES social.group_project(id) ON DELETE CASCADE,
    created_by        UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,  -- the researcher
    title             TEXT NOT NULL,
    message           TEXT NOT NULL,
    target_haplogroup TEXT NOT NULL,                 -- exact match vs fed.biosample y/mt haplogroup (v1)
    lineage           TEXT NOT NULL,                 -- Y_DNA | MT_DNA
    status            TEXT NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | CLOSED
    cohort_size       INTEGER NOT NULL DEFAULT 0,    -- how many were invited (aggregate only)
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX recruitment_campaign_project_idx ON research.recruitment_campaign (project_id, created_at DESC);

-- Per-target delivery + response. The AppView knows these (it already knows did→haplogroup
-- from fed.biosample); the researcher read-path exposes ONLY status='ACCEPTED' rows.
CREATE TABLE research.recruitment_target (
    campaign_id   BIGINT NOT NULL REFERENCES research.recruitment_campaign(id) ON DELETE CASCADE,
    target_did    TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'INVITED',  -- INVITED | ACCEPTED | DECLINED
    responded_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (campaign_id, target_did)
);
-- A member's open invitations (and dedup so a DID is invited once per campaign).
CREATE INDEX recruitment_target_did_idx ON research.recruitment_target (target_did, status);
