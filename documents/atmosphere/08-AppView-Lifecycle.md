# AppView Lifecycle Management

As an AppView, `decodingus` subscribes to the AT Protocol Firehose to maintain a synchronized state of the genomic network.

---

## 1. The Firehose Event Stream

We listen for `com.atproto.sync.subscribeRepos` events containing operations for these collections:

**Core Records:**
- `com.decodingus.atmosphere.biosample`
- `com.decodingus.atmosphere.sequencerun`
- `com.decodingus.atmosphere.alignment`
- `com.decodingus.atmosphere.project`
- `com.decodingus.atmosphere.workspace`

**Future Scope Records:**
- `com.decodingus.atmosphere.genotype`
- `com.decodingus.atmosphere.imputation`
- `com.decodingus.atmosphere.populationBreakdown`
- `com.decodingus.atmosphere.instrumentObservation`
- `com.decodingus.atmosphere.matchConsent`
- `com.decodingus.atmosphere.matchList`
- `com.decodingus.atmosphere.matchRequest`
- `com.decodingus.atmosphere.strProfile`
- `com.decodingus.atmosphere.haplogroupAncestralStr`

---

## 2. Event Handling Strategy

### Biosample Events

| Event | Description | DecodingUs Logic |
|:---|:---|:---|
| **Create** | User creates a new biosample | 1. Extract `citizenDid` and record body.<br>2. Create `biosample` row with donor metadata and haplogroups (computed locally in Navigator Workbench).<br>3. Store `at_uri` and `at_cid`.<br>4. If `haplogroups` contains `privateVariants`, add to branch discovery consensus pool.<br>5. `sequenceRunRefs` and `genotypeRefs` may be empty initially. |
| **Update** | User modifies biosample metadata | 1. Lookup by `at_uri`.<br>2. Compare `at_cid` for version conflict.<br>3. Check `meta.lastModifiedField` for targeted update.<br>4. Update only changed fields (description, haplogroups, etc.).<br>5. Update `at_cid` and `meta.version`. |
| **Delete** | User removes biosample | 1. Lookup by `at_uri`.<br>2. Soft-delete biosample.<br>3. Mark related sequence runs, genotypes, and alignments as orphaned (do not delete).<br>4. Revoke any active `matchConsent`. |

### Sequence Run Events

| Event | Description | DecodingUs Logic |
|:---|:---|:---|
| **Create** | User adds a new sequencing run | 1. Resolve `biosampleRef` to internal biosample ID.<br>2. Create `sequence_libraries` row.<br>3. Store `at_uri` and `at_cid`.<br>4. If `instrumentId` present, trigger lab inference check.<br>5. Update parent biosample's `sequenceRunRefs` if not already present. |
| **Update** | User updates run metadata | 1. Lookup by `at_uri`.<br>2. Update only changed fields (files, metrics).<br>3. Parent biosample unchanged. |
| **Delete** | User removes a run | 1. Soft-delete sequence run.<br>2. Mark child alignments as orphaned.<br>3. Remove from parent's `sequenceRunRefs`. |

### Alignment Events

| Event | Description | DecodingUs Logic |
|:---|:---|:---|
| **Create** | User adds alignment results | 1. Resolve `sequenceRunRef` to internal sequence run ID.<br>2. Create `alignments` row with metrics.<br>3. Store `at_uri` and `at_cid`. |
| **Update** | User updates metrics or files | 1. Lookup by `at_uri`.<br>2. Update metrics or file references.<br>3. Parent records unchanged (key benefit). |
| **Delete** | User removes alignment | 1. Soft-delete alignment only.<br>2. Parent sequence run unchanged. |

### Genotype Events (Future)

| Event | Description | DecodingUs Logic |
|:---|:---|:---|
| **Create** | User adds genotype data | 1. Resolve `biosampleRef` to internal biosample ID.<br>2. Create `genotype_data` row with metadata (file info, chip type).<br>3. Haplogroup calling performed locally in Navigator Workbench; results sync via biosample haplogroups.<br>4. IBD analysis performed locally; only `matchConsent` determines visibility in potential matches. |
| **Update** | User updates genotype metadata | 1. Lookup by `at_uri`.<br>2. Update chip info, file metadata.<br>3. If haplogroup results changed, biosample update follows separately. |
| **Delete** | User removes genotype | 1. Soft-delete genotype.<br>2. Orphan any dependent imputation records.<br>3. Remove from IBD matching index. |

