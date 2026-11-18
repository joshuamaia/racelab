-- Least privilege: when the runtime role created by infrastructure/postgres/init exists,
-- grant it DML only (no DDL, no TRUNCATE). Flyway itself runs as the schema owner.
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'racelab_app') THEN
        GRANT USAGE ON SCHEMA public TO racelab_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO racelab_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO racelab_app;
        REVOKE TRUNCATE ON ALL TABLES IN SCHEMA public FROM racelab_app;
        -- Templates are read-only for the application.
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO racelab_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO racelab_app;
    END IF;
END
$$;
