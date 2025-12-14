# Performance Tuning: Variant Ingestion

**Context:** Optimization of the `YBrowseVariantIngestionService` and `VariantV2Repository` for processing large-scale variant datasets (e.g., YBrowse GFF3 dump).

## Bottlenecks & Solutions

### 1. Connection Pool Exhaustion
**Problem:** The initial implementation launched parallel `Future`s for every variant in a batch (e.g., `batchSize=1000`). This flooded the Slick connection pool (`numThreads=32`) with thousands of simultaneous `findMatches` and `create/update` queries, causing timeouts and application unresponsiveness.

**Solution:**
*   **Reduced Concurrency:** Shifted from per-variant parallel processing to **per-batch** processing.
*   **Sequential Batches:** The background task now processes one batch at a time (sequentially), ensuring it never consumes more than 1-2 connections from the pool, leaving the rest available for user traffic.

### 2. Inefficient Matching Queries
**Problem:** The `findMatches` query used complex `OR` logic combining coordinate searches and name searches (with `jsonb_array_elements`).
```sql
-- Initial slow query pattern
SELECT * FROM variant_v2 WHERE coordinates->... OR EXISTS (SELECT ... from aliases)
```
This forced PostgreSQL to perform a **Sequential Scan** (full table scan) instead of using the GIN indexes, resulting in >4s execution time per query.

**Solution:**
*   **Split Queries:** Rewrote the lookup to use separate, index-optimized queries for coordinates (`coordinates @> ...`) and names (`aliases ?| ...`).
*   **GIN Indexing:** Leveraged the GIN indexes on `coordinates` and `aliases` for sub-millisecond lookups.

### 3. Batch Upsert Optimization
**Problem:** Processing variants one-by-one (even with fast queries) has significant overhead. 100 variants = 100 reads + 100 writes.

**Solution:**
*   **`upsertBatch` Implementation:** Implemented a true batch upsert using PostgreSQL's `INSERT ... ON CONFLICT DO UPDATE`.
*   **Single Statement:** This processes an entire batch (e.g., 100 variants) in a **single SQL statement**, drastically reducing round-trip overhead.
*   **Conflict Handling:** Handles two distinct conflict targets (named vs. unnamed variants) by splitting the batch and executing two optimized SQL statements.

### 4. Duplicate Key Errors
**Problem:** `ON CONFLICT DO UPDATE` fails if the *input batch itself* contains duplicates (e.g., two rows for "Y1993" in the same GFF file). PostgreSQL cannot update the same row twice in one statement.

**Solution:**
*   **In-Memory Deduplication:** Added logic inside `upsertBatch` to group incoming variants by their unique keys (canonical name + haplogroup OR coordinates) and pick a representative before constructing the SQL.

## Final Performance
*   **Throughput:** High (limited primarily by network download speed and CPU for GFF parsing).
*   **Database Load:** Minimal (efficient batch writes).
*   **Responsiveness:** No impact on foreground user requests.
