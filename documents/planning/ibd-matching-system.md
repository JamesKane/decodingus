# IBD Matching and Relationship Discovery System

## Executive Summary

This document outlines a comprehensive system enabling Genetic Genealogists to discover and confirm IBD (Identity By Descent) relationships with other participating users. The system leverages the AT Protocol for decentralized consent management, coordinates with the Java-based Edge Computing Application for secure data exchange, and builds upon existing schema infrastructure (`ibd_discovery_index`, `ibd_pds_attestation`).

---

## User Story

> As a **Genetic Genealogist**
> I need to **be able to perform IBD relationship comparisons with participating Genetic Genealogists**
> So that I can **discover potential relatives and build my family tree**

---

## Problem Statement

Genetic genealogists need to:

1. **Discover potential matches** - Find other users who may share DNA segments indicating common ancestry
2. **Prioritize comparisons** - Focus on matches likely to be meaningful (shared contacts, similar population breakdowns)
3. **Request consent** - Ask potential matches for permission to perform detailed IBD analysis
4. **Exchange data securely** - Share encrypted genetic data for comparison without exposing raw sequences
5. **Record confirmed relationships** - Persist validated matches for future discovery

### Current Gap

The existing system has:
- Database schema for IBD discovery (`ibd_discovery_index`, `ibd_pds_attestation`) - **not utilized**
- Ancestry analysis infrastructure (`ancestry_analysis`, `population`) - **not connected to matching**
- User/PDS infrastructure - **no consent workflow**
- No match list concept
- No Lexicon definitions for match requests or population breakdowns

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           User A's Environment                               │
├─────────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐       │
│  │   Edge App      │────▶│  User A's PDS   │────▶│  AT Protocol    │       │
│  │   (Java)        │     │                 │     │  Network        │       │
│  │                 │     │ • Biosample     │     │                 │       │
│  │ • IBD Analysis  │     │ • Match List    │     │ • Firehose      │       │
│  │ • Encryption    │     │ • Match Requests│     │ • XRPC          │       │
│  │ • Key Exchange  │     │ • Population    │     │                 │       │
│  └────────┬────────┘     └─────────────────┘     └────────┬────────┘       │
│           │                                                │                │
└───────────┼────────────────────────────────────────────────┼────────────────┘
            │                                                │
            │         Encrypted P2P Channel                  │
            │         (Edge App ↔ Edge App)                  │
            ▼                                                ▼
┌───────────┼────────────────────────────────────────────────┼────────────────┐
│           │                                                │                │
│  ┌────────┴────────┐     ┌─────────────────┐     ┌────────┴────────┐       │
│  │   Edge App      │◀────│  User B's PDS   │◀────│  AT Protocol    │       │
│  │   (Java)        │     │                 │     │  Network        │       │
│  │                 │     │ • Biosample     │     │                 │       │
│  │ • IBD Analysis  │     │ • Match List    │     │ • Firehose      │       │
│  │ • Encryption    │     │ • Match Requests│     │ • XRPC          │       │
│  │ • Key Exchange  │     │ • Population    │     │                 │       │
│  └─────────────────┘     └─────────────────┘     └─────────────────┘       │
│                           User B's Environment                               │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          DecodingUs AppView                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│  • Subscribes to Firehose for match confirmations                           │
│  • Indexes confirmed matches in ibd_discovery_index                         │
│  • Aggregates population data for discovery suggestions                     │
│  • Provides match discovery API                                             │
│  • Tracks attestation consensus                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Lexicon Extensions

### Namespace: `com.decodingus.atmosphere`

#### 1. Match List Record (`com.decodingus.atmosphere.matchList`)

Stores a user's confirmed genetic matches in their PDS.

