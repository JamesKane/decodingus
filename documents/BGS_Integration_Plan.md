# BGS / Firehose Integration Plan

## Status: Transitioning to Atmosphere Lexicon Events

This document outlines the transition of the BGS integration from a monolithic REST API to a more granular, event-driven model based on the Atmosphere Lexicon. While the initial REST API (`/api/external-biosamples`, `/api/projects`) remains functional for backward compatibility, new integrations should prefer the generic `/api/firehose/event` endpoint.

---

## Architecture Overview

For the MVP and early phases, we utilize a **Secure REST API** pattern. The BGS server (or Edge App) acts as an authenticated API client.

### Generic Atmosphere Event API (Recommended for New Integrations)

This endpoint provides a unified entry point for all Atmosphere Lexicon records (Biosample, Sequence Run, Alignment, Project, etc.). The client sends a JSON payload representing a `FirehoseEvent` type, which is then dispatched to the appropriate handler.

*   **Integration Point:** `POST /api/firehose/event`
*   **Controller:** `app/controllers/CitizenBiosampleController.scala` (specifically `processEvent` action)
*   **Handler:** `app/services/firehose/AtmosphereEventHandler.scala`
*   **Data Models:** `app/models/atmosphere/*Record.scala` and `app/services/firehose/*Event.scala`
*   **Security:** API Key authentication via `X-API-Key` header (`ApiSecurityAction`)

### Legacy (Phase 1) Monolithic APIs (For Backward Compatibility)

These endpoints handle `ExternalBiosampleRequest` which is a monolithic structure that embeds all related data. This will eventually be deprecated in favor of the granular Atmosphere events.

#### Citizen Biosample API

*   **Integration Point:** `POST /api/external-biosamples`
*   **Controller:** `app/controllers/CitizenBiosampleController.scala`
*   **Service:** `app/services/CitizenBiosampleService.scala`
*   **Data Model:** `app/models/api/ExternalBiosampleRequest.scala`
*   **Security:** API Key authentication via `X-API-Key` header (`ApiSecurityAction`)

#### Full CRUD Operations (Legacy)

| Operation | Endpoint | Description |
|-----------|----------|-------------|
| **Create** | `POST /api/external-biosamples` | Create new citizen biosample with donor resolution |
| **Update** | `PUT /api/external-biosamples/{atUri}` | Update existing biosample (optimistic locking via `atCid`) |
| **Delete** | `DELETE /api/external-biosamples/{atUri}` | Soft delete biosample |

#### Project API (Legacy)

| Operation | Endpoint | Description |
|-----------|----------|-------------|
| **Create** | `POST /api/projects` | Create new research project |
| **Update** | `PUT /api/projects/{atUri}` | Update project (optimistic locking) |
| **Delete** | `DELETE /api/projects/{atUri}` | Soft delete project |

---

## Data Model: Atmosphere Lexicon Granular Records

The Edge App now generates and sends granular records defined by the `com.decodingus.atmosphere` Lexicon. The previous monolithic `ExternalBiosampleRequest` is now broken down into distinct, inter-referenced records.

### Key Concepts

1.  **PDS Owner (citizenDid):** The researcher/genealogist running the Edge App. Owns the AT Protocol records.
2.  **Granular Records:** Each significant entity (Biosample, Sequence Run, Alignment, Project) is its own top-level record with a unique `atUri`.
3.  **Referential Integrity:** Records link to each other using `atUri` references (e.g., `SequenceRunRecord.biosampleRef` points to a `BiosampleRecord`).

### Linkage Keys

*   `atUri`: The canonical AT Protocol identifier (`at://did:plc:xxx/collection/rkey`) - uniquely identifies *any* record.
*   `atCid`: Content Identifier for optimistic locking / version tracking.
*   `citizenDid`: Identifies the PDS owner, extracted from `atUri` or provided explicitly.
*   `donorIdentifier`: Identifies the specific biological source (person) within that PDS owner's collection.

### SpecimenDonor Resolution Logic

Implemented in `CitizenBiosampleService.resolveOrCreateDonor()`:

1. Extract `citizenDid` from `atUri` (format: `at://did:plc:xxx/...`)
2. Look up `SpecimenDonor` by `(citizenDid, donorIdentifier)` pair
3. If found: Link biosample to existing donor (aggregates multiple datasets)
4. If not found: Create new `SpecimenDonor` with `donorType = Citizen`



---

## Data Payload Specification (Atmosphere Lexicon Events)

Clients should send JSON payloads corresponding to `FirehoseEvent` wrappers around the Atmosphere Lexicon records. The `action` field (`Create`, `Update`, `Delete`) dictates the operation.

### 1. `BiosampleEvent` (for `com.decodingus.atmosphere.biosample`)

**Example `Create` Payload:**

