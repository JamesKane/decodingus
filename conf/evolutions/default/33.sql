-- !Ups

-- Insert default roles
INSERT INTO auth.roles (id, name, description, created_at, updated_at)
VALUES
(gen_random_uuid(), 'Admin', 'Administrator with full access', NOW(), NOW()),
(gen_random_uuid(), 'Curator', 'Curator access for managing content', NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

-- !Downs

DELETE FROM auth.roles WHERE name IN ('Admin', 'Curator');
