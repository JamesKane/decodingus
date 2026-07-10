-- Replay hardening for the D1 blind relay.
--
-- A (session_id, from_did, seq) triple is a sender's unique message slot in a session,
-- so a resubmitted (replayed) relay envelope must collide with the original rather than
-- insert a second, duplicate ciphertext row the recipient would pull twice. Dedup any
-- pre-existing collisions (keep the earliest id) before enforcing uniqueness; the
-- insert path is switched to ON CONFLICT DO NOTHING so a legitimate retry is idempotent.

DELETE FROM exchange.relay_envelope a
USING exchange.relay_envelope b
WHERE a.session_id = b.session_id
  AND a.from_did = b.from_did
  AND a.seq = b.seq
  AND a.id > b.id;

CREATE UNIQUE INDEX relay_envelope_sender_seq_uidx
    ON exchange.relay_envelope (session_id, from_did, seq);
