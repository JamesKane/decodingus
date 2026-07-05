-- Standardized DTC test-profile label (design doc 11-Standardized-Test-Profiles.md).
-- DTC sequencing is sold by yield (Gbases) + read config, not depth, and the vendor
-- product names ("WGS", "WGS+") are ambiguous across labs. Navigator now publishes the
-- two missing inputs — total_bases (Σ read_length_histogram) and read_type (HIFI vs CLR
-- vs SHORT vs ONT_*) — so the AppView can classify each run into a vendor-neutral label
-- (e.g. "WGS150 45Gbases", "HiFi 90Gbases", "BigY-700") via the shared
-- du_domain::testprofile::standardized_label function.
--
-- We mirror the two raw inputs AND persist the computed label (classified once at ingest
-- from the shared function, not re-derived per read and not re-implemented in SQL), so the
-- coverage benchmark can GROUP BY / filter on it and the sample report can display it.
-- All nullable + additive: rows stay NULL until re-published, and readers COALESCE back to
-- the raw test_type. Table: 0012_fed_reporting.sql.
ALTER TABLE fed.sequencerun ADD COLUMN total_bases BIGINT;
ALTER TABLE fed.sequencerun ADD COLUMN read_type TEXT;
ALTER TABLE fed.sequencerun ADD COLUMN test_profile_label TEXT;

-- The coverage benchmark groups/filters cohorts by the standardized label.
CREATE INDEX fed_sequencerun_test_profile_idx ON fed.sequencerun (test_profile_label);