**NSID:** `com.decodingus.atmosphere.matchList`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.matchList",
  "defs": {
    "main": {
      "type": "record",
      "description": "A user's list of confirmed genetic matches stored in their PDS.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["ownerDid", "matches"],
        "properties": {
          "ownerDid": {
            "type": "string",
            "description": "The DID of the user who owns this match list."
          },
          "matches": {
            "type": "array",
            "description": "List of confirmed matches.",
            "items": {
              "type": "ref",
              "ref": "#confirmedMatch"
            }
          },
          "lastUpdated": {
            "type": "string",
            "format": "datetime"
          }
        }
      }
    },
    "confirmedMatch": {
      "type": "object",
      "description": "A confirmed genetic match with another user.",
      "required": ["matchedUserDid", "matchedBiosampleUri", "relationshipType", "confirmedAt"],
      "properties": {
        "matchedUserDid": {
          "type": "string",
          "description": "DID of the matched user."
        },
        "matchedBiosampleUri": {
          "type": "string",
          "description": "AT URI of the matched user's biosample record."
        },
        "relationshipType": {
          "type": "string",
          "description": "Type of genetic relationship.",
          "knownValues": ["AUTOSOMAL", "Y_CHROMOSOME", "MT_DNA", "X_CHROMOSOME"]
        },
        "totalSharedCm": {
          "type": "float",
          "description": "Total shared centimorgans (autosomal)."
        },
        "numSharedSegments": {
          "type": "integer",
          "description": "Number of shared DNA segments."
        },
        "largestSegmentCm": {
          "type": "float",
          "description": "Size of the largest shared segment in cM."
        },
        "estimatedRelationship": {
          "type": "string",
          "description": "Estimated relationship (e.g., '2nd Cousin', '3rd-4th Cousin')."
        },
        "sharedAncestors": {
          "type": "array",
          "description": "Known shared ancestors (if any).",
          "items": { "type": "string" }
        },
        "confirmedAt": {
          "type": "string",
          "format": "datetime"
        },
        "matchSignature": {
          "type": "string",
          "description": "Cryptographic signature confirming both parties agreed to this match."
        },
        "notes": {
          "type": "string",
          "description": "User notes about this match."
        }
      }
    }
  }
}
```

---

#### 2. Match Request Record (`com.decodingus.atmosphere.matchRequest`)

A request from one user to another for IBD comparison (similar to Bluesky DMs).

**NSID:** `com.decodingus.atmosphere.matchRequest`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.matchRequest",
  "defs": {
    "main": {
      "type": "record",
      "description": "A request to perform IBD comparison with another user.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["requesterDid", "requesterBiosampleUri", "targetDid", "requestType", "status", "createdAt"],
        "properties": {
          "requesterDid": {
            "type": "string",
            "description": "DID of the user initiating the match request."
          },
          "requesterBiosampleUri": {
            "type": "string",
            "description": "AT URI of the requester's biosample."
          },
          "targetDid": {
            "type": "string",
            "description": "DID of the user being requested for a match."
          },
          "targetBiosampleUri": {
            "type": "string",
            "description": "AT URI of the target's biosample (if known)."
          },
          "requestType": {
            "type": "string",
            "description": "Type of comparison requested.",
            "knownValues": ["AUTOSOMAL", "Y_CHROMOSOME", "MT_DNA", "FULL"]
          },
          "status": {
            "type": "string",
            "description": "Current status of the request.",
            "knownValues": ["PENDING", "ACCEPTED", "REJECTED", "EXPIRED", "COMPLETED", "CANCELLED"]
          },
          "discoveryReason": {
            "type": "ref",
            "ref": "#discoveryReason",
            "description": "Why this match was suggested."
          },
          "message": {
            "type": "string",
            "description": "Optional message from requester explaining interest."
          },
          "createdAt": {
            "type": "string",
            "format": "datetime"
          },
          "expiresAt": {
            "type": "string",
            "format": "datetime",
            "description": "Request expiration (default 30 days)."
          },
          "respondedAt": {
            "type": "string",
            "format": "datetime"
          },
          "responseMessage": {
            "type": "string",
            "description": "Response message from target user."
          }
        }
      }
    },
    "discoveryReason": {
      "type": "object",
      "description": "Reason this match was suggested.",
      "properties": {
        "reasonType": {
          "type": "string",
          "knownValues": ["SHARED_MATCH", "POPULATION_OVERLAP", "HAPLOGROUP_MATCH", "MANUAL"]
        },
        "sharedMatchDids": {
          "type": "array",
          "description": "DIDs of users both parties match with.",
          "items": { "type": "string" }
        },
        "populationOverlapScore": {
          "type": "float",
          "description": "Score indicating population breakdown similarity (0-1)."
        },
        "sharedHaplogroup": {
          "type": "string",
          "description": "Shared terminal haplogroup (Y-DNA or mtDNA)."
        }
      }
    }
  }
}
```

---

#### 3. Population Breakdown Record (`com.decodingus.atmosphere.populationBreakdown`)

Ancestry composition data stored in the user's PDS.

**NSID:** `com.decodingus.atmosphere.populationBreakdown`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.populationBreakdown",
  "defs": {
    "main": {
      "type": "record",
      "description": "Ancestry population breakdown for a biosample.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["biosampleUri", "analysisMethod", "populations", "analyzedAt"],
        "properties": {
          "biosampleUri": {
            "type": "string",
            "description": "AT URI of the biosample this breakdown belongs to."
          },
          "analysisMethod": {
            "type": "string",
            "description": "Method/algorithm used for analysis (e.g., 'ADMIXTURE_K12', 'PCA_REFERENCE')."
          },
          "referencePanel": {
            "type": "string",
            "description": "Reference panel used (e.g., 'Human Origins', '1000 Genomes')."
          },
          "populations": {
            "type": "array",
            "description": "Population percentages.",
            "items": {
              "type": "ref",
              "ref": "#populationComponent"
            }
          },
          "analyzedAt": {
            "type": "string",
            "format": "datetime"
          },
          "confidenceLevel": {
            "type": "string",
            "description": "Overall confidence in the breakdown.",
            "knownValues": ["HIGH", "MEDIUM", "LOW"]
          }
        }
      }
    },
    "populationComponent": {
      "type": "object",
      "description": "A single population component in the breakdown.",
      "required": ["populationName", "percentage"],
      "properties": {
        "populationName": {
          "type": "string",
          "description": "Name of the population (e.g., 'Northern European', 'East Asian')."
        },
        "populationCode": {
          "type": "string",
          "description": "Standardized code for the population."
        },
        "percentage": {
          "type": "float",
          "description": "Percentage of ancestry from this population (0-100)."
        },
        "confidenceInterval": {
          "type": "ref",
          "ref": "#confidenceInterval"
        },
        "parentPopulation": {
          "type": "string",
          "description": "Parent population category for hierarchical breakdowns."
        }
      }
    },
    "confidenceInterval": {
      "type": "object",
      "properties": {
        "lower": { "type": "float" },
        "upper": { "type": "float" }
      }
    }
  }
}
```

---

#### 4. Match Consent Vote Record (`com.decodingus.atmosphere.matchConsent`)

Records a user's consent decision for a match request. Both users must have matching consent records for a match to be confirmed.

**NSID:** `com.decodingus.atmosphere.matchConsent`

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.matchConsent",
  "defs": {
    "main": {
      "type": "record",
      "description": "A user's consent vote for a match comparison.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["matchRequestUri", "voterDid", "vote", "votedAt"],
        "properties": {
          "matchRequestUri": {
            "type": "string",
            "description": "AT URI of the match request this consent applies to."
          },
          "voterDid": {
            "type": "string",
            "description": "DID of the user casting this vote."
          },
          "voterBiosampleUri": {
            "type": "string",
            "description": "AT URI of the voter's biosample."
          },
          "vote": {
            "type": "string",
            "description": "The consent decision.",
            "knownValues": ["ACCEPT", "REJECT", "DEFER"]
          },
          "votedAt": {
            "type": "string",
            "format": "datetime"
          },
          "expiresAt": {
            "type": "string",
            "format": "datetime",
            "description": "When this consent expires (requires renewal)."
          },
          "scope": {
            "type": "array",
            "description": "What data can be shared.",
            "items": {
              "type": "string",
              "knownValues": ["SEGMENT_POSITIONS", "SHARED_CM_TOTAL", "HAPLOGROUP", "POPULATION_OVERLAP"]
            }
          },
          "signature": {
            "type": "string",
            "description": "Cryptographic signature of the consent."
          }
        }
      }
    }
  }
}
```

