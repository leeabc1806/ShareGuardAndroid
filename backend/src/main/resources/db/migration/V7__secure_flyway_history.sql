-- Flyway is the only writer of its migration history. The application connects as
-- the postgres table owner; Android/Supabase API roles must not access this table.
-- Do not FORCE RLS: Flyway still needs to read and write its history on startup.
revoke all on table public.flyway_schema_history
    from public, anon, authenticated, service_role;

alter table public.flyway_schema_history enable row level security;
