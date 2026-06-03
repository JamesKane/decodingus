-- Strand-canonical allele key, so the same physical SNP recorded on opposite
-- strands (A>G vs reverse-complement T>C) folds together during reconciliation.
-- Ancestral/derived ORDER is preserved, so a polarity swap (A>G vs G>A) does NOT
-- fold — that's a real disagreement, left for a curator.

CREATE FUNCTION core.dna_complement(b text) RETURNS text LANGUAGE sql IMMUTABLE AS
$$ SELECT CASE upper(b) WHEN 'A' THEN 'T' WHEN 'T' THEN 'A' WHEN 'C' THEN 'G' WHEN 'G' THEN 'C' ELSE upper(b) END $$;

-- Canonical "anc>der" for a SNP: the lexicographically-smaller of the forward
-- representation and its strand reverse-complement (single ACGT bases only;
-- anything else passes through upper-cased). e.g. both A>G and T>C → 'A>G'.
CREATE FUNCTION core.ysnp_canon(anc text, der text) RETURNS text LANGUAGE sql IMMUTABLE AS
$$ SELECT CASE
       WHEN anc IS NULL OR der IS NULL THEN COALESCE(upper(anc),'?') || '>' || COALESCE(upper(der),'?')
       WHEN anc ~ '^[ACGTacgt]$' AND der ~ '^[ACGTacgt]$' THEN
           least(upper(anc) || '>' || upper(der),
                 core.dna_complement(anc) || '>' || core.dna_complement(der))
       ELSE upper(anc) || '>' || upper(der)
   END $$;