---

## Match Discovery Workflow

### Phase 1: Discovery Suggestions

Users can discover potential matches through several mechanisms:

#### 1a. Shared Match Discovery

```
User A has matches: [M1, M2, M3, M4]
User B has matches: [M2, M3, M5, M6]

Shared matches: [M2, M3]

If |shared| >= threshold (configurable, default 2):
  → Suggest A and B as potential matches
  → Higher shared count = higher suggestion priority
```

#### 1b. Population Overlap Discovery

```
User A population: {Northern European: 45%, British Isles: 30%, Germanic: 15%, ...}
User B population: {Northern European: 50%, British Isles: 25%, Scandinavian: 15%, ...}

Overlap Score = Σ min(A[pop], B[pop]) for all populations
             = min(45,50) + min(30,25) + ...
             = 45 + 25 + ...

If overlapScore >= threshold (configurable, default 60%):
  → Suggest A and B as potential matches
```

#### 1c. Haplogroup Match Discovery

```
User A: Y-DNA R-M269, mtDNA H1a
User B: Y-DNA R-M269, mtDNA J1c

If A.yHaplogroup == B.yHaplogroup (for males):
  → Suggest Y-DNA comparison
  → Priority based on terminal depth match

If A.mtHaplogroup == B.mtHaplogroup:
  → Suggest mtDNA comparison
```

### Phase 2: Match Request Flow

```
┌─────────────┐                           ┌─────────────┐
│   User A    │                           │   User B    │
│  (Requester)│                           │  (Target)   │
└──────┬──────┘                           └──────┬──────┘
       │                                         │
       │  1. Create matchRequest in A's PDS      │
       │────────────────────────────────────────▶│
       │     (status: PENDING)                   │
       │                                         │
       │  2. AT Protocol delivers to B's PDS    │
       │     (B sees pending request)            │
       │                                         │
       │                    3. B reviews request │
       │                    (sees discovery reason)
       │                                         │
       │  4. B creates matchConsent in B's PDS  │
       │◀────────────────────────────────────────│
       │     (vote: ACCEPT)                      │
       │                                         │
       │  5. A creates matchConsent in A's PDS  │
       │────────────────────────────────────────▶│
       │     (vote: ACCEPT)                      │
       │                                         │
       │  ═══════════════════════════════════════│
       │  Both consents present = Ready for IBD  │
       │  ═══════════════════════════════════════│
       │                                         │
```

### Phase 3: IBD Analysis (Edge App Coordination)

Once both users consent, the Edge Apps coordinate the actual analysis:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    EDGE APP COORDINATION PROTOCOL                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. KEY EXCHANGE                                                             │
│     ┌──────────────┐                          ┌──────────────┐              │
│     │ Edge App A   │───── ECDH Key Exchange ──│ Edge App B   │              │
│     │              │      (via AT Protocol)   │              │              │
│     └──────────────┘                          └──────────────┘              │
│                                                                              │
│  2. ENCRYPTED DATA EXCHANGE                                                  │
│     • App A encrypts variant positions with shared key                       │
│     • App A sends encrypted payload to App B (P2P or relay)                 │
│     • App B decrypts and performs local comparison                          │
│     • App B encrypts results and sends back                                 │
│                                                                              │
│  3. RESULT VERIFICATION                                                      │
│     • Both apps independently calculate shared segments                      │
│     • Results are hashed and compared                                       │
│     • Matching hashes confirm valid analysis                                │
│                                                                              │
│  4. ATTESTATION                                                              │
│     • Both apps sign the match result                                        │
│     • Attestations written to respective PDS                                │
│     • DecodingUs indexes confirmed match                                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Phase 4: Match Confirmation and Indexing

