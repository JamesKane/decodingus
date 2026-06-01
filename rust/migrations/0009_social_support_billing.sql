-- social / support / billing schemas.

-- ── social: reputation ──────────────────────────────────────────────────────
CREATE TABLE social.reputation_event_type (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    default_points_change INTEGER NOT NULL DEFAULT 0,
    is_positive     BOOLEAN NOT NULL DEFAULT true,
    is_system_generated BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE social.reputation_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,
    event_type_id   UUID NOT NULL REFERENCES social.reputation_event_type(id),
    actual_points_change INTEGER NOT NULL,
    source_user_id  UUID REFERENCES ident.users(id) ON DELETE SET NULL,
    related_entity_type TEXT,
    related_entity_id UUID,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX reputation_event_user_idx ON social.reputation_event (user_id, created_at DESC);

CREATE TABLE social.user_reputation_score (
    user_id         UUID PRIMARY KEY REFERENCES ident.users(id) ON DELETE CASCADE,
    score           BIGINT NOT NULL DEFAULT 0,
    last_calculated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── social: messaging & feed ─────────────────────────────────────────────────
CREATE TABLE social.user_block (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    blocker_id      UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,
    blocked_id      UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (blocker_id, blocked_id)
);

CREATE TABLE social.conversation (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    participant_ids UUID[] NOT NULL DEFAULT '{}',
    subject         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message_at TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ
);
CREATE INDEX conversation_participants_gin ON social.conversation USING gin (participant_ids);

CREATE TABLE social.message (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES social.conversation(id) ON DELETE CASCADE,
    sender_id       UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,
    body            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at         TIMESTAMPTZ
);
CREATE INDEX message_conversation_idx ON social.message (conversation_id, created_at);

CREATE TABLE social.feed_post (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id       UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,
    content         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

-- ── social: group projects (legacy public.group_project) ─────────────────────
-- Rich access-control policies kept as TEXT (flexible) + atproto JSONB.
CREATE TABLE social.group_project (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_guid    UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    project_name    TEXT NOT NULL,
    project_type    TEXT NOT NULL,            -- HAPLOGROUP/SURNAME/GEOGRAPHIC/ETHNIC/RESEARCH/CUSTOM
    target_haplogroup TEXT,
    target_lineage  TEXT,                      -- Y_DNA/MT_DNA/BOTH
    description     TEXT,
    join_policy     TEXT NOT NULL DEFAULT 'OPEN',
    member_list_visibility TEXT NOT NULL DEFAULT 'MEMBERS_ONLY',
    str_policy      TEXT,
    snp_policy      TEXT,
    public_tree_view BOOLEAN NOT NULL DEFAULT false,
    succession_policy TEXT,
    owner_did       TEXT,
    atproto         JSONB,
    deleted         BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── support: contact messages (authenticated or anonymous) ───────────────────
CREATE TABLE support.contact_message (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES ident.users(id) ON DELETE SET NULL,
    sender_name     TEXT,
    sender_email    TEXT,
    subject         TEXT,
    message         TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'new',   -- new/read/replied/closed
    ip_address_hash VARCHAR(64),
    user_last_viewed_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX contact_message_status_idx ON support.contact_message (status, created_at DESC);
CREATE INDEX contact_message_user_idx ON support.contact_message (user_id);

-- ── billing: subscriptions ───────────────────────────────────────────────────
CREATE TABLE billing.patron_subscription (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,
    patron_tier     TEXT NOT NULL,            -- SUPPORTER/CONTRIBUTOR/SUSTAINER/FOUNDING_PATRON
    status          TEXT NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE/CANCELLED/PAST_DUE/EXPIRED
    payment_provider TEXT,                     -- STRIPE/PAYPAL
    provider_subscription_id TEXT,
    amount_cents    INTEGER,
    currency        TEXT NOT NULL DEFAULT 'USD',
    billing_interval TEXT,                     -- MONTHLY/YEARLY
    current_period_start TIMESTAMPTZ,
    current_period_end   TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX patron_subscription_user_idx ON billing.patron_subscription (user_id, status);