### Instrument Observation Events (Future)

| Event | Description | DecodingUs Logic |
|:---|:---|:---|
| **Create** | User submits lab observation | 1. Record observation in `instrument_observation` table.<br>2. Aggregate with existing observations for same `instrumentId`.<br>3. Update proposal if threshold reached.<br>4. Flag for curator review if confidence increases. |
| **Update** | User corrects observation | 1. Update observation.<br>2. Recalculate consensus. |
| **Delete** | User retracts observation | 1. Remove observation.<br>2. Recalculate consensus. |

### Match Consent Events (Future)

| Event | Description | DecodingUs Logic |
|:---|:---|:---|
| **Create** | User opts into matching | 1. Create consent record.<br>2. Include biosample in potential match discovery (matching computed locally, candidates identified network-wide).<br>3. Set visibility level based on `consentLevel`. |
| **Update** | User modifies consent | 1. Update consent parameters.<br>2. Adjust match visibility accordingly.<br>3. May need to reprocess matches. |
| **Delete** | User revokes consent | 1. Remove from active matching pool.<br>2. Remove matches from other users' match lists.<br>3. Preserve audit trail. |

### Match List Events (Future)

| Event | Description | DecodingUs Logic |
|:---|:---|:---|
| **Create** | AppView publishes match results | 1. Validate this is from authorized AppView.<br>2. Store match data for citizen access.<br>3. This is typically written BY DecodingUs, not by citizens. |
| **Update** | AppView updates matches | 1. Replace match list with new version.<br>2. Notify citizen of significant changes. |

### STR Profile Events

| Event | Description | DecodingUs Logic |
|:---|:---|:---|
| **Create** | User adds STR profile | 1. Resolve `biosampleRef` to internal biosample ID.<br>2. Create `str_profiles` row.<br>3. Parse and validate marker values (handle simple, multi-copy, complex).<br>4. Update biosample's `strProfileRef` if not already set. |
| **Update** | User updates STR data | 1. Lookup by `at_uri`.<br>2. Update marker values (e.g., adding more panels).<br>3. Trigger recomputation of group project comparisons if member. |
| **Delete** | User removes STR profile | 1. Soft-delete profile.<br>2. Remove from project STR analyses. |

### Haplogroup Ancestral STR Events (Future)

| Event | Description | DecodingUs Logic |
|:---|:---|:---|
| **Create** | AppView computes ancestral state | 1. Validate this is from authorized AppView.<br>2. Store ancestral reconstruction for haplogroup node.<br>3. This is computed BY DecodingUs from descendant samples. |
| **Update** | AppView recomputes after new data | 1. Replace with new reconstruction.<br>2. Update confidence scores and TMRCA estimates. |

---

## 3. Schema Requirements

To support robust syncing, internal tables require these tracking fields:

| Field | Type | Description |
|:---|:---|:---|
| `at_uri` | String, Unique | Canonical decentralized address for lookups |
| `at_cid` | String | Content identifier for version/conflict detection |
| `at_version` | Integer | Mirrors `meta.version` for optimistic locking |
| `at_status` | Enum | `active`, `orphaned`, `deleted` |
| `at_synced_at` | Timestamp | Last successful sync from firehose |

---

## 4. Orphan Handling

When a parent record is deleted but children exist:

1. Children are marked `orphaned` (not deleted)
2. Orphaned records are hidden from normal queries
3. If parent is recreated with same `at_uri`, children can be reattached
4. Periodic cleanup job can hard-delete orphans after retention period

---

## 5. Conflict Resolution

When processing firehose events:

1. Compare incoming `at_cid` with stored value
2. If different, compare `meta.version` numbers
3. Higher version wins; on tie, later timestamp wins
4. Log conflicts for manual review if needed
