-- External-identifier index for biosample dedup (proposals/biosample-identifier-dedup.md).
--
-- One physical donor recurs across vendor namespaces (FTDNA kit, YSEQ #, Dante, FGC,
-- Nebula) and public catalogs (PGP, IGSR/ENA). The overloaded `core.biosample.accession`/
-- `alias` single slots can't hold that many-to-one mapping, and vendor kit ids must exist
-- for dedup yet never appear on a public surface. This table is the deterministic dedup
-- key: `(namespace, value)` → one sample.
--
-- Privacy: `is_public=false` rows (vendor kits) are matched in the background but excluded
-- from every public read projection; `is_public=true` rows (PGP/IGSR/ENA catalog ids) are
-- displayable. No hashing — a vendor kit does not identify the donor (it resolves to a
-- person only via a vendor's private roster), so confidentiality is by non-disclosure.
CREATE TABLE core.biosample_identifier (
    id           BIGSERIAL PRIMARY KEY,
    sample_guid  UUID NOT NULL REFERENCES core.biosample(sample_guid) ON DELETE CASCADE,
    namespace    TEXT NOT NULL,          -- FTDNA | YSEQ | DANTE | FGC | NEBULA | PGP | IGSR | ENA | ...
    value        TEXT NOT NULL,          -- normalized vendor/catalog id (upper, trimmed)
    is_public    BOOLEAN NOT NULL DEFAULT false,
    confidence   TEXT NOT NULL DEFAULT 'ASSERTED',   -- ASSERTED | VERIFIED
    source       TEXT NOT NULL,          -- 'cohort-manifest' | 'federation' | 'curator'
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One (namespace, value) maps to exactly one sample — the deterministic dedup key. Namespacing
-- by vendor separates cross-vendor numeric-kit collisions (YSEQ:229 != DANTE:229).
CREATE UNIQUE INDEX bsid_ns_value_key ON core.biosample_identifier (namespace, value);
CREATE INDEX bsid_sample_idx ON core.biosample_identifier (sample_guid);
-- Public-tier lookups (the only rows a public read path may touch).
CREATE INDEX bsid_public_idx ON core.biosample_identifier (namespace, value) WHERE is_public;
