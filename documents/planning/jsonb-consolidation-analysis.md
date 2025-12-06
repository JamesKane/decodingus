# JSONB Consolidation Analysis

## Executive Summary

This document analyzes the current database schema to identify tables that would be better served as JSONB columns on their parent tables. The analysis considers query patterns, reporting performance, cardinality relationships, and PostgreSQL JSONB capabilities.

**Key Finding:** 7 tables are strong candidates for JSONB consolidation, potentially eliminating 5-7 tables while improving data locality and reducing JOIN overhead for common access patterns.

---

## Evaluation Criteria

Each table was evaluated against:

| Criterion | Favors JSONB | Favors Separate Table |
|-----------|--------------|----------------------|
| Cardinality | 1:1 or 1:few | 1:many or many:many |
| Query pattern | Always with parent | Independent queries |
| Filtering/JOINs | Never filtered independently | Used in WHERE/JOIN |
| Aggregations | Never aggregated | SUM/AVG/COUNT queries |
| Update frequency | Set once, rarely changed | Frequently updated |
| Data size | Small, bounded | Large, unbounded |
| Constraints | Simple validation | Complex CHECK/UNIQUE |

---

## Reporting Performance Implications

### JSONB Advantages
- **Reduced JOINs**: Co-located data eliminates JOIN overhead for 1:1 relationships
- **Better locality**: Related data on same page reduces I/O
- **Flexible schema**: Easy to add optional fields without migrations
- **GIN indexing**: Efficient containment and existence queries

### JSONB Disadvantages
- **Aggregation overhead**: `->>'field'` casting slower than typed columns
- **Full column updates**: No partial JSONB updates (entire value replaced)
- **Index size**: GIN indexes larger than B-tree on typed columns
- **Query complexity**: Path expressions less readable than column names

### Mitigation Strategies
```sql
-- For frequently aggregated JSONB fields, create expression indexes:
CREATE INDEX idx_coverage_mean ON alignment_metadata
  USING BTREE ((coverage->>'mean_depth')::double precision);

-- For containment queries, use jsonb_path_ops:
CREATE INDEX idx_checksums ON sequence_file
  USING GIN (checksums jsonb_path_ops);
```

---

## Strong Candidates for Consolidation

### Tier 1: Clear Wins (Low Risk, High Reward)

#### 1. sequence_file_checksum → sequence_file.checksums

| Aspect | Current | Proposed |
|--------|---------|----------|
| Relationship | 1:few (1-2 per file) | JSONB array |
| Access pattern | Always with parent | Same |
| Independent queries | None | N/A |
| Rows eliminated | ~50-70% reduction | - |

**Current Schema:**
```sql
CREATE TABLE sequence_file_checksum (
    id SERIAL PRIMARY KEY,
    sequence_file_id INT NOT NULL REFERENCES sequence_file(id),
    checksum VARCHAR(255) NOT NULL,
    algorithm VARCHAR(50) NOT NULL,
    verified_at TIMESTAMP NOT NULL,
    UNIQUE (sequence_file_id, algorithm)
);
```

**Proposed JSONB:**
```sql
ALTER TABLE sequence_file ADD COLUMN checksums JSONB DEFAULT '[]'::jsonb;

-- Structure: [{"algorithm": "MD5", "checksum": "abc123...", "verified_at": "2025-01-01T00:00:00Z"}, ...]
-- Constraint moved to application layer
```

**Migration:**
```sql
UPDATE sequence_file sf
SET checksums = (
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'algorithm', sfc.algorithm,
        'checksum', sfc.checksum,
        'verified_at', sfc.verified_at
    )), '[]'::jsonb)
    FROM sequence_file_checksum sfc
    WHERE sfc.sequence_file_id = sf.id
);
```

---

#### 2. sequence_http_location → sequence_file.http_locations

| Aspect | Current | Proposed |
|--------|---------|----------|
| Relationship | 1:few (1-3 per file) | JSONB array |
| Access pattern | Always with parent | Same |
| Independent queries | None | N/A |
| Rows eliminated | ~80% reduction | - |

