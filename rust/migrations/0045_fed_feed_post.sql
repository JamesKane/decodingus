-- Federated public feed (Tier 3b). The AppView mirrors `com.decodingus.atmosphere.feed.post`
-- records published to members' PDSes (via Jetstream, same backbone as fed.coverage_summary
-- et al.) and merges them into the community feed read-only. PII-free: a DID + public post
-- text + pointers; no donor data. The central social.feed_post path (web posting) coexists.
--
-- Lexicon `com.decodingus.atmosphere.feed.post` (documents/atmosphere): { text, createdAt,
-- topic?, reply?: { root: strongRef, parent: strongRef } }.

CREATE TABLE fed.feed_post (
    did               TEXT NOT NULL,
    rkey              TEXT NOT NULL,
    at_uri            TEXT NOT NULL,
    cid               TEXT,
    text              TEXT NOT NULL,
    topic             TEXT,
    parent_uri        TEXT,            -- reply.parent.uri (a reply's immediate target)
    root_uri          TEXT,            -- reply.root.uri (thread root)
    record_created_at TIMESTAMPTZ,     -- the record's own createdAt
    time_us           BIGINT NOT NULL, -- Jetstream cursor of the producing event
    indexed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);
-- Recency feed (top-level posts first, replies fetched per-thread).
CREATE INDEX feed_post_fed_recent_idx ON fed.feed_post (record_created_at DESC NULLS LAST)
    WHERE parent_uri IS NULL;
-- Topic channels.
CREATE INDEX feed_post_fed_topic_idx ON fed.feed_post (topic, record_created_at DESC)
    WHERE topic IS NOT NULL;
