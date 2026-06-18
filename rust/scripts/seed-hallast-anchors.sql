-- Seed genealogical age-calibration anchors from Hallast et al. 2026
-- ("Population-scale Y chromosome assemblies...", bioRxiv 2026.06.03.729890).
--
-- These are BEAST-dated TMRCAs from the paper's time-calibrated phylogeny
-- (Suppl. Fig. 1, ISOGG v15.73 labels; 95% HPD intervals) — *model-dated*, not
-- radiocarbon, hence anchor_type = 'MODEL_DATED' with full provenance in details
-- so a curator can down-weight or exclude them. See the "Calibration anchors"
-- subsection of documents/proposals/branch-age-estimation.md (incl. the
-- circularity caveat: these calibrate our SNP clock against another SNP clock).
--
-- Storage convention (matches du_db::age anchor consumer):
--   date_ce = PRESENT_YEAR(1950) - TMRCA_ybp   → consumer recovers ybp
--   details->>'uncertainty_years' = sigma = (HPD_hi - HPD_lo) / (2 * 1.96)
--
-- Idempotent: clears prior Hallast-sourced rows, then re-inserts only for clades
-- whose name exists in the current tree (keyed by name, never by id — ids churn
-- across loads). Run AFTER the Y tree is loaded:
--   psql "$DATABASE_URL" -f scripts/seed-hallast-anchors.sql

BEGIN;

DELETE FROM tree.genealogical_anchor
WHERE details->>'source' = 'Hallast et al. 2026';

INSERT INTO tree.genealogical_anchor (haplogroup_id, anchor_type, date_ce, confidence, details)
SELECT h.id,
       'MODEL_DATED',
       1950 - v.tmrca_ybp,
       v.confidence,
       jsonb_build_object(
         'source', 'Hallast et al. 2026',
         'reference', 'bioRxiv 2026.06.03.729890',
         'clock', 'BEAST v1.10.4 strict molecular clock (0.76e-9 sub/site/yr)',
         'tmrca_ybp', v.tmrca_ybp,
         'hpd95_low_ybp', v.hpd_lo,
         'hpd95_high_ybp', v.hpd_hi,
         'uncertainty_years', round((v.hpd_hi - v.hpd_lo) / (2 * 1.96))::int,
         'figure', v.figure,
         'note', v.note
       )
FROM (VALUES
        -- clade,   TMRCA, HPD_lo, HPD_hi, conf,  figure,        note
        ('D1',      19450, 16360,  22880,  0.80, 'Fig 1b / Suppl. Fig 1', 'major-branch TMRCA on ISOGG v15.73 phylogeny')
     ) AS v(name, tmrca_ybp, hpd_lo, hpd_hi, confidence, figure, note)
JOIN tree.haplogroup h
  ON h.name = v.name
 AND h.haplogroup_type = 'Y_DNA'::core.dna_type
 AND h.valid_until IS NULL;

-- Pending nodes (recorded, not yet seeded — no clean haplogroup-name mapping):
--   • HG00512 ⋂ HG02056 TMRCA ~10,300 ybp (HPD 8,400–12,300; Suppl. Fig 61) —
--     a sample-pair MRCA; needs the ISOGG label of their common node.
--   • HG00609-referenced node TMRCA 10,350 ybp (HPD 8,540–12,330).
-- Harvest more major-branch nodes from the Suppl. Tables workbook (Fig 1b / Suppl.
-- Fig 1 are figures; per-node TMRCAs are not in the extractable supplement text)
-- and add VALUES rows above once mapped to our clade names.

COMMIT;
