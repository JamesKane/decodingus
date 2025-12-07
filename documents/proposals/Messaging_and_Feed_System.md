# Messaging & Feed System Design

## 1. Overview
This document outlines the design for a comprehensive messaging and social feed system for DecodingUs. The goal is to facilitate communication between:
1.  **Java Edge Applications (PDS Managers):** Automated or semi-automated agents running on user hardware (The Navigator Workbench) that need to coordinate with other nodes or contact researchers.
2.  **Researchers & Curators:** Who need to recruit participants or clarify data issues.
3.  **End Users (Citizens):** Who wish to coordinate genealogical research with genetic matches.
4.  **Community:** A general discussion feed (Bluesky-style) for broad topics.

This system will integrate with the existing **Reputation** system and introduce a **Foes/Blocking** mechanism to ensure safety and quality.

## 2. Architecture

While DecodingUs integrates with the decentralized AT Protocol (Atmosphere), privacy and immediate consistency for direct messaging (DM) suggest a **hybrid approach**:

*   **Public Feeds:** Can be decentralized. Posts can be stored as AT Protocol records (`com.decodingus.atmosphere.feed.post`) on the user's PDS and indexed by DecodingUs (AppView).
*   **Direct Messages (DMs) & System Alerts:** Stored centrally on DecodingUs (as an encrypted mailbox service) or routed directly between Edge Apps if online. For the MVP, a **centralized mailbox** on DecodingUs is recommended for reliability, allowing offline async delivery.

### 2.1. Actors
*   **Edge App (Navigator):** Authenticates via API Key/DID. Polling or WebSocket connection for new messages.
*   **Web User:** Accesses via DecodingUs portal.
*   **System:** Automated notifications (e.g., "New Match Found").

## 3. Database Schema Extensions

We will introduce new tables to the Postgres schema.

### 3.1. User Relationships (The "Foes" List)
Allows users to block or mute others.

```sql
CREATE TABLE user_blocks (
    blocker_did VARCHAR(255) NOT NULL,
    blocked_did VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    reason TEXT, -- Optional: "Spam", "Harassment", "Irrelevant"
    PRIMARY KEY (blocker_did, blocked_did)
);
-- Index for quick lookup during message delivery
CREATE INDEX idx_user_blocks_blocker ON user_blocks(blocker_did);
CREATE INDEX idx_user_blocks_blocked ON user_blocks(blocked_did);
```

### 3.2. Messaging (Conversations)
Supports 1:1 and Group chats.

```sql
CREATE TABLE conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(50) NOT NULL, -- 'DIRECT', 'GROUP', 'SYSTEM', 'RECRUITMENT'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE conversation_participants (
    conversation_id UUID REFERENCES conversations(id) ON DELETE CASCADE,
    user_did VARCHAR(255) NOT NULL,
    role VARCHAR(50) DEFAULT 'MEMBER', -- 'ADMIN', 'MEMBER'
    last_read_at TIMESTAMP,
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, user_did)
);

CREATE TABLE messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID REFERENCES conversations(id) ON DELETE CASCADE,
    sender_did VARCHAR(255) NOT NULL, -- User DID or 'SYSTEM'
    content TEXT NOT NULL,
    content_type VARCHAR(50) DEFAULT 'TEXT', -- 'TEXT', 'MARKDOWN', 'JSON_PAYLOAD'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_edited BOOLEAN DEFAULT FALSE
);
```

### 3.3. Public Feed (Microblogging)
Stores posts for the community feed.

```sql
CREATE TABLE feed_posts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_did VARCHAR(255) NOT NULL,
    content TEXT NOT NULL, -- 300 chars limit?
    parent_post_id UUID REFERENCES feed_posts(id), -- For replies
    root_post_id UUID REFERENCES feed_posts(id), -- Thread context
    topic VARCHAR(100), -- 'GENERAL', 'HAPLOGROUP_R', 'PROJECT_123'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    -- Reputation Snapshot at time of posting?
    author_reputation_score INT DEFAULT 0
);
```

## 4. Reputation Integration

The messaging system will heavily leverage the existing Reputation tables (`user_reputation_scores`).

1.  **Gated Access:**
    *   **Global Feed Posting:** Requires a minimum Reputation Score (e.g., > 10) to prevent spam from fresh accounts.
    *   **Cold DMing:** Requires a higher Reputation Score (e.g., > 50) to initiate contact with a stranger who isn't a genetic match.
    *   **Recruitment:** "Verified Researchers" (special role or high rep) can send bulk recruitment requests.

2.  **Reputation Actions:**
    *   **"Helpful" Vote:** Marking a forum post as helpful increases author's reputation.
    *   **"Spam" Report:** Confirmed spam reports decrease reputation.

## 5. Atmosphere Lexicon Extensions

To support the Public Feed in the decentralized "Atmosphere" (so it's not just a DecodingUs silo), we define new Lexicon records.

### `com.decodingus.atmosphere.feed.post`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.feed.post",
  "defs": {
    "main": {
      "type": "record",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["text", "createdAt"],
        "properties": {
          "text": { "type": "string", "maxLength": 3000 },
          "createdAt": { "type": "string", "format": "datetime" },
          "reply": {
            "type": "object",
            "properties": {
              "root": { "type": "link", "target": "com.decodingus.atmosphere.feed.post" },
              "parent": { "type": "link", "target": "com.decodingus.atmosphere.feed.post" }
            }
          },
          "topic": {
             "type": "string",
             "description": "Context tag, e.g., 'haplogroup:R-M269'"
          }
        }
      }
    }
  }
}
```

## 6. Java Edge Application Workflow

The Java Edge Application (managing the PDS) will act as an agent.

**Scenario: Researcher wants to recruit users with Haplogroup J-M172.**

1.  **Query:** Researcher uses DecodingUs "Cohort Builder" to find anonymous matches.
2.  **Campaign:** Researcher drafts a "Recruitment Message".
3.  **Delivery:** DecodingUs system creates a `RECRUITMENT` conversation with each target User DID.
4.  **Edge App Sync:**
    *   The User's Edge App polls `GET /api/atmosphere/messages?status=unread`.
    *   Edge App downloads the recruitment offer.
    *   Edge App notifies the user locally (System Tray / UI).
5.  **Response:** User accepts/declines locally. Edge App POSTs reply to `conversations/{id}/messages`.

## 7. Implementation Plan

1.  **Phase 1: Database & API**
    *   Create SQL migrations for `user_blocks`, `conversations`, `messages`, `feed_posts`.
    *   Implement `MessagingController` and `FeedController`.
2.  **Phase 2: Reputation Logic**
    *   Implement `ReputationGuard` service to check scores before allowing actions.
3.  **Phase 3: Frontend**
    *   Add "Inbox" and "Feed" tabs to the DecodingUs dashboard.
    *   Integrate "Block User" button on profiles.