**Current Schema:**
```sql
CREATE TABLE sequence_http_location (
    id SERIAL PRIMARY KEY,
    sequence_file_id INT NOT NULL REFERENCES sequence_file(id),
    file_url TEXT NOT NULL,
    file_index_url TEXT
);
```

**Proposed JSONB:**
```sql
ALTER TABLE sequence_file ADD COLUMN http_locations JSONB DEFAULT '[]'::jsonb;

-- Structure: [{"file_url": "https://...", "file_index_url": "https://..."}, ...]
```

---

#### 3. sequence_atp_location → sequence_file.atp_location

| Aspect | Current | Proposed |
|--------|---------|----------|
| Relationship | 1:1 (one per file max) | JSONB object |
| Access pattern | Always with parent | Same |
| Independent queries | None | N/A |
| Table eliminated | Yes | - |

**Current Schema:**
```sql
CREATE TABLE sequence_atp_location (
    id SERIAL PRIMARY KEY,
    sequence_file_id INT NOT NULL REFERENCES sequence_file(id),
    repo_did VARCHAR(255) NOT NULL,
    record_cid VARCHAR(255) NOT NULL,
    record_path TEXT NOT NULL,
    index_did VARCHAR(255),
    index_cid VARCHAR(255)
);
```

**Proposed JSONB:**
```sql
ALTER TABLE sequence_file ADD COLUMN atp_location JSONB;

-- Structure: {"repo_did": "did:plc:...", "record_cid": "...", "record_path": "...", ...}
-- NULL when no ATP location
```

---

### Tier 2: Strong Candidates (Medium Effort)

#### 4. alignment_coverage → alignment_metadata.coverage

| Aspect | Current | Proposed |
|--------|---------|----------|
| Relationship | 1:1 (strict FK) | JSONB object |
| Access pattern | Always JOINed | Same |
| Aggregations | Yes (CoverageBenchmark) | Requires expression indexes |
| Rows eliminated | 50% | - |

**Current Schema:**
```sql
CREATE TABLE alignment_coverage (
    id SERIAL PRIMARY KEY,
    alignment_metadata_id INT NOT NULL UNIQUE REFERENCES alignment_metadata(id),
    mean_depth DOUBLE PRECISION,
    median_depth DOUBLE PRECISION,
    percent_coverage_at_1x DOUBLE PRECISION,
    percent_coverage_at_5x DOUBLE PRECISION,
    percent_coverage_at_10x DOUBLE PRECISION,
    percent_coverage_at_20x DOUBLE PRECISION,
    percent_coverage_at_30x DOUBLE PRECISION,
    bases_no_coverage BIGINT,
    bases_low_quality_mapping BIGINT,
    bases_callable BIGINT,
    mean_mapping_quality DOUBLE PRECISION
);
```

**Proposed JSONB:**
```sql
ALTER TABLE alignment_metadata ADD COLUMN coverage JSONB;

-- Structure: {
--   "mean_depth": 30.5,
--   "median_depth": 29.0,
--   "percent_coverage_at_1x": 0.99,
--   ...
-- }
```

**Required Index for Aggregations:**
```sql
-- Support CoverageBenchmark queries
CREATE INDEX idx_am_coverage_mean_depth
  ON alignment_metadata USING BTREE ((coverage->>'mean_depth')::double precision);
CREATE INDEX idx_am_coverage_median
  ON alignment_metadata USING BTREE ((coverage->>'median_depth')::double precision);
```

**Impact:** Requires rewriting 4 aggregation queries in `CoverageBenchmarkRepository`:
- `getBenchmarksByLab`
- `getBenchmarksByLabAndTestType`
- `getBenchmarksByContig`
- `getOverallBenchmarks`

---

#### 5. citizen_biosample_original_haplogroup → citizen_biosample.original_haplogroups

| Aspect | Current | Proposed |
|--------|---------|----------|
| Relationship | 1:few (per publication) | JSONB array |
| Access pattern | With parent | Same |
| Constraint | UNIQUE(biosample_id, publication_id) | Application-level |
| Existing JSONB | y_haplogroup, mt_haplogroup already JSONB | Consistent pattern |

**Current Schema:**
```sql
CREATE TABLE citizen_biosample_original_haplogroup (
    id SERIAL PRIMARY KEY,
    citizen_biosample_id INT NOT NULL REFERENCES citizen_biosample(id),
    publication_id INT NOT NULL REFERENCES publication(id),
    y_haplogroup_result JSONB,
    mt_haplogroup_result JSONB,
    notes TEXT,
    UNIQUE(citizen_biosample_id, publication_id)
);
```

**Proposed JSONB:**
```sql
ALTER TABLE citizen_biosample
  ADD COLUMN original_haplogroups_by_publication JSONB DEFAULT '[]'::jsonb;

-- Structure: [
--   {
--     "publication_id": 123,
--     "y_haplogroup_result": {...},
--     "mt_haplogroup_result": {...},
--     "notes": "..."
--   },
--   ...
-- ]
```

**Uniqueness Enforcement:**
```scala
// Application-level validation
def addOriginalHaplogroup(biosample: CitizenBiosample, pubId: Int, data: HaplogroupData): Future[CitizenBiosample] = {
  val existing = biosample.originalHaplogroupsByPublication.getOrElse(Seq.empty)
  if (existing.exists(_.publicationId == pubId)) {
    Future.failed(DuplicatePublicationHaplogroupError(pubId))
  } else {
    val updated = existing :+ OriginalHaplogroup(pubId, data.y, data.mt, data.notes)
    biosampleRepository.update(biosample.copy(originalHaplogroupsByPublication = Some(updated)))
  }
}
```

---

#### 6. biosample_original_haplogroup → biosample.original_haplogroups

Same pattern as #5, for publication (external) biosamples.

**Current Schema:**
```sql
CREATE TABLE biosample_original_haplogroup (
    id SERIAL PRIMARY KEY,
    biosample_id INT NOT NULL REFERENCES biosample(id),
    publication_id INT NOT NULL REFERENCES publication(id),
    y_haplogroup_result JSONB,
    mt_haplogroup_result JSONB,
    notes TEXT,
    UNIQUE(biosample_id, publication_id)
);
```

**Proposed:** Same JSONB array pattern on `biosample` table.

---

### Tier 3: Conditional Candidates (Trade-offs)

#### 7. Revision Metadata Tables (haplogroup_variant_metadata, relationship_revision_metadata)

| Aspect | Current | Consideration |
|--------|---------|---------------|
| Relationship | 1:many (revision history) | JSONB array for history |
| Access pattern | Recursive chain queries | Would need app-level recursion |
| Use case | Audit trail | Append-only log fits JSONB |

**Recommendation:** Hybrid approach - keep current table for active revisions, add JSONB column for historical snapshot:

```sql
-- Add historical log to parent tables
ALTER TABLE haplogroup_variant ADD COLUMN revision_history JSONB DEFAULT '[]'::jsonb;
ALTER TABLE haplogroup_relationship ADD COLUMN revision_history JSONB DEFAULT '[]'::jsonb;

-- Structure: [
--   {"revision_id": 1, "author": "...", "timestamp": "...", "change_type": "CREATE", "comment": "..."},
--   {"revision_id": 2, "author": "...", "timestamp": "...", "change_type": "UPDATE", "comment": "..."}
-- ]
```

**Trade-off:** Faster history reads, but complex recursive queries (getVariantRevisionChain) would need application logic.

---

## Do NOT Consolidate

### Many-to-Many Junction Tables
- `publication_biosample`
- `publication_citizen_biosample`
- `publication_ena_study`

**Reason:** Junction tables with two FKs are the correct pattern for M:N relationships. JSONB arrays on both sides would require complex sync logic.

### Core Entity Tables
- `biosample`, `citizen_biosample`, `specimen_donor`
- `haplogroup`, `variant`, `haplogroup_variant`
- `publication`, `genomic_studies`

**Reason:** Independently queried entities with complex filtering, constraints, and relationships.

