# Reputation System Implementation Plan

## 1. Overview
The Reputation System is a core mechanism to ensure quality interactions within the DecodingUs social features (Messaging, Feed, Lab Discovery). It rewards positive contributions and limits spam/abuse by gating features based on a user's `UserReputationScore`.

This document outlines the implementation of the business logic, building upon the existing database schema (now in the `social` schema).

## 2. Core Concepts

### 2.1. Reputation Event Types (`social.reputation_event_types`)
These are the definitions of actions that affect reputation.
*   **System Generated:** Automated checks (e.g., "Account Verified", "Email Confirmed").
*   **User Generated:** Peer feedback (e.g., "Post Upvoted", "Spam Report").

#### Initial Seed Data
| Name | Description | Points | System? |
| :--- | :--- | :--- | :--- |
| `ACCOUNT_VERIFIED` | Email/Identity verification complete | +10 | Yes |
| `LAB_OBSERVATION_ACCEPTED` | Submitted sequencer metadata verified by consensus | +5 | Yes |
| `FEED_POST_UPVOTED` | Community member upvoted a post | +1 | No |
| `FEED_POST_DOWNVOTED` | Community member downvoted a post | -1 | No |
| `SPAM_REPORT_VALIDATED` | Content marked as spam by moderator/consensus | -50 | Yes |
| `RECRUITMENT_ACCEPTED` | User accepted a recruitment request (shows good faith) | +2 | No |
| `NEW_USER_BONUS` | Welcome bonus to allow basic interactions | +5 | Yes |

### 2.2. Reputation Events (`social.reputation_events`)
The immutable ledger of history.
*   Links `user_id` (Actor) and `source_user_id` (Voter/Judge).
*   Links `related_entity_type` (e.g., `FEED_POST`) and `related_entity_id`.

### 2.3. User Score (`social.user_reputation_scores`)
A cached aggregate of a user's total points.
*   Updated transactionally whenever a `ReputationEvent` is inserted.

## 3. Service Architecture

We will implement the following components in `app/services/social/` and `app/repositories/social/`.

### 3.1. Repositories
*   `ReputationEventTypeRepository`: Read-only mostly, for fetching definitions.
*   `ReputationEventRepository`: Insert-only log.
*   `UserReputationScoreRepository`: Upsert logic for the aggregate score.

### 3.2. `ReputationService`
The main entry point for business logic.

**Methods:**
*   `recordEvent(userId: UUID, eventTypeName: String, relatedEntity: Option[(String, UUID)], sourceUserId: Option[UUID]): Future[Int]`
    *   Look up `ReputationEventType`.
    *   Insert `ReputationEvent`.
    *   Update `UserReputationScore` (atomic increment).
    *   Return new score.
*   `getScore(userId: UUID): Future[Int]`
    *   Fetch from `user_reputation_scores` or calculate if missing.

### 3.3. `ReputationGuard` (Trait/Service)
A helper to check permissions.

**Methods:**
*   `canPostToFeed(userId: UUID): Future[Boolean]` (Requires Score > 5)
*   `canInitiateDM(userId: UUID): Future[Boolean]` (Requires Score > 20)
*   `canCreateGroup(userId: UUID): Future[Boolean]` (Requires Score > 50)

## 4. Integration Points

### 4.1. Authentication / Onboarding
*   **Trigger:** When a user verifies their email.
*   **Action:** `reputationService.recordEvent(user.id, "ACCOUNT_VERIFIED")`
*   **Action:** `reputationService.recordEvent(user.id, "NEW_USER_BONUS")`

### 4.2. Social Feed
*   **Trigger:** User attempts to create a post.
*   **Check:** `reputationGuard.canPostToFeed(user.id)`
*   **Trigger:** User clicks "Upvote".
*   **Action:** `reputationService.recordEvent(postAuthorId, "FEED_POST_UPVOTED", Some("FEED_POST", postId), Some(voterId))`

### 4.3. Messaging
*   **Trigger:** User sends a DM to a stranger (no prior interaction/match).
*   **Check:** `reputationGuard.canInitiateDM(user.id)`

## 5. Implementation Steps

1.  **Seed Data:** Create a new Evolution (or append to #26) to insert the initial `reputation_event_types`.
2.  **Repositories:** Implement the Slick repositories.
3.  **Service:** Implement `ReputationService` with transactional score updates.
4.  **Guard:** Implement `ReputationGuard`.
5.  **Controllers:** Update `AuthController` (to award initial points) and `FeedController` (to check limits).
