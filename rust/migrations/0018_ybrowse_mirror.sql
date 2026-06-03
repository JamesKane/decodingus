-- YBrowse source mirror. YBrowse publishes `snps_hg38.gff3` as a FULL snapshot
-- (no deltas), with the same physical SNP often appearing under several names on
-- separate lines. Ingest refreshes THIS mirror verbatim (one row per upstream
-- name); the curated `core.variant` catalog is then *derived* from the mirror by
-- reconciliation, so curator decisions survive re-ingest.
CREATE SCHEMA IF NOT EXISTS source;

CREATE TABLE source.ybrowse_snp (
    name        TEXT PRIMARY KEY,           -- the GFF Name/ID (authority identifier)
    contig      TEXT NOT NULL,
    position    BIGINT NOT NULL,
    allele_anc  TEXT,
    allele_der  TEXT,
    coordinates JSONB NOT NULL DEFAULT '{}'::jsonb,  -- multi-build {GRCh38, GRCh37, hs1}
    evidence    JSONB NOT NULL DEFAULT '{}'::jsonb,  -- source, isogg/ycc haplogroup, yfull, ref, primers, ...
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Physical-SNP key: reconciliation folds synonyms (same coordinate + alleles).
CREATE INDEX ybrowse_snp_physical ON source.ybrowse_snp (contig, position, allele_anc, allele_der);

-- Canonical-name preference for a synonym cluster (lower = more preferred). Ranks
-- by the name's alpha prefix; established authority prefixes first, provisional
-- (YFS/FTE/…) last. A cluster whose best rank is provisional (>= 90) gets a minted
-- DU name instead. TUNABLE domain policy — edit the CASE to adjust authority order.
CREATE FUNCTION core.ysnp_name_rank(nm text) RETURNS int LANGUAGE sql IMMUTABLE AS
$$ SELECT CASE upper(substring(nm from '^[A-Za-z]+'))
       WHEN 'M' THEN 1  WHEN 'P' THEN 2  WHEN 'L' THEN 3  WHEN 'U' THEN 4
       WHEN 'V' THEN 5  WHEN 'PF' THEN 6 WHEN 'CTS' THEN 7 WHEN 'Z' THEN 8
       WHEN 'S' THEN 9  WHEN 'DF' THEN 10 WHEN 'FGC' THEN 11 WHEN 'BY' THEN 12
       WHEN 'FT' THEN 13 WHEN 'Y' THEN 14 WHEN 'A' THEN 15
       WHEN 'YFS' THEN 90 WHEN 'YFE' THEN 91 WHEN 'FTE' THEN 92
       ELSE 50 END $$;

-- Synonym clusters whose names already map to MORE THAN ONE existing variant —
-- the catalog has them split across rows (some possibly tree-linked), so
-- reconciliation does NOT auto-merge them; it records them here for curator
-- review. Rebuilt each reconcile run.
CREATE TABLE source.ybrowse_reconcile_flag (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contig      TEXT NOT NULL,
    position    BIGINT NOT NULL,
    allele_anc  TEXT,
    allele_der  TEXT,
    names       TEXT[] NOT NULL,
    variant_ids BIGINT[] NOT NULL,
    flagged_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
