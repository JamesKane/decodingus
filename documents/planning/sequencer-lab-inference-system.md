# Sequencer Lab Inference System

## Executive Summary

This document outlines enhancements to the existing sequencer lab lookup system to support:

1. **Edge Compute Integration**: Public API for Edge analyzers to infer sequencing laboratory from BAM/CRAM read metadata
2. **Consensus-Based Discovery**: New Lexicon element allowing citizens to contribute instrument-to-lab associations
3. **Curator Oversight**: Workflow for curators to review, accept, or reject community-submitted associations

---

## Problem Statement

The Edge Compute analysis pipeline extracts detailed platform and technology heuristics from BAM/CRAM read headers, including:
- Instrument ID (e.g., `A00123`, `M01234`)
- Platform (e.g., `ILLUMINA`, `PACBIO`)
- Model inference from flowcell patterns

However, the pipeline lacks a way to resolve the **sequencing laboratory** that owns a given instrument. This information is valuable for:
- Populating `centerName` in the biosample record
- Quality benchmarking by lab
- Identifying D2C (direct-to-consumer) vs. clinical lab origins
- Troubleshooting systematic quality issues

---

## Current State

### Existing Schema (Evolution 21.sql)

```sql
-- Sequencing labs
CREATE TABLE public.sequencing_lab (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    is_d2c BOOLEAN DEFAULT false NOT NULL,
    website_url VARCHAR(255),
    description_markdown TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP
);

-- Sequencer instruments
CREATE TABLE public.sequencer_instrument (
    id SERIAL PRIMARY KEY,
    instrument_id VARCHAR(255) NOT NULL UNIQUE,
    lab_id INTEGER NOT NULL REFERENCES public.sequencing_lab(id),
    manufacturer VARCHAR(255),
    model VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP
);
```

### Existing API Endpoints

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/api/v1/sequencer/lab` | GET | Public | Lookup lab by `instrument_id` query param |
| `/api/v1/sequencer/lab-instruments` | GET | Public | List all lab-instrument associations |
| `/api/v1/sequencer/lab/associate` | POST | API Key | Associate instrument with lab (curator) |

### Existing Domain Models

- `SequencingLab`: Lab entity with D2C flag and metadata
- `SequencerInstrument`: Instrument entity linked to lab
- `SequencerLabInfo`: API response DTO

---

## Gaps to Address

1. **No Consensus Tracking**: The current system only supports curator-driven associations; no way for users to contribute observations
2. **No Evidence Trail**: When a curator makes an association, there's no record of supporting evidence
3. **No Lexicon Integration**: Edge Apps cannot contribute instrument-lab observations to their PDS
4. **No Confidence Scoring**: All associations are treated equally regardless of evidence strength
5. **No Conflict Resolution**: If multiple labs claim the same instrument ID, no workflow exists

---

## Proposed Solution

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Edge App / BGS Node                              │
│  • Extracts instrument_id from @RG headers                              │
│  • Queries Public API for lab lookup                                    │
│  • Creates instrumentObservation record in user's PDS (optional)        │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
              ┌──────────┐   ┌──────────────┐  ┌─────────────┐
              │  Public  │   │   Firehose   │  │  Curator    │
              │  Lookup  │   │   Ingestion  │  │  Dashboard  │
              │   API    │   │              │  │             │
              └──────────┘   └──────────────┘  └─────────────┘
                    │               │               │
                    ▼               ▼               ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Instrument Association Registry                      │
│  • sequencing_lab (existing)                                            │
│  • sequencer_instrument (existing)                                      │
│  • instrument_observation (NEW) - consensus evidence                    │
│  • instrument_association_proposal (NEW) - pending curator review       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Data Model Extensions

### 1. Instrument Observation (Consensus Evidence)

Tracks observations from citizens about instrument-lab associations.

```sql
-- Evolution XX: Instrument Association Consensus

