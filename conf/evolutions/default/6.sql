# --- !Ups
--- Add tables for Authentication and Authorization
CREATE SCHEMA auth;
CREATE EXTENSION IF NOT EXISTS citext;

-- Schema: public
-- Users Table
CREATE TABLE public.users
(
    id           UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    email        CITEXT UNIQUE,
    did          VARCHAR(255) UNIQUE NOT NULL,
    handle       VARCHAR(255) UNIQUE,
    display_name VARCHAR(255),
    created_at   TIMESTAMP           NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP           NOT NULL DEFAULT NOW(),
    is_active    BOOLEAN             NOT NULL DEFAULT TRUE
);

-- User PDS Information
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

-- Reputation Event Types Table
CREATE TABLE public.reputation_event_types
(
    id                    UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    name                  VARCHAR(100) UNIQUE NOT NULL,
    description           TEXT,
    default_points_change INTEGER             NOT NULL,
    is_positive           BOOLEAN             NOT NULL,
    is_system_generated   BOOLEAN             NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMP           NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP           NOT NULL DEFAULT NOW()
);

-- Reputation Events Log Table
CREATE TABLE public.reputation_events
(
    id                   UUID PRIMARY KEY   DEFAULT gen_random_uuid(),
    user_id              UUID      NOT NULL,
    event_type_id        UUID      NOT NULL,
    actual_points_change INTEGER   NOT NULL,
    source_user_id       UUID,                                                                                                                  -- NULL if system-generated
    related_entity_type  VARCHAR(50),
    related_entity_id    UUID,                                                                                                                  -- For specific post/comment/etc.
    notes                TEXT,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_reputation_events_user_id FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_reputation_events_event_type_id FOREIGN KEY (event_type_id) REFERENCES public.reputation_event_types (id) ON DELETE RESTRICT, -- RESTRICT to prevent deleting event types that are referenced
    CONSTRAINT fk_reputation_events_source_user_id FOREIGN KEY (source_user_id) REFERENCES public.users (id) ON DELETE SET NULL                 -- Set to NULL if source user is deleted
);

-- User Reputation Scores Table (Aggregated Score)
CREATE TABLE public.user_reputation_scores
(
    user_id            UUID PRIMARY KEY,
    score              BIGINT    NOT NULL DEFAULT 0,
    last_calculated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_reputation_scores_user_id FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE
);

-- Schema: auth

-- User Login Info Table
CREATE TABLE auth.user_login_info
(
    id           UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL, -- Links to public.users
    provider_id  VARCHAR(255) NOT NULL,
    provider_key VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_auth_user_login_info_user_id FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE,
    CONSTRAINT uq_auth_provider_id_key UNIQUE (provider_id, provider_key)
);

-- User OAuth2 Info Table (for storing tokens)
CREATE TABLE auth.user_oauth2_info
(
    id            UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    login_info_id UUID UNIQUE NOT NULL,
    access_token  TEXT        NOT NULL,
    token_type    VARCHAR(50),
    expires_in    BIGINT,
    refresh_token TEXT,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    scope         TEXT,
    CONSTRAINT fk_auth_user_oauth2_info_login_info_id FOREIGN KEY (login_info_id) REFERENCES auth.user_login_info (id) ON DELETE CASCADE
);

-- Roles Table
CREATE TABLE auth.roles
(
    id          UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    name        VARCHAR(255) UNIQUE NOT NULL,
    description TEXT,
    created_at  TIMESTAMP           NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP           NOT NULL DEFAULT NOW()
);

-- Permissions Table
CREATE TABLE auth.permissions
(
    id          UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    name        VARCHAR(255) UNIQUE NOT NULL,
    description TEXT,
    created_at  TIMESTAMP           NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP           NOT NULL DEFAULT NOW()
);

-- Role Permissions Table (Many-to-Many)
CREATE TABLE auth.role_permissions
(
    role_id       UUID NOT NULL,
    permission_id UUID NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_auth_role_permissions_role_id FOREIGN KEY (role_id) REFERENCES auth.roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_auth_role_permissions_permission_id FOREIGN KEY (permission_id) REFERENCES auth.permissions (id) ON DELETE CASCADE
);

-- User Roles Table (Many-to-Many)
CREATE TABLE auth.user_roles
(
    user_id UUID NOT NULL, -- Links to public.users
    role_id UUID NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_auth_user_roles_user_id FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_auth_user_roles_role_id FOREIGN KEY (role_id) REFERENCES auth.roles (id) ON DELETE CASCADE
);

-- AT Protocol Authorization Servers
CREATE TABLE auth.atprotocol_authorization_servers
(
    id                                    UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    issuer_url                            VARCHAR(255) UNIQUE NOT NULL,
    authorization_endpoint                VARCHAR(255),
    token_endpoint                        VARCHAR(255),
    pushed_authorization_request_endpoint VARCHAR(255),
    dpop_signing_alg_values_supported     TEXT,
    scopes_supported                      TEXT,
    client_id_metadata_document_supported BOOLEAN,
    metadata_fetched_at                   TIMESTAMP           NOT NULL DEFAULT NOW(),
    created_at                            TIMESTAMP           NOT NULL DEFAULT NOW(),
    updated_at                            TIMESTAMP           NOT NULL DEFAULT NOW()
);

-- AT Protocol Client Metadata
CREATE TABLE auth.atprotocol_client_metadata
(
    id            UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    client_id_url VARCHAR(255) UNIQUE NOT NULL,
    client_name   VARCHAR(255),
    client_uri    VARCHAR(255),
    logo_uri      VARCHAR(255),
    tos_uri       VARCHAR(255),
    policy_uri    VARCHAR(255),
    redirect_uris TEXT,
    created_at    TIMESTAMP           NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP           NOT NULL DEFAULT NOW()
);

# --- !Downs

DROP TABLE auth.atprotocol_client_metadata;
DROP TABLE auth.atprotocol_authorization_servers;
DROP TABLE auth.user_roles;
DROP TABLE auth.role_permissions;
DROP TABLE auth.permissions;
DROP TABLE auth.roles;
DROP TABLE auth.user_oauth2_info;
DROP TABLE auth.user_login_info;
DROP TABLE public.user_reputation_scores;
DROP TABLE public.reputation_events;
DROP TABLE public.reputation_event_types;
DROP TABLE public.user_pds_info;
DROP TABLE public.users;

DROP SCHEMA auth;