-- !Ups

-- Insert permission
INSERT INTO auth.permissions (id, name, description, created_at, updated_at)
VALUES (gen_random_uuid(), 'view_publication_candidates', 'View and manage publication candidates', NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

-- Assign permission to Admin and Curator roles
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM auth.roles r, auth.permissions p
WHERE r.name IN ('Admin', 'Curator') AND p.name = 'view_publication_candidates'
ON CONFLICT DO NOTHING;

-- !Downs

DELETE FROM auth.permissions WHERE name = 'view_publication_candidates';
