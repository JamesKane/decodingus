-- D5: the group-project collaborator-team ACL. A *project* (social.group_project,
-- mig 0009 — reused, not duplicated) is the consent/scope boundary the whole stack
-- references (scope=project:<id>); project_member is the trust circle that gates it:
-- D1 only relays GENEALOGY_PII between team members, D2 registry ops are role-gated,
-- D4 (later) serves R2 to the team. PII-free: DIDs + roles only. The project's
-- owner_did is the founding ADMIN (no row needed for it).

CREATE TABLE research.project_member (
    project_id    BIGINT NOT NULL REFERENCES social.group_project(id) ON DELETE CASCADE,
    member_did    TEXT NOT NULL,
    role          TEXT NOT NULL,                 -- ADMIN/CO_ADMIN/MODERATOR/CURATOR
    permissions   TEXT[] NOT NULL DEFAULT '{}',  -- granular overrides (forward; role-gated for v1)
    appointed_by  TEXT,
    joined_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at       TIMESTAMPTZ,                    -- revocation; NULL = live
    PRIMARY KEY (project_id, member_did)
);
CREATE INDEX project_member_did_idx ON research.project_member (member_did) WHERE left_at IS NULL;
