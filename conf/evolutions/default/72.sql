# --- !Ups

-- Group Project definition
CREATE TABLE public.group_project (
    id              SERIAL PRIMARY KEY,
    project_guid    UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    project_name    VARCHAR(100) NOT NULL,
    project_type    VARCHAR(30) NOT NULL CHECK (project_type IN ('HAPLOGROUP', 'SURNAME', 'GEOGRAPHIC', 'ETHNIC', 'RESEARCH', 'CUSTOM')),
    target_haplogroup VARCHAR(100),
    target_lineage  VARCHAR(10) CHECK (target_lineage IN ('Y_DNA', 'MT_DNA', 'BOTH')),
    description     TEXT,
    background_info TEXT,
    join_policy     VARCHAR(30) NOT NULL DEFAULT 'APPROVAL_REQUIRED' CHECK (join_policy IN ('OPEN', 'APPROVAL_REQUIRED', 'INVITE_ONLY', 'HAPLOGROUP_VERIFIED')),
    haplogroup_requirement VARCHAR(255),
    member_list_visibility VARCHAR(20) NOT NULL DEFAULT 'MEMBERS_ONLY' CHECK (member_list_visibility IN ('PUBLIC', 'MEMBERS_ONLY', 'ADMINS_ONLY', 'HIDDEN')),
    str_policy      VARCHAR(20) NOT NULL DEFAULT 'DISTANCE_ONLY' CHECK (str_policy IN ('HIDDEN', 'DISTANCE_ONLY', 'MODAL_COMPARISON', 'MEMBERS_ONLY_RAW', 'PUBLIC_RAW')),
    snp_policy      VARCHAR(30) NOT NULL DEFAULT 'TERMINAL_ONLY' CHECK (snp_policy IN ('HIDDEN', 'TERMINAL_ONLY', 'FULL_PATH', 'WITH_PRIVATE_VARIANTS')),
    public_tree_view BOOLEAN NOT NULL DEFAULT FALSE,
    succession_policy VARCHAR(30) DEFAULT 'CO_ADMIN_INHERITS' CHECK (succession_policy IN ('CO_ADMIN_INHERITS', 'MEMBER_VOTE', 'DECODINGUS_APPOINTS', 'PROJECT_CLOSES')),
    owner_did       VARCHAR(255) NOT NULL,
    at_uri          VARCHAR(512),
    at_cid          VARCHAR(255),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_group_project_owner ON public.group_project (owner_did);
CREATE INDEX idx_group_project_type ON public.group_project (project_type);
CREATE INDEX idx_group_project_haplogroup ON public.group_project (target_haplogroup) WHERE target_haplogroup IS NOT NULL;

-- Group Project membership
CREATE TABLE public.group_project_member (
    id              SERIAL PRIMARY KEY,
    group_project_id INTEGER NOT NULL REFERENCES public.group_project(id),
    citizen_did     VARCHAR(255) NOT NULL,
    biosample_at_uri VARCHAR(512),
    role            VARCHAR(20) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('ADMIN', 'CO_ADMIN', 'MODERATOR', 'CURATOR', 'MEMBER')),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL' CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'SUSPENDED', 'LEFT', 'REMOVED')),
    display_name    VARCHAR(50),
    kit_id          VARCHAR(50),
    visibility      JSONB NOT NULL DEFAULT '{}',
    subgroup_ids    TEXT[] NOT NULL DEFAULT '{}',
    contribution_level VARCHAR(20) DEFAULT 'OBSERVER' CHECK (contribution_level IN ('OBSERVER', 'CONTRIBUTOR', 'ACTIVE_RESEARCHER')),
    joined_at       TIMESTAMP,
    at_uri          VARCHAR(512),
    at_cid          VARCHAR(255),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (group_project_id, citizen_did)
);

CREATE INDEX idx_gpm_project ON public.group_project_member (group_project_id);
CREATE INDEX idx_gpm_citizen ON public.group_project_member (citizen_did);
CREATE INDEX idx_gpm_status ON public.group_project_member (status);

# --- !Downs

DROP TABLE IF EXISTS public.group_project_member;
DROP TABLE IF EXISTS public.group_project;
