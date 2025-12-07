# PDS Workbench Biosample Flow Design

## Overview

This proposal describes a redesigned biosample management flow where researchers use the **Decoding-Us Navigator** desktop application as their primary interface for managing external biosamples, with data flowing naturally through their Personal Data Store (PDS) to the DecodingUs AppView.

### Current State

Today, researchers submit external biosamples via dedicated REST APIs:
- `POST /api/private/external/biosamples` (traditional biosample API)
- `POST /api/external-biosamples` (citizen/firehose-aware API)

These APIs require:
1. Manual JSON payload construction
2. Direct API authentication
3. No local preview or validation
4. No workspace organization
5. Disconnect between local analysis and remote submission

### Proposed State

Researchers use Navigator's workspace to:
1. Organize biosamples into projects locally
2. Import and analyze BAM/CRAM files with full GATK pipeline
3. Compose biosample metadata with publication linkage
4. Sync biosamples to their PDS (creating Atmosphere Lexicon records)
5. DecodingUs AppView automatically ingests via Firehose subscription

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        RESEARCHER WORKFLOW                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─────────────────────┐     ┌─────────────────────┐                    │
│  │  BAM/CRAM Files     │────▶│  Navigator Desktop  │                    │
│  │  (Local Analysis)   │     │  Application        │                    │
│  └─────────────────────┘     └──────────┬──────────┘                    │
│                                         │                                │
│                              ┌──────────▼──────────┐                    │
│                              │  Local Workspace    │                    │
│                              │  - Projects         │                    │
│                              │  - Biosamples       │                    │
│                              │  - Analysis Cache   │                    │
│                              └──────────┬──────────┘                    │
│                                         │                                │
│                              ┌──────────▼──────────┐                    │
│                              │  PDS Sync Engine    │                    │
│                              │  (AT Protocol)      │                    │
│                              └──────────┬──────────┘                    │
│                                         │                                │
└─────────────────────────────────────────┼────────────────────────────────┘
                                          │
                               ┌──────────▼──────────┐
                               │  Researcher's PDS   │
                               │  - workspace        │
                               │  - biosample(s)     │
                               │  - sequencerun(s)   │
                               │  - alignment(s)     │
                               │  - strProfile(s)    │
                               └──────────┬──────────┘
                                          │
                               ┌──────────▼──────────┐
                               │  AT Protocol        │
                               │  Firehose           │
                               └──────────┬──────────┘
                                          │
                               ┌──────────▼──────────┐
                               │  DecodingUs AppView │
                               │  (Backend)          │
                               └──────────────────────┘
```

---

## Record Flow Mapping

### From Navigator Analysis to Atmosphere Lexicon Records

| Navigator Concept | Atmosphere Record | Notes |
|:---|:---|:---|
| Workspace | `workspace` | Root container in PDS |
| Project | `project` | Aggregates biosamples for research |
| Biosample | `biosample` | Core sample with donor metadata |
| Library Analysis | `sequencerun` | From BAM/CRAM header parsing |
| WGS Metrics | `alignment` | Coverage stats, callable loci |
| Haplogroup Results | `biosample.haplogroups` | Y-DNA and mtDNA assignments |
| STR Extraction | `strProfile` | If STR calling enabled |
| Publication Link | External reference | Via `publication` field in request |

### Analysis-to-Record Mapping

```
Navigator Analysis Pipeline          Atmosphere Records Created
─────────────────────────────        ─────────────────────────────

┌─────────────────────────┐
│ Import BAM/CRAM         │
│ (drag-drop or picker)   │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐         ┌─────────────────────────┐
│ Library Statistics      │────────▶│ sequencerun             │
│ - Platform detection    │         │ - platformName          │
│ - Read length           │         │ - instrumentModel       │
│ - Insert size           │         │ - instrumentId          │
│ - @RG header parsing    │         │ - testType              │
└───────────┬─────────────┘         │ - files[]               │
            │                       └─────────────────────────┘
            ▼
┌─────────────────────────┐         ┌─────────────────────────┐
│ WGS Metrics             │────────▶│ alignment               │
│ - Mean coverage         │         │ - referenceBuild        │
│ - Depth thresholds      │         │ - aligner               │
│ - Per-contig stats      │         │ - metrics.meanCoverage  │
│ - Callable loci         │         │ - metrics.contigs[]     │
└───────────┬─────────────┘         └─────────────────────────┘
            │
            ▼
┌─────────────────────────┐         ┌─────────────────────────┐
│ Haplogroup Analysis     │────────▶│ biosample.haplogroups   │
│ - Y-DNA tree matching   │         │ - yDna.haplogroupName   │
│ - mtDNA tree matching   │         │ - yDna.lineagePath[]    │
│ - Private SNP detection │         │ - yDna.privateVariants  │
└───────────┬─────────────┘         │ - mtDna.*               │
            │                       └─────────────────────────┘
            ▼
┌─────────────────────────┐         ┌─────────────────────────┐
│ STR Extraction          │────────▶│ strProfile              │
│ (Optional, from WGS)    │         │ - markers[]             │
│ - HipSTR/GangSTR        │         │ - derivationMethod      │
└─────────────────────────┘         │ - source: WGS_DERIVED   │
                                    └─────────────────────────┘
