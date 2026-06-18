-- D1: the encrypted Edge-to-Edge exchange substrate, AppView **broker** side. The
-- AppView never sees plaintext or session keys — it brokers consent (verifying
-- Ed25519 DID signatures), mirrors the published X25519 keys, and blind-relays
-- ciphertext. Generalizes the IBD-specific match_request/match_consent (mig 0007,
-- unused) into a purpose-tagged exchange.* schema shared by IBD + genealogy PII.

CREATE SCHEMA IF NOT EXISTS exchange;

-- A signed request to exchange with a partner (PII-free: dids + purpose + scope).
CREATE TABLE exchange.exchange_request (
    request_uri     TEXT PRIMARY KEY,                -- at:// uri of the signed record
    initiator_did   TEXT NOT NULL,
    partner_did     TEXT NOT NULL,
    purpose         TEXT NOT NULL,                   -- IBD_AUTOSOMAL/IBD_Y/IBD_MT/GENEALOGY_PII/...
    scope           TEXT,                            -- consent boundary, e.g. 'project:<id>'
    status          TEXT NOT NULL DEFAULT 'PENDING', -- PENDING/CONSENTED/DECLINED/CANCELLED/EXPIRED
    details         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX exchange_request_partner_idx ON exchange.exchange_request (partner_did, status);
CREATE INDEX exchange_request_initiator_idx ON exchange.exchange_request (initiator_did, status);

-- One signed consent per (request, did); the broker verifies BOTH before a session.
CREATE TABLE exchange.exchange_consent (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_uri     TEXT NOT NULL REFERENCES exchange.exchange_request(request_uri) ON DELETE CASCADE,
    consenting_did  TEXT NOT NULL,
    consent_given   BOOLEAN NOT NULL,
    consent_uri     TEXT,
    signature       TEXT NOT NULL,                   -- Ed25519, verified by the broker
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (request_uri, consenting_did)
);

-- A consented session; the Edges run ECDH + exchange under it (broker opaque).
CREATE TABLE exchange.exchange_session (
    session_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_uri     TEXT NOT NULL REFERENCES exchange.exchange_request(request_uri) ON DELETE CASCADE,
    status          TEXT NOT NULL DEFAULT 'ESTABLISHING', -- ESTABLISHING/ACTIVE/COMPLETE/EXPIRED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ
);
CREATE INDEX exchange_session_request_idx ON exchange.exchange_session (request_uri);

-- The blind store-and-forward buffer — ciphertext only; deleted on ack or TTL.
CREATE TABLE exchange.relay_envelope (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES exchange.exchange_session(session_id) ON DELETE CASCADE,
    from_did        TEXT NOT NULL,
    to_did          TEXT NOT NULL,
    seq             INTEGER NOT NULL,
    size_bytes      INTEGER NOT NULL,
    blob            BYTEA NOT NULL,                  -- opaque AES-GCM ciphertext envelope
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ
);
CREATE INDEX relay_envelope_pull_idx ON exchange.relay_envelope (session_id, to_did, delivered_at);

-- Mirror of each DID's published, Ed25519-signed static X25519 exchange key.
CREATE TABLE exchange.exchange_publickey (
    did             TEXT PRIMARY KEY,
    x25519_pub      BYTEA NOT NULL,
    key_uri         TEXT,
    sig_verified_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Fold: the IBD-specific request/consent generalize into exchange.* (D1 §8). They
-- carry no data and no code referenced them (the candidate engine uses
-- ibd.match_suggestion / ibd_discovery_index, which remain).
DROP TABLE IF EXISTS ibd.match_consent;
DROP TABLE IF EXISTS ibd.match_request;
