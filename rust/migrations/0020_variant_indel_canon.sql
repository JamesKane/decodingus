-- INDEL handling for reconciliation, borrowing the Scala YBrowse heuristics
-- (repeat-notation expansion + VCF-style trim). YBrowse labels every row 'snp'
-- and encodes most indels as bare "ins"/"del" markers (no bases); a handful are
-- true multi-base alleles. MNPs (equal-length multi-base) are left ALONE.

-- "3T" -> "TTT", "2AG" -> "AGAG"; an already-nucleotide allele passes through;
-- a non-nucleotide marker (ins/del/.) returns its upper-cased letters.
CREATE FUNCTION core.ysnp_expand_repeat(a text) RETURNS text LANGUAGE plpgsql IMMUTABLE AS
$$
DECLARE up text; digits text; bases text;
BEGIN
    up := upper(trim(coalesce(a, '')));
    IF up ~ '^[ACGTN]+$' THEN RETURN up; END IF;
    digits := regexp_replace(up, '[^0-9]', '', 'g');
    bases  := regexp_replace(up, '[0-9]', '', 'g');
    IF digits <> '' AND bases <> '' THEN RETURN repeat(bases, digits::int); END IF;
    RETURN bases;  -- e.g. "INS"/"DEL" markers, digits stripped
END
$$;

-- Classify a variant from its ancestral/derived alleles: single bases = SNP;
-- equal-length multi-base = MNP (left alone); unequal/markers = INDEL.
CREATE FUNCTION core.ysnp_mutation_type(anc text, der text) RETURNS text LANGUAGE plpgsql IMMUTABLE AS
$$
DECLARE a text; d text;
BEGIN
    a := core.ysnp_expand_repeat(anc);
    d := core.ysnp_expand_repeat(der);
    IF a ~ '^[ACGT]$' AND d ~ '^[ACGT]$' THEN RETURN 'SNP'; END IF;
    IF a ~ '^[ACGT]+$' AND d ~ '^[ACGT]+$' THEN
        IF length(a) = length(d) THEN RETURN 'MNP'; END IF;
        RETURN 'INDEL';
    END IF;
    RETURN 'INDEL';  -- ins/del markers / gaps / dirty
END
$$;

-- Replace the SNP-only canon with one that also folds equivalent INDEL
-- representations: SNP -> strand-canonical (as before); MNP -> left alone;
-- INDEL -> trim common suffix then prefix (keeping >=1 base each — VCF-style,
-- minus the reference-FASTA padding we don't have), so e.g. T>TC and TA>TCA fold.
DROP FUNCTION IF EXISTS core.ysnp_canon(text, text);
CREATE FUNCTION core.ysnp_canon(anc text, der text) RETURNS text LANGUAGE plpgsql IMMUTABLE AS
$$
DECLARE a text; d text;
BEGIN
    a := core.ysnp_expand_repeat(anc);
    d := core.ysnp_expand_repeat(der);
    IF a ~ '^[ACGT]$' AND d ~ '^[ACGT]$' THEN
        RETURN least(a || '>' || d, core.dna_complement(a) || '>' || core.dna_complement(d));
    END IF;
    IF a ~ '^[ACGT]+$' AND d ~ '^[ACGT]+$' THEN
        IF length(a) = length(d) THEN RETURN a || '>' || d; END IF;  -- MNP: untouched
        WHILE length(a) > 1 AND length(d) > 1 AND right(a, 1) = right(d, 1) LOOP
            a := left(a, length(a) - 1); d := left(d, length(d) - 1);
        END LOOP;
        WHILE length(a) > 1 AND length(d) > 1 AND left(a, 1) = left(d, 1) LOOP
            a := right(a, length(a) - 1); d := right(d, length(d) - 1);
        END LOOP;
        RETURN a || '>' || d;
    END IF;
    RETURN a || '>' || d;  -- markers / gaps
END
$$;