-- Observations from citizen biosamples about instrument-lab associations
CREATE TABLE public.instrument_observation (
    id SERIAL PRIMARY KEY,

    -- The instrument being observed
    instrument_id VARCHAR(255) NOT NULL,

    -- The claimed lab association
    claimed_lab_name VARCHAR(255) NOT NULL,

    -- Evidence source (citizen biosample that provided this observation)
    citizen_biosample_id INTEGER REFERENCES public.citizen_biosample(id),
    citizen_did VARCHAR(255) NOT NULL,

    -- Additional context from the read headers
    platform VARCHAR(100),           -- e.g., 'ILLUMINA'
    instrument_model VARCHAR(255),   -- e.g., 'NovaSeq 6000' (inferred)
    flowcell_id VARCHAR(100),        -- Flowcell identifier if available
    run_date DATE,                   -- Sequencing run date if extractable

    -- AT Protocol reference
    at_uri VARCHAR(500),             -- Reference to PDS record
    at_cid VARCHAR(100),

    -- Metadata
    observed_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Prevent duplicate observations from same biosample
    UNIQUE(citizen_biosample_id, instrument_id)
);

CREATE INDEX idx_io_instrument ON instrument_observation(instrument_id);
CREATE INDEX idx_io_lab ON instrument_observation(claimed_lab_name);
CREATE INDEX idx_io_citizen ON instrument_observation(citizen_did);
```

### 2. Instrument Association Proposal

Tracks proposed associations pending curator review.

```sql
-- Proposals for new or updated instrument-lab associations
CREATE TABLE public.instrument_association_proposal (
    id SERIAL PRIMARY KEY,

    -- The instrument being proposed
    instrument_id VARCHAR(255) NOT NULL,

    -- Proposed association
    proposed_lab_name VARCHAR(255) NOT NULL,
    proposed_manufacturer VARCHAR(255),
    proposed_model VARCHAR(255),

    -- Current state (if updating existing association)
    existing_lab_id INTEGER REFERENCES public.sequencing_lab(id),

    -- Evidence summary
    observation_count INTEGER NOT NULL DEFAULT 0,
    distinct_citizen_count INTEGER NOT NULL DEFAULT 0,
    earliest_observation TIMESTAMP,
    latest_observation TIMESTAMP,

    -- Status workflow
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'READY_FOR_REVIEW', 'UNDER_REVIEW',
                          'ACCEPTED', 'REJECTED', 'SUPERSEDED')),

    -- Curator review
    reviewed_at TIMESTAMP,
    reviewed_by VARCHAR(255),
    review_notes TEXT,

    -- If accepted, link to the created/updated records
    accepted_lab_id INTEGER REFERENCES public.sequencing_lab(id),
    accepted_instrument_id INTEGER REFERENCES public.sequencer_instrument(id),

    -- Metadata
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- One active proposal per instrument
    UNIQUE(instrument_id, status) WHERE status NOT IN ('ACCEPTED', 'REJECTED', 'SUPERSEDED')
);

CREATE INDEX idx_iap_status ON instrument_association_proposal(status);
CREATE INDEX idx_iap_instrument ON instrument_association_proposal(instrument_id);
```

### 3. Enhanced Sequencer Instrument

Add tracking for observation-based discovery.

```sql
-- Add observation tracking to existing table
ALTER TABLE public.sequencer_instrument
    ADD COLUMN source VARCHAR(30) DEFAULT 'CURATOR'
        CHECK (source IN ('CURATOR', 'CONSENSUS', 'PUBLICATION')),
    ADD COLUMN observation_count INTEGER DEFAULT 0,
    ADD COLUMN confidence_score DOUBLE PRECISION DEFAULT 1.0,
    ADD COLUMN last_observed_at TIMESTAMP;

-- Index for confidence-based queries
CREATE INDEX idx_si_confidence ON public.sequencer_instrument(confidence_score DESC);
```

---

## Domain Models

### New Domain Entities

```scala
package models.domain.genomics

/**
 * An observation of an instrument-lab association from a citizen biosample.
 */
