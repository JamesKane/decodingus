# OpenAlex Publication Auto-Discovery System

## Overview

Automatically discover and surface relevant genomic publications using OpenAlex, reducing curator burden and ensuring the platform stays current with academic research.

## Problem Statement

Currently, publications enter the system through manual curator submission. This creates two issues:

1. **Discovery lag** — Relevant papers may exist for months before a curator notices them
2. **Curator burden** — Manual searching across journals is time-consuming
3. **Coverage gaps** — Papers in less-monitored journals may be missed entirely

## Proposed Solution

Use the OpenAlex API to periodically search for publications matching configurable criteria, then surface candidates for curator review.

### Key Capabilities

- **Scheduled discovery** — Periodic queries against OpenAlex for new publications
- **Configurable search criteria** — Keywords, journals, concepts, date ranges
- **Candidate queue** — Discovered papers staged for curator review (not auto-imported)
- **Deduplication** — Match against existing publications by DOI/OpenAlex ID
- **Relevance scoring** — Rank candidates based on keyword matches, citation patterns, concept alignment

### Search Criteria Examples

| Category | Example Queries |
|----------|-----------------|
| Haplogroup-specific | "Y-DNA haplogroup", "mtDNA phylogeny", "Y-chromosome phylogenetic" |
| Method-specific | "whole genome sequencing ancient DNA", "Y-STR analysis" |
| Population genetics | "population structure", "human migration genetics" |
| Journal filters | Molecular Biology and Evolution, European Journal of Human Genetics, AJHG |
| Concept filters | OpenAlex concepts: "Haplogroup", "Y chromosome", "Mitochondrial DNA" |

## Data Flow

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Scheduler     │────►│  OpenAlex API   │────►│  Candidate      │
│   (Pekko Quartz)│     │  Query Service  │     │  Publications   │
└─────────────────┘     └─────────────────┘     └────────┬────────┘
                                                         │
                                                         ▼
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Publications  │◄────│  Curator        │◄────│  Review Queue   │
│   (accepted)    │     │  Approval       │     │  (pending)      │
└─────────────────┘     └─────────────────┘     └─────────────────┘
```

## Curator Workflow

1. **Review queue** — Curators see list of candidate publications with relevance scores
2. **Quick actions:**
   - **Accept** — Import publication, optionally flag for biosample extraction
   - **Reject** — Mark as not relevant (excluded from future discovery)
   - **Defer** — Keep in queue for later review
3. **Bulk operations** — Accept/reject multiple candidates matching criteria
4. **Feedback loop** — Rejected papers inform relevance scoring improvements

## Database Schema

### New Tables

```sql
-- Candidate publications awaiting curator review
CREATE TABLE publication_candidates (
    id SERIAL PRIMARY KEY,
    openalex_id VARCHAR(255) UNIQUE NOT NULL,
    doi VARCHAR(255),
    title TEXT NOT NULL,
    abstract TEXT,
    publication_date DATE,
    journal_name VARCHAR(500),
    relevance_score FLOAT,
    discovery_date TIMESTAMP DEFAULT NOW(),
    status VARCHAR(50) DEFAULT 'pending', -- pending, accepted, rejected, deferred
    reviewed_by INT REFERENCES users(id),
    reviewed_at TIMESTAMP,
    rejection_reason TEXT,
    raw_metadata JSONB -- Full OpenAlex response
);

