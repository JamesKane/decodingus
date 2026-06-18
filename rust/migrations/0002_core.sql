-- core schema: variants, specimen donors, the UNIFIED biosample, genome regions.
-- This migration realizes the central de-sprawl moves from plan §2.

-- ── Variants ──────────────────────────────────────────────────────────────
-- One row per variant. Replaces legacy `variant` + `variant_alias` + per-build
-- coordinate rows. JSONB payload shapes are defined in du-domain::variant.
CREATE TABLE core.variant (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    canonical_name  TEXT NOT NULL,
    mutation_type   core.mutation_type NOT NULL,
    naming_status   core.naming_status NOT NULL DEFAULT 'UNNAMED',
    aliases         JSONB NOT NULL DEFAULT '{}'::jsonb,   -- {common_names, rs_ids, sources}
    coordinates     JSONB NOT NULL DEFAULT '{}'::jsonb,   -- {GRCh38:{...}, hs1:{...}, ...}
    annotations     JSONB NOT NULL DEFAULT '{}'::jsonb,   -- {cytobands, str_overlaps}
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX variant_canonical_name_key ON core.variant (canonical_name);
-- GIN indexes on the queried JSONB paths (alias lookup, build-coordinate search).
CREATE INDEX variant_aliases_gin ON core.variant USING gin (aliases jsonb_path_ops);
CREATE INDEX variant_coordinates_gin ON core.variant USING gin (coordinates jsonb_path_ops);

-- ── Specimen donors ───────────────────────────────────────────────────────
-- Owns demographic data (consolidated from legacy biosample in evolutions 16/18).
CREATE TABLE core.specimen_donor (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    donor_identifier TEXT,
    origin_biobank  TEXT,
    sex             core.biological_sex,
    donor_type      core.biosample_source NOT NULL DEFAULT 'STANDARD',
    geocoord        geometry(Point, 4326),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX specimen_donor_geocoord_gist ON core.specimen_donor USING gist (geocoord);

-- ── Unified biosample ───────────────────────────────────────────────────────
-- Replaces three legacy tables (biosample, citizen_biosample, pgp_biosample).
-- `source` discriminates; `source_attrs` JSONB holds source-specific fields
-- (at_uri/at_cid, pgp_participant_id, ena accession, …). `atproto` is the single
-- consistent federation reference replacing scattered at_uri/at_cid columns.
CREATE TABLE core.biosample (
    sample_guid     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    donor_id        BIGINT REFERENCES core.specimen_donor(id),
    source          core.biosample_source NOT NULL,
    accession       TEXT,
    alias           TEXT,
    description     TEXT,
    center_name     TEXT,
    locked          BOOLEAN NOT NULL DEFAULT false,
    deleted         BOOLEAN NOT NULL DEFAULT false,
    source_attrs    JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- original haplogroup calls per publication (folded in from the dropped
    -- biosample_original_haplogroup / citizen_* tables).
    original_haplogroups JSONB NOT NULL DEFAULT '[]'::jsonb,
    atproto         JSONB,   -- {uri, cid, repo_did} or NULL when not federated
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX biosample_accession_key ON core.biosample (accession) WHERE accession IS NOT NULL;
CREATE INDEX biosample_source_idx ON core.biosample (source);
CREATE INDEX biosample_source_attrs_gin ON core.biosample USING gin (source_attrs jsonb_path_ops);
-- Unique federation URI when present (citizen samples carry an at:// URI).
CREATE UNIQUE INDEX biosample_atproto_uri_key
    ON core.biosample ((atproto->>'uri')) WHERE atproto IS NOT NULL;

-- ── Genome regions ──────────────────────────────────────────────────────────
-- Multi-build structural regions (centromere/telomere/PAR/…). Coordinates as
-- JSONB keyed by build (legacy genome_region_v2 shape).
CREATE TABLE core.genome_region (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    region_type     TEXT NOT NULL,
    name            TEXT NOT NULL,
    coordinates     JSONB NOT NULL DEFAULT '{}'::jsonb,   -- {GRCh38:{contig,start,end}, hs1:{...}}
    properties      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX genome_region_type_name_key ON core.genome_region (region_type, name);
CREATE INDEX genome_region_coordinates_gin ON core.genome_region USING gin (coordinates jsonb_path_ops);