### Graph/Tree Structures
- `pangenome_graph`, `pangenome_node`, `pangenome_edge`, `pangenome_path`
- `haplogroup_relationship` (tree structure)

**Reason:** Graph algorithms require efficient traversal; JSONB would complicate recursive queries.

### High-Volume Data Tables
- `reported_variant_pangenome`
- `quality_metrics`
- `ibd_discovery_index`

**Reason:** High cardinality, independent queries, aggregation targets.

---

## Implementation Plan

### Phase 1: Sequence File Consolidation (Low Risk)

**Evolution XX:**
```sql
-- !Ups

-- 1. Add JSONB columns
ALTER TABLE sequence_file ADD COLUMN checksums JSONB DEFAULT '[]'::jsonb;
ALTER TABLE sequence_file ADD COLUMN http_locations JSONB DEFAULT '[]'::jsonb;
ALTER TABLE sequence_file ADD COLUMN atp_location JSONB;

-- 2. Migrate data
UPDATE sequence_file sf SET checksums = (
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'algorithm', sfc.algorithm,
        'checksum', sfc.checksum,
        'verified_at', sfc.verified_at
    ) ORDER BY sfc.algorithm), '[]'::jsonb)
    FROM sequence_file_checksum sfc WHERE sfc.sequence_file_id = sf.id
);

UPDATE sequence_file sf SET http_locations = (
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'file_url', shl.file_url,
        'file_index_url', shl.file_index_url
    )), '[]'::jsonb)
    FROM sequence_http_location shl WHERE shl.sequence_file_id = sf.id
);

UPDATE sequence_file sf SET atp_location = (
    SELECT jsonb_build_object(
        'repo_did', sal.repo_did,
        'record_cid', sal.record_cid,
        'record_path', sal.record_path,
        'index_did', sal.index_did,
        'index_cid', sal.index_cid
    )
    FROM sequence_atp_location sal WHERE sal.sequence_file_id = sf.id
);

-- 3. Create indexes
CREATE INDEX idx_sf_checksums ON sequence_file USING GIN (checksums jsonb_path_ops);

-- 4. Drop old tables
DROP TABLE sequence_file_checksum;
DROP TABLE sequence_http_location;
DROP TABLE sequence_atp_location;

-- !Downs
-- (reverse migration SQL)
```

**Code Changes:**
- Update `SequenceFile` domain model
- Remove `SequenceFileChecksum`, `SequenceHttpLocation`, `SequenceAtpLocation` models
- Update `SequenceFileRepository` to handle JSONB
- Update `BiosampleDataService` file creation logic

---

### Phase 2: Alignment Coverage Consolidation (Medium Risk)

**Evolution XX+1:**
```sql
-- !Ups

ALTER TABLE alignment_metadata ADD COLUMN coverage JSONB;

UPDATE alignment_metadata am SET coverage = (
    SELECT jsonb_build_object(
        'mean_depth', ac.mean_depth,
        'median_depth', ac.median_depth,
        'percent_coverage_at_1x', ac.percent_coverage_at_1x,
        'percent_coverage_at_5x', ac.percent_coverage_at_5x,
        'percent_coverage_at_10x', ac.percent_coverage_at_10x,
        'percent_coverage_at_20x', ac.percent_coverage_at_20x,
        'percent_coverage_at_30x', ac.percent_coverage_at_30x,
        'bases_no_coverage', ac.bases_no_coverage,
        'bases_low_quality_mapping', ac.bases_low_quality_mapping,
        'bases_callable', ac.bases_callable,
        'mean_mapping_quality', ac.mean_mapping_quality
    )
    FROM alignment_coverage ac WHERE ac.alignment_metadata_id = am.id
);

-- Indexes for aggregation queries
CREATE INDEX idx_am_cov_mean_depth ON alignment_metadata
  USING BTREE (((coverage->>'mean_depth')::double precision));
CREATE INDEX idx_am_cov_pct_30x ON alignment_metadata
  USING BTREE (((coverage->>'percent_coverage_at_30x')::double precision));

DROP TABLE alignment_coverage;
```

