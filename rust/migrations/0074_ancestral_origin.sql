-- Mirrored ancestral-origin records (`com.decodingus.atmosphere.ancestralOrigin`) — the
-- locality substrate for the genealogical-era origins icicle on the public tree.
-- Design: `proposals/ancestral-origin-icicle.md`.
--
-- WHY THIS EXISTS. The AppView's only locality datum is `core.specimen_donor.geocoord`, and
-- of 9,642 placed Y samples only 1,380 carry one — all of them ancient or academic. The 7,882
-- `cohort=bigy` D2C tips, which are the entire genealogical era, have 3 between them. A view of
-- where a branch's lines went had nothing to draw from.
--
-- PRIVACY. This is the MDKA (most distant known ancestor) of a lineage: the surname, origin and
-- dates of the earliest documented paternal-line ancestor. Per
-- `proposals/biosample-identifier-dedup.md`, MDKA is genealogical context, NOT living-donor PII —
-- and this table narrows that rather than widening it. Gates enforced at ingest, each REJECTING
-- the record rather than merely hiding it (see `du_jobs::jetstream::build_ancestral_origin`):
--
--   1. `surname` is a single token — never a given name, whatever the client sent.
--   2. `birth_year <= 1900`, the check that makes "not PII" verifiable rather than asserted.
--   3. No birth year → country only; place text and coordinate are dropped.
--   4. Coordinates coarsened to 2dp (~1 km) at ingest, because the publisher cannot be trusted
--      to have done it.
--   5. The join key (an FTDNA kit id) is never rendered — every vendor namespace is
--      `core.biosample_identifier.is_public = false`.
--
-- Two older migration headers say MDKA never reaches the AppView: `0012_fed_reporting` here and
-- `0030_mdka` in Navigator. They are NOT edited — both repos run `sqlx::migrate!`, which
-- checksums applied migrations, so editing even a comment would fail every existing database
-- with VersionMismatch. `proposals/ancestral-origin-icicle.md` §2 is the amendment of record.
--
-- The D4 assertion-store PII rail (`research.assertion` rejecting MDKA_IS) STANDS UNCHANGED: it
-- governs assertions about a *living research subject* inside a project, which is a different
-- question from publishing a deceased ancestor's parish — and that rail is what keeps them apart.
--
-- Envelope matches `fed.private_variant` (mig 0028): keyed (did, rkey), one collection per table,
-- idempotent time_us-ordered upsert from the firehose.

CREATE TABLE fed.ancestral_origin (
    did             TEXT NOT NULL,
    rkey            TEXT NOT NULL,
    at_uri          TEXT NOT NULL,
    cid             TEXT,
    -- at-uri of the parent biosample record. Present only for genuinely federated samples;
    -- the bulk-loaded tips that carry the genealogical era have none, so resolution normally
    -- runs through `external_ids` below (see the design doc §4).
    biosample_ref   TEXT,
    -- Published external identifiers, `[{namespace, value}]` — the working join key against
    -- `core.biosample_identifier (namespace, value)`. Vendor namespaces stay background-only.
    external_ids    JSONB NOT NULL DEFAULT '[]'::jsonb,
    lineage         TEXT,                  -- Y_DNA | MT_DNA
    surname         TEXT,                  -- single token; gate 1
    origin_place    TEXT,                  -- as recorded; normalized at read time by du_db::place
    origin_country  TEXT,
    birth_year      INTEGER,               -- gate 2 bounds this
    death_year      INTEGER,
    geocoord        geometry(Point, 4326), -- gate 4 coarsens this
    record_created_at TIMESTAMPTZ,
    time_us         BIGINT NOT NULL,
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);

-- Resolution paths: the at-uri join (federated samples) and the identifier join (everything on
-- the tree today). The GIN index serves the `external_ids @> [{namespace,value}]` containment
-- lookup the aggregate uses.
CREATE INDEX fed_ancestral_origin_biosample_idx ON fed.ancestral_origin (biosample_ref)
    WHERE biosample_ref IS NOT NULL;
CREATE INDEX fed_ancestral_origin_extids_gin ON fed.ancestral_origin
    USING gin (external_ids jsonb_path_ops);
-- The icicle reads one arm at a time.
CREATE INDEX fed_ancestral_origin_lineage_idx ON fed.ancestral_origin (lineage);
