-- !Ups

-- Genome regions curator permissions
INSERT INTO auth.permissions (id, name, description, created_at, updated_at) VALUES
  (gen_random_uuid(), 'genome_region.view',   'View genome region details', NOW(), NOW()),
  (gen_random_uuid(), 'genome_region.create', 'Create genome regions', NOW(), NOW()),
  (gen_random_uuid(), 'genome_region.update', 'Update genome regions', NOW(), NOW()),
  (gen_random_uuid(), 'genome_region.delete', 'Delete genome regions', NOW(), NOW()),
  (gen_random_uuid(), 'cytoband.view',        'View cytoband details', NOW(), NOW()),
  (gen_random_uuid(), 'cytoband.create',      'Create cytobands', NOW(), NOW()),
  (gen_random_uuid(), 'cytoband.update',      'Update cytobands', NOW(), NOW()),
  (gen_random_uuid(), 'cytoband.delete',      'Delete cytobands', NOW(), NOW()),
  (gen_random_uuid(), 'str_marker.view',      'View STR marker details', NOW(), NOW()),
  (gen_random_uuid(), 'str_marker.create',    'Create STR markers', NOW(), NOW()),
  (gen_random_uuid(), 'str_marker.update',    'Update STR markers', NOW(), NOW()),
  (gen_random_uuid(), 'str_marker.delete',    'Delete STR markers', NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

-- Grant to Curator role
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM auth.roles r, auth.permissions p
WHERE r.name = 'Curator'
  AND p.name IN ('genome_region.view', 'genome_region.create', 'genome_region.update', 'genome_region.delete',
                 'cytoband.view', 'cytoband.create', 'cytoband.update', 'cytoband.delete',
                 'str_marker.view', 'str_marker.create', 'str_marker.update', 'str_marker.delete')
ON CONFLICT DO NOTHING;

-- Grant to Admin role
INSERT INTO auth.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM auth.roles r, auth.permissions p
WHERE r.name = 'Admin'
  AND p.name IN ('genome_region.view', 'genome_region.create', 'genome_region.update', 'genome_region.delete',
                 'cytoband.view', 'cytoband.create', 'cytoband.update', 'cytoband.delete',
                 'str_marker.view', 'str_marker.create', 'str_marker.update', 'str_marker.delete')
ON CONFLICT DO NOTHING;

-- !Downs

DELETE FROM auth.role_permissions
WHERE permission_id IN (SELECT id FROM auth.permissions WHERE name LIKE 'genome_region.%' OR name LIKE 'cytoband.%' OR name LIKE 'str_marker.%');

DELETE FROM auth.permissions
WHERE name LIKE 'genome_region.%' OR name LIKE 'cytoband.%' OR name LIKE 'str_marker.%';
