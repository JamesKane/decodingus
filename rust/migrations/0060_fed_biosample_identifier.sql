-- Federated mirror of a biosample record's published external identifiers
-- (Phase 3 of proposals/biosample-identifier-dedup.md). Navigator publishes an
-- `externalIds: [{namespace, value}]` list on the biosample record; the AppView mirrors
-- it here so `link_federated_subjects` (Phase 4) can match a re-published donor to its
-- existing `core.biosample_identifier` row deterministically, at ingest, without genotype.
--
-- Keyed to the fed record by (did, rkey); cascade-deleted when the biosample record is.
-- `is_public` is derived by the AppView from a namespace policy (public catalog ids
-- displayable; vendor kits background-only) — see du_db::identifier::is_public_namespace.
CREATE TABLE fed.biosample_identifier (
    did        TEXT NOT NULL,
    rkey       TEXT NOT NULL,
    at_uri     TEXT NOT NULL,
    namespace  TEXT NOT NULL,          -- FTDNA | YSEQ | PGP | IGSR | ENA | ...
    value      TEXT NOT NULL,          -- normalized (upper, trimmed)
    is_public  BOOLEAN NOT NULL DEFAULT false,
    indexed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey, namespace, value),
    FOREIGN KEY (did, rkey) REFERENCES fed.biosample (did, rkey) ON DELETE CASCADE
);

-- The anchor-dedup lookup key (Phase 4): resolve a published (namespace, value) to its
-- federated record.
CREATE INDEX fed_bsid_ns_value_idx ON fed.biosample_identifier (namespace, value);
