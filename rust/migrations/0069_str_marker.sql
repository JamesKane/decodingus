-- Y-STR marker reference: the repeat unit (motif), its period, and eventually the
-- locus coordinates for each marker name we observe in profiles.
--
-- Until now marker identity was a bare `marker_name TEXT` everywhere — in
-- genomics.biosample_str_profile.markers, fed.str_profile.markers,
-- tree.haplogroup_ancestral_str, tree.haplogroup_str_asr and
-- genomics.str_mutation_rate. Nothing said what DYS390 *is*. This table is that
-- missing dimension, and the join target for the corpus-wide marker report
-- (du_db::ystr::marker_stats → /str-markers, /api/v1/reports/str-markers).
--
-- `coordinates` deliberately mirrors the core.genome_region shape
-- ({GRCh38:{contig,start,end}, hs1:{...}}) so the existing coordinate-lift
-- machinery can fill it in place once we have a locus source. It is empty today:
-- no table in this database has ever held an STR locus position.
--
-- `aliases` is not cosmetic. The profiles say DYS19 and Y-GATA-H4; the mutation-rate
-- table (seeded from Willems 2016 / YHRD) says DYS394 and YGATAH4 for the same two
-- markers. A join on name alone silently drops the two most recognizable markers in
-- the panel, so every lookup folds through aliases. Canonical `marker_name` is
-- always the form that appears in profiles — that is what a tester sees on a report.
--
-- Seeded from the only motif information this database holds: the free-text
-- `source` strings on genomics.str_mutation_rate, e.g.
--   'Willems et al. 2016 (1000G MUTEA); motif AGAT, n=468'
-- which yields a motif for 126 of the 137 rate rows. That is ~132 markers against
-- the 829 we actually observe: the extended Big Y markers (DYS425, DYF395S1,
-- DYS520, ...) have no published motif in any source we currently hold, and the
-- report renders them blank rather than guessing. Idempotent (corrective re-run).

CREATE TABLE genomics.str_marker (
    marker_name  TEXT PRIMARY KEY,      -- canonical name, as it appears in profiles
    motif        TEXT,                  -- repeat unit, e.g. AGAT
    period       SMALLINT,              -- length(motif)
    coordinates  JSONB NOT NULL DEFAULT '{}'::jsonb,  -- {GRCh38:{...}, hs1:{...}}; empty today
    panel_names  TEXT[],
    aliases      TEXT[],                -- alternate names used by other sources
    multi_copy   BOOLEAN NOT NULL DEFAULT false,      -- palindromic/duplicated; unscored by the age model
    source       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX str_marker_aliases_gin ON genomics.str_marker USING gin (aliases);

WITH alias(canonical, alt) AS (
    -- canonical (the form profiles use) ← alternate (rate-table / literature form)
    VALUES ('DYS19',      'DYS394'),    -- same locus; Willems/YHRD use the DYS394 designation
           ('Y-GATA-H4',  'YGATAH4'),
           ('Y-GATA-A10', 'YGATAA10'),
           ('DYS389I',    'DYS389i'),
           ('DYS389II',   'DYS389ii')
), canon AS (
    SELECT COALESCE(a.canonical, r.marker_name) AS marker_name,
           (regexp_match(r.source, 'motif ([ACGT]+)'))[1] AS motif,
           r.panel_names,
           r.source
      FROM genomics.str_mutation_rate r
      LEFT JOIN alias a ON a.alt = r.marker_name
), folded AS (
    -- The seed emitted DYS19 and DYS394 as separate rows for one locus (identical
    -- rate and motif), so both fold to DYS19 here. Prefer the row that carries a
    -- motif; ties break on name for determinism.
    SELECT DISTINCT ON (marker_name) *
      FROM canon
     ORDER BY marker_name, (motif IS NULL), source
)
INSERT INTO genomics.str_marker (marker_name, motif, period, panel_names, aliases, multi_copy, source)
SELECT f.marker_name,
       f.motif,
       length(f.motif)::smallint,
       f.panel_names,
       NULLIF(ARRAY(SELECT alt FROM alias WHERE canonical = f.marker_name), '{}'),
       -- Multi-copy (palindromic/duplicated) markers: the 7 observed as
       -- {type:multiCopy} in our profiles, plus DYF387S1, which is multi-copy but
       -- absent from the FTDNA DYS panel export. du_db::ystr scores none of them.
       f.marker_name IN ('DYS385','DYS464','CDY','YCAII','DYS459','DYF395S1','DYS413','DYF387S1'),
       'derived from genomics.str_mutation_rate (Willems 2016 1000G MUTEA / YHRD)'
  FROM folded f
ON CONFLICT (marker_name) DO UPDATE SET
    motif       = COALESCE(EXCLUDED.motif, genomics.str_marker.motif),
    period      = COALESCE(EXCLUDED.period, genomics.str_marker.period),
    panel_names = EXCLUDED.panel_names,
    aliases     = EXCLUDED.aliases,
    multi_copy  = EXCLUDED.multi_copy,
    source      = EXCLUDED.source,
    updated_at  = now();

-- The multi-copy markers absent from the rate table (they are unscored, so they
-- were never given one) still belong in the registry — otherwise the flag is true
-- for DYS385 and silently false for the six equally palindromic loci beside it.
-- No motif: a duplicated locus has one per copy, and we hold none of them.
INSERT INTO genomics.str_marker (marker_name, multi_copy, source)
SELECT m, true, 'multi-copy locus; observed as {type:multiCopy} in vendor profiles'
  FROM unnest(ARRAY['DYS464','CDY','YCAII','DYS459','DYF395S1','DYS413']) AS m
ON CONFLICT (marker_name) DO UPDATE SET
    multi_copy = true,
    updated_at = now();
