-- De-novo tree vs reference (ISOGG / PhyloTree) placement conflicts, surfaced for
-- curator triage. Each row = a reference clade whose de-novo placement disagrees:
-- `foreign_in` tips sit inside the clade's de-novo home node that don't belong,
-- and/or `members_away` of the clade's members landed elsewhere; `magnitude` ranks
-- the discrepancy. Populated by the de-novo loader (du_db::denovo::load) and
-- replaced per dna_type on each reload (cleared by haplogroup::clear_dna).
CREATE TABLE tree.denovo_conflict (
    id             BIGSERIAL PRIMARY KEY,
    dna_type       core.dna_type NOT NULL,
    haplogroup     TEXT NOT NULL,          -- the reference clade (ISOGG / mt haplogroup)
    label          TEXT,                   -- display label
    n_tips         INTEGER NOT NULL,
    magnitude      INTEGER NOT NULL,
    home_node      TEXT,                   -- the de-novo NodeN the clade maps to
    foreign_in     INTEGER NOT NULL,       -- foreign tips inside the home node
    members_away   INTEGER NOT NULL,       -- clade members placed outside the home node
    source         TEXT NOT NULL DEFAULT 'decodingus-denovo',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX denovo_conflict_triage_idx
    ON tree.denovo_conflict (dna_type, magnitude DESC, n_tips DESC);