```

---

## Data Model Extensions

### Local Workspace State (Navigator)

Navigator needs to track sync state for each local entity:

```scala
case class SyncState(
  atUri: Option[String],        // AT URI if synced to PDS
  atCid: Option[String],        // Content ID for versioning
  syncStatus: SyncStatus,       // Pending, Synced, Modified, Conflict
  lastSyncedAt: Option[Instant],
  localVersion: Int,            // Local modification counter
  remoteVersion: Option[Int]    // PDS meta.version
)

enum SyncStatus:
  case NotSynced    // Never pushed to PDS
  case Pending      // Queued for sync
  case Syncing      // Currently uploading
  case Synced       // Up to date with PDS
  case Modified     // Local changes since last sync
  case Conflict     // Both local and remote changed
  case Error        // Sync failed
```

### Biosample Composition Model

Navigator needs a richer model for composing biosamples before sync:

```scala
case class ComposedBiosample(
  // Core identity
  localId: UUID,
  sampleAccession: String,
  donorIdentifier: Option[String],

  // Donor metadata
  description: Option[String],
  sex: Option[BiologicalSex],
  location: Option[GeoCoordinate],

  // Analysis results (from Navigator pipeline)
  analysisResults: Option[AnalysisResults],

  // Publication linkage
  publication: Option[PublicationInfo],

  // Sync state
  syncState: SyncState,

  // Project membership (local organization)
  projectIds: Set[UUID]
)

case class AnalysisResults(
  libraryStats: Option[LibraryStatistics],
  wgsMetrics: Option[WgsMetrics],
  callableLoci: Option[CallableLociSummary],
  yDnaHaplogroup: Option[HaplogroupResult],
  mtDnaHaplogroup: Option[HaplogroupResult],
  strProfile: Option[StrProfile],
  privateSnps: Option[PrivateSnpReport]
)