case class InstrumentObservation(
  id: Option[Int],
  instrumentId: String,
  claimedLabName: String,
  citizenBiosampleId: Option[Int],
  citizenDid: String,
  platform: Option[String],
  instrumentModel: Option[String],
  flowcellId: Option[String],
  runDate: Option[LocalDate],
  atUri: Option[String],
  atCid: Option[String],
  observedAt: LocalDateTime
)

/**
 * A proposal for a new or updated instrument-lab association.
 */
case class InstrumentAssociationProposal(
  id: Option[Int],
  instrumentId: String,
  proposedLabName: String,
  proposedManufacturer: Option[String],
  proposedModel: Option[String],
  existingLabId: Option[Int],
  observationCount: Int,
  distinctCitizenCount: Int,
  earliestObservation: Option[LocalDateTime],
  latestObservation: Option[LocalDateTime],
  status: ProposalStatus,
  reviewedAt: Option[LocalDateTime],
  reviewedBy: Option[String],
  reviewNotes: Option[String],
  acceptedLabId: Option[Int],
  acceptedInstrumentId: Option[Int],
  createdAt: LocalDateTime,
  updatedAt: LocalDateTime
)

enum ProposalStatus:
  case Pending, ReadyForReview, UnderReview, Accepted, Rejected, Superseded

/**
 * Source of an instrument-lab association.
 */
enum AssociationSource:
  case Curator       // Manually created by curator
  case Consensus     // Derived from citizen observations
  case Publication   // From academic publication
```

---

## Lexicon Extension

### New Lexicon: Instrument Observation (`com.decodingus.atmosphere.instrumentObservation`)

This record allows citizens to contribute instrument-lab observations from their sequencing data.

```json
{
  "lexicon": 1,
  "id": "com.decodingus.atmosphere.instrumentObservation",
  "defs": {
    "main": {
      "type": "record",
      "description": "An observation of a sequencer instrument and its associated laboratory, extracted from BAM/CRAM read headers.",
      "key": "tid",
      "record": {
        "type": "object",
        "required": ["instrumentId", "labName", "biosampleRef", "observedAt"],
        "properties": {
          "instrumentId": {
            "type": "string",
            "description": "The instrument ID extracted from the @RG header (e.g., 'A00123').",
            "minLength": 1,
            "maxLength": 255
          },
          "labName": {
            "type": "string",
            "description": "The name of the sequencing laboratory (as known by the user or inferred).",
            "minLength": 1,
            "maxLength": 255
          },
          "biosampleRef": {
            "type": "ref",
            "ref": "com.decodingus.atmosphere.biosample",
            "description": "Reference to the biosample from which this observation was extracted."
          },
          "platform": {
            "type": "string",
            "description": "Sequencing platform (e.g., 'ILLUMINA', 'PACBIO').",
            "knownValues": ["ILLUMINA", "PACBIO", "ONT", "MGI", "ELEMENT", "ULTIMA"]
          },
          "instrumentModel": {
            "type": "string",
            "description": "Inferred or known instrument model (e.g., 'NovaSeq 6000')."
          },
          "flowcellId": {
            "type": "string",
            "description": "Flowcell identifier if extractable from read headers."
          },
          "runDate": {
            "type": "string",
            "format": "datetime",
            "description": "Date of the sequencing run if extractable."
          },
          "confidence": {
            "type": "string",
            "description": "Confidence level of the lab association.",
            "knownValues": ["KNOWN", "INFERRED", "GUESSED"],
            "default": "INFERRED"
          },
          "observedAt": {
            "type": "string",
            "format": "datetime",
            "description": "When this observation was recorded."
          }
        }
      }
    }
  }
}
```

### Confidence Levels

| Level | Description | Weight |
|-------|-------------|--------|
| `KNOWN` | User directly knows the lab (e.g., received results from them) | 1.0 |
| `INFERRED` | Lab name derived from file metadata or common knowledge | 0.7 |
| `GUESSED` | User's best guess based on patterns | 0.3 |

---

## Service Layer

### 1. InstrumentObservationService

Handles ingestion of observations from the Firehose. All observations arrive via PDS records.

```scala
trait InstrumentObservationService {
  /**
   * Record an observation from a citizen's PDS.
   * Called by the Firehose handler when processing instrumentObservation records.
   */
  def recordObservation(
    observation: InstrumentObservation
  ): Future[InstrumentObservation]

