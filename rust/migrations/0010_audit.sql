-- Curator audit trail. Home for the legacy `curator.audit_log`: entity-level
-- change history (who changed what, with old/new JSONB snapshots). Distinct from
-- tree.curator_action, which records proposed-branch approval decisions only.
CREATE TABLE ident.audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES ident.users(id) ON DELETE CASCADE,
    entity_type TEXT NOT NULL,           -- 'variant', 'haplogroup', ...
    entity_id   BIGINT NOT NULL,         -- legacy stored an int; catalog ids are bigint
    action      TEXT NOT NULL,           -- CREATE/UPDATE/DELETE
    old_value   JSONB,
    new_value   JSONB,
    comment     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_entity_idx ON ident.audit_log (entity_type, entity_id);
CREATE INDEX audit_log_user_idx ON ident.audit_log (user_id);