case class PublicationInfo(
  doi: Option[String],
  pubmedId: Option[String],
  title: Option[String],
  authors: Option[String],
  year: Option[Int],
  originalHaplogroups: Option[OriginalHaplogroupInfo]
)
```

---

## Navigator UI Modifications

### 1. Enhanced Workspace View

**Current**: Simple list of projects and biosamples
**Proposed**: Rich workspace with sync status indicators

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Workspace                                              [↻ Sync All]    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  🔵 PDS: did:plc:researcher123                      Connected ✓        │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━   │
│                                                                         │
│  📁 Viking Age Study (12 samples)                    [⬆ 3 pending]     │
│  │                                                                      │
│  ├── 🧬 VIK-001  R-Z284    ✓ Synced                                    │
│  ├── 🧬 VIK-002  I-M253    ⬆ Modified (haplogroup updated)             │
│  ├── 🧬 VIK-003  R-U106    ○ Not synced                                │
│  └── ...                                                                │
│                                                                         │
│  📁 Iron Age Britain (8 samples)                     [✓ All synced]    │
│  │                                                                      │
│  └── ...                                                                │
│                                                                         │
│  📁 Unpublished Analysis (draft)                     [○ Local only]    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2. Biosample Composition Panel

New panel for composing biosample metadata before sync:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Biosample: VIK-003                                    [Save] [Sync ⬆]  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─ Identity ─────────────────────────────────────────────────────────┐ │
│  │  Sample Accession: [VIK-003____________]                           │ │
│  │  Donor Identifier: [DONOR-VIK-003______]  (optional)               │ │
│  │  Description:      [Ancient DNA from Birka burial site_________]   │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  ┌─ Donor Metadata ───────────────────────────────────────────────────┐ │
│  │  Biological Sex:   (•) Male  ( ) Female  ( ) Unknown               │ │
│  │  Location:         [59.3369°N, 17.5544°E]  📍                      │ │
│  │  Date Range:       [750] to [850] CE                               │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  ┌─ Analysis Results (from Navigator) ────────────────────────────────┐ │
│  │  ✓ Library Stats     Platform: Illumina NovaSeq                    │ │
│  │  ✓ WGS Metrics       Coverage: 32.5x                               │ │
│  │  ✓ Y-DNA Haplogroup  R-U106 (score: 0.97)                          │ │
│  │  ✓ mtDNA Haplogroup  H1a (score: 0.99)                             │ │
│  │  ○ STR Profile       [Run STR Extraction]                          │ │
│  │  ✓ Private SNPs      3 novel variants detected                     │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  ┌─ Publication Link (optional) ──────────────────────────────────────┐ │
│  │  DOI:      [10.1038/s41586-024-00001-1]  [🔍 Lookup]               │ │
│  │  PubMed:   [39012345]                                              │ │
│  │  Title:    Ancient Genomics of Viking Age Scandinavia              │ │
│  │  Authors:  Smith et al.                                            │ │
│  │                                                                     │ │
│  │  Original Haplogroups (from paper):                                │ │
│  │    Y-DNA: [R1a1a1_______]  mtDNA: [H1a__________]                  │ │
│  │    Notes: [Supplementary Table S2, Sample ID: BKA-003]             │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  ┌─ Sync Status ──────────────────────────────────────────────────────┐ │
│  │  Status: ○ Not yet synced to PDS                                   │ │
│  │  [  Sync to PDS  ]  [  Preview JSON  ]                             │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3. Bulk Import Wizard

For researchers importing multiple samples from a publication:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Bulk Import Wizard                                          Step 2/4   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Publication: 10.1038/s41586-024-00001-1                               │
│  "Ancient Genomics of Viking Age Scandinavia"                          │
│                                                                         │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━   │
│                                                                         │
│  Import CSV with sample metadata:                                       │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  [sample_metadata.csv]                      [Browse...]         │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Column Mapping:                                                        │
│  ┌────────────────────┬────────────────────────────────────────────┐   │
│  │ CSV Column         │ Maps To                                    │   │
│  ├────────────────────┼────────────────────────────────────────────┤   │
│  │ sample_id          │ [Sample Accession    ▼]                    │   │
│  │ sex                │ [Biological Sex      ▼]                    │   │
│  │ lat                │ [Latitude            ▼]                    │   │
│  │ lon                │ [Longitude           ▼]                    │   │
│  │ y_haplogroup       │ [Original Y-DNA      ▼]                    │   │
│  │ mt_haplogroup      │ [Original mtDNA      ▼]                    │   │
│  │ bam_path           │ [BAM File Path       ▼]                    │   │
│  └────────────────────┴────────────────────────────────────────────┘   │
│                                                                         │
│  Preview (first 5 rows):                                               │
│  ┌────────┬─────┬─────────┬──────────┬────────────────────────────┐   │
│  │ ID     │ Sex │ Y-Hg    │ mt-Hg    │ BAM                        │   │
│  ├────────┼─────┼─────────┼──────────┼────────────────────────────┤   │
│  │ VIK-01 │ M   │ R-Z284  │ H1a      │ /data/viking/VIK-01.bam    │   │
│  │ VIK-02 │ M   │ I-M253  │ U5b      │ /data/viking/VIK-02.bam    │   │
│  │ VIK-03 │ F   │ -       │ H1c      │ /data/viking/VIK-03.bam    │   │
│  │ VIK-04 │ M   │ R-U106  │ K1a      │ /data/viking/VIK-04.bam    │   │
│  │ VIK-05 │ M   │ N-L550  │ H6a      │ /data/viking/VIK-05.bam    │   │
│  └────────┴─────┴─────────┴──────────┴────────────────────────────┘   │
│                                                                         │
│  [◀ Back]                                              [Next: Analyze ▶]│
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4. Sync Status Dashboard

Global view of PDS sync state:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  PDS Sync Dashboard                                                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Connection: did:plc:researcher123 @ bsky.social         ✓ Connected   │
│                                                                         │
│  ┌─ Sync Summary ─────────────────────────────────────────────────────┐ │
│  │                                                                     │ │
│  │  Total Biosamples:  156                                            │ │
│  │  ├── ✓ Synced:      142 (91%)                                      │ │
│  │  ├── ⬆ Pending:       8 (5%)                                       │ │
│  │  ├── ⚠ Conflicts:     2 (1%)                                       │ │
│  │  └── ○ Local only:    4 (3%)                                       │ │
│  │                                                                     │ │
│  │  Last sync: 2025-12-07 14:30:22 UTC                                │ │
│  │                                                                     │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  ┌─ Pending Changes ──────────────────────────────────────────────────┐ │
│  │                                                                     │ │
│  │  ☑ VIK-002    Modified: Haplogroup refined R-Z284 → R-Z284>BY3456  │ │
│  │  ☑ VIK-015    New: Ready for initial sync                          │ │
│  │  ☑ VIK-016    New: Ready for initial sync                          │ │
│  │  ☐ IAB-003    Modified: Coverage updated (re-analysis)             │ │
│  │  ...                                                                │ │
│  │                                                                     │ │
│  │  [Select All]  [Deselect All]              [Sync Selected (3) ⬆]   │ │
│  │                                                                     │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  ┌─ Conflicts (require resolution) ───────────────────────────────────┐ │
│  │                                                                     │ │
│  │  ⚠ ANC-007    Local: mtDNA H1a    Remote: mtDNA H1a1 (updated by   │ │
│  │               AppView haplogroup refinement)                        │ │
│  │               [Keep Local] [Accept Remote] [View Diff]              │ │
│  │                                                                     │ │
│  │  ⚠ ANC-012    Local: deleted      Remote: still exists             │ │
│  │               [Confirm Delete] [Restore Local]                      │ │
│  │                                                                     │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5. Publication Lookup Integration

DOI/PubMed lookup with auto-population:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Publication Lookup                                                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Enter DOI or PubMed ID: [10.1038/s41586-024-00001-1____] [🔍 Search]   │
│                                                                         │
│  ┌─ Found Publication ────────────────────────────────────────────────┐ │
│  │                                                                     │ │
│  │  Title:   Ancient Genomics of Viking Age Scandinavia               │ │
│  │  Authors: Smith J, Jones A, Brown B, et al.                        │ │
│  │  Journal: Nature (2024)                                            │ │
│  │  DOI:     10.1038/s41586-024-00001-1                               │ │
│  │  PubMed:  39012345                                                 │ │
│  │                                                                     │ │
│  │  Abstract: (truncated)                                             │ │
│  │  We present genome-wide data from 150 ancient individuals from    │ │
│  │  Viking Age Scandinavia, revealing complex patterns of...          │ │
│  │                                                                     │ │
│  │  ┌─ Already in DecodingUs ──────────────────────────────────────┐  │ │
│  │  │  ✓ This publication exists in our database                   │  │ │
│  │  │  Current samples linked: 127                                 │  │ │
│  │  │  [View Publication Page]                                     │  │ │
│  │  └──────────────────────────────────────────────────────────────┘  │ │
│  │                                                                     │ │
│  │  [Use This Publication]                              [Cancel]      │ │
│  │                                                                     │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## PDS Sync Protocol

### Record Creation Flow

When syncing a new biosample to PDS:

```
Navigator                           PDS                          AppView
────────                           ───                          ───────
    │                               │                              │
    │  1. Build Atmosphere records  │                              │
    │  ────────────────────────▶    │                              │
    │                               │                              │
    │  POST com.atproto.repo.createRecord                         │
    │  collection: com.decodingus.atmosphere.biosample            │
    │  ─────────────────────────────▶                             │
    │                               │                              │
    │  ◀─ { uri, cid }              │                              │
    │                               │                              │
    │  2. Store atUri/atCid locally │                              │
    │                               │                              │
    │                               │  Firehose event              │
    │                               │  ─────────────────────────▶  │
    │                               │                              │
    │                               │     Process biosample        │
    │                               │     Create DB records        │
    │                               │     Link to publication      │
    │                               │     Queue haplogroup work    │
    │                               │                              │
