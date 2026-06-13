-- D4: the attributed-claim assertion store (R2 — non-PII, project-scoped). Co-admin
-- research is modeled as append-only, attributed, scoped assertions over a pseudonymous
-- research_subject (D2), NOT direct row mutation. The predicate's PII-class picks the
-- rail: only non-PII predicates land here (R2/R1); PII (MDKA_IS/IDENTITY/PII NOTE) is
-- R3 — D1 P2P only, folded locally in Navigator, and by construction has NO table here.
-- INVARIANT: every column is pseudonymous (UUID/DID) or a non-PII classification/JSONB.

-- Append-only claims. An edit is a new row with supersedes_id = old.id; a retraction
-- sets retracted_at. Nothing is overwritten — conflict-with-provenance (two admins
-- disagree → two live rows, both attributed).
CREATE TABLE research.assertion (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id    UUID NOT NULL REFERENCES research.research_subject(research_subject_id) ON DELETE CASCADE,
    predicate     TEXT NOT NULL,                 -- SAME_PERSON_AS | BELONGS_TO_BRANCH | HAPLOGROUP_IS | NOTE(non-PII)
    value         JSONB NOT NULL,                -- predicate-specific; scrubbed for obvious PII
    author_did    TEXT NOT NULL,                 -- attribution
    scope         TEXT NOT NULL,                 -- PUBLIC | PROJECT:<id>  (the consent/visibility boundary)
    evidence      JSONB,                         -- optional (STR distance, SNP, IBD ref, doc citation)
    record_uri    TEXT,                          -- at:// of the PDS record (R1) if any; NULL for R2
    supersedes_id BIGINT REFERENCES research.assertion(id),  -- append-only edit chain
    retracted_at  TIMESTAMPTZ,                   -- drops out of current_view; kept for audit
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- The live-claim lookup the fold reads (per subject/predicate/scope, non-retracted).
CREATE INDEX assertion_live_idx ON research.assertion (subject_id, predicate, scope) WHERE retracted_at IS NULL;

-- Materialized fold — per-(subject, predicate, scope), so a subject in two projects
-- never bleeds claims across them (per-project isolation). A project view folds its own
-- PROJECT:<id> claims together with PUBLIC ones; the recompute runs on every write.
CREATE TABLE research.subject_current_view (
    subject_id  UUID NOT NULL,
    predicate   TEXT NOT NULL,
    scope       TEXT NOT NULL,                   -- the viewing scope: PUBLIC | PROJECT:<id>
    state       TEXT NOT NULL,                   -- SETTLED | DISPUTED
    view        JSONB NOT NULL,                  -- live claims [{assertion_id, value, author_did, evidence, created_at}]
    refolded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (subject_id, predicate, scope)
);