```
┌─────────────┐     ┌─────────────┐     ┌─────────────────────┐
│   User A    │     │   User B    │     │  DecodingUs AppView │
│    PDS      │     │    PDS      │     │                     │
└──────┬──────┘     └──────┬──────┘     └──────────┬──────────┘
       │                   │                       │
       │  attestation A    │  attestation B        │
       │───────────────────┼───────────────────────▶
       │                   │                       │
       │                   │     Firehose events   │
       │                   │                       │
       │                   │   ┌───────────────────┤
       │                   │   │ Verify signatures │
       │                   │   │ Match attestations│
       │                   │   │ Index in DB       │
       │                   │   └───────────────────┤
       │                   │                       │
       │                   │   ibd_discovery_index │
       │                   │   ibd_pds_attestation │
       │                   │                       │
```

---

## Database Schema Extensions

### New Tables (in `public` schema, or consider `matching` schema)

```sql
-- Evolution XX: IBD Matching System Extensions

-- ============================================================================
-- PART 1: Match Discovery Tables
-- ============================================================================

-- Match suggestions generated by the discovery engine
CREATE TABLE match_suggestion (
    id BIGSERIAL PRIMARY KEY,
    suggester_sample_guid UUID NOT NULL,
    suggested_sample_guid UUID NOT NULL,
    suggestion_type VARCHAR(50) NOT NULL
        CHECK (suggestion_type IN ('SHARED_MATCH', 'POPULATION_OVERLAP', 'HAPLOGROUP_MATCH')),
    score DOUBLE PRECISION NOT NULL,
    metadata JSONB,  -- Stores reason details (shared match DIDs, overlap score, etc.)
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISMISSED', 'CONVERTED', 'EXPIRED')),
    UNIQUE(suggester_sample_guid, suggested_sample_guid, suggestion_type)
);

CREATE INDEX idx_ms_suggester ON match_suggestion(suggester_sample_guid);
CREATE INDEX idx_ms_suggested ON match_suggestion(suggested_sample_guid);
CREATE INDEX idx_ms_type ON match_suggestion(suggestion_type);
CREATE INDEX idx_ms_score ON match_suggestion(score DESC);

-- ============================================================================
-- PART 2: Population Overlap Caching
-- ============================================================================

-- Cached population breakdowns for efficient overlap calculation
CREATE TABLE population_breakdown_cache (
    id BIGSERIAL PRIMARY KEY,
    sample_guid UUID NOT NULL UNIQUE,
    citizen_did VARCHAR(255),
    analysis_method VARCHAR(100) NOT NULL,
    breakdown JSONB NOT NULL,  -- {populationCode: percentage, ...}
    breakdown_hash VARCHAR(64) NOT NULL,  -- For change detection
    source_at_uri VARCHAR(500),
    cached_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP
);

CREATE INDEX idx_pbc_sample ON population_breakdown_cache(sample_guid);
CREATE INDEX idx_pbc_did ON population_breakdown_cache(citizen_did);
CREATE INDEX idx_pbc_method ON population_breakdown_cache(analysis_method);

-- Pre-computed population overlap scores for discovery
CREATE TABLE population_overlap_score (
    id BIGSERIAL PRIMARY KEY,
    sample_guid_1 UUID NOT NULL,
    sample_guid_2 UUID NOT NULL,
    overlap_score DOUBLE PRECISION NOT NULL,
    analysis_method VARCHAR(100) NOT NULL,
    computed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(LEAST(sample_guid_1, sample_guid_2), GREATEST(sample_guid_1, sample_guid_2), analysis_method)
);

CREATE INDEX idx_pos_sample1 ON population_overlap_score(sample_guid_1);
CREATE INDEX idx_pos_sample2 ON population_overlap_score(sample_guid_2);
CREATE INDEX idx_pos_score ON population_overlap_score(overlap_score DESC);

-- ============================================================================
-- PART 3: Match Request Tracking
-- ============================================================================

-- Local tracking of match requests (supplements PDS records)
CREATE TABLE match_request_tracking (
    id BIGSERIAL PRIMARY KEY,
    request_at_uri VARCHAR(500) NOT NULL UNIQUE,
    requester_did VARCHAR(255) NOT NULL,
    requester_sample_guid UUID NOT NULL,
    target_did VARCHAR(255) NOT NULL,
    target_sample_guid UUID,
    request_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    discovery_reason JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_mrt_requester ON match_request_tracking(requester_did);
CREATE INDEX idx_mrt_target ON match_request_tracking(target_did);
CREATE INDEX idx_mrt_status ON match_request_tracking(status);

-- Consent votes (local index of PDS consent records)
CREATE TABLE match_consent_tracking (
    id BIGSERIAL PRIMARY KEY,
    consent_at_uri VARCHAR(500) NOT NULL UNIQUE,
    match_request_at_uri VARCHAR(500) NOT NULL,
    voter_did VARCHAR(255) NOT NULL,
    voter_sample_guid UUID NOT NULL,
    vote VARCHAR(20) NOT NULL CHECK (vote IN ('ACCEPT', 'REJECT', 'DEFER')),
    scope JSONB,
    signature TEXT NOT NULL,
    voted_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    UNIQUE(match_request_at_uri, voter_did)
);

CREATE INDEX idx_mct_request ON match_consent_tracking(match_request_at_uri);
CREATE INDEX idx_mct_voter ON match_consent_tracking(voter_did);

-- ============================================================================
-- PART 4: Extend existing IBD tables
-- ============================================================================

-- Add match request reference to ibd_discovery_index
ALTER TABLE ibd_discovery_index
    ADD COLUMN match_request_at_uri VARCHAR(500),
    ADD COLUMN requester_did VARCHAR(255),
    ADD COLUMN target_did VARCHAR(255);

CREATE INDEX idx_ibd_request_uri ON ibd_discovery_index(match_request_at_uri);
```