```

### Multi-Record Transaction

A complete biosample with sequence data requires multiple records:

```scala
// Pseudo-code for sync operation
def syncBiosampleToPds(biosample: ComposedBiosample): Future[SyncResult] = {
  for {
    // 1. Create sequence run record first (child)
    sequenceRunUri <- createSequenceRunRecord(biosample.analysisResults)

    // 2. Create alignment record (grandchild)
    alignmentUri <- createAlignmentRecord(biosample.analysisResults, sequenceRunUri)

    // 3. Create STR profile if available
    strProfileUri <- biosample.analysisResults.strProfile match {
      case Some(str) => createStrProfileRecord(str).map(Some(_))
      case None => Future.successful(None)
    }

    // 4. Create biosample record with references
    biosampleUri <- createBiosampleRecord(
      biosample,
      sequenceRunRefs = List(sequenceRunUri),
      strProfileRef = strProfileUri
    )

    // 5. Update workspace record to include new biosample
    _ <- updateWorkspaceRecord(biosampleUri)

  } yield SyncResult.Success(biosampleUri)
}
```

### Conflict Resolution Strategy

```scala
enum ConflictResolution:
  case KeepLocal      // Overwrite PDS with local version
  case AcceptRemote   // Discard local changes, pull from PDS
  case Merge          // Attempt automatic merge (field-level)
  case Manual         // Require user intervention

def resolveConflict(
  local: ComposedBiosample,
  remote: AtmosphereBiosample
): ConflictResolution = {

  // AppView-computed fields always win (haplogroup refinement)
  val appViewFields = Set("haplogroups.yDna", "haplogroups.mtDna")

  // If only AppView fields changed remotely, merge
  if (remote.meta.lastModifiedField.exists(appViewFields.contains)) {
    ConflictResolution.Merge
  }
  // If local has newer analysis results, prefer local
  else if (local.analysisResults.isDefined &&
           local.syncState.localVersion > remote.meta.version) {
    ConflictResolution.KeepLocal
  }
  // Otherwise require manual resolution
  else {
    ConflictResolution.Manual
  }
}
```

---

## API Integration

### DecodingUs Backend Changes

The existing `CitizenBiosampleController` and Firehose handler already support this flow. Minor enhancements needed:

1. **Publication Lookup Endpoint** (new)
   ```
   GET /api/publications/lookup?doi={doi}&pubmed={pubmedId}
   ```
   Returns publication metadata for Navigator's lookup feature.

2. **Batch Validation Endpoint** (new)
   ```
   POST /api/external-biosamples/validate
   ```
   Validates a batch of biosample records without creating them.

3. **Sync Status Endpoint** (new)
   ```
   GET /api/external-biosamples/sync-status?atUris[]={uri1}&atUris[]={uri2}
   ```
   Returns current state of biosamples in AppView (for conflict detection).

### Navigator API Client

New module for AT Protocol and DecodingUs API integration:

```scala
// AT Protocol client for PDS operations
trait PdsClient {
  def createRecord[T](collection: String, record: T): Future[CreateRecordResponse]
  def updateRecord[T](uri: String, record: T): Future[UpdateRecordResponse]
  def deleteRecord(uri: String): Future[Unit]
  def getRecord[T](uri: String): Future[Option[T]]
  def listRecords[T](collection: String, cursor: Option[String]): Future[ListRecordsResponse[T]]
}

