-- IBD attestation ingest: let two consented Edges report the *outcome* of their
-- encrypted comparison back to the AppView. The match graph (ibd.ibd_discovery_index)
-- + attestations (ibd.ibd_pds_attestation) already exist (mig 0007); this adapts the
-- attestation row to the DID-based device-key auth (the AT Protocol pivot — attestations
-- are signed by an Edge's DID, not a PDS guid) and makes ingest idempotent.

-- The DID that signed the attestation (the PDS-guid identity predates the device-key
-- pivot; DID is the live identity). Legacy guid kept but no longer required.
ALTER TABLE ibd.ibd_pds_attestation ADD COLUMN attesting_did TEXT;
ALTER TABLE ibd.ibd_pds_attestation ALTER COLUMN attesting_pds_guid DROP NOT NULL;

-- Provenance: the consented exchange this attestation came out of (the privacy rail —
-- every graph edge traces to a real dual-consent). NULL only for legacy rows.
ALTER TABLE ibd.ibd_pds_attestation ADD COLUMN exchange_request_uri TEXT;

-- Each party's own reported figures, kept per-attestation so consensus can check that
-- the two sides *agree* (a match is CONFIRMED only when both report compatible totals).
-- The agreed value is then summarized onto ibd_discovery_index.total_shared_cm_approx.
ALTER TABLE ibd.ibd_pds_attestation ADD COLUMN reported_total_cm DOUBLE PRECISION;
ALTER TABLE ibd.ibd_pds_attestation ADD COLUMN reported_segments INTEGER;

-- Idempotent ingest: one attestation per (match, attester, type). A re-submit corrects
-- the prior figures (cM / segment count / notes) rather than duplicating the edge.
CREATE UNIQUE INDEX ibd_attestation_did_key
    ON ibd.ibd_pds_attestation (ibd_discovery_index_id, attesting_did, attestation_type)
    WHERE attesting_did IS NOT NULL;
