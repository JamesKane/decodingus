-- Social layer orchestration — activate the dormant mig-0009 social.* schema for the
-- alpha/beta team↔tester communication layer. Three channels: tester→team support
-- threads, team→all announcements, and an open community feed. This is operator
-- communication (testers ↔ the DecodingUs team), NOT citizen↔citizen peer DMs — so
-- central plaintext storage is acceptable (the no-PII direction targets P2P bodies).
--
-- Testers are bridged into ident.users by DID (ident.users.did already exists +
-- du_db::auth::upsert_user_by_did); the "team" is a ROLE (Curator/Admin), not a
-- conversation participant, so any curator can triage/reply to a support thread.
--
-- ALTER (reuse), do not recreate: mig 0009 already defines social.{conversation,
-- message, feed_post, user_block, reputation_*}.

-- ── support threads: conversation gets triage state + per-side read marks ─────
ALTER TABLE social.conversation
    ADD COLUMN kind              TEXT NOT NULL DEFAULT 'SUPPORT',  -- SUPPORT | SYSTEM | DIRECT(future)
    ADD COLUMN status            TEXT NOT NULL DEFAULT 'open',     -- open | replied | closed
    ADD COLUMN team_last_read_at TIMESTAMPTZ,                      -- team inbox unread cutoff
    ADD COLUMN user_last_read_at TIMESTAMPTZ;                      -- tester/web-user unread cutoff

-- Team inbox: open/replied threads, most-recently-active first.
CREATE INDEX conversation_team_inbox_idx
    ON social.conversation (status, last_message_at DESC)
    WHERE deleted_at IS NULL;

-- ── message: denormalize sender side so Edge polling/render is FK-free ────────
-- from_team = the sender is a curator/admin replying on behalf of the team. Cheap
-- to read on the tester's poll without a role join.
ALTER TABLE social.message
    ADD COLUMN from_team BOOLEAN NOT NULL DEFAULT false;

-- ── feed: announcements (team broadcast) + open community posts + threading ───
ALTER TABLE social.feed_post
    ADD COLUMN kind           TEXT NOT NULL DEFAULT 'COMMUNITY',   -- ANNOUNCEMENT | COMMUNITY
    ADD COLUMN topic          TEXT,                                -- e.g. 'general', 'haplogroup:R-M269'
    ADD COLUMN parent_post_id UUID REFERENCES social.feed_post(id) ON DELETE CASCADE,
    ADD COLUMN pinned         BOOLEAN NOT NULL DEFAULT false;

-- Feed listing: by kind (announcements vs community), newest first, live only.
CREATE INDEX feed_post_kind_idx
    ON social.feed_post (kind, created_at DESC)
    WHERE deleted_at IS NULL;
-- Topic channels + reply threads.
CREATE INDEX feed_post_topic_idx
    ON social.feed_post (topic, created_at DESC)
    WHERE deleted_at IS NULL AND topic IS NOT NULL;
CREATE INDEX feed_post_parent_idx
    ON social.feed_post (parent_post_id, created_at)
    WHERE parent_post_id IS NOT NULL;
