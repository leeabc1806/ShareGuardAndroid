-- Add server-managed PASS verification metadata to an existing ShareGuard project.
alter table public.profiles
    add column if not exists verified_at timestamptz,
    add column if not exists verification_provider text,
    add column if not exists verification_reference text;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'profiles_verification_provider_check'
          and conrelid = 'public.profiles'::regclass
    ) then
        alter table public.profiles
            add constraint profiles_verification_provider_check
            check (verification_provider in ('portone'));
    end if;
end
$$;

create unique index if not exists profiles_verification_reference_key
    on public.profiles (verification_reference)
    where verification_reference is not null;

-- A signed-in client may edit only presentation fields. Verification stays server-managed.
revoke update on public.profiles from authenticated;
grant update (display_name, avatar_url) on public.profiles to authenticated;
