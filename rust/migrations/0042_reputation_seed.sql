-- Reputation engine (Tier 1a): seed the event-type catalog + a one-time-bonus guard.
-- Schema is mig 0009 (social.{reputation_event_type, reputation_event,
-- user_reputation_score}); this activates it. The social layer (mig 0041) already reads
-- user_reputation_score to gate feed posting — now events actually move the score.

-- ── seed event types ─────────────────────────────────────────────────────────
-- default_points_change is the standard award; reputation_event.actual_points_change may
-- override it (e.g. a vote toggle of -2). is_positive/is_system_generated are advisory.
INSERT INTO social.reputation_event_type (name, description, default_points_change, is_positive, is_system_generated)
VALUES
    ('ACCOUNT_VERIFIED',       'Identity verified via AT Protocol / email',           10, true,  true),
    ('NEW_USER_BONUS',         'Welcome bonus enabling basic interactions',            5, true,  true),
    ('LAB_OBSERVATION_ACCEPTED','Sequencer-lab observation verified by consensus',      5, true,  true),
    ('FEED_POST_UPVOTED',      'A community member upvoted the user''s post',           1, true,  false),
    ('FEED_POST_DOWNVOTED',    'A community member downvoted the user''s post',        -1, false, false),
    ('SPAM_REPORT_VALIDATED',  'Content confirmed as spam by a moderator',            -50, false, true),
    ('RECRUITMENT_ACCEPTED',   'User accepted a recruitment request (good faith)',      2, true,  false)
ON CONFLICT (name) DO NOTHING;

-- ── one-time system bonuses are awarded at most once per user ─────────────────
-- ACCOUNT_VERIFIED / NEW_USER_BONUS carry no related entity; this partial unique index
-- lets `record_once` use ON CONFLICT DO NOTHING. Per-post/per-vote events DO set
-- related_entity_id, so they are unconstrained (a user can be up/downvoted many times).
CREATE UNIQUE INDEX reputation_event_once_idx
    ON social.reputation_event (user_id, event_type_id)
    WHERE related_entity_id IS NULL AND source_user_id IS NULL;
