-- Local disposable PostgreSQL fixture only. Never apply to Supabase.
create schema auth;
create schema storage;
create schema extensions;
create role anon nologin;
create role authenticated nologin;
create role service_role nologin bypassrls;
create table auth.users(id uuid primary key, email text, raw_user_meta_data jsonb default '{}');
create function auth.uid() returns uuid language sql stable as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;
grant usage on schema auth to anon, authenticated;
grant execute on function auth.uid() to anon, authenticated;
create table storage.buckets(id text primary key, name text, public boolean,
    file_size_limit bigint, allowed_mime_types text[]);
create table storage.objects(id uuid primary key default gen_random_uuid(),
    bucket_id text references storage.buckets(id), name text, owner_id text);
create function storage.foldername(text) returns text[] language sql immutable as $$
    select string_to_array($1, '/')
$$;
alter database shareguard set search_path to public, extensions;