---

## Service Layer

### 1. MatchDiscoveryService

Generates match suggestions based on various criteria.

```scala
trait MatchDiscoveryService {
  /**
   * Find potential matches for a user based on shared matches.
   */
  def findSharedMatchSuggestions(
    sampleGuid: UUID,
    minSharedMatches: Int = 2
  ): Future[Seq[MatchSuggestion]]

  /**
   * Find potential matches based on population overlap.
   */
  def findPopulationOverlapSuggestions(
    sampleGuid: UUID,
    minOverlapScore: Double = 0.6
  ): Future[Seq[MatchSuggestion]]

  /**
   * Find potential matches based on shared haplogroups.
   */
  def findHaplogroupMatchSuggestions(
    sampleGuid: UUID,
    haplogroupType: HaplogroupType
  ): Future[Seq[MatchSuggestion]]

  /**
   * Get all suggestions for a user, ranked by score.
   */
  def getSuggestionsForUser(
    userDid: String,
    limit: Int = 50
  ): Future[Seq[RankedMatchSuggestion]]

  /**
   * Dismiss a suggestion (user not interested).
   */
  def dismissSuggestion(suggestionId: Long, userDid: String): Future[Boolean]
}
```

### 2. MatchRequestService

Manages match request lifecycle.

```scala
trait MatchRequestService {
  /**
   * Create a new match request.
   * Writes to requester's PDS and tracks locally.
   */
  def createMatchRequest(
    requesterDid: String,
    requesterBiosampleUri: String,
    targetDid: String,
    requestType: MatchRequestType,
    discoveryReason: Option[DiscoveryReason],
    message: Option[String]
  ): Future[MatchRequest]

  /**
   * Get pending requests for a user (as target).
   */
  def getPendingRequestsForUser(targetDid: String): Future[Seq[MatchRequest]]

  /**
   * Get requests initiated by a user.
   */
  def getRequestsByUser(requesterDid: String): Future[Seq[MatchRequest]]

  /**
   * Record a consent vote for a request.
   */
  def recordConsent(
    matchRequestUri: String,
    voterDid: String,
    voterBiosampleUri: String,
    vote: ConsentVote,
    scope: Seq[ConsentScope]
  ): Future[MatchConsent]

  /**
   * Check if both parties have consented.
   */
  def checkMutualConsent(matchRequestUri: String): Future[Option[MutualConsent]]

  /**
   * Cancel a pending request.
   */
  def cancelRequest(requestUri: String, requesterDid: String): Future[Boolean]
}
```

### 3. PopulationAnalysisService

Manages population breakdown data and overlap calculations.

```scala
trait PopulationAnalysisService {
  /**
   * Cache a population breakdown from PDS.
   */
  def cachePopulationBreakdown(
    sampleGuid: UUID,
    citizenDid: String,
    breakdown: PopulationBreakdown
  ): Future[Unit]

  /**
   * Calculate overlap score between two samples.
   */
  def calculateOverlapScore(
    sampleGuid1: UUID,
    sampleGuid2: UUID
  ): Future[Double]

  /**
   * Batch compute overlap scores for a sample against all others.
   */
  def computeOverlapScoresForSample(sampleGuid: UUID): Future[Int]

  /**
   * Get population breakdown for a sample.
   */
  def getBreakdown(sampleGuid: UUID): Future[Option[PopulationBreakdown]]
}
```

### 4. IbdMatchingService

Handles IBD match indexing and querying. **Coordinates with Edge App for actual analysis.**

```scala
trait IbdMatchingService {
  /**
   * Record a confirmed IBD match from Edge App attestations.
   */
  def recordConfirmedMatch(
    sampleGuid1: UUID,
    sampleGuid2: UUID,
    matchDetails: IbdMatchDetails,
    attestation1: IbdAttestation,
    attestation2: IbdAttestation
  ): Future[IbdDiscoveryIndex]

  /**
   * Get all matches for a sample.
   */
  def getMatchesForSample(sampleGuid: UUID): Future[Seq[IbdMatch]]

  /**
   * Get match details between two samples.
   */
  def getMatchBetween(sampleGuid1: UUID, sampleGuid2: UUID): Future[Option[IbdMatch]]

  /**
   * Verify attestation signatures.
   */
  def verifyAttestations(
    attestation1: IbdAttestation,
    attestation2: IbdAttestation,
    matchHash: String
  ): Future[Boolean]

  /**
   * Update consensus status based on attestations.
   */
  def updateConsensusStatus(indexId: Long): Future[ConsensusStatus]
}
```

---

## Edge App Coordination Points

