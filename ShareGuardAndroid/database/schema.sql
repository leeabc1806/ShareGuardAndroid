-- ShareGuard Supabase/PostgreSQL schema
-- Run once in Supabase Dashboard > SQL Editor.

create extension if not exists btree_gist with schema extensions;
create schema if not exists private;

create table public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    display_name text not null check (char_length(display_name) between 2 and 30),
    avatar_url text,
    role text not null default 'user' check (role in ('user', 'admin')),
    verified boolean not null default false,
    trust_score integer not null default 500 check (trust_score between 0 and 1000),
    rating numeric(2, 1) not null default 0 check (rating between 0 and 5),
    deal_count integer not null default 0 check (deal_count >= 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.items (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null default auth.uid() references public.profiles(id) on delete cascade,
    title text not null check (char_length(title) between 2 and 100),
    category text not null check (char_length(category) between 1 and 30),
    description text not null default '' check (char_length(description) <= 2000),
    daily_price integer not null check (daily_price >= 0),
    deposit integer not null check (deposit >= 0),
    address text not null check (char_length(address) between 2 and 200),
    latitude double precision,
    longitude double precision,
    status text not null default 'active' check (status in ('active', 'reserved', 'rented', 'hidden')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check ((latitude is null and longitude is null) or
           (latitude between -90 and 90 and longitude between -180 and 180))
);

create table public.item_images (
    id uuid primary key default gen_random_uuid(),
    item_id uuid not null references public.items(id) on delete cascade,
    storage_path text not null unique,
    sort_order smallint not null default 0 check (sort_order between 0 and 4),
    created_at timestamptz not null default now(),
    unique (item_id, sort_order)
);

create table public.reservations (
    id uuid primary key default gen_random_uuid(),
    item_id uuid not null references public.items(id) on delete restrict,
    borrower_id uuid not null default auth.uid() references public.profiles(id) on delete restrict,
    start_date date not null,
    end_date date not null,
    rental_period daterange generated always as (daterange(start_date, end_date, '[]')) stored,
    rental_fee integer not null check (rental_fee >= 0),
    deposit integer not null check (deposit >= 0),
    total_amount integer generated always as (rental_fee + deposit) stored,
    status text not null default 'pending' check (
        status in ('pending', 'approved', 'rejected', 'cancelled', 'paid', 'renting', 'completed', 'disputed')
    ),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (end_date >= start_date),
    constraint reservations_no_overlapping_active_periods exclude using gist (
        item_id with =,
        rental_period with &&
    ) where (status in ('approved', 'paid', 'renting'))
);

create table public.payments (
    id uuid primary key default gen_random_uuid(),
    reservation_id uuid not null unique references public.reservations(id) on delete restrict,
    payer_id uuid not null references public.profiles(id) on delete restrict,
    provider text not null,
    provider_payment_id text unique,
    amount integer not null check (amount >= 0),
    status text not null default 'ready' check (status in ('ready', 'paid', 'held', 'partially_refunded', 'refunded', 'failed')),
    paid_at timestamptz,
    refunded_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.rental_contracts (
    id uuid primary key default gen_random_uuid(),
    reservation_id uuid not null unique references public.reservations(id) on delete restrict,
    owner_signed_at timestamptz,
    borrower_signed_at timestamptz,
    contract_snapshot jsonb not null default '{}'::jsonb,
    status text not null default 'draft' check (status in ('draft', 'signed', 'active', 'completed', 'disputed')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.chat_rooms (
    id uuid primary key default gen_random_uuid(),
    reservation_id uuid not null unique references public.reservations(id) on delete cascade,
    created_at timestamptz not null default now()
);

create table public.chat_room_members (
    room_id uuid not null references public.chat_rooms(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    last_read_at timestamptz,
    primary key (room_id, user_id)
);

create table public.chat_messages (
    id bigint generated always as identity primary key,
    room_id uuid not null references public.chat_rooms(id) on delete cascade,
    sender_id uuid not null default auth.uid() references public.profiles(id) on delete restrict,
    body text not null check (char_length(body) between 1 and 2000),
    created_at timestamptz not null default now()
);

create table public.condition_photos (
    id uuid primary key default gen_random_uuid(),
    contract_id uuid not null references public.rental_contracts(id) on delete cascade,
    uploader_id uuid not null default auth.uid() references public.profiles(id) on delete restrict,
    phase text not null check (phase in ('before', 'after')),
    storage_path text not null unique,
    created_at timestamptz not null default now()
);

create table public.qr_verifications (
    id uuid primary key default gen_random_uuid(),
    contract_id uuid not null references public.rental_contracts(id) on delete cascade,
    verified_by uuid not null default auth.uid() references public.profiles(id) on delete restrict,
    verification_type text not null check (verification_type in ('start', 'end')),
    verified_at timestamptz not null default now(),
    latitude double precision,
    longitude double precision,
    unique (contract_id, verification_type)
);

create table public.reviews (
    id uuid primary key default gen_random_uuid(),
    reservation_id uuid not null references public.reservations(id) on delete cascade,
    reviewer_id uuid not null default auth.uid() references public.profiles(id) on delete restrict,
    reviewee_id uuid not null references public.profiles(id) on delete restrict,
    rating smallint not null check (rating between 1 and 5),
    content text not null default '' check (char_length(content) <= 1000),
    created_at timestamptz not null default now(),
    unique (reservation_id, reviewer_id),
    check (reviewer_id <> reviewee_id)
);

create table public.reports (
    id uuid primary key default gen_random_uuid(),
    reservation_id uuid not null references public.reservations(id) on delete restrict,
    reporter_id uuid not null default auth.uid() references public.profiles(id) on delete restrict,
    reported_user_id uuid not null references public.profiles(id) on delete restrict,
    report_type text not null check (report_type in ('damage', 'no_show', 'fraud', 'abuse', 'other')),
    reason text not null check (char_length(reason) between 10 and 2000),
    status text not null default 'open' check (status in ('open', 'reviewing', 'resolved', 'dismissed')),
    resolution text,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    check (reporter_id <> reported_user_id)
);

-- Foreign-key and common filtering indexes.
create index items_owner_id_idx on public.items(owner_id);
create index items_active_created_at_idx on public.items(created_at desc) where status = 'active';
create index reservations_item_id_idx on public.reservations(item_id);
create index reservations_borrower_id_idx on public.reservations(borrower_id);
create index reservations_status_idx on public.reservations(status);
create index item_images_item_id_idx on public.item_images(item_id);
create index payments_payer_id_idx on public.payments(payer_id);
create index chat_room_members_user_id_idx on public.chat_room_members(user_id);
create index chat_messages_room_created_at_idx on public.chat_messages(room_id, created_at desc);
create index condition_photos_contract_id_idx on public.condition_photos(contract_id);
create index condition_photos_uploader_id_idx on public.condition_photos(uploader_id);
create index qr_verifications_contract_id_idx on public.qr_verifications(contract_id);
create index qr_verifications_verified_by_idx on public.qr_verifications(verified_by);
create index reviews_reviewer_id_idx on public.reviews(reviewer_id);
create index reviews_reviewee_id_idx on public.reviews(reviewee_id);
create index reports_reservation_id_idx on public.reports(reservation_id);
create index reports_reporter_id_idx on public.reports(reporter_id);
create index reports_reported_user_id_idx on public.reports(reported_user_id);
create index reports_open_idx on public.reports(created_at desc) where status in ('open', 'reviewing');

create or replace function private.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

create trigger profiles_set_updated_at before update on public.profiles
for each row execute function private.set_updated_at();
create trigger items_set_updated_at before update on public.items
for each row execute function private.set_updated_at();
create trigger reservations_set_updated_at before update on public.reservations
for each row execute function private.set_updated_at();
create trigger payments_set_updated_at before update on public.payments
for each row execute function private.set_updated_at();
create trigger rental_contracts_set_updated_at before update on public.rental_contracts
for each row execute function private.set_updated_at();

create or replace function private.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.profiles (id, display_name)
    values (
        new.id,
        left(
            case
                when char_length(coalesce(nullif(new.raw_user_meta_data ->> 'display_name', ''), split_part(new.email, '@', 1))) < 2
                    then '사용자'
                else coalesce(nullif(new.raw_user_meta_data ->> 'display_name', ''), split_part(new.email, '@', 1))
            end,
            30
        )
    );
    return new;
end;
$$;

create trigger on_auth_user_created
after insert on auth.users
for each row execute function private.handle_new_user();

create or replace function private.create_reservation_chat_room()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    new_room_id uuid;
    item_owner_id uuid;
begin
    select owner_id into item_owner_id
    from public.items
    where id = new.item_id;

    insert into public.chat_rooms (reservation_id)
    values (new.id)
    returning id into new_room_id;

    insert into public.chat_room_members (room_id, user_id)
    values
        (new_room_id, new.borrower_id),
        (new_room_id, item_owner_id);
    return new;
end;
$$;

create trigger on_reservation_created
after insert on public.reservations
for each row execute function private.create_reservation_chat_room();

revoke all on schema private from public, anon, authenticated;
revoke execute on function private.set_updated_at() from public, anon, authenticated;
revoke execute on function private.handle_new_user() from public, anon, authenticated;
revoke execute on function private.create_reservation_chat_room() from public, anon, authenticated;

-- Exposed tables always use RLS.
alter table public.profiles enable row level security;
alter table public.items enable row level security;
alter table public.item_images enable row level security;
alter table public.reservations enable row level security;
alter table public.payments enable row level security;
alter table public.rental_contracts enable row level security;
alter table public.chat_rooms enable row level security;
alter table public.chat_room_members enable row level security;
alter table public.chat_messages enable row level security;
alter table public.condition_photos enable row level security;
alter table public.qr_verifications enable row level security;
alter table public.reviews enable row level security;
alter table public.reports enable row level security;

-- Least-privilege Data API grants. Payment state is server-managed.
revoke all on public.profiles, public.items, public.item_images, public.reservations,
    public.payments, public.rental_contracts, public.chat_rooms, public.chat_room_members,
    public.chat_messages, public.condition_photos, public.qr_verifications, public.reviews,
    public.reports from anon, authenticated;
grant select (id, display_name, avatar_url, role, verified, trust_score, rating, deal_count)
    on public.profiles to anon, authenticated;
grant update (display_name, avatar_url) on public.profiles to authenticated;
grant select on public.items, public.item_images to anon, authenticated;
grant insert, update, delete on public.items, public.item_images to authenticated;
grant select, insert, update on public.reservations to authenticated;
grant select on public.payments to authenticated;
grant select, insert, update on public.rental_contracts to authenticated;
grant select on public.chat_rooms, public.chat_room_members to authenticated;
grant select, insert on public.chat_messages to authenticated;
grant update on public.chat_room_members to authenticated;
grant select, insert on public.condition_photos, public.qr_verifications, public.reviews, public.reports to authenticated;
grant update on public.reports to authenticated;
grant usage, select on sequence public.chat_messages_id_seq to authenticated;

create policy profiles_public_read on public.profiles
for select to anon, authenticated using (true);
create policy profiles_update_self on public.profiles
for update to authenticated
using ((select auth.uid()) = profiles.id)
with check ((select auth.uid()) = profiles.id);

create policy items_read_active_or_own on public.items
for select to anon, authenticated
using (status = 'active' or owner_id = (select auth.uid()));
create policy items_insert_own on public.items
for insert to authenticated
with check (owner_id = (select auth.uid()));
create policy items_update_own on public.items
for update to authenticated
using (owner_id = (select auth.uid()))
with check (owner_id = (select auth.uid()));
create policy items_delete_own on public.items
for delete to authenticated
using (owner_id = (select auth.uid()));

create policy item_images_read_visible_items on public.item_images
for select to anon, authenticated
using (exists (select 1 from public.items i where i.id = item_images.item_id));
create policy item_images_insert_own_items on public.item_images
for insert to authenticated
with check (exists (
    select 1 from public.items i where i.id = item_images.item_id and i.owner_id = (select auth.uid())
));
create policy item_images_update_own_items on public.item_images
for update to authenticated
using (exists (
    select 1 from public.items i where i.id = item_images.item_id and i.owner_id = (select auth.uid())
))
with check (exists (
    select 1 from public.items i where i.id = item_images.item_id and i.owner_id = (select auth.uid())
));
create policy item_images_delete_own_items on public.item_images
for delete to authenticated
using (exists (
    select 1 from public.items i where i.id = item_images.item_id and i.owner_id = (select auth.uid())
));

create policy reservations_read_participants on public.reservations
for select to authenticated
using (
    borrower_id = (select auth.uid()) or exists (
        select 1 from public.items i where i.id = reservations.item_id and i.owner_id = (select auth.uid())
    )
);
create policy reservations_insert_borrower on public.reservations
for insert to authenticated
with check (
    borrower_id = (select auth.uid()) and exists (
        select 1 from public.items i
        where i.id = reservations.item_id and i.status = 'active' and i.owner_id <> (select auth.uid())
    )
);
create policy reservations_update_participants on public.reservations
for update to authenticated
using (
    borrower_id = (select auth.uid()) or exists (
        select 1 from public.items i where i.id = reservations.item_id and i.owner_id = (select auth.uid())
    )
)
with check (
    borrower_id = (select auth.uid()) or exists (
        select 1 from public.items i where i.id = reservations.item_id and i.owner_id = (select auth.uid())
    )
);

create policy payments_read_participants on public.payments
for select to authenticated
using (exists (
    select 1
    from public.reservations r
    join public.items i on i.id = r.item_id
    where r.id = payments.reservation_id
      and (r.borrower_id = (select auth.uid()) or i.owner_id = (select auth.uid()))
));

create policy contracts_read_participants on public.rental_contracts
for select to authenticated
using (exists (
    select 1
    from public.reservations r
    join public.items i on i.id = r.item_id
    where r.id = rental_contracts.reservation_id
      and (r.borrower_id = (select auth.uid()) or i.owner_id = (select auth.uid()))
));
create policy contracts_insert_participants on public.rental_contracts
for insert to authenticated
with check (exists (
    select 1
    from public.reservations r
    join public.items i on i.id = r.item_id
    where r.id = rental_contracts.reservation_id
      and (r.borrower_id = (select auth.uid()) or i.owner_id = (select auth.uid()))
));
create policy contracts_update_participants on public.rental_contracts
for update to authenticated
using (exists (
    select 1
    from public.reservations r
    join public.items i on i.id = r.item_id
    where r.id = rental_contracts.reservation_id
      and (r.borrower_id = (select auth.uid()) or i.owner_id = (select auth.uid()))
))
with check (exists (
    select 1
    from public.reservations r
    join public.items i on i.id = r.item_id
    where r.id = rental_contracts.reservation_id
      and (r.borrower_id = (select auth.uid()) or i.owner_id = (select auth.uid()))
));

create policy chat_rooms_read_members on public.chat_rooms
for select to authenticated
using (exists (
    select 1 from public.chat_room_members m
    where m.room_id = chat_rooms.id and m.user_id = (select auth.uid())
));
create policy chat_rooms_insert_reservation_participants on public.chat_rooms
for insert to authenticated
with check (exists (
    select 1
    from public.reservations r
    join public.items i on i.id = r.item_id
    where r.id = chat_rooms.reservation_id
      and (r.borrower_id = (select auth.uid()) or i.owner_id = (select auth.uid()))
));

create policy room_members_read_room on public.chat_room_members
for select to authenticated
using (user_id = (select auth.uid()));
create policy room_members_insert_self on public.chat_room_members
for insert to authenticated
with check (user_id = (select auth.uid()));
create policy room_members_update_self on public.chat_room_members
for update to authenticated
using (user_id = (select auth.uid()))
with check (user_id = (select auth.uid()));

create policy messages_read_members on public.chat_messages
for select to authenticated
using (exists (
    select 1 from public.chat_room_members m
    where m.room_id = chat_messages.room_id and m.user_id = (select auth.uid())
));
create policy messages_insert_members on public.chat_messages
for insert to authenticated
with check (
    sender_id = (select auth.uid()) and exists (
        select 1 from public.chat_room_members m
        where m.room_id = chat_messages.room_id and m.user_id = (select auth.uid())
    )
);

create policy condition_photos_read_participants on public.condition_photos
for select to authenticated
using (exists (
    select 1
    from public.rental_contracts c
    join public.reservations r on r.id = c.reservation_id
    join public.items i on i.id = r.item_id
    where c.id = condition_photos.contract_id
      and (r.borrower_id = (select auth.uid()) or i.owner_id = (select auth.uid()))
));
create policy condition_photos_insert_participants on public.condition_photos
for insert to authenticated
with check (
    uploader_id = (select auth.uid()) and exists (
        select 1
        from public.rental_contracts c
        join public.reservations r on r.id = c.reservation_id
        join public.items i on i.id = r.item_id
        where c.id = condition_photos.contract_id
          and (r.borrower_id = (select auth.uid()) or i.owner_id = (select auth.uid()))
    )
);

create policy qr_verifications_read_participants on public.qr_verifications
for select to authenticated
using (verified_by = (select auth.uid()) or exists (
    select 1
    from public.rental_contracts c
    join public.reservations r on r.id = c.reservation_id
    join public.items i on i.id = r.item_id
    where c.id = qr_verifications.contract_id
      and (r.borrower_id = (select auth.uid()) or i.owner_id = (select auth.uid()))
));
create policy qr_verifications_insert_participants on public.qr_verifications
for insert to authenticated
with check (
    verified_by = (select auth.uid()) and exists (
        select 1
        from public.rental_contracts c
        join public.reservations r on r.id = c.reservation_id
        join public.items i on i.id = r.item_id
        where c.id = qr_verifications.contract_id
          and (r.borrower_id = (select auth.uid()) or i.owner_id = (select auth.uid()))
    )
);

create policy reviews_read_authenticated on public.reviews
for select to authenticated using (true);
create policy reviews_insert_completed_participant on public.reviews
for insert to authenticated
with check (
    reviewer_id = (select auth.uid()) and exists (
        select 1
        from public.reservations r
        join public.items i on i.id = r.item_id
        where r.id = reviews.reservation_id and r.status = 'completed'
          and (
              (r.borrower_id = reviews.reviewer_id and i.owner_id = reviews.reviewee_id) or
              (i.owner_id = reviews.reviewer_id and r.borrower_id = reviews.reviewee_id)
          )
    )
);

create policy reports_read_participants_or_admin on public.reports
for select to authenticated
using (
    reporter_id = (select auth.uid()) or reported_user_id = (select auth.uid()) or exists (
        select 1 from public.profiles p
        where p.id = (select auth.uid()) and p.role = 'admin'
    )
);
create policy reports_insert_participant on public.reports
for insert to authenticated
with check (
    reporter_id = (select auth.uid()) and exists (
        select 1
        from public.reservations r
        join public.items i on i.id = r.item_id
        where r.id = reports.reservation_id
          and (
              (r.borrower_id = reports.reporter_id and i.owner_id = reports.reported_user_id) or
              (i.owner_id = reports.reporter_id and r.borrower_id = reports.reported_user_id)
          )
    )
);
create policy reports_update_admin on public.reports
for update to authenticated
using (exists (
    select 1 from public.profiles p
    where p.id = (select auth.uid()) and p.role = 'admin'
))
with check (exists (
    select 1 from public.profiles p
    where p.id = (select auth.uid()) and p.role = 'admin'
));

-- Storage buckets and ownership policies.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values
    ('item-images', 'item-images', true, 10485760, array['image/jpeg', 'image/png', 'image/webp']),
    ('condition-photos', 'condition-photos', false, 10485760, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do nothing;

create policy item_images_storage_read on storage.objects
for select to anon, authenticated using (bucket_id = 'item-images');
create policy item_images_storage_insert on storage.objects
for insert to authenticated with check (
    bucket_id = 'item-images' and (storage.foldername(name))[1] = (select auth.uid())::text
);
create policy item_images_storage_update on storage.objects
for update to authenticated
using (bucket_id = 'item-images' and owner_id = (select auth.uid())::text)
with check (bucket_id = 'item-images' and owner_id = (select auth.uid())::text);
create policy item_images_storage_delete on storage.objects
for delete to authenticated
using (bucket_id = 'item-images' and owner_id = (select auth.uid())::text);

create policy condition_photos_storage_read on storage.objects
for select to authenticated using (
    bucket_id = 'condition-photos' and (storage.foldername(name))[1] = (select auth.uid())::text
);
create policy condition_photos_storage_insert on storage.objects
for insert to authenticated with check (
    bucket_id = 'condition-photos' and (storage.foldername(name))[1] = (select auth.uid())::text
);
create policy condition_photos_storage_update on storage.objects
for update to authenticated
using (bucket_id = 'condition-photos' and owner_id = (select auth.uid())::text)
with check (bucket_id = 'condition-photos' and owner_id = (select auth.uid())::text);
create policy condition_photos_storage_delete on storage.objects
for delete to authenticated
using (bucket_id = 'condition-photos' and owner_id = (select auth.uid())::text);
