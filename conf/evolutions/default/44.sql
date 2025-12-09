-- !Ups

-- Support schema for contact/messaging system
CREATE SCHEMA support;

-- Contact messages from users (both authenticated and anonymous)
CREATE TABLE support.contact_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- For authenticated users
    user_id UUID REFERENCES public.users(id) ON DELETE SET NULL,

    -- For anonymous users (captured from form)
    sender_name VARCHAR(255),
    sender_email VARCHAR(255),

    -- Message content
    subject VARCHAR(500) NOT NULL,
    message TEXT NOT NULL,

    -- Status tracking
    status VARCHAR(50) NOT NULL DEFAULT 'new',  -- new, read, replied, closed

    -- Metadata
    ip_address_hash VARCHAR(64),
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Admin replies to contact messages
CREATE TABLE support.message_replies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES support.contact_messages(id) ON DELETE CASCADE,
    admin_user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE RESTRICT,
    reply_text TEXT NOT NULL,

    -- For anonymous users, track if email was sent
    email_sent BOOLEAN NOT NULL DEFAULT FALSE,
    email_sent_at TIMESTAMP,

    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_contact_messages_user_id ON support.contact_messages(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_contact_messages_status ON support.contact_messages(status);
CREATE INDEX idx_contact_messages_created_at ON support.contact_messages(created_at DESC);
CREATE INDEX idx_message_replies_message_id ON support.message_replies(message_id);

COMMENT ON SCHEMA support IS 'Support ticket and contact message system';
COMMENT ON TABLE support.contact_messages IS 'Contact form submissions from authenticated and anonymous users';
COMMENT ON TABLE support.message_replies IS 'Admin replies to contact messages';
COMMENT ON COLUMN support.contact_messages.status IS 'Message status: new, read, replied, closed';

-- !Downs

DROP TABLE support.message_replies;
DROP TABLE support.contact_messages;
DROP SCHEMA support;
