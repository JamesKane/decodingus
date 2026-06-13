-- Seed the sequencer lab + instrument lookup from the old YDNA Warehouse d2c export
-- (instrument_centers.tsv). Rule: keep instruments with n_crams > 2; when an instrument
-- maps to several labs, assign the max-frequency one (a NO_CSV "no lab" placeholder never
-- wins a qualifying row). Lab short codes → canonical full names; all is_d2c. model_name =
-- the export's platform string; manufacturer derived (Illumina / MGI / unknown→NULL).
-- The lookup (du_db::sequencer::lookup_lab → /api/v1/sequencer/lab) reads this tie directly.
-- Idempotent: labs ON CONFLICT DO NOTHING (preserve any consensus-created row), instruments
-- ON CONFLICT DO UPDATE (corrective re-run).

INSERT INTO genomics.sequencing_lab (name, is_d2c) VALUES
    ('Family Tree DNA', true),
    ('Dante Labs', true),
    ('Nebula Genomics', true),
    ('Full Genomes Corporation', true),
    ('YSEQ', true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO genomics.sequencer_instrument (instrument_id, model_name, manufacturer, lab_id) VALUES
    -- Family Tree DNA (6)
    ('A00186',        'NovaSeq6000',                  'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Family Tree DNA')),
    ('SN7001368',     'HiSeq2000/2500',               'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Family Tree DNA')),
    ('SN7001371',     'HiSeq2000/2500',               'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Family Tree DNA')),
    ('HWI-ST1368',    'HiSeq2000',                    'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Family Tree DNA')),
    ('USSD-TL1-1227', 'unknown',                      NULL,       (SELECT id FROM genomics.sequencing_lab WHERE name = 'Family Tree DNA')),
    ('8QRF6V1',       'unknown',                      NULL,       (SELECT id FROM genomics.sequencing_lab WHERE name = 'Family Tree DNA')),
    -- Dante Labs (6)
    ('A00925',        'NovaSeq6000',                  'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Dante Labs')),
    ('A00910',        'NovaSeq6000',                  'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Dante Labs')),
    ('A00966',        'NovaSeq6000',                  'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Dante Labs')),
    ('A01245',        'NovaSeq6000',                  'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Dante Labs')),
    ('A00197',        'NovaSeq6000',                  'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Dante Labs')),
    ('V100002649',    'MGI/BGI(DNBSEQ)',              'MGI',      (SELECT id FROM genomics.sequencing_lab WHERE name = 'Dante Labs')),
    -- YSEQ (11)
    ('YSEQ1',         'MGI in-house (YSEQ)',          'MGI',      (SELECT id FROM genomics.sequencing_lab WHERE name = 'YSEQ')),
    ('A00182',        'NovaSeq6000',                  'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'YSEQ')),
    ('A00788',        'NovaSeq6000',                  'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'YSEQ')),
    ('FP200006039',   'MGI/BGI(DNBSEQ)',              'MGI',      (SELECT id FROM genomics.sequencing_lab WHERE name = 'YSEQ')),
    ('V350158671',    'MGI/BGI(DNBSEQ)',              'MGI',      (SELECT id FROM genomics.sequencing_lab WHERE name = 'YSEQ')),
    ('V300063980',    'MGI/BGI(DNBSEQ)',              'MGI',      (SELECT id FROM genomics.sequencing_lab WHERE name = 'YSEQ')),
    ('V350181030',    'MGI/BGI(DNBSEQ)',              'MGI',      (SELECT id FROM genomics.sequencing_lab WHERE name = 'YSEQ')),
    ('V350218029',    'MGI/BGI(DNBSEQ)',              'MGI',      (SELECT id FROM genomics.sequencing_lab WHERE name = 'YSEQ')),
    ('V350218277',    'MGI/BGI(DNBSEQ)',              'MGI',      (SELECT id FROM genomics.sequencing_lab WHERE name = 'YSEQ')),
    ('V350202941',    'MGI/BGI(DNBSEQ)',              'MGI',      (SELECT id FROM genomics.sequencing_lab WHERE name = 'YSEQ')),
    ('V350180275',    'MGI/BGI(DNBSEQ)',              'MGI',      (SELECT id FROM genomics.sequencing_lab WHERE name = 'YSEQ')),
    -- Full Genomes Corporation (11)
    ('ST-E00317',     'HiSeqX',                       'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Full Genomes Corporation')),
    ('ST-E00192',     'HiSeqX',                       'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Full Genomes Corporation')),
    ('E00500',        'HiSeqX',                       'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Full Genomes Corporation')),
    ('ST-E00126',     'HiSeqX',                       'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Full Genomes Corporation')),
    ('E00548',        'HiSeqX',                       'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Full Genomes Corporation')),
    ('E00576',        'HiSeqX',                       'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Full Genomes Corporation')),
    ('ST-E00494',     'HiSeqX',                       'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Full Genomes Corporation')),
    ('ST-E00142',     'HiSeqX',                       'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Full Genomes Corporation')),
    ('D00539',        'HiSeq2500',                    'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Full Genomes Corporation')),
    ('FCC3KJLACXX',   'HiSeq2000/2500(flowcell-id)',  'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Full Genomes Corporation')),
    ('A00818',        'NovaSeq6000',                  'Illumina', (SELECT id FROM genomics.sequencing_lab WHERE name = 'Full Genomes Corporation')),
    -- Nebula Genomics (2)
    ('E100006791',    'MGI/BGI(DNBSEQ)',              'MGI',      (SELECT id FROM genomics.sequencing_lab WHERE name = 'Nebula Genomics')),
    ('FP200007833',   'MGI/BGI(DNBSEQ)',              'MGI',      (SELECT id FROM genomics.sequencing_lab WHERE name = 'Nebula Genomics'))
ON CONFLICT (instrument_id) DO UPDATE SET
    model_name = EXCLUDED.model_name,
    manufacturer = EXCLUDED.manufacturer,
    lab_id = EXCLUDED.lab_id;