// DecodingUs API client for auxiliary operations
trait DecodingUsClient {
  def lookupPublication(doi: Option[String], pubmedId: Option[String]): Future[Option[Publication]]
  def validateBiosamples(biosamples: Seq[BiosampleValidation]): Future[ValidationResult]
  def getSyncStatus(atUris: Seq[String]): Future[Map[String, SyncStatus]]
}
```

---

## Implementation Phases

### Phase 1: Local Composition (MVP)
- Biosample composition panel in Navigator
- Publication lookup integration
- Local-only save (no PDS sync yet)
- Export to JSON for manual API submission

### Phase 2: PDS Sync
- AT Protocol authentication in Navigator
- Single-record sync (biosample only)
- Basic conflict detection
- Sync status indicators in UI

### Phase 3: Full Record Graph
- Multi-record sync (sequencerun, alignment, strProfile)
- Workspace record management
- Bulk sync operations
- Background sync with retry

### Phase 4: Bidirectional Sync
- Pull changes from PDS (AppView updates)
- Automatic conflict resolution for AppView-computed fields
- Real-time sync status updates
- Offline queue with eventual consistency

---

## Security Considerations

### Authentication Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        OAuth 2.0 + DPoP Flow                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. User clicks "Connect PDS" in Navigator                             │
│  2. Navigator opens browser to PDS authorization URL                    │
│  3. User authenticates with PDS (handle + password or passkey)         │
│  4. PDS redirects back to Navigator with auth code                      │
│  5. Navigator exchanges code for access token + DPoP key               │
│  6. Navigator stores refresh token securely (OS keychain)              │
│  7. Navigator uses access token for API calls                          │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Data Privacy

- All genomic data stays local until explicit sync
- Only Atmosphere record metadata synced to PDS
- File locations can be local paths (not synced) or remote URLs
- User controls what gets published to their PDS

---

## Benefits

### For Researchers
1. **Unified workflow**: Analysis and submission in one tool
2. **Local preview**: Review and validate before publishing
3. **Batch operations**: Import and sync multiple samples efficiently
4. **Offline capable**: Work without internet, sync later
5. **Version control**: Track changes, resolve conflicts

### For DecodingUs
1. **Reduced API complexity**: Firehose handles all ingestion
2. **Better data quality**: Navigator validates before sync
3. **Richer metadata**: Full analysis results included
4. **Provenance tracking**: Clear audit trail via AT Protocol

### For the Ecosystem
1. **Data sovereignty**: Researchers own their PDS data
2. **Interoperability**: Standard AT Protocol records
3. **Decentralization**: No single point of failure
4. **Transparency**: Public record of contributions

---

## Cross-Researcher Deduplication

### The Problem

Many researchers work with the same canonical datasets:
- **1000 Genomes Project**: ~3,200 samples widely used in population genetics
- **Human Genome Diversity Project (HGDP)**: ~900 samples
- **Simons Genome Diversity Project**: ~300 samples
- **Ancient DNA publications**: Shared samples across meta-analyses

When multiple researchers sync these samples to their PDS, the AppView receives duplicate records for the same biological sample from different sources.

### Deduplication Model

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        CANONICAL SAMPLE REGISTRY                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Canonical Sample: HG00096 (1000 Genomes)                               │
│  ══════════════════════════════════════════                             │
│                                                                          │
│  ┌─ Authoritative Identity ─────────────────────────────────────────┐   │
│  │  Canonical Accession: HG00096                                    │   │
│  │  Registry: 1000GENOMES                                           │   │
│  │  ENA Accession: SAMEA3302682                                     │   │
│  │  BioSample: SAMN00001598                                         │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌─ Researcher Contributions ───────────────────────────────────────┐   │
│  │                                                                   │   │
│  │  did:plc:alice   →  at://did:plc:alice/.../biosample/hg00096    │   │
│  │                      Analysis: 32x coverage, haplogroup R-L21   │   │
│  │                      Files: local analysis only                  │   │
│  │                                                                   │   │
│  │  did:plc:bob     →  at://did:plc:bob/.../biosample/1kg-hg00096  │   │
│  │                      Analysis: 45x coverage (deep WGS)          │   │
│  │                      Files: s3://bob-lab/HG00096.cram           │   │
│  │                                                                   │   │
│  │  did:plc:carol   →  at://did:plc:carol/.../biosample/hg00096    │   │
│  │                      Analysis: haplogroup R-L21>FT12345 (novel) │   │
│  │                      STR Profile: Y-111                          │   │
│  │                                                                   │   │
│  └───────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌─ Merged View (AppView Computed) ─────────────────────────────────┐   │
│  │  Best Coverage: 45x (from did:plc:bob)                           │   │
│  │  Refined Haplogroup: R-L21>FT12345 (from did:plc:carol)          │   │
│  │  STR Profile: Y-111 markers (from did:plc:carol)                 │   │
│  │  Contributing Researchers: 3                                     │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Canonical Accession Resolution

The AppView maintains a registry of known canonical sample identifiers:

```scala
case class CanonicalSampleRegistry(
  registryCode: String,         // "1000GENOMES", "HGDP", "SGDP", "ENA", "NCBI"
  pattern: Regex,               // Pattern to match accessions
  normalizeFn: String => String // Normalize variations (HG00096 vs hg00096)
)

