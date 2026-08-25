-- DecodingUs Grid — the coordination substrate for community realignment & analysis.
-- Design: `documents/design/distributed-compute-grid.md` in the DUNavigator repo (§4, §11).
--
-- WHY THIS EXISTS. The AppView publishes a list of public-ENA work units; volunteer Navigator
-- instances lease one, fetch it, (re)align it to CHM13, run the analysis stack, and submit a
-- signed digest. Validated contributions earn compute credit on a public leaderboard. The payoff
-- is a uniformly hs1-aligned community corpus derived once and verified, at no central compute
-- cost. This migration is the coordination half: the catalogue, the lease, the submission, and
-- the credit ledger.
--
-- WHAT IS DELIBERATELY REUSED, not rebuilt:
--   * `fed.pds_node`      — the node registry (capabilities, heartbeat, software_version) from
--                           `0008_fed.sql`, built for almost exactly this and never wired.
--   * `fed.device_key`    — Ed25519 keys a node published to its own repo. Every grid endpoint
--                           authenticates through `du_web::sig::verify_signed{,_fresh}`, the same
--                           path the D1 exchange and the recruitment Edge already use.
--   * `ident.users`       — credit attribution, joined on the DID.
-- `fed.pds_submission` is NOT reused: its status lifecycle means curator review of a proposed
-- variant call, which is a different thing from digest quorum, and overloading it would make both
-- meanings unreadable.
--
-- THE STATE MODEL, and why it is not the one the design sketched. The design's §3 diagram gives
-- the work unit the states AVAILABLE → LEASED → SUBMITTED → CANONICAL. That cannot express what
-- the same document requires two paragraphs later: `required_replicas` defaults to 2, so a unit
-- routinely needs a second independent result while a first node still holds a lease. "LEASED"
-- and "SUBMITTED" would each have to mean "…and also still claimable", which is not a state.
--
-- So `work_unit.state` carries only the lifecycle milestones that are genuinely exclusive
-- (AVAILABLE / CANONICAL / CONTESTED / RETIRED), and **claimability is derived**:
--
--     claimable  ⇔  state IN ('AVAILABLE','CONTESTED')
--                   AND (active leases + non-divergent submissions) < required_replicas
--                   AND the calling DID holds no lease or submission on the unit
--
-- One SELECT … FOR UPDATE SKIP LOCKED answers that, which is also the design's §4.2 requirement.
-- The lease and submission tables are the source of truth for "how many replicas are in flight";
-- no counter needs maintaining, so no counter can drift.
--
-- P1 SCOPE (decided 2026-08-24, amending the design's D3). D3 staged CRAM-passthrough first to
-- retire aligner risk before the coordination loop. That risk evaporated when the realignment
-- module shipped on a pure-Rust mapper, so P1 now carries BOTH data kinds — hence `data_kind` on
-- the work unit from the first migration rather than added later, and `est_bases` from the start
-- because the per-Gbp credit factor is live immediately.

CREATE SCHEMA IF NOT EXISTS grid;   -- distributed community compute: work units, leases, credit

-- The catalogue of claimable work. One row = one ENA **sample** (decided 2026-08-24 over
-- per-run), because that is the grain Navigator analyses at: `App::analyze_biosample` is the unit
-- of work on the edge, consensus haplogroups are per-biosample, and `du_jobs::crawl_project`
-- already groups ENA runs by sample. A multi-run sample is one lease whose manifest lists every
-- run; the node merges them, which is work it must do anyway before consensus means anything.
CREATE TABLE grid.work_unit (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_accession  TEXT NOT NULL UNIQUE,      -- SAMEA…/SAMN… — the unit's identity
    study_accession   TEXT,                      -- PRJEB…/PRJNA… — provenance + curation filter
    data_kind         TEXT NOT NULL,             -- CRAM/FASTQ — decides whether the node realigns
    -- Everything the node needs to fetch without asking ENA anything itself. One entry per file:
    -- {run_accession, url, md5, bytes, format}. Curated centrally so a fleet does not hammer the
    -- ENA portal rediscovering the same file list (design §6.2, ENA fair-use).
    manifest          JSONB NOT NULL DEFAULT '[]'::jsonb,
    est_bases         BIGINT,                    -- read_count × read_length; drives per-Gbp credit
    total_bytes       BIGINT,                    -- the node's download budget preflight
    state             TEXT NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE/CANONICAL/CONTESTED/RETIRED
    required_replicas SMALLINT NOT NULL DEFAULT 2,
    canonical_digest  JSONB,                     -- the agreed digest, once a quorum forms
    canonical_at      TIMESTAMPTZ,
    note              TEXT,                      -- why RETIRED, or what CONTESTED it
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The claim path's index. Partial, because a mature catalogue is mostly CANONICAL and the claim
-- query never looks at those rows.
CREATE INDEX work_unit_claimable_idx ON grid.work_unit (data_kind, id)
    WHERE state IN ('AVAILABLE', 'CONTESTED');
CREATE INDEX work_unit_study_idx ON grid.work_unit (study_accession);

-- A reservation of one unit by one node, for a bounded time. Separate from the work unit because
-- `required_replicas` > 1 means several nodes legitimately hold concurrent leases on the same
-- unit — the thing a `leased_by` column on the unit itself cannot represent.
CREATE TABLE grid.lease (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    work_unit_id  BIGINT NOT NULL REFERENCES grid.work_unit(id) ON DELETE CASCADE,
    did           TEXT NOT NULL,                 -- the contributor; authenticated per request
    node_id       BIGINT REFERENCES fed.pds_node(id) ON DELETE SET NULL,
    claimed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,          -- honesty bound; the reaper reclaims past this
    heartbeat_at  TIMESTAMPTZ,                   -- extends nothing by itself; evidence of liveness
    progress      JSONB,                         -- {stage, fraction} for the fleet view
    released_at   TIMESTAMPTZ,                   -- NULL ⇒ active
    outcome       TEXT                           -- SUBMITTED/RELEASED/EXPIRED
);

-- One *active* lease per (unit, DID). A node that re-claims a unit it already holds gets its
-- existing lease back rather than a second row, which makes `claim` idempotent under retry.
-- Partial, so the history of released leases on the same unit stays queryable.
CREATE UNIQUE INDEX lease_active_unit_did_idx ON grid.lease (work_unit_id, did)
    WHERE released_at IS NULL;
CREATE INDEX lease_expiry_idx ON grid.lease (expires_at) WHERE released_at IS NULL;
CREATE INDEX lease_did_idx ON grid.lease (did);

-- A signed result. The digest — not the BAM — is what validation compares: realignment is not
-- byte-deterministic across thread counts and builds, so the agreement test runs over discrete
-- calls plus bucketed continuous metrics (design §5.2).
--
-- NOTE on `mt_terminal`: the design listed it as an exact-match field. It is NOT (decided
-- 2026-08-24). `App::analyze_biosample` declines to assign mtDNA because that value "is not final
-- on CHM13", and the Grid realigns *to* CHM13 — so the digest cannot require a value the analysis
-- path deliberately does not produce. mt is still published in the full records; it just does not
-- gate canonicalization.
CREATE TABLE grid.submission (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    work_unit_id    BIGINT NOT NULL REFERENCES grid.work_unit(id) ON DELETE CASCADE,
    did             TEXT NOT NULL,
    lease_id        BIGINT REFERENCES grid.lease(id) ON DELETE SET NULL,
    digest          JSONB NOT NULL,              -- the canonical digest object, as signed
    digest_sig      TEXT NOT NULL,               -- Ed25519 over the canonical digest bytes
    stack_version   TEXT NOT NULL,               -- only compatible majors are compared
    reference_build TEXT NOT NULL,               -- pins what the calls are even about
    aligner         TEXT,                        -- NULL for CRAM passthrough
    record_refs     JSONB NOT NULL DEFAULT '[]'::jsonb,  -- at:// URIs of the published fed records
    status          TEXT NOT NULL DEFAULT 'PENDING',     -- PENDING/AGREED/DIVERGENT/SUPERSEDED
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    validated_at    TIMESTAMPTZ
);

-- One submission per (unit, DID): a contributor cannot pad a quorum with its own repeats, and a
-- resubmit is an update rather than a second vote (design §6.2, free-riding).
CREATE UNIQUE INDEX submission_unit_did_idx ON grid.submission (work_unit_id, did);
CREATE INDEX submission_pending_idx ON grid.submission (work_unit_id) WHERE status = 'PENDING';
CREATE INDEX submission_did_idx ON grid.submission (did);

-- The cobblestone ledger. Append-only, awarded only on AGREED/canonical, and unique per
-- (unit, DID) so a re-validation cannot pay twice. The leaderboard is a SUM over this joined to
-- `ident.users` on the DID.
CREATE TABLE grid.credit (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    did           TEXT NOT NULL,
    -- Resolved at award time. Nullable because a `did:key` node need not have an account yet;
    -- such a row is still an honest record of work done, it just cannot appear on the board.
    user_id       UUID REFERENCES ident.users(id) ON DELETE SET NULL,
    work_unit_id  BIGINT NOT NULL REFERENCES grid.work_unit(id) ON DELETE CASCADE,
    submission_id BIGINT REFERENCES grid.submission(id) ON DELETE SET NULL,
    -- Milli-cobblestones, as an exact integer. NUMERIC would need a decimal feature on sqlx that
    -- this workspace does not build, and f64 is the wrong shape for a ledger that gets SUMmed over
    -- every contribution ever made. Three decimal places was the intended precision anyway, so the
    -- integer *is* the value — 1 cobblestone = 1000 here. Never render this number raw.
    cobblestones_milli BIGINT NOT NULL,
    kind          TEXT NOT NULL,                 -- CANONICAL_FIRST/QUORUM_AGREE/SPOTCHECK_PASS
    awarded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX credit_unit_did_idx ON grid.credit (work_unit_id, did);
CREATE INDEX credit_did_idx ON grid.credit (did);
CREATE INDEX credit_user_idx ON grid.credit (user_id) WHERE user_id IS NOT NULL;
