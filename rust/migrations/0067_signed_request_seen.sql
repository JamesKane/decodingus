-- Replay guard for signed Edge requests (D1 exchange, D3 IBD, D2/D4/D5 research,
-- social, recruitment).
--
-- Every *mutating* signed Edge request now frames its canonical message as
-- "{ts}\n{base_message}" and is single-use: on first acceptance its Ed25519 signature
-- is recorded here, so a replay of the identical signed bytes within the freshness
-- window (±5 min) collides on the primary key and is rejected. The signed `ts` bounds
-- retention — rows past `expires_at` are purged (a stale ts is rejected on its own), so
-- this table only ever holds the last few minutes of accepted signatures.

CREATE TABLE fed.signed_request_seen (
    signature   TEXT PRIMARY KEY,
    did         TEXT NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX signed_request_seen_expiry_idx ON fed.signed_request_seen (expires_at);