val knownRegistries = Seq(
  CanonicalSampleRegistry(
    "1000GENOMES",
    """^(HG|NA)\d{5}$""".r,
    _.toUpperCase
  ),
  CanonicalSampleRegistry(
    "HGDP",
    """^HGDP\d{5}$""".r,
    _.toUpperCase
  ),
  CanonicalSampleRegistry(
    "ENA",
    """^SAM[END]A?\d+$""".r,
    _.toUpperCase
  ),
  CanonicalSampleRegistry(
    "NCBI_BIOSAMPLE",
    """^SAMN\d+$""".r,
    _.toUpperCase
  )
)

def resolveCanonicalId(sampleAccession: String): Option[CanonicalIdentity] = {
  knownRegistries.collectFirst {
    case reg if reg.pattern.matches(sampleAccession) =>
      CanonicalIdentity(
        registry = reg.registryCode,
        canonicalAccession = reg.normalizeFn(sampleAccession)
      )
  }
}
```

### Database Schema for Deduplication

```sql
-- Canonical sample identity (one per biological sample)
CREATE TABLE canonical_sample (
  id SERIAL PRIMARY KEY,
  registry VARCHAR(50) NOT NULL,           -- '1000GENOMES', 'HGDP', 'ENA'
  canonical_accession VARCHAR(255) NOT NULL,

  -- Cross-references to other registries
  ena_accession VARCHAR(50),
  ncbi_biosample VARCHAR(50),

  -- Merged/computed best values
  best_coverage FLOAT,
  best_coverage_source_at_uri TEXT,
  refined_y_haplogroup TEXT,
  refined_y_haplogroup_source_at_uri TEXT,
  refined_mt_haplogroup TEXT,
  refined_mt_haplogroup_source_at_uri TEXT,

  -- Tracking
  contributor_count INT DEFAULT 0,
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),

  UNIQUE(registry, canonical_accession)
);

-- Link between canonical samples and researcher contributions
CREATE TABLE canonical_sample_contribution (
  id SERIAL PRIMARY KEY,
  canonical_sample_id INT REFERENCES canonical_sample(id),

  -- The researcher's PDS record
  contributor_did TEXT NOT NULL,
  biosample_at_uri TEXT NOT NULL,
  biosample_at_cid TEXT,

  -- What this contribution provides
  coverage FLOAT,
  y_haplogroup TEXT,
  mt_haplogroup TEXT,
  has_str_profile BOOLEAN DEFAULT FALSE,
  has_private_variants BOOLEAN DEFAULT FALSE,

  -- File availability
  files_accessible BOOLEAN DEFAULT FALSE,  -- Can AppView access the files?

  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),

  UNIQUE(canonical_sample_id, contributor_did)
);

-- Index for fast lookup during Firehose processing
CREATE INDEX idx_canonical_sample_accession
  ON canonical_sample(registry, canonical_accession);
```

### Firehose Event Handling with Deduplication

```scala
def handleBiosampleCreate(event: BiosampleCreateEvent): Future[ProcessingResult] = {
  val biosample = event.record

  // 1. Check if this matches a canonical registry
  val canonicalId = resolveCanonicalId(biosample.sampleAccession)

  canonicalId match {
    case Some(canonical) =>
      // This is a known canonical sample (1KG, HGDP, etc.)
      handleCanonicalSampleContribution(canonical, biosample, event.citizenDid)

    case None =>
      // Novel sample - check for cross-researcher duplicates by other means
      handleNovelSample(biosample, event.citizenDid)
  }
}

