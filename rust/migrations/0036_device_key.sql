-- Device-key registry (the Edge auth foundation). A desktop client (Navigator) cannot
-- sign with the PDS-custodied #atproto repo key, and cannot add its own verificationMethod
-- to a did:plc doc (that needs a rotation-key PLC op the PDS holds). So each client
-- publishes its own Ed25519 DEVICE public key as a record in the user's own repo — writing
-- to your own repo IS the proof of control over repo_did — and the AppView ingests it here
-- (like every other fed.* record). crate::sig::verify_signed then verifies signed Edge calls
-- against the registered key. PII-free: a DID + a PUBLIC key + record pointers only.
--
-- (did, rkey) PK ⇒ a DID may register N device keys (N devices); revocation = the user
-- deletes the record, which routes through fed::delete → table_for.

CREATE TABLE fed.device_key (
    did               TEXT NOT NULL,
    rkey              TEXT NOT NULL,
    at_uri            TEXT NOT NULL,
    cid               TEXT,
    public_key        TEXT NOT NULL,         -- the device Ed25519 pubkey as a did:key:z… string
    record_created_at TIMESTAMPTZ,
    time_us           BIGINT NOT NULL,
    indexed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (did, rkey)
);
CREATE INDEX device_key_did_idx ON fed.device_key (did);