```json
{
  "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
  "atCid": "bafyreihp47vj6t24z4k3f2f5vj4b5t3g2d5c3v2h5j4k3l2m5n6o4p3q2r",
  "action": "Create",
  "payload": {
    "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
    "meta": {
      "version": 1,
      "createdAt": "2025-12-07T10:00:00Z"
    },
    "sampleAccession": "BGS-SAMPLE-001",
    "donorIdentifier": "Subject-Alice-1",
    "citizenDid": "did:plc:alice123",
    "description": "Blood sample from Alice's WGS",
    "centerName": "Home Lab BGS Node",
    "sex": "Female",
    "haplogroups": {
      "yDna": {
        "haplogroupName": "H1",
        "score": 0.99
      },
      "mtDna": {
        "haplogroupName": "K1a10",
        "score": 0.98
      }
    },
    "sequenceRunRefs": [],
    "genotypeRefs": [],
    "populationBreakdownRef": null,
    "strProfileRef": null
  }
}
```

### 2. `SequenceRunEvent` (for `com.decodingus.atmosphere.sequencerun`)

**Example `Create` Payload:**

```json
{
  "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.sequencerun/abc123xyz",
  "atCid": "bafyreiaabcdefghijklmnopqrstuvwxyz0123456789",
  "action": "Create",
  "payload": {
    "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.sequencerun/abc123xyz",
    "meta": {
      "version": 1,
      "createdAt": "2025-12-07T11:00:00Z"
    },
    "biosampleRef": "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
    "platformName": "ILLUMINA",
    "instrumentModel": "NovaSeq 6000",
    "instrumentId": "SN0001",
    "testType": "WGS",
    "libraryLayout": "PAIRED",
    "totalReads": 850000000,
    "readLength": 150,
    "meanInsertSize": 350.5,
    "runDate": "2025-10-15T09:00:00Z",
    "files": [
      {
        "fileName": "alice_wgs.fastq.gz",
        "fileSizeBytes": 50000000000,
        "fileFormat": "FASTQ",
        "checksum": "sha256-...",
        "checksumAlgorithm": "SHA-256",
        "location": "/data/alice/alice_wgs.fastq.gz"
      }
    ],
    "alignmentRefs": []
  }
}
```

### 3. `AlignmentEvent` (for `com.decodingus.atmosphere.alignment`)

**Example `Create` Payload:**

```json
{
  "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.alignment/def456uvw",
  "atCid": "bafyreic1d2e3f4g5h6i7j8k9l0m1n2o3p4q5r6s7t8u9v0w",
  "action": "Create",
  "payload": {
    "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.alignment/def456uvw",
    "meta": {
      "version": 1,
      "createdAt": "2025-12-07T12:00:00Z"
    },
    "sequenceRunRef": "at://did:plc:alice123/com.decodingus.atmosphere.sequencerun/abc123xyz",
    "biosampleRef": "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
    "referenceBuild": "GRCh38",
    "aligner": "BWA-MEM 0.7.17",
    "variantCaller": "GATK HaplotypeCaller 4.2",
    "files": [
      {
        "fileName": "alice_wgs.cram",
        "fileSizeBytes": 20000000000,
        "fileFormat": "CRAM",
        "checksum": "sha256-...",
        "checksumAlgorithm": "SHA-256",
        "location": "/data/alice/alice_wgs.cram"
      }
    ],
    "metrics": {
      "genomeTerritory": 3000000000,
      "meanCoverage": 35.5,
      "medianCoverage": 30.0,
      "sdCoverage": 10.2,
      "pctExcDupe": 0.05,
      "pctExcMapq": 0.01,
      "pct10x": 0.95,
      "pct20x": 0.90,
      "pct30x": 0.85,
      "hetSnpSensitivity": 0.99
    }
  }
}
```

### 4. `AtmosphereProjectEvent` (for `com.decodingus.atmosphere.project`)

**Example `Create` Payload:**

```json
{
  "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.project/my-family-project",
  "atCid": "bafyreidf8w9x7y6z5a4b3c2d1e0f9g8h7i6j5k4l3m2n1o0p9q8r7s6t5u4v3w2",
  "action": "Create",
  "payload": {
    "atUri": "at://did:plc:alice123/com.decodingus.atmosphere.project/my-family-project",
    "meta": {
      "version": 1,
      "createdAt": "2025-12-07T13:00:00Z"
    },
    "projectName": "Alice's Family Tree Project",
    "description": "Research project on the maternal lineage of the Alice family.",
    "administrator": "did:plc:alice123",
    "memberRefs": [
      "at://did:plc:alice123/com.decodingus.atmosphere.biosample/3jui7q2lx",
      "at://did:plc:alice123/com.decodingus.atmosphere.biosample/other-sample"
    ]
  }
}
```

### Key Fields (Atmosphere Events)

| Field | Required | Description |
|-------|----------|-------------|
| `atUri` | Yes | AT Protocol URI - canonical identifier for the specific record |
| `atCid` | Yes | Content Identifier for optimistic locking / version tracking |
| `action` | Yes | Operation type (`Create`, `Update`, `Delete`) |
| `payload` | Yes (for Create/Update) | The specific Lexicon record (e.g., `BiosampleRecord`, `SequenceRunRecord`) |
| `payload.meta.createdAt` | Yes | Timestamp of record creation |
| `payload.biosampleRef` | Yes (for child records) | AT URI of the parent biosample |
| `payload.sequenceRunRef` | Yes (for Alignment) | AT URI of the parent sequence run |