def handleCanonicalSampleContribution(
  canonical: CanonicalIdentity,
  biosample: AtmosphereBiosample,
  contributorDid: String
): Future[ProcessingResult] = {

  for {
    // Find or create canonical sample record
    canonicalSample <- canonicalSampleRepo.findOrCreate(
      canonical.registry,
      canonical.canonicalAccession
    )

    // Record this researcher's contribution
    contribution <- contributionRepo.upsert(
      CanonicalSampleContribution(
        canonicalSampleId = canonicalSample.id,
        contributorDid = contributorDid,
        biosampleAtUri = biosample.atUri,
        biosampleAtCid = biosample.meta.atCid,
        coverage = biosample.extractCoverage(),
        yHaplogroup = biosample.haplogroups.flatMap(_.yDna.map(_.haplogroupName)),
        mtHaplogroup = biosample.haplogroups.flatMap(_.mtDna.map(_.haplogroupName)),
        hasStrProfile = biosample.strProfileRef.isDefined,
        hasPrivateVariants = biosample.hasPrivateVariants()
      )
    )

    // Recompute merged "best" values
    _ <- recomputeCanonicalSampleMergedValues(canonicalSample.id)

  } yield ProcessingResult.CanonicalContribution(
    canonicalSampleId = canonicalSample.id,
    isNewContributor = contribution.isNew,
    improvedFields = contribution.improvements
  )
}
```

### Merged Value Computation

When multiple researchers contribute data for the same canonical sample:

```scala
def recomputeCanonicalSampleMergedValues(canonicalSampleId: Int): Future[Unit] = {
  for {
    contributions <- contributionRepo.findByCanonicalSample(canonicalSampleId)

    // Best coverage = highest value
    bestCoverage = contributions
      .filter(_.coverage.isDefined)
      .maxByOption(_.coverage.get)

    // Best haplogroup = most refined (deepest tree depth)
    bestYHaplogroup = contributions
      .flatMap(c => c.yHaplogroup.map(h => (c, h)))
      .maxByOption { case (_, hg) => haplogroupTreeDepth(hg) }

    bestMtHaplogroup = contributions
      .flatMap(c => c.mtHaplogroup.map(h => (c, h)))
      .maxByOption { case (_, hg) => haplogroupTreeDepth(hg) }

    // Update canonical sample with merged values
    _ <- canonicalSampleRepo.update(
      canonicalSampleId,
      CanonicalSampleUpdate(
        bestCoverage = bestCoverage.flatMap(_.coverage),
        bestCoverageSourceAtUri = bestCoverage.map(_.biosampleAtUri),
        refinedYHaplogroup = bestYHaplogroup.map(_._2),
        refinedYHaplogroupSourceAtUri = bestYHaplogroup.map(_._1.biosampleAtUri),
        refinedMtHaplogroup = bestMtHaplogroup.map(_._2),
        refinedMtHaplogroupSourceAtUri = bestMtHaplogroup.map(_._1.biosampleAtUri),
        contributorCount = contributions.map(_.contributorDid).distinct.size
      )
    )
  } yield ()
}
```

### Navigator UI: Duplicate Detection

When a researcher imports a sample, Navigator checks for existing canonical samples:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Import Sample: HG00096                                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─ Canonical Sample Detected ────────────────────────────────────────┐ │
│  │                                                                     │ │
│  │  ⚠ This sample exists in the 1000 Genomes Project registry        │ │
│  │                                                                     │ │
│  │  Canonical ID:     HG00096                                         │ │
│  │  Registry:         1000 Genomes Project (Phase 3)                  │ │
│  │  ENA Accession:    SAMEA3302682                                    │ │
│  │  Population:       GBR (British)                                   │ │
│  │                                                                     │ │
│  │  ┌─ Existing Contributions in DecodingUs ───────────────────────┐  │ │
│  │  │  3 researchers have contributed analysis for this sample:    │  │ │
│  │  │                                                               │  │ │
│  │  │  • Best coverage: 45x (from did:plc:bob)                     │  │ │
│  │  │  • Y-DNA: R-L21 (2 contributors agree)                       │  │ │
│  │  │  • mtDNA: H1a (3 contributors agree)                         │  │ │
│  │  │  • STR Profile: Available (Y-111)                            │  │ │
│  │  └───────────────────────────────────────────────────────────────┘  │ │
│  │                                                                     │ │
│  │  Your contribution will be added to the merged record.             │ │
│  │                                                                     │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  What would you like to do?                                            │
│                                                                         │
│  (•) Add my analysis as a new contribution                             │
│      Your haplogroup calls and coverage will be compared with          │
│      existing data. Novel findings (deeper haplogroups, private        │
│      SNPs) will be highlighted.                                        │
│                                                                         │
│  ( ) Skip this sample (already well-characterized)                     │
│                                                                         │
│  ( ) Import anyway as a separate local sample                          │
│      (Will not sync to PDS)                                            │
│                                                                         │
│  [Continue Import]                                            [Cancel]  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Contribution Value Indicator

Show researchers what value their contribution adds:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Contribution Analysis: HG00096                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Your Analysis Results:                                                 │
│  ┌─────────────────────┬──────────────────┬────────────────────────┐   │
│  │ Field               │ Your Value       │ Current Best           │   │
│  ├─────────────────────┼──────────────────┼────────────────────────┤   │
│  │ Coverage            │ 32x              │ 45x (did:plc:bob)      │   │
│  │ Y-DNA Haplogroup    │ R-L21>FT54321 🆕 │ R-L21                  │   │
│  │ mtDNA Haplogroup    │ H1a              │ H1a (same)             │   │
│  │ STR Profile         │ Y-67             │ Y-111 (more markers)   │   │
│  │ Private Variants    │ 2 novel SNPs 🆕  │ None detected          │   │
│  └─────────────────────┴──────────────────┴────────────────────────┘   │
│                                                                         │
│  ┌─ Contribution Value ───────────────────────────────────────────────┐ │
│  │                                                                     │ │
│  │  ✓ Your Y-DNA haplogroup is MORE REFINED than current best        │ │
│  │    R-L21 → R-L21>FT54321 (new terminal SNP!)                       │ │
│  │                                                                     │ │
│  │  ✓ You discovered 2 NOVEL PRIVATE VARIANTS                         │ │
│  │    These will be submitted to the Haplogroup Discovery System      │ │
│  │                                                                     │ │
│  │  ○ Your coverage (32x) does not improve on current best (45x)     │ │
│  │                                                                     │ │
│  │  ○ Your STR profile (Y-67) has fewer markers than current (Y-111) │ │
│  │                                                                     │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  [Sync Contribution]                                          [Cancel]  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### API Endpoints for Deduplication

```
# Check if a sample accession is canonical
GET /api/canonical-samples/lookup?accession={accession}
Response: {
  "isCanonical": true,
  "registry": "1000GENOMES",
  "canonicalAccession": "HG00096",
  "crossReferences": {
    "ena": "SAMEA3302682",
    "ncbiBiosample": "SAMN00001598"
  },
  "contributorCount": 3,
  "mergedValues": {
    "bestCoverage": 45.0,
    "refinedYHaplogroup": "R-L21",
    "refinedMtHaplogroup": "H1a",
    "hasStrProfile": true
  }
}