**Code Changes:**
- Update `AlignmentMetadata` model to include `AlignmentCoverage` as embedded case class
- Rewrite `CoverageBenchmarkRepository` aggregation queries
- Update `AlignmentRepository` to handle embedded coverage

**Query Migration Example:**
```sql
-- Before (with JOIN)
SELECT sl.lab, sl.test_type, AVG(ac.mean_depth)
FROM sequence_library sl
JOIN sequence_file sf ON sf.library_id = sl.id
JOIN alignment_metadata am ON am.sequence_file_id = sf.id
JOIN alignment_coverage ac ON ac.alignment_metadata_id = am.id
GROUP BY sl.lab, sl.test_type;

-- After (JSONB)
SELECT sl.lab, sl.test_type, AVG((am.coverage->>'mean_depth')::double precision)
FROM sequence_library sl
JOIN sequence_file sf ON sf.library_id = sl.id
JOIN alignment_metadata am ON am.sequence_file_id = sf.id
WHERE am.coverage IS NOT NULL
GROUP BY sl.lab, sl.test_type;
```

---

### Phase 3: Haplogroup Tracking Consolidation (Medium Risk)

**Evolution XX+2:**
```sql
-- !Ups

ALTER TABLE citizen_biosample
  ADD COLUMN original_haplogroups_by_publication JSONB DEFAULT '[]'::jsonb;

ALTER TABLE biosample
  ADD COLUMN original_haplogroups_by_publication JSONB DEFAULT '[]'::jsonb;

-- Migrate citizen_biosample data
UPDATE citizen_biosample cb SET original_haplogroups_by_publication = (
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'publication_id', cboh.publication_id,
        'y_haplogroup_result', cboh.y_haplogroup_result,
        'mt_haplogroup_result', cboh.mt_haplogroup_result,
        'notes', cboh.notes
    )), '[]'::jsonb)
    FROM citizen_biosample_original_haplogroup cboh
    WHERE cboh.citizen_biosample_id = cb.id
);

-- Migrate biosample data
UPDATE biosample b SET original_haplogroups_by_publication = (
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'publication_id', boh.publication_id,
        'y_haplogroup_result', boh.y_haplogroup_result,
        'mt_haplogroup_result', boh.mt_haplogroup_result,
        'notes', boh.notes
    )), '[]'::jsonb)
    FROM biosample_original_haplogroup boh
    WHERE boh.biosample_id = b.id
);

-- Index for publication lookups
CREATE INDEX idx_cb_orig_hg_pub ON citizen_biosample
  USING GIN (original_haplogroups_by_publication jsonb_path_ops);
CREATE INDEX idx_b_orig_hg_pub ON biosample
  USING GIN (original_haplogroups_by_publication jsonb_path_ops);

DROP TABLE citizen_biosample_original_haplogroup;
DROP TABLE biosample_original_haplogroup;
```

---

## Summary

### Tables to Consolidate (7 total)

| Current Table | Target Parent | Column Type | Priority |
|--------------|---------------|-------------|----------|
| sequence_file_checksum | sequence_file | JSONB array | P1 |
| sequence_http_location | sequence_file | JSONB array | P1 |
| sequence_atp_location | sequence_file | JSONB object | P1 |
| alignment_coverage | alignment_metadata | JSONB object | P2 |
| pangenome_alignment_coverage | pangenome_alignment_metadata | JSONB object | P2 |
| citizen_biosample_original_haplogroup | citizen_biosample | JSONB array | P3 |
| biosample_original_haplogroup | biosample | JSONB array | P3 |

### Expected Outcomes

- **Tables eliminated:** 7
- **JOIN reduction:** 3-4 fewer JOINs per sequence file query
- **Code simplification:** Fewer repository classes, simpler data access
- **Performance:** Faster reads for 1:1/1:few relationships; requires monitoring for aggregations

### Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Aggregation slowdown | Expression indexes on frequently aggregated fields |
| Lost constraints | Application-level validation |
| Migration errors | Staged rollout with verification queries |
| Reporting impact | Benchmark before/after with production-like data |
