-- ident schema: users, RBAC, AT Protocol identity/OAuth, consent.

CREATE TABLE ident.users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           CITEXT UNIQUE,           -- case-insensitive
    did             TEXT UNIQUE,             -- AT Protocol DID
    handle          TEXT UNIQUE,
    display_name    TEXT,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── RBAC ─────────────────────────────────────────────────────────────────────
CREATE TABLE ident.roles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ident.permissions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ident.role_permissions (
    role_id         UUID NOT NULL REFERENCES ident.roles(id) ON DELETE CASCADE,
    permission_id   UUID NOT NULL REFERENCES ident.permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE ident.user_roles (
    user_id         UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,
    role_id         UUID NOT NULL REFERENCES ident.roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- Base roles the app expects to exist (Admin, Curator, TreeCurator).
INSERT INTO ident.roles (name, description) VALUES
    ('Admin', 'Full administrative access'),
    ('Curator', 'Content curation'),
    ('TreeCurator', 'Haplogroup tree curation')
ON CONFLICT (name) DO NOTHING;

-- ── AT Protocol identity / OAuth ────────────────────────────────────────────
CREATE TABLE ident.user_pds_info (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL UNIQUE REFERENCES ident.users(id) ON DELETE CASCADE,
    pds_url         VARCHAR(512) NOT NULL,
    did             TEXT UNIQUE,
    handle          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ident.user_login_info (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,
    provider_id     TEXT NOT NULL,
    provider_key    TEXT NOT NULL,
    -- bcrypt for legacy verification, argon2 for new hashes (NULL for OAuth-only).
    password_hash   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider_id, provider_key)
);

CREATE TABLE ident.user_oauth2_info (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    login_info_id   UUID NOT NULL UNIQUE REFERENCES ident.user_login_info(id) ON DELETE CASCADE,
    access_token    TEXT NOT NULL,
    token_type      TEXT,
    expires_in      BIGINT,
    refresh_token   TEXT,
    scope           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ident.atprotocol_authorization_servers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    issuer_url      TEXT NOT NULL UNIQUE,
    authorization_endpoint TEXT,
    token_endpoint  TEXT,
    pushed_authorization_request_endpoint TEXT,
    dpop_signing_alg_values_supported TEXT,
    scopes_supported TEXT,
    metadata_fetched_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ident.atprotocol_client_metadata (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id_url   TEXT NOT NULL UNIQUE,
    client_name     TEXT,
    logo_uri        TEXT,
    tos_uri         TEXT,
    policy_uri      TEXT,
    redirect_uris   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── GDPR cookie consent (authenticated or anonymous) ─────────────────────────
CREATE TABLE ident.cookie_consents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES ident.users(id) ON DELETE SET NULL,
    session_id      TEXT,
    ip_address_hash VARCHAR(64),
    consent_given   BOOLEAN NOT NULL DEFAULT false,
    consent_timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    policy_version  TEXT,
    user_agent      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