### Interface Contract: DecodingUs ↔ Edge App

The Edge App (Java) must implement these coordination points:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    EDGE APP INTERFACE CONTRACT                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. NOTIFICATION CHANNEL                                                     │
│     ────────────────────                                                     │
│     DecodingUs → Edge App: Notify of mutual consent                         │
│                                                                              │
│     Endpoint: POST /api/edge/v1/match-ready                                 │
│     Payload: {                                                               │
│       matchRequestUri: String,                                               │
│       partnerDid: String,                                                    │
│       partnerPdsUrl: String,                                                 │
│       requestType: "AUTOSOMAL" | "Y_CHROMOSOME" | "MT_DNA" | "FULL",        │
│       consentScope: ["SEGMENT_POSITIONS", "SHARED_CM_TOTAL", ...]           │
│     }                                                                        │
│                                                                              │
│  2. KEY EXCHANGE PROTOCOL                                                    │
│     ─────────────────────                                                    │
│     Edge App ↔ Edge App: ECDH key agreement                                 │
│                                                                              │
│     • Use AT Protocol for key exchange messages                             │
│     • Lexicon: com.decodingus.edge.keyExchange                              │
│     • Keys rotated per comparison session                                   │
│                                                                              │
│  3. DATA EXCHANGE FORMAT                                                     │
│     ─────────────────────                                                    │
│     Edge App A → Edge App B: Encrypted variant data                         │
│                                                                              │
│     Format: {                                                                │
│       sessionId: UUID,                                                       │
│       encryptedPayload: Base64,  // AES-256-GCM encrypted                   │
│       iv: Base64,                                                            │
│       authTag: Base64,                                                       │
│       dataType: "VARIANT_POSITIONS" | "SEGMENT_BOUNDARIES"                  │
│     }                                                                        │
│                                                                              │
│  4. RESULT ATTESTATION                                                       │
│     ──────────────────                                                       │
│     Edge App → DecodingUs: Submit match results                             │
│                                                                              │
│     Endpoint: POST /api/v1/ibd/attestation                                  │
│     Payload: {                                                               │
│       matchRequestUri: String,                                               │
│       attestingDid: String,                                                  │
│       attestingSampleGuid: UUID,                                             │
│       matchSummary: {                                                        │
│         totalSharedCm: Double,                                               │
│         numSegments: Int,                                                    │
│         largestSegmentCm: Double,                                            │
│         regionType: String                                                   │
│       },                                                                     │
│       matchSummaryHash: String,  // SHA-256 of canonical summary            │
│       signature: String,         // Ed25519 signature with PDS key          │
│       partnerSummaryHash: String // Hash received from partner              │
│     }                                                                        │
│                                                                              │
│  5. SECURITY REQUIREMENTS                                                    │
│     ─────────────────────                                                    │
│     • All data encrypted in transit (TLS 1.3+)                              │
│     • All data encrypted at rest on Edge App                                │
│     • Variant data never stored on DecodingUs servers                       │
│     • Only match summaries (cM, segments) indexed                           │
│     • Keys derived from PDS signing keys (verifiable)                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Edge App Responsibilities

| Responsibility | Description |
|---------------|-------------|
| **Variant Storage** | Securely store user's variant calls locally |
| **Key Management** | Generate/rotate session keys for P2P exchange |
| **IBD Algorithm** | Implement IBD segment detection algorithm |
| **Encryption** | Encrypt/decrypt variant data for exchange |
| **P2P Communication** | Establish direct connection with partner Edge App |
| **Result Signing** | Sign match results with user's PDS key |
| **UI/UX** | Present match requests, manage consent workflow |

### DecodingUs Responsibilities

| Responsibility | Description |
|---------------|-------------|
| **Discovery Engine** | Generate match suggestions |
| **Request Routing** | Track match requests across PDS |
| **Consent Verification** | Verify mutual consent before triggering Edge Apps |
| **Attestation Indexing** | Index confirmed matches in `ibd_discovery_index` |
| **Match Querying** | Provide API for match list queries |
| **Population Caching** | Cache population data for overlap calculations |

---

## API Endpoints

### Discovery API

```
# Match Suggestions
GET  /api/v1/discovery/suggestions
     ?type={SHARED_MATCH|POPULATION_OVERLAP|HAPLOGROUP_MATCH}
     &limit={int}
     → Seq[RankedMatchSuggestion]

POST /api/v1/discovery/suggestions/{id}/dismiss
     → { success: Boolean }

# Population Analysis
GET  /api/v1/discovery/population/{sampleGuid}
     → PopulationBreakdown

GET  /api/v1/discovery/population/overlap/{sampleGuid1}/{sampleGuid2}
     → { overlapScore: Double }
```

### Match Request API

```
# Match Requests
POST /api/v1/matches/request
     Body: CreateMatchRequest
     → MatchRequest

GET  /api/v1/matches/requests/pending
     → Seq[MatchRequest]

GET  /api/v1/matches/requests/sent
     → Seq[MatchRequest]

POST /api/v1/matches/requests/{uri}/cancel
     → { success: Boolean }

# Consent
POST /api/v1/matches/consent
     Body: CreateMatchConsent
     → MatchConsent

GET  /api/v1/matches/consent/status/{requestUri}
     → ConsentStatus
```

