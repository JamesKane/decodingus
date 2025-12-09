-- !Ups

-- Track when authenticated users last viewed their message history
-- This allows us to show a badge for new replies since their last visit
ALTER TABLE support.contact_messages ADD COLUMN user_last_viewed_at TIMESTAMP;

COMMENT ON COLUMN support.contact_messages.user_last_viewed_at IS 'Timestamp when authenticated user last viewed this message thread';

-- !Downs

ALTER TABLE support.contact_messages DROP COLUMN user_last_viewed_at;