-- Search configuration
CREATE TABLE publication_search_configs (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    search_query TEXT NOT NULL, -- OpenAlex query string
    concepts JSONB, -- OpenAlex concept IDs to filter
    journals JSONB, -- Journal/source filters
    enabled BOOLEAN DEFAULT TRUE,
    last_run TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Track search history for debugging/optimization
CREATE TABLE publication_search_runs (
    id SERIAL PRIMARY KEY,
    config_id INT REFERENCES publication_search_configs(id),
    run_at TIMESTAMP DEFAULT NOW(),
    candidates_found INT,
    new_candidates INT, -- After deduplication
    query_used TEXT,
    duration_ms INT
);
```

### Indexes

```sql
CREATE INDEX idx_pub_candidates_status ON publication_candidates(status);
CREATE INDEX idx_pub_candidates_relevance ON publication_candidates(relevance_score DESC) WHERE status = 'pending';
CREATE INDEX idx_pub_candidates_openalex ON publication_candidates(openalex_id);
CREATE INDEX idx_pub_candidates_doi ON publication_candidates(doi);
```

## Implementation Phases

### Phase 1: Core Discovery (MVP)

- [ ] OpenAlex query service with configurable search terms
- [ ] Candidate table and basic deduplication (DOI match)
- [ ] Scheduled job (weekly) via Pekko Quartz
- [ ] Simple curator review UI (list view, accept/reject)

### Phase 2: Relevance Scoring

- [ ] Keyword-based relevance scoring
- [ ] OpenAlex concept weighting
- [ ] Citation count factoring
- [ ] Author affiliation signals (known ancient DNA labs, etc.)

### Phase 3: Smart Discovery

- [ ] Learn from curator accept/reject patterns
- [ ] Related paper suggestions (papers citing accepted publications)
- [ ] Author following (new papers from prolific contributors)
- [ ] Notification system for high-relevance candidates

### Phase 4: Biosample Extraction Hints

- [ ] Parse abstracts for sample count mentions
- [ ] Identify papers likely to have ENA/SRA accessions
- [ ] Flag papers with supplementary data tables
- [ ] Integration with GenomicStudyService for auto-population hints

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/private/publication-candidates` | List pending candidates (paginated) |
| GET | `/api/private/publication-candidates/:id` | Get candidate details |
| POST | `/api/private/publication-candidates/:id/accept` | Accept and import |
| POST | `/api/private/publication-candidates/:id/reject` | Reject with reason |
| POST | `/api/private/publication-candidates/:id/defer` | Defer for later |
| GET | `/api/private/publication-search-configs` | List search configurations |
| POST | `/api/private/publication-search-configs` | Create new search config |
| POST | `/api/private/publication-search-configs/:id/run` | Trigger manual search run |

## Configuration

```hocon
openalex {
  baseUrl = "https://api.openalex.org"
  mailToEmail = ${openalex.mailToEmail} # Already configured

  discovery {
    enabled = true
    enabled = ${?OPENALEX_DISCOVERY_ENABLED}

    # Run weekly on Sunday at 2 AM UTC
    schedule = "0 0 2 ? * SUN"

    # Maximum candidates to fetch per run
    maxResultsPerQuery = 100

    # Default relevance threshold for surfacing
    minRelevanceScore = 0.5
  }
}
```

## Integration with Existing Systems

### OpenAlexService

Extend existing `app/services/OpenAlexService.scala` with:
- `searchWorks(query: String, filters: OpenAlexFilters): Future[Seq[OpenAlexWork]]`
- `getWorkById(openAlexId: String): Future[Option[OpenAlexWork]]`

### PublicationService

Add method to create publication from candidate:
- `createFromCandidate(candidateId: Int): Future[Publication]`

### Scheduler

Add to `conf/application.conf`:
```hocon
pekko.quartz.schedules {
  PublicationDiscovery {
    expression = "0 0 2 ? * SUN"
    timezone = "UTC"
    description = "Discover new publications via OpenAlex"
  }
}
```

## Success Metrics

| Metric | Target |
|--------|--------|
| Discovery-to-import lag | < 2 weeks for high-relevance papers |
| Curator review time | < 5 minutes per candidate batch |
| Precision (accepted/surfaced) | > 30% initially, improving with feedback |
| Coverage | > 80% of relevant papers discovered within 1 month of publication |

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| OpenAlex rate limits | Medium | Respect polite pool, batch queries, cache results |
| Low precision floods curators | Medium | Configurable thresholds, bulk reject, relevance tuning |
| Duplicate detection misses | Low | Multiple matching strategies (DOI, title similarity, OpenAlex ID) |
| OpenAlex API changes | Low | Abstract behind service layer, monitor changelog |

## References

- [OpenAlex API Documentation](https://docs.openalex.org/)
- [OpenAlex Concepts](https://docs.openalex.org/api-entities/concepts)
- Existing integration: `app/services/OpenAlexService.scala`
- Scheduler config: `conf/application.conf` (pekko.quartz.schedules)