  /**
   * Record an observation extracted from a biosample's sequenceData.
   * Called by CitizenBiosampleEventHandler during biosample processing.
   */
  def recordObservationFromBiosample(
    biosample: CitizenBiosample,
    sequenceData: SequenceData
  ): Future[Option[InstrumentObservation]]

  /**
   * Get all observations for an instrument.
   */
  def getObservationsForInstrument(
    instrumentId: String
  ): Future[Seq[InstrumentObservation]]

  /**
   * Aggregate observations into a proposed association.
   * Called after recording new observations.
   */
  def aggregateObservations(
    instrumentId: String
  ): Future[Option[InstrumentAssociationProposal]]
}
```

### 2. InstrumentProposalService

Manages the proposal lifecycle.

```scala
trait InstrumentProposalService {
  /**
   * Create or update a proposal based on aggregated observations.
   */
  def createOrUpdateProposal(
    instrumentId: String,
    proposedLabName: String,
    observations: Seq[InstrumentObservation]
  ): Future[InstrumentAssociationProposal]

  /**
   * Check all pending proposals against thresholds.
   */
  def evaluateProposalThresholds(): Future[Seq[InstrumentAssociationProposal]]

  /**
   * Get proposals ready for curator review.
   */
  def getProposalsForReview(
    status: Option[ProposalStatus],
    page: Int,
    pageSize: Int
  ): Future[Page[InstrumentAssociationProposal]]

  /**
   * Accept a proposal, creating the official association.
   */
  def acceptProposal(
    proposalId: Int,
    curatorId: String,
    labName: String,        // May differ from proposed
    manufacturer: Option[String],
    model: Option[String],
    notes: Option[String]
  ): Future[SequencerInstrument]

  /**
   * Reject a proposal.
   */
  def rejectProposal(
    proposalId: Int,
    curatorId: String,
    reason: String
  ): Future[InstrumentAssociationProposal]
}
```

### 3. Enhanced SequencerInstrumentService

Extend existing service with observation-aware lookup.

```scala
trait SequencerInstrumentService {
  // Existing methods...

  /**
   * Lookup lab with fallback to pending proposals.
   * Returns both confirmed associations and pending proposals.
   */
  def lookupLabWithProposals(
    instrumentId: String
  ): Future[InstrumentLookupResult]

  /**
   * Get instruments with low confidence for curator attention.
   */
  def getInstrumentsNeedingReview(
    minObservations: Int,
    maxConfidence: Double
  ): Future[Seq[SequencerInstrumentWithProposals]]
}

case class InstrumentLookupResult(
  confirmedLab: Option[SequencerLabInfo],
  pendingProposal: Option[InstrumentAssociationProposal],
  observationCount: Int,
  source: AssociationSource
)
```

---

## API Endpoints

### Data Flow Pattern

**Important:** All citizen data flows through the PDS/Firehose pattern. There are NO direct submission APIs for citizen data.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              Data Flow                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Edge App                                                                │
│     │                                                                    │
│     ├──► GET /api/v1/sequencer/lab (Public lookup - READ ONLY)          │
│     │                                                                    │
│     └──► Creates instrumentObservation record in User's PDS             │
│              │                                                           │
│              ▼                                                           │
│         AT Protocol Firehose                                             │
│              │                                                           │
│              ▼                                                           │
│         DecodingUs App View ingests observation                          │
│              │                                                           │
│              ▼                                                           │
│         InstrumentObservationService.recordObservation()                 │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Public Lookup API (Read-Only)

These are the only public endpoints - they are read-only lookups for Edge Apps.

```
# Existing endpoint - enhanced response
GET  /api/v1/sequencer/lab
     ?instrument_id={id}
     → SequencerLabLookupResponse

