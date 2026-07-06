-- Remove seeded 'credentials' (test-password) logins so accounts authenticate ONLY
-- via AT Protocol (bsky.app) OAuth.
--
-- Background: for local testing, a password login was seeded into
-- ident.user_login_info (provider_id = 'credentials') so the native login form
-- worked without a live OAuth flow. That row rode along in the DB dump to
-- production. It does not block OAuth, but it is a test artifact (a password login)
-- that should not exist in prod.
--
-- Safe to run: OAuth sign-in (du_db::auth::upsert_user_by_did) matches the existing
-- ident.users row by DID and inserts a separate 'atproto' login_info row, so
-- deleting the 'credentials' row keeps the account, its DID/handle/email, and its
-- roles — it only drops the password login method. Idempotent.
--
--   PGPASSWORD='<pw>' psql -h localhost -U decoding_us_user -d decodingus_db \
--       -f scripts/remove-test-credential-login.sql
-- (or:  psql "$DATABASE_URL" -f scripts/remove-test-credential-login.sql )

\set ON_ERROR_STOP on
BEGIN;

\echo '--- credentials (password) logins present BEFORE ---'
SELECT li.id, li.provider_key, u.handle, u.did, u.email
FROM ident.user_login_info li
JOIN ident.users u ON u.id = li.user_id
WHERE li.provider_id = 'credentials'
ORDER BY u.handle;

DELETE FROM ident.user_login_info
WHERE provider_id = 'credentials';

\echo '--- remaining login methods AFTER (expect only atproto, or none until first OAuth login) ---'
SELECT provider_id, count(*) AS n
FROM ident.user_login_info
GROUP BY provider_id
ORDER BY provider_id;

COMMIT;
