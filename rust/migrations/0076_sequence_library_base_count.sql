-- `genomics.sequence_library.base_count` — total bases sequenced in one run.
--
-- WHY THIS EXISTS. The DecodingUs Grid pays contributors per gigabase realigned
-- (`documents/design/distributed-compute-grid.md` §6.3), and it had nothing to weigh a FASTQ unit
-- by. `grid.work_unit.est_bases` was computed as `reads × read_length`, but the ENA crawl never
-- sets `read_length`: `du_jobs::crawl_project::mk_lib` writes `read_length: None`, because
-- `du_external::ena::RUN_FIELDS` did not request a length field at all. So `est_bases` was NULL for
-- essentially every crawled sample, and `grid-validate` paid a 90 Gbp realignment exactly what it
-- paid a CRAM passthrough.
--
-- ENA's `filereport` has published `base_count` on `read_run` all along. This column is where it
-- lands. It is the measured total, not `reads × read_length` — which is only ever a mean-length
-- approximation, and wrong outright for variable-length reads (which is to say, for every long-read
-- platform).
--
-- WHY NOT THE `atproto` JSONB SLOT, which already carries `{source, run_accession}` for crawled
-- runs. That slot is provenance — where this row came from. `base_count` is a measurement of the
-- library itself, the same kind of fact as the `reads` and `read_length` columns beside it, and it
-- gets summed in an aggregate query that feeds a ledger paying real people. That belongs in a
-- typed column where a NULL is visible, not inside a JSON blob where a missing key reads the same
-- as a zero.
--
-- BACKFILL. Existing rows stay NULL: `sequence::ingest_libraries` is idempotent at *sample*
-- granularity and skips a sample that already has files, so a re-crawl will not fill them in. Run
-- `du-jobs run-once ena-base-count` to populate them from ENA, a bounded batch at a time.
-- `grid::curation_candidates` falls back to `reads × read_length` where `base_count` is still
-- absent, and `grid-curate` warns with a count of units it published with no estimate at all.

ALTER TABLE genomics.sequence_library
    ADD COLUMN base_count BIGINT;   -- total bases in the run, as ENA reports it

-- The backfill job's work list: crawled ENA runs that have no measurement yet.
CREATE INDEX sequence_library_base_count_backfill_idx ON genomics.sequence_library (id)
    WHERE base_count IS NULL;