# Get all contributions for a canonical sample
GET /api/canonical-samples/{registry}/{accession}/contributions
Response: {
  "canonicalAccession": "HG00096",
  "contributions": [
    {
      "contributorDid": "did:plc:alice",
      "biosampleAtUri": "at://did:plc:alice/.../biosample/hg00096",
      "coverage": 32.0,
      "yHaplogroup": "R-L21>FT54321",
      "mtHaplogroup": "H1a",
      "hasStrProfile": true,
      "hasPrivateVariants": true,
      "contributedAt": "2025-12-07T10:30:00Z"
    },
    // ...
  ]
}

# Preview contribution value before sync
POST /api/canonical-samples/preview-contribution
Request: {
  "sampleAccession": "HG00096",
  "coverage": 32.0,
  "yHaplogroup": "R-L21>FT54321",
  "mtHaplogroup": "H1a",
  "strMarkerCount": 67,
  "privateVariantCount": 2
}
Response: {
  "isCanonical": true,
  "improvements": [
    { "field": "yHaplogroup", "current": "R-L21", "yours": "R-L21>FT54321", "isImprovement": true },
    { "field": "privateVariants", "current": 0, "yours": 2, "isImprovement": true }
  ],
  "noChange": [
    { "field": "mtHaplogroup", "value": "H1a" }
  ],
  "notBest": [
    { "field": "coverage", "current": 45.0, "yours": 32.0 },
    { "field": "strMarkerCount", "current": 111, "yours": 67 }
  ]
}
```

### Conflict Resolution for Canonical Samples

When contributions disagree:

```scala
case class HaplogroupDisagreement(
  canonicalSampleId: Int,
  field: String,                    // "yHaplogroup" or "mtHaplogroup"
  values: Map[String, Set[String]], // haplogroup -> Set of contributor DIDs
  suggestedResolution: Option[String],
  resolutionReason: Option[String]
)

def detectHaplogroupDisagreements(canonicalSampleId: Int): Future[Seq[HaplogroupDisagreement]] = {
  for {
    contributions <- contributionRepo.findByCanonicalSample(canonicalSampleId)

    yHaplogroupGroups = contributions
      .flatMap(c => c.yHaplogroup.map(h => (h, c.contributorDid)))
      .groupBy(_._1)
      .view.mapValues(_.map(_._2).toSet).toMap

    yDisagreement = if (yHaplogroupGroups.size > 1) {
      // Check if disagreements are just refinement levels
      val baseHaplogroups = yHaplogroupGroups.keys.map(extractBaseHaplogroup).toSet
      if (baseHaplogroups.size == 1) {
        // All agree on base, just different refinement levels
        val mostRefined = yHaplogroupGroups.keys.maxBy(haplogroupTreeDepth)
        Some(HaplogroupDisagreement(
          canonicalSampleId,
          "yHaplogroup",
          yHaplogroupGroups,
          suggestedResolution = Some(mostRefined),
          resolutionReason = Some("Most refined call, compatible with others")
        ))
      } else {
        // True disagreement - needs manual review
        Some(HaplogroupDisagreement(
          canonicalSampleId,
          "yHaplogroup",
          yHaplogroupGroups,
          suggestedResolution = None,
          resolutionReason = Some("Conflicting base haplogroups - curator review needed")
        ))
      }
    } else None

  } yield Seq(yDisagreement, mtDisagreement).flatten
}
```

---

## Open Questions

1. **File storage**: Should sequence files (BAM/CRAM) be referenced by local path, remote URL, or uploaded to blob storage?

2. **Project visibility**: Should `project` records be public or private by default?

3. **Batch limits**: What's the maximum number of records to sync in one operation?

4. **Offline duration**: How long should Navigator queue changes before warning about potential conflicts?

5. **AppView authority**: Should AppView-computed updates (haplogroup refinement) automatically overwrite local values?

6. **Canonical registry maintenance**: Who maintains the list of known canonical registries (1KG, HGDP, etc.) and their accession patterns?

7. **Contribution attribution**: How should we display multi-researcher contributions on the public biosample page?

8. **Disagreement handling**: When researchers disagree on haplogroup calls, should the AppView auto-resolve or flag for curator review?

---

## Related Documents

- [Atmosphere Lexicon Design](../Atmosphere_Lexicon.md) - Record schemas
- [Group Project System](./group-project-system.md) - Project membership model
- [Haplogroup Discovery System](../planning/haplogroup-discovery-system.md) - Private variant flow
