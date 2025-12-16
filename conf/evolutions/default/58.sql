-- !Ups

-- Tree versioning curator permissions
INSERT INTO auth.permissions (id, name, description, created_at, updated_at) VALUES
  (gen_random_uuid(), 'tree.version.view',    'View change sets and diffs from tree merge operations', NOW(), NOW()),
  (gen_random_uuid(), 'tree.version.review',  'Review and approve/reject individual changes', NOW(), NOW()),
  (gen_random_uuid(), 'tree.version.promote', 'Apply approved change sets to production', NOW(), NOW()),
  (gen_random_uuid(), 'tree.version.discard', 'Discard change sets', NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

-- Grant to TreeCurator role
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM auth.roles r, auth.permissions p
WHERE r.name = 'TreeCurator'
  AND p.name IN ('tree.version.view', 'tree.version.review', 'tree.version.promote', 'tree.version.discard')
ON CONFLICT DO NOTHING;

-- Grant to Curator role
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM auth.roles r, auth.permissions p
WHERE r.name = 'Curator'
  AND p.name IN ('tree.version.view', 'tree.version.review', 'tree.version.promote', 'tree.version.discard')
ON CONFLICT DO NOTHING;

-- Grant to Admin role
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM auth.roles r, auth.permissions p
WHERE r.name = 'Admin'
  AND p.name IN ('tree.version.view', 'tree.version.review', 'tree.version.promote', 'tree.version.discard')
ON CONFLICT DO NOTHING;

-- !Downs

DELETE FROM auth.role_permissions
WHERE permission_id IN (SELECT id FROM auth.permissions WHERE name LIKE 'tree.version.%');

DELETE FROM auth.permissions
WHERE name LIKE 'tree.version.%';
