SET LOCAL lock_timeout = '3s';

-- The backend's own database user, clozest_app, separate from the owner that runs these migrations (see "Database
-- users" in the README). It may read and change rows, and nothing else: no creating, changing or dropping tables,
-- no changing Flyway's history. So a leaked DB_PASSWORD from the server's variables can't drop tables or change the
-- schema; the owner's password stays out of Railway (only GitHub's migration job has it).
--
-- Made here without a password (it can't sign in yet), so every database, the tests' included, has it. On Neon the
-- owner then gives it a password once, in the SQL editor:  ALTER ROLE clozest_app WITH LOGIN PASSWORD '...';
-- (made in SQL, not in Neon's console: a console-made role is put in neon_superuser and could change tables).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'clozest_app') THEN
        CREATE ROLE clozest_app NOLOGIN;
    END IF;
END
$$;

-- Rows of every table there is now: read, add, change, delete. Sequences (booking numbers, ticket numbers): use.
GRANT USAGE ON SCHEMA public TO clozest_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO clozest_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO clozest_app;

-- Flyway's history: the app may look, never change it (a changed history could make a migration run again or be
-- skipped)
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON flyway_schema_history FROM clozest_app;

-- Every table and sequence later migrations make (they run as this same owner) gets the same rights by itself, so a
-- new migration never has to remember a GRANT
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO clozest_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO clozest_app;

-- The 5 s limit per statement (a request's slow query is stopped instead of holding a connection), as the database
-- user's own default, so it holds on Neon's pooled connections too. The background jobs raise it inside their own
-- transactions (SET LOCAL), as before.
ALTER ROLE clozest_app SET statement_timeout = '5s';
