# --- !Ups
CREATE SCHEMA IF NOT EXISTS social;

-- 1. Move existing reputation tables to social schema
-- NOTE: We must drop constraints that reference these tables if they are not schema-qualified or if necessary,
-- but typically changing schema preserves data. Foreign keys might need adjustment if they are schema-bound.
-- In Postgres, moving a table to a new schema preserves its data and indexes.
-- However, we should be careful about the FKs from public.users.
-- The existing FKs in 6.sql were:
-- fk_reputation_events_user_id references public.users
-- fk_reputation_events_event_type_id references public.reputation_event_types
-- fk_reputation_events_source_user_id references public.users
-- fk_user_reputation_scores_user_id references public.users

ALTER TABLE public.reputation_event_types SET SCHEMA social;
ALTER TABLE public.reputation_events SET SCHEMA social;
ALTER TABLE public.user_reputation_scores SET SCHEMA social;

-- 2. Create new social tables

-- User Relationships (Foes/Blocks)
CREATE TABLE social.user_blocks (
    blocker_did VARCHAR(255) NOT NULL,
    blocked_did VARCHAR(255) NOT NULL,
    reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (blocker_did, blocked_did)
);

CREATE INDEX idx_user_blocks_blocker ON social.user_blocks(blocker_did);
CREATE INDEX idx_user_blocks_blocked ON social.user_blocks(blocked_did);

-- Conversations (Threads)
CREATE TABLE social.conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(50) NOT NULL, -- 'DIRECT', 'GROUP', 'SYSTEM', 'RECRUITMENT'
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Conversation Participants
CREATE TABLE social.conversation_participants (
    conversation_id UUID NOT NULL,
    user_did VARCHAR(255) NOT NULL,
    role VARCHAR(50) DEFAULT 'MEMBER', -- 'ADMIN', 'MEMBER'
    last_read_at TIMESTAMP,
    joined_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (conversation_id, user_did),
    CONSTRAINT fk_conversation_participants_conversation_id FOREIGN KEY (conversation_id) REFERENCES social.conversations(id) ON DELETE CASCADE
);

-- Messages
CREATE TABLE social.messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL,
    sender_did VARCHAR(255) NOT NULL, -- User DID or 'SYSTEM'
    content TEXT NOT NULL,
    content_type VARCHAR(50) DEFAULT 'TEXT', -- 'TEXT', 'MARKDOWN', 'JSON_PAYLOAD'
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    is_edited BOOLEAN DEFAULT FALSE,
    CONSTRAINT fk_messages_conversation_id FOREIGN KEY (conversation_id) REFERENCES social.conversations(id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_conversation_id ON social.messages(conversation_id);
CREATE INDEX idx_messages_sender_did ON social.messages(sender_did);

-- Feed Posts (Public/Community)
CREATE TABLE social.feed_posts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_did VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    parent_post_id UUID, -- For replies
    root_post_id UUID, -- Thread context
    topic VARCHAR(100), -- 'GENERAL', 'HAPLOGROUP_R', etc.
    author_reputation_score INT DEFAULT 0, -- Snapshot at time of posting
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_feed_posts_parent_id FOREIGN KEY (parent_post_id) REFERENCES social.feed_posts(id),
    CONSTRAINT fk_feed_posts_root_id FOREIGN KEY (root_post_id) REFERENCES social.feed_posts(id)
);

CREATE INDEX idx_feed_posts_author_did ON social.feed_posts(author_did);
CREATE INDEX idx_feed_posts_topic ON social.feed_posts(topic);
CREATE INDEX idx_feed_posts_created_at ON social.feed_posts(created_at);

-- 3. Seed initial reputation event types
INSERT INTO social.reputation_event_types (name, description, default_points_change, is_positive, is_system_generated) VALUES
('ACCOUNT_VERIFIED', 'Email and identity verification complete', 10, TRUE, TRUE),
('LAB_OBSERVATION_ACCEPTED', 'Submitted sequencer metadata verified by consensus', 5, TRUE, TRUE),
('FEED_POST_UPVOTED', 'Community member upvoted a post', 1, TRUE, FALSE),
('FEED_POST_DOWNVOTED', 'Community member downvoted a post', -1, FALSE, FALSE),
('SPAM_REPORT_VALIDATED', 'Content marked as spam by moderator or consensus', -50, FALSE, TRUE),
('RECRUITMENT_ACCEPTED', 'User accepted a recruitment request', 2, TRUE, FALSE),
('NEW_USER_BONUS', 'Welcome bonus for new users', 5, TRUE, TRUE)
ON CONFLICT (name) DO NOTHING;

# --- !Downs

DROP TABLE social.feed_posts;
DROP TABLE social.messages;
DROP TABLE social.conversation_participants;
DROP TABLE social.conversations;
DROP TABLE social.user_blocks;

-- Move tables back to public
ALTER TABLE social.user_reputation_scores SET SCHEMA public;
ALTER TABLE social.reputation_events SET SCHEMA public;
ALTER TABLE social.reputation_event_types SET SCHEMA public;

DROP SCHEMA social;