### IBD Results API

```
# Confirmed Matches
GET  /api/v1/matches/confirmed
     ?regionType={AUTOSOMAL|Y_CHROMOSOME|MT_DNA}
     &minCm={double}
     → Seq[IbdMatch]

GET  /api/v1/matches/confirmed/{sampleGuid}
     → Seq[IbdMatch]

# Attestation (called by Edge App)
POST /api/v1/ibd/attestation
     Body: IbdAttestation
     → { indexed: Boolean, consensusStatus: String }
```

---

## Tapir Endpoint Definitions

```scala
object MatchDiscoveryEndpoints {

  val getSuggestions: Endpoint[String, SuggestionQuery, ApiError, Seq[RankedMatchSuggestion], Any] =
    endpoint.get
      .securityIn(auth.bearer[String]())
      .in("api" / "v1" / "discovery" / "suggestions")
      .in(query[Option[String]]("type"))
      .in(query[Int]("limit").default(50))
      .out(jsonBody[Seq[RankedMatchSuggestion]])
      .errorOut(jsonBody[ApiError])

  val getPopulationOverlap: Endpoint[String, (UUID, UUID), ApiError, OverlapResult, Any] =
    endpoint.get
      .securityIn(auth.bearer[String]())
      .in("api" / "v1" / "discovery" / "population" / "overlap")
      .in(path[UUID]("sampleGuid1") / path[UUID]("sampleGuid2"))
      .out(jsonBody[OverlapResult])
      .errorOut(jsonBody[ApiError])
}

object MatchRequestEndpoints {

  val createRequest: Endpoint[String, CreateMatchRequest, ApiError, MatchRequest, Any] =
    endpoint.post
      .securityIn(auth.bearer[String]())
      .in("api" / "v1" / "matches" / "request")
      .in(jsonBody[CreateMatchRequest])
      .out(jsonBody[MatchRequest])
      .errorOut(jsonBody[ApiError])

  val recordConsent: Endpoint[String, CreateMatchConsent, ApiError, MatchConsent, Any] =
    endpoint.post
      .securityIn(auth.bearer[String]())
      .in("api" / "v1" / "matches" / "consent")
      .in(jsonBody[CreateMatchConsent])
      .out(jsonBody[MatchConsent])
      .errorOut(jsonBody[ApiError])
}

object IbdEndpoints {

  val submitAttestation: Endpoint[String, IbdAttestationRequest, ApiError, AttestationResult, Any] =
    endpoint.post
      .securityIn(auth.bearer[String]())  // Edge App auth
      .in("api" / "v1" / "ibd" / "attestation")
      .in(jsonBody[IbdAttestationRequest])
      .out(jsonBody[AttestationResult])
      .errorOut(jsonBody[ApiError])

  val getConfirmedMatches: Endpoint[String, MatchQuery, ApiError, Seq[IbdMatch], Any] =
    endpoint.get
      .securityIn(auth.bearer[String]())
      .in("api" / "v1" / "matches" / "confirmed")
      .in(query[Option[String]]("regionType"))
      .in(query[Option[Double]]("minCm"))
      .out(jsonBody[Seq[IbdMatch]])
      .errorOut(jsonBody[ApiError])
}
```

---

## Security Considerations

### Data Privacy

| Data Type | Storage Location | Encryption |
|-----------|-----------------|------------|
| Variant calls | Edge App only | AES-256 at rest |
| Match requests | User PDS | AT Protocol signing |
| Consent votes | User PDS | AT Protocol signing |
| Match summaries | DecodingUs DB | Standard DB encryption |
| Population breakdowns | User PDS + cache | AT Protocol + DB encryption |

### Authentication & Authorization

1. **User Authentication**: OAuth2/DID-based via AT Protocol
2. **Edge App Authentication**: API keys + request signing
3. **Consent Verification**: Dual-signature requirement before data exchange
4. **Rate Limiting**: Prevent discovery enumeration attacks

### Cryptographic Requirements

```
Key Exchange:     ECDH (X25519)
Data Encryption:  AES-256-GCM
Signatures:       Ed25519 (AT Protocol standard)
Hashing:          SHA-256 for match summaries
```

---

## Implementation Phases

### Phase 1: Lexicon & Schema

**Scope:**
- Define and publish Lexicon extensions
- Database schema migration
- Repository layer for new tables

**Deliverables:**
- [ ] Lexicon JSON files for matchList, matchRequest, populationBreakdown, matchConsent
- [ ] Database evolution script
- [ ] `MatchSuggestionRepository`
- [ ] `PopulationBreakdownCacheRepository`
- [ ] `MatchRequestTrackingRepository`
- [ ] `MatchConsentTrackingRepository`

**Edge App Coordination:**
- Share Lexicon definitions with Edge App team
- Agree on key exchange protocol

### Phase 2: Discovery Engine

**Scope:**
- Implement match suggestion algorithms
- Population overlap calculation
- Suggestion ranking

**Deliverables:**
- [ ] `MatchDiscoveryService` implementation
- [ ] `PopulationAnalysisService` implementation
- [ ] Background job for overlap score computation
- [ ] Discovery API endpoints

**Edge App Coordination:**
- None required (server-side only)

### Phase 3: Request & Consent Flow

**Scope:**
- Match request lifecycle management
- Consent voting and verification
- PDS record creation via AT Protocol

