-- Per-variant authority provenance (universal-variant-schema "evidence"). The
-- YBrowse GFF3 — the central document Y-DNA naming authorities flow through —
-- carries the SNP's haplogroup hint, YFull node, citation, primers, and comment.
-- Ingestion (the GFF3 job) populates this; other paths leave it '{}'.
ALTER TABLE core.variant ADD COLUMN evidence JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX variant_evidence_gin ON core.variant USING gin (evidence jsonb_path_ops);