# Response includes confidence and proposal info
{
  "instrumentId": "A00123",
  "labName": "Nebula Genomics",
  "isD2c": true,
  "manufacturer": "Illumina",
  "model": "NovaSeq 6000",
  "websiteUrl": "https://nebula.org",
  "source": "CONSENSUS",           // NEW: CURATOR, CONSENSUS, PUBLICATION
  "confidenceScore": 0.95,         // NEW: 0.0-1.0
  "observationCount": 15,          // NEW: number of supporting observations
  "pendingUpdate": null            // NEW: if a newer proposal exists
}

# Existing endpoint - list all associations
GET  /api/v1/sequencer/lab-instruments
     → SequencerLabInstrumentsResponse
```

### Curator API (Internal)

Curator endpoints for proposal review - these are internal dashboard APIs, not for citizen submission.

```
# Get proposals ready for review
GET  /api/v1/curator/instrument-proposals
     ?status={READY_FOR_REVIEW|PENDING|...}
     &page={int}
     &pageSize={int}
     → Page[InstrumentProposalView]

# Get proposal details with all observations
GET  /api/v1/curator/instrument-proposals/{id}
     → InstrumentProposalDetails

# Accept a proposal
POST /api/v1/curator/instrument-proposals/{id}/accept
     Body: {
       "labName": "Nebula Genomics",
       "manufacturer": "Illumina",
       "model": "NovaSeq 6000",
       "isD2c": true,
       "notes": "Confirmed via 15 citizen observations"
     }
     → SequencerLabInfo

# Reject a proposal
POST /api/v1/curator/instrument-proposals/{id}/reject
     Body: { "reason": "Instrument ID appears to be from multiple labs" }
     → InstrumentProposalView
```

### Removed APIs

The following APIs are NOT needed because data flows via PDS/Firehose:

| Removed | Reason |
|---------|--------|
| ~~`POST /api/v1/sequencer/observation`~~ | Observations come via Firehose from PDS |
| ~~`POST /api/v1/sequencer/lab/associate`~~ | Use curator proposal workflow instead |

---

## Consensus Algorithm

### Threshold Configuration

```hocon
decodingus.sequencer-inference {
  # Minimum observations to create a proposal
  min-observations-for-proposal = 2

  # Observations needed for READY_FOR_REVIEW status
  ready-for-review-threshold = 5

  # Observations needed for auto-acceptance (if unanimous)
  auto-accept-threshold = 10

  # Minimum distinct citizens for auto-acceptance
  min-distinct-citizens = 3

  # Required agreement ratio for auto-acceptance
  agreement-ratio = 0.9

  # Confidence score calculation weights
  confidence {
    observation-weight = 0.4
    citizen-diversity-weight = 0.3
    recency-weight = 0.2
    confidence-level-weight = 0.1
  }
}
```

### Confidence Score Calculation

```
confidence = w1 * min(observationCount / autoAcceptThreshold, 1.0) +
             w2 * min(distinctCitizens / minDistinctCitizens, 1.0) +
             w3 * recencyFactor +
             w4 * avgConfidenceLevel

Where:
- w1 = 0.4 (observation count weight)
- w2 = 0.3 (citizen diversity weight)
- w3 = 0.2 (recency: 1.0 if observed in last 30 days, decays)
- w4 = 0.1 (average of KNOWN=1.0, INFERRED=0.7, GUESSED=0.3)
```

### Conflict Detection

When observations suggest different labs for the same instrument:

```scala
case class InstrumentConflict(
  instrumentId: String,
  proposals: Seq[ConflictingProposal],
  dominantLabName: String,
  dominantRatio: Double
)

case class ConflictingProposal(
  labName: String,
  observationCount: Int,
  distinctCitizens: Int,
  ratio: Double
)

