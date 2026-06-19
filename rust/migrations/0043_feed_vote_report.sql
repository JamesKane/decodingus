-- Feed voting + moderation (Tier 1b). Votes drive author reputation
-- (FEED_POST_UPVOTED/DOWNVOTED via du_db::reputation); reports feed a curator
-- moderation queue whose spam verdict fires SPAM_REPORT_VALIDATED + soft-deletes.

-- One row per (post, voter); value ∈ {-1, 1}. Removing a vote deletes the row. The
-- post's net score is sum(value). PK prevents double-voting.
CREATE TABLE social.feed_vote (
    post_id     UUID NOT NULL REFERENCES social.feed_post(id) ON DELETE CASCADE,
    voter_id    UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,
    value       SMALLINT NOT NULL CHECK (value IN (-1, 1)),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, voter_id)
);

-- Abuse reports. One open report per (post, reporter); a moderator resolves it
-- dismissed/actioned.
CREATE TABLE social.feed_report (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id     UUID NOT NULL REFERENCES social.feed_post(id) ON DELETE CASCADE,
    reporter_id UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,
    reason      TEXT,
    status      TEXT NOT NULL DEFAULT 'open',   -- open | dismissed | actioned
    resolved_by UUID REFERENCES ident.users(id) ON DELETE SET NULL,
    resolved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (post_id, reporter_id)
);
-- Moderation queue: open reports, oldest first.
CREATE INDEX feed_report_open_idx ON social.feed_report (status, created_at) WHERE status = 'open';