---

## PDS Registration

Before syncing data, PDS instances must be registered:

**Endpoint:** `POST /api/registerPDS`

```json
{
  "did": "did:plc:abc123",
  "handle": "researcher.bsky.social",
  "pdsUrl": "https://pds.example.com",
  "rToken": "auth-token-from-edge-app"
}
```

The registration process:
1. Verifies PDS is reachable via `com.atproto.sync.getLatestCommit`
2. Stores DID, PDS URL, and initial sync cursor
3. Enables the Rust sync cluster to poll for updates

### PDS Lease Management

For parallel sync processing, the `pds_registrations` table includes:
- `leased_by_instance_id`: Which sync worker owns this PDS
- `lease_expires_at`: Lease expiration for failover
- `processing_status`: idle | processing | error

---

## Database Schema

### Tables

| Table | Purpose |
|-------|---------|
| `citizen_biosample` | Citizen/Atmosphere biosample records |
| `specimen_donor` | Physical persons (donors) - linked via `specimen_donor_id` FK |
| `project` | Research projects grouping biosamples |
| `sequence_library` | Sequence run records |
| `sequence_file` | Sequence file metadata |
| `alignment_metadata` | Alignment metadata and metrics |
| `pds_registrations` | Registered PDS instances for sync |
| `publication_citizen_biosample` | Links biosamples to publications |
| `citizen_biosample_original_haplogroup` | Publication-reported haplogroups |

### Key Columns on `citizen_biosample` (and other Atmosphere-enabled tables)

| Column | Type | Purpose |
|--------|------|---------|
| `at_uri` | VARCHAR | AT Protocol canonical identifier |
| `at_cid` | VARCHAR | Version for optimistic locking |
| `specimen_donor_id` | INT FK | Link to physical donor |
| `deleted` | BOOLEAN | Soft delete flag |
| `y_haplogroup` | JSONB | Full HaplogroupResult with scoring |
| `mt_haplogroup` | JSONB | Full HaplogroupResult with scoring |

---

## Integration Roadmap

### Phase 1 (Legacy): Direct REST API

*   **Mechanism:** Synchronous HTTP POST
*   **Flow:** `Edge App` → `CitizenBiosampleController` (`/api/external-biosamples`, `/api/projects`) → `CitizenBiosampleService` → `CitizenBiosampleEventHandler` → `DB`
*   **Status:** Functional for existing integrations. Use the new `/api/firehose/event` for all new Atmosphere Lexicon-based events.

### Phase 2: Asynchronous Event Ingestion (Kafka)

*   **Mechanism:** Message Queue
*   **Flow:** `Edge App` → `Kafka Topic` → `DecodingUs Kafka Consumer` → `AtmosphereEventHandler` → `DB`
*   **Change:** Edge App uses Kafka Producer; DecodingUs adds Kafka Consumer service. Processes raw Atmosphere Lexicon events.
*   **Benefits:** Decoupled; handles traffic bursts; high resilience.

### Phase 3: Decentralized AppView (AT Protocol Firehose)

*   **Mechanism:** AT Protocol Firehose subscription
*   **Flow:** `Edge App` → `User's PDS` → `AT Proto Relay` → `DecodingUs Firehose Consumer` → `AtmosphereEventHandler` → `DB`
*   **Change:** Edge App writes directly to PDS using `com.decodingus.atmosphere.*` Lexicon records; DecodingUs becomes a passive indexer.
*   **Benefits:** True user data ownership; interoperability with AT Protocol ecosystem.

---

## Deployment Checklist

### For New Atmosphere Integrations (using `/api/firehose/event`)

1. **API Key:** Configure in AWS Secrets Manager (prod) or `application.conf` (dev)
2. **Database:** Ensure all relevant evolutions (`25.sql` and prior) have been applied to update table schemas (e.g., `at_uri`, `at_cid` on `sequence_library`, new fields on `alignment_metadata`).
3. **Edge App Config:** Set DecodingUs API URL and API key. Configure the Edge App to construct and send `FirehoseEvent` JSON payloads as per the Atmosphere Lexicon.
4. **Test:** POST example `FirehoseEvent` payloads (e.g., `BiosampleEvent`, `SequenceRunEvent`, `AlignmentEvent`, `AtmosphereProjectEvent`) to `/api/firehose/event`.
5. **Verify:** Check `citizen_biosample`, `specimen_donor`, `sequence_library`, `sequence_file`, `alignment_metadata`, and `project` tables for correctly ingested and linked data.

### Swagger UI

API documentation available at: `/api/docs`

Documented endpoints now include:
- **Generic Atmosphere Event Processor:** `POST /api/firehose/event`
- Legacy Citizen Biosamples (Create, Update, Delete)
- Legacy Projects (Create, Update, Delete)
- References, Haplogroups, Coverage, Sequencer APIs