// If no lab has > 70% of observations, flag for manual review
// If lab has > 90% and meets thresholds, allow auto-acceptance
```

---

## Integration with Existing System

### 1. Biosample Ingestion Hook

Extend `CitizenBiosampleEventHandler` to extract and submit observations:

```scala
// In CitizenBiosampleEventHandler.handle()

private def extractInstrumentObservation(
  biosample: CitizenBiosample,
  sequenceData: SequenceData
): Option[InstrumentObservation] = {
  // Extract instrument_id from RG headers (already done in Edge)
  // Look for lab hints in metadata

  for {
    instrumentId <- extractInstrumentId(sequenceData)
    labName <- inferLabName(biosample, sequenceData)
  } yield InstrumentObservation(
    id = None,
    instrumentId = instrumentId,
    claimedLabName = labName,
    citizenBiosampleId = biosample.id,
    citizenDid = biosample.citizenDid,
    platform = sequenceData.platformName,
    instrumentModel = sequenceData.instrumentModel,
    flowcellId = extractFlowcellId(sequenceData),
    runDate = extractRunDate(sequenceData),
    atUri = biosample.atUri,
    atCid = None, // Set after PDS sync
    observedAt = LocalDateTime.now()
  )
}

private def inferLabName(
  biosample: CitizenBiosample,
  sequenceData: SequenceData
): Option[String] = {
  // Priority:
  // 1. centerName from biosample (if not generic)
  // 2. Lab name embedded in file paths
  // 3. Known patterns (e.g., Nebula flowcell patterns)
  biosample.centerName
    .filter(name => !isGenericCenterName(name))
    .orElse(extractLabFromFilePath(sequenceData.files))
    .orElse(inferFromFlowcellPattern(sequenceData))
}
```

### 2. Edge App Enhancement

The Edge App should:
1. Query `/api/v1/sequencer/lab` during analysis
2. If no result found, prompt user for lab name (optional)
3. Create `instrumentObservation` record in PDS
4. Include observation reference in biosample submission

```typescript
// Edge App pseudocode
async function processSequenceData(bamFile: BamFile): Promise<void> {
  const instrumentId = extractInstrumentId(bamFile);

  // Lookup existing association
  const labInfo = await api.lookupSequencerLab(instrumentId);

  if (labInfo.confirmedLab) {
    // Use confirmed lab
    biosample.centerName = labInfo.confirmedLab.labName;
  } else if (labInfo.pendingProposal) {
    // Show pending proposal to user
    biosample.centerName = labInfo.pendingProposal.proposedLabName;
  } else {
    // Prompt user for lab name (optional)
    const userLabName = await promptForLabName();
    if (userLabName) {
      // Create observation in PDS
      await createInstrumentObservation({
        instrumentId,
        labName: userLabName,
        confidence: 'KNOWN',
        biosampleRef: biosample.atUri
      });
    }
  }
}
```

### 3. Firehose Integration

The Firehose ingestion should process `instrumentObservation` records:

```scala
// In atmosphere firehose event handler

case "com.decodingus.atmosphere.instrumentObservation" =>
  for {
    observation <- parseInstrumentObservation(commit)
    _ <- instrumentObservationService.recordObservation(observation)
    _ <- instrumentProposalService.aggregateObservations(observation.instrumentId)
  } yield ProcessingResult.success
