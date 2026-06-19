-- Notifications (Tier 2a) — the in-app alert rail + the SYSTEM entry point the
-- collaboration flows (IBD/match-consent, D4 assertions) write into. One-way alerts
-- (distinct from the two-way support threads); the unread bell reuses the lazy-badge
-- pattern from mig 0041.

CREATE TABLE social.notification (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id  UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,
    kind          TEXT NOT NULL,        -- THREAD_REPLY | FEED_REPLY | MATCH | ASSERTION | SYSTEM
    title         TEXT NOT NULL,        -- the human summary (no embedded name; actor joined for display)
    body          TEXT,                 -- optional detail
    link          TEXT,                 -- where clicking navigates (e.g. /messages/<id>, /feed)
    actor_id      UUID REFERENCES ident.users(id) ON DELETE SET NULL,  -- who triggered it (NULL for SYSTEM)
    related_entity_type TEXT,
    related_entity_id   UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at       TIMESTAMPTZ
);
-- Recipient's list, newest first.
CREATE INDEX notification_recipient_idx ON social.notification (recipient_id, created_at DESC);
-- Unread badge.
CREATE INDEX notification_unread_idx ON social.notification (recipient_id) WHERE read_at IS NULL;
