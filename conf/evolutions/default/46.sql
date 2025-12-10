-- !Ups

-- Add TreeCurator role
INSERT INTO auth.roles (id, name, description, created_at, updated_at)
VALUES (gen_random_uuid(), 'TreeCurator', 'Curator access for haplogroups and variants', NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

-- Create curator permissions
INSERT INTO auth.permissions (id, name, description, created_at, updated_at) VALUES
  (gen_random_uuid(), 'haplogroup.view',   'View haplogroup details', NOW(), NOW()),
  (gen_random_uuid(), 'haplogroup.create', 'Create new haplogroups', NOW(), NOW()),
  (gen_random_uuid(), 'haplogroup.update', 'Update existing haplogroups', NOW(), NOW()),
  (gen_random_uuid(), 'haplogroup.delete', 'Delete haplogroups', NOW(), NOW()),
  (gen_random_uuid(), 'variant.view',      'View variant details', NOW(), NOW()),
  (gen_random_uuid(), 'variant.create',    'Create new variants', NOW(), NOW()),
  (gen_random_uuid(), 'variant.update',    'Update existing variants', NOW(), NOW()),
  (gen_random_uuid(), 'variant.delete',    'Delete variants', NOW(), NOW()),
  (gen_random_uuid(), 'audit.view',        'View audit history', NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

-- Grant all curator permissions to TreeCurator role
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM auth.roles r, auth.permissions p
WHERE r.name = 'TreeCurator'
  AND p.name IN ('haplogroup.view', 'haplogroup.create', 'haplogroup.update', 'haplogroup.delete',
                 'variant.view', 'variant.create', 'variant.update', 'variant.delete', 'audit.view')
ON CONFLICT DO NOTHING;

-- Grant all curator permissions to Admin role
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM auth.roles r, auth.permissions p
WHERE r.name = 'Admin'
  AND p.name IN ('haplogroup.view', 'haplogroup.create', 'haplogroup.update', 'haplogroup.delete',
                 'variant.view', 'variant.create', 'variant.update', 'variant.delete', 'audit.view')
ON CONFLICT DO NOTHING;

-- Create curator schema
CREATE SCHEMA IF NOT EXISTS curator;

-- Create audit_log table
CREATE TABLE curator.audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    entity_type     VARCHAR(50) NOT NULL,
    entity_id       INT NOT NULL,
    action          VARCHAR(20) NOT NULL,
    old_value       JSONB,
    new_value       JSONB,
    comment         TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_audit_log_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_log_entity ON curator.audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_log_user ON curator.audit_log(user_id);
CREATE INDEX idx_audit_log_created_at ON curator.audit_log(created_at DESC);

COMMENT ON TABLE curator.audit_log IS 'Audit trail for all curator actions on haplogroups and variants';

-- !Downs

DROP TABLE IF EXISTS curator.audit_log;
DROP SCHEMA IF EXISTS curator;

DELETE FROM auth.role_permissions
WHERE permission_id IN (SELECT id FROM auth.permissions WHERE name LIKE 'haplogroup.%' OR name LIKE 'variant.%' OR name = 'audit.view');

DELETE FROM auth.permissions
WHERE name IN ('haplogroup.view', 'haplogroup.create', 'haplogroup.update', 'haplogroup.delete',
               'variant.view', 'variant.create', 'variant.update', 'variant.delete', 'audit.view');

DELETE FROM auth.roles WHERE name = 'TreeCurator';