```

---

## Implementation Phases

### Phase 1: Schema and Core Services

**Scope:**
- Database evolution for observation and proposal tables
- Domain models and repositories
- Basic observation recording service

**Deliverables:**
- [ ] Database evolution script (instrument_observation, instrument_association_proposal, sequencer_instrument updates)
- [ ] `InstrumentObservation` domain model and DAL table
- [ ] `InstrumentAssociationProposal` domain model and DAL table
- [ ] `InstrumentObservationRepository`
- [ ] `InstrumentProposalRepository`
- [ ] `InstrumentObservationService` implementation

### Phase 2: Lexicon and Firehose Integration

**Scope:**
- Lexicon definition for instrumentObservation
- Firehose ingestion handler for observation records
- Edge App guidance documentation

**Deliverables:**
- [ ] `com.decodingus.atmosphere.instrumentObservation` Lexicon definition
- [ ] Firehose handler for `instrumentObservation` records
- [ ] Updated Atmosphere_Lexicon.md
- [ ] Edge App integration documentation

### Phase 3: Proposal Engine

**Scope:**
- Proposal aggregation logic (triggered by Firehose events)
- Threshold evaluation
- Confidence scoring

**Deliverables:**
- [ ] `InstrumentProposalService` implementation
- [ ] Confidence score calculation
- [ ] Threshold evaluation job
- [ ] Conflict detection logic

### Phase 4: Enhanced Lookup API

**Scope:**
- Enhanced public lookup endpoint with confidence info
- Curator proposal management API (read/accept/reject)

**Deliverables:**
- [ ] Enhanced `/api/v1/sequencer/lab` response with confidence info
- [ ] Curator proposal endpoints (GET, accept, reject)
- [ ] Tapir endpoint definitions
- [ ] OpenAPI documentation

### Phase 5: Biosample Integration

**Scope:**
- Extract observations from existing biosample Firehose events
- Lab inference from biosample metadata
- Backfill existing biosamples

**Deliverables:**
- [ ] CitizenBiosampleEventHandler integration (extract observations)
- [ ] Lab inference logic from centerName and file paths
- [ ] Backfill script for existing biosamples
- [ ] Integration tests

---

## Curator Workflow

### Dashboard Features

1. **Proposal Queue**
   - List of READY_FOR_REVIEW proposals
   - Filter by observation count, conflict status
   - Sort by age, evidence strength

2. **Proposal Detail View**
   - All supporting observations
   - Distinct citizen count
   - Geographic distribution (if available)
   - Conflicting proposals (if any)
   - Timeline of observations

3. **Actions**
   - Accept with modifications (lab name, model, D2C flag)
   - Reject with reason
   - Merge with existing lab (if typo/variant)
   - Split (if instrument shared by multiple labs)

### Audit Trail

All curator actions logged to existing `curator_action` table (from haplogroup-discovery-system):

```scala
CuratorAction(
  curatorId = "curator@example.com",
  actionType = CuratorActionType.Accept,
  targetType = CuratorTargetType.InstrumentProposal,
  targetId = proposalId,
  previousState = proposalJsonBefore,
  newState = proposalJsonAfter,
  reason = Some("Confirmed via 15 observations from 8 distinct citizens"),
  timestamp = LocalDateTime.now()
)
```

---

## Configuration

```hocon
decodingus.sequencer-inference {
  # Thresholds
  min-observations-for-proposal = 2
  ready-for-review-threshold = 5
  auto-accept-threshold = 10
  min-distinct-citizens = 3
  agreement-ratio = 0.9

  # Confidence weights
  confidence {
    observation-weight = 0.4
    citizen-diversity-weight = 0.3
    recency-weight = 0.2
    confidence-level-weight = 0.1
  }

  # Background jobs
  evaluation-interval = 15 minutes

  # Generic center names to ignore
  generic-center-names = [
    "Unknown",
    "Self",
    "Home",
    "N/A",
    ""
  ]
}
```

---

## Testing Strategy

### Unit Tests
- Confidence score calculation
- Threshold evaluation
- Conflict detection
- Lab name inference heuristics

### Integration Tests
- Observation recording and aggregation
- Proposal lifecycle (create → ready → accept)
- API endpoint functionality
- Firehose ingestion

### End-to-End Tests
- Full workflow from Edge App to accepted association
- Curator accept/reject flows

---

## Future Considerations

1. **Flowcell-Level Tracking**: Track flowcell IDs to detect instrument moves between labs
2. **Geographic Inference**: Use citizen location hints to validate lab geography
3. **Publication Cross-Reference**: Match instrument IDs to publications for additional evidence
4. **Instrument Decommissioning**: Handle cases where instruments change ownership
5. **API Rate Limiting**: Prevent gaming of observation counts
6. **Reputation System**: Weight observations by citizen's historical accuracy
