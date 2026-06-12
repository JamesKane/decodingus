-- D6: the privateVariant lexicon mirror. Citizens publish their per-sample private
-- variant set (mutations beyond the assigned terminal haplogroup) as a
-- com.decodingus.atmosphere.privateVariant record; the Jetstream consumer mirrors
-- it here. The discovery consensus engine (du_db::discovery) materializes these
-- into tree.biosample_private_variant and pools them into proposed branches.
--
-- Privacy posture matches the existing strProfile/biosample summary records:
-- citizen-opt-in, keyed by biosample ref (no donor PII), variants anonymized to
-- coordinates/names.

CREATE TABLE fed.private_variant (
    did               TEXT NOT NULL,
    rkey              TEXT NOT NULL,
    at_uri            TEXT NOT NULL,
    cid               TEXT,
    biosample_ref     TEXT,                  -- at-uri of the parent biosample (join key)
    sequence_run_ref  TEXT,                  -- optional, for precision
    dna_type          TEXT,                  -- Y_DNA / MT_DNA
    terminal_haplogroup TEXT,                -- the assigned terminal (name string)
    variants          JSONB NOT NULL DEFAULT '[]'::jsonb,  -- [{name?,contig,position,ancestral,derived,rsId?}]
    record_created_at TIMESTAMPTZ,
    time_us           BIGINT NOT NULL,
    indexed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);
CREATE INDEX fed_private_variant_biosample_idx ON fed.private_variant (biosample_ref);