**Deliverables:**
- [ ] `MatchRequestService` implementation
- [ ] AT Protocol client for PDS writes
- [ ] Firehose listener for consent records
- [ ] Match request API endpoints

**Edge App Coordination:**
- Coordinate on consent UI/UX
- Define notification webhook contract

### Phase 4: IBD Integration

**Scope:**
- Edge App notification on mutual consent
- Attestation submission endpoint
- Match indexing and consensus tracking

**Deliverables:**
- [ ] `IbdMatchingService` implementation
- [ ] Extend existing `IbdDiscoveryIndexRepository`
- [ ] Extend existing `IbdPdsAttestationRepository`
- [ ] IBD API endpoints
- [ ] Attestation verification logic

**Edge App Coordination:**
- **CRITICAL**: Define and test data exchange protocol
- Implement key exchange mechanism
- Test P2P encrypted communication
- Verify attestation signing/verification

### Phase 5: UI & Notifications

**Scope:**
- User-facing match discovery interface
- Request/consent management UI
- Match list visualization

**Deliverables:**
- [ ] Twirl templates for discovery pages
- [ ] Match request notification system
- [ ] Match list dashboard
- [ ] Population breakdown visualization

**Edge App Coordination:**
- Coordinate on consistent UX across platforms
- Define deep-linking for match requests

---

## Configuration

```hocon
decodingus.matching {
  discovery {
    shared-match-threshold = 2        # Minimum shared matches for suggestion
    population-overlap-threshold = 0.6 # Minimum overlap score (0-1)
    suggestion-expiry-days = 90
    max-suggestions-per-user = 100
  }

  requests {
    default-expiry-days = 30
    max-pending-requests = 50
    consent-expiry-days = 365
  }

  ibd {
    attestation-timeout-hours = 24    # Time for both attestations
    min-shared-cm-to-index = 7.0      # Don't index tiny matches
  }

  edge-app {
    notification-webhook-timeout = 30.seconds
    retry-attempts = 3
  }
}
```

---

## Testing Strategy

### Unit Tests
- Overlap score calculation
- Suggestion ranking algorithm
- Consent verification logic
- Attestation signature verification

### Integration Tests
- Full request → consent → attestation flow
- Firehose event processing
- Edge App webhook delivery

### End-to-End Tests
- Complete match discovery workflow
- Cross-PDS consent synchronization

### Edge App Integration Testing
- **Joint testing required** with Edge App team
- Key exchange protocol verification
- Encrypted data round-trip
- Attestation interoperability

---

## Monitoring & Metrics

### Key Metrics

- Suggestions generated per day
- Request conversion rate (suggestion → request)
- Consent acceptance rate
- Average time to mutual consent
- Attestation success rate
- Match indexing rate

### Alerts

- Attestation verification failures
- Consent timeout rate spike
- Edge App webhook failures
- Population cache staleness

---

## Future Considerations

1. **Group Matching**: Support for family/surname project group comparisons
2. **Triangulation**: Automated triangulation detection across multiple matches
3. **Chromosome Browser**: Visual segment comparison (requires Edge App coordination)
4. **Match Notes Sync**: Synchronize match notes across users
5. **Relationship Prediction ML**: Machine learning for relationship estimation

---

## Appendix: Existing Schema Reference

### ibd_discovery_index (Evolution 7)

```sql
CREATE TABLE public.ibd_discovery_index (
    id BIGSERIAL PRIMARY KEY,
    sample_guid_1 UUID NOT NULL,
    sample_guid_2 UUID NOT NULL,
    pangenome_graph_id INTEGER NOT NULL,
    match_region_type VARCHAR(50) NOT NULL,  -- AUTOSOMAL, Y_CHROMOSOME, etc.
    total_shared_cm_approx DOUBLE PRECISION,
    num_shared_segments_approx INTEGER,
    is_publicly_discoverable BOOLEAN DEFAULT FALSE,
    consensus_status VARCHAR(50) DEFAULT 'INITIATED',
    last_consensus_update TIMESTAMP DEFAULT NOW(),
    validation_service_guid UUID,
    validation_timestamp TIMESTAMP,
    indexed_by_service VARCHAR(255),
    indexed_date TIMESTAMP DEFAULT NOW()
);
```

### ibd_pds_attestation (Evolution 7)

```sql
CREATE TABLE public.ibd_pds_attestation (
    id BIGSERIAL PRIMARY KEY,
    ibd_discovery_index_id BIGINT NOT NULL,
    attesting_pds_guid UUID NOT NULL,
    attesting_sample_guid UUID NOT NULL,
    attestation_timestamp TIMESTAMP DEFAULT NOW(),
    attestation_signature TEXT NOT NULL,
    match_summary_hash VARCHAR(255) NOT NULL,
    attestation_type VARCHAR(50) NOT NULL,  -- INITIAL_REPORT, CONFIRMATION, etc.
    attestation_notes TEXT
);
```

### ancestry_analysis (Evolution 1)

```sql
CREATE TABLE ancestry_analysis (
    ancestry_analysis_id SERIAL PRIMARY KEY,
    sample_guid UUID NOT NULL,
    analysis_method_id INT NOT NULL,
    population_id INT NOT NULL,
    probability DECIMAL(5, 4)
);
```
