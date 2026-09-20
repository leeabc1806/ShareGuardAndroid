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
    verified_at timestamptz,
    verification_provider text,
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
create index chat_messages_sender_id_idx on public.chat_messages(sender_id);
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

-- Owner-only, atomic reservation decisions exposed through the Data API.
-- SECURITY INVOKER keeps the caller's RLS and table grants in force.
create or replace function public.approve_reservation(p_reservation_id uuid)
returns public.rental_contracts
language plpgsql
security invoker
set search_path = ''
as $$
declare
    approved_reservation public.reservations;
    created_contract public.rental_contracts;
begin
    update public.reservations r
    set status = 'approved'
    where r.id = p_reservation_id
      and r.status = 'pending'
      and exists (
          select 1 from public.items i
          where i.id = r.item_id and i.owner_id = (select auth.uid())
      )
    returning r.* into approved_reservation;

    if approved_reservation.id is null then
        raise exception '예약이 없거나 승인 권한이 없습니다.' using errcode = 'P0001';
    end if;

    insert into public.rental_contracts (reservation_id, contract_snapshot)
    values (
        approved_reservation.id,
        jsonb_build_object(
            'item_id', approved_reservation.item_id,
            'borrower_id', approved_reservation.borrower_id,
            'start_date', approved_reservation.start_date,
            'end_date', approved_reservation.end_date,
            'rental_fee', approved_reservation.rental_fee,
            'deposit', approved_reservation.deposit,
            'approved_at', now()
        )
    )
    returning * into created_contract;

    return created_contract;
end;
$$;

create or replace function public.reject_reservation(p_reservation_id uuid)
returns public.reservations
language plpgsql
security invoker
set search_path = ''
as $$
declare
    rejected_reservation public.reservations;
begin
    update public.reservations r
    set status = 'rejected'
    where r.id = p_reservation_id
      and r.status = 'pending'
      and exists (
          select 1 from public.items i
          where i.id = r.item_id and i.owner_id = (select auth.uid())
      )
    returning r.* into rejected_reservation;

    if rejected_reservation.id is null then
        raise exception '예약이 없거나 거절 권한이 없습니다.' using errcode = 'P0001';
    end if;

    return rejected_reservation;
end;
$$;

revoke all on function public.approve_reservation(uuid) from public, anon;
revoke all on function public.reject_reservation(uuid) from public, anon;
grant execute on function public.approve_reservation(uuid) to authenticated;
grant execute on function public.reject_reservation(uuid) to authenticated;

create or replace function public.pay_demo_reservation(p_reservation_id uuid)
returns public.payments
language plpgsql
security invoker
set search_path = ''
as $$
declare
    target_reservation public.reservations;
    created_payment public.payments;
begin
    if not exists (
        select 1 from public.profiles p
        where p.id = (select auth.uid()) and p.verified
    ) then
        raise exception 'PASS 휴대폰 본인인증 후 결제할 수 있습니다.' using errcode = '42501';
    end if;

    select * into target_reservation
    from public.reservations r
    where r.id = p_reservation_id
      and r.borrower_id = (select auth.uid())
      and r.status = 'approved'
    for update;

    if target_reservation.id is null then
        raise exception '결제 가능한 예약이 아니거나 권한이 없습니다.' using errcode = 'P0001';
    end if;

    insert into public.payments (reservation_id, payer_id, provider, amount, status, paid_at)
    values (target_reservation.id, (select auth.uid()), 'demo', target_reservation.total_amount, 'held', now())
    returning * into created_payment;

    update public.reservations set status = 'paid' where id = target_reservation.id;
    return created_payment;
end;
$$;

create or replace function public.sign_rental_contract(p_contract_id uuid)
returns public.rental_contracts
language plpgsql
security invoker
set search_path = ''
as $$
declare
    caller_id uuid := (select auth.uid());
    target_contract public.rental_contracts;
    target_reservation_id uuid;
    reservation_borrower uuid;
    item_owner uuid;
    reservation_status text;
    contract_status text;
begin
    select c.reservation_id, r.borrower_id, i.owner_id, r.status, c.status
    into target_reservation_id, reservation_borrower, item_owner, reservation_status, contract_status
    from public.rental_contracts c
    join public.reservations r on r.id = c.reservation_id
    join public.items i on i.id = r.item_id
    where c.id = p_contract_id
    for update of c;

    if target_reservation_id is null or caller_id not in (reservation_borrower, item_owner) then
        raise exception '계약이 없거나 서명 권한이 없습니다.' using errcode = 'P0001';
    end if;
    if reservation_status <> 'paid' or contract_status not in ('draft', 'signed') then
        raise exception '결제 완료 후에만 서명할 수 있습니다.' using errcode = 'P0001';
    end if;

    update public.rental_contracts
    set owner_signed_at = case when caller_id = item_owner then coalesce(owner_signed_at, now()) else owner_signed_at end,
        borrower_signed_at = case when caller_id = reservation_borrower then coalesce(borrower_signed_at, now()) else borrower_signed_at end
    where id = p_contract_id
    returning * into target_contract;

    if target_contract.owner_signed_at is not null and target_contract.borrower_signed_at is not null then
        update public.rental_contracts set status = 'signed'
        where id = p_contract_id returning * into target_contract;
    end if;
    return target_contract;
end;
$$;

create or replace function public.verify_rental_transition(p_contract_id uuid, p_action text)
returns public.rental_contracts
language plpgsql
security invoker
set search_path = ''
as $$
declare
    caller_id uuid := (select auth.uid());
    target_contract public.rental_contracts;
    target_reservation_id uuid;
    target_borrower_id uuid;
    target_contract_status text;
    item_owner uuid;
begin
    if p_action not in ('start', 'end') then
        raise exception '지원하지 않는 QR 인증 단계입니다.' using errcode = '22023';
    end if;

    select c.reservation_id, c.status, r.borrower_id, i.owner_id
    into target_reservation_id, target_contract_status, target_borrower_id, item_owner
    from public.rental_contracts c
    join public.reservations r on r.id = c.reservation_id
    join public.items i on i.id = r.item_id
    where c.id = p_contract_id
    for update of c, r;

    if target_reservation_id is null or caller_id not in (target_borrower_id, item_owner) then
        raise exception '계약이 없거나 인증 권한이 없습니다.' using errcode = 'P0001';
    end if;

    if p_action = 'start' then
        if target_contract_status <> 'signed' or not exists (
            select 1 from public.condition_photos p where p.contract_id = p_contract_id and p.phase = 'before'
        ) then
            raise exception '양측 서명과 대여 전 사진이 필요합니다.' using errcode = 'P0001';
        end if;
        insert into public.qr_verifications (contract_id, verified_by, verification_type)
        values (p_contract_id, caller_id, 'start');
        update public.rental_contracts set status = 'active' where id = p_contract_id returning * into target_contract;
        update public.reservations set status = 'renting' where id = target_contract.reservation_id;
    else
        if target_contract_status <> 'active' or not exists (
            select 1 from public.condition_photos p where p.contract_id = p_contract_id and p.phase = 'after'
        ) then
            raise exception '대여 중 상태와 반납 후 사진이 필요합니다.' using errcode = 'P0001';
        end if;
        insert into public.qr_verifications (contract_id, verified_by, verification_type)
        values (p_contract_id, caller_id, 'end');
        update public.rental_contracts set status = 'completed' where id = p_contract_id returning * into target_contract;
        update public.reservations set status = 'completed' where id = target_contract.reservation_id;
        update public.payments set status = 'refunded', refunded_at = now()
        where reservation_id = target_contract.reservation_id and provider = 'demo';
    end if;
    return target_contract;
end;
$$;

create or replace function public.open_rental_dispute(
    p_reservation_id uuid,
    p_reported_user_id uuid,
    p_report_type text,
    p_reason text
)
returns public.reports
language plpgsql
security invoker
set search_path = ''
as $$
declare
    created_report public.reports;
begin
    insert into public.reports (reservation_id, reporter_id, reported_user_id, report_type, reason)
    values (p_reservation_id, (select auth.uid()), p_reported_user_id, p_report_type, p_reason)
    returning * into created_report;

    update public.reservations set status = 'disputed' where id = p_reservation_id;
    update public.rental_contracts set status = 'disputed' where reservation_id = p_reservation_id;
    return created_report;
end;
$$;

revoke all on function public.pay_demo_reservation(uuid) from public, anon;
revoke all on function public.sign_rental_contract(uuid) from public, anon;
revoke all on function public.verify_rental_transition(uuid, text) from public, anon;
revoke all on function public.open_rental_dispute(uuid, uuid, text, text) from public, anon;
grant execute on function public.pay_demo_reservation(uuid) to authenticated;
grant execute on function public.sign_rental_contract(uuid) to authenticated;
grant execute on function public.verify_rental_transition(uuid, text) to authenticated;
grant execute on function public.open_rental_dispute(uuid, uuid, text, text) to authenticated;

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
grant insert on public.payments to authenticated;
grant update (status, refunded_at) on public.payments to authenticated;
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
with check (
    owner_id = (select auth.uid())
    and exists (
        select 1 from public.profiles p
        where p.id = (select auth.uid()) and p.verified
    )
);
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
create policy payments_insert_demo_borrower on public.payments
for insert to authenticated
with check (
    payer_id = (select auth.uid())
    and provider = 'demo'
    and status = 'held'
    and exists (
        select 1 from public.profiles p
        where p.id = (select auth.uid()) and p.verified
    )
    and exists (
        select 1 from public.reservations r
        where r.id = payments.reservation_id
          and r.borrower_id = (select auth.uid())
          and r.status = 'approved'
          and r.total_amount = payments.amount
    )
);
create policy payments_refund_completed_demo on public.payments
for update to authenticated
using (
    provider = 'demo' and exists (
        select 1
        from public.reservations r
        join public.items i on i.id = r.item_id
        where r.id = payments.reservation_id
          and (r.borrower_id = (select auth.uid()) or i.owner_id = (select auth.uid()))
    )
)
with check (
    provider = 'demo' and status = 'refunded' and exists (
        select 1 from public.reservations r
        where r.id = payments.reservation_id and r.status = 'completed'
    )
);

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
    bucket_id = 'condition-photos' and (
        owner_id = (select auth.uid())::text or exists (
            select 1
            from public.condition_photos p
            join public.rental_contracts c on c.id = p.contract_id
            join public.reservations r on r.id = c.reservation_id
            join public.items i on i.id = r.item_id
            where p.storage_path = storage.objects.name
              and (r.borrower_id = (select auth.uid()) or i.owner_id = (select auth.uid()))
        )
    )
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

-- Keep profile rating, deal count, and trust score synchronized without an external API.
create or replace function private.recalculate_profile_trust(p_user_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    review_average numeric(2, 1);
    completed_deals integer;
    open_disputes integer;
    is_verified boolean;
    calculated integer;
begin
    select coalesce(avg(r.rating), 0)::numeric(2, 1), count(*)::integer
      into review_average, completed_deals
      from public.reviews r where r.reviewee_id = p_user_id;
    select count(*)::integer into open_disputes
      from public.reports r where r.reported_user_id = p_user_id and r.status in ('open', 'reviewing');
    select verified into is_verified from public.profiles where id = p_user_id;
    calculated := 500
        + case when is_verified then 150 else 0 end
        + least(completed_deals * 12, 180)
        + least(greatest(round((review_average - 3.0) * 80), 0), 160)::integer
        - least(open_disputes * 80, 320);
    update public.profiles
       set rating = review_average,
           deal_count = completed_deals,
           trust_score = greatest(0, least(1000, calculated)),
           updated_at = now()
     where id = p_user_id;
end;
$$;

create or replace function private.sync_trust_from_review()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    perform private.recalculate_profile_trust(coalesce(new.reviewee_id, old.reviewee_id));
    return coalesce(new, old);
end;
$$;

create or replace function private.sync_trust_from_report()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    perform private.recalculate_profile_trust(coalesce(new.reported_user_id, old.reported_user_id));
    return coalesce(new, old);
end;
$$;

drop trigger if exists reviews_sync_trust on public.reviews;
create trigger reviews_sync_trust after insert or update or delete on public.reviews
for each row execute function private.sync_trust_from_review();
drop trigger if exists reports_sync_trust on public.reports;
create trigger reports_sync_trust after insert or update or delete on public.reports
for each row execute function private.sync_trust_from_report();

revoke execute on function private.recalculate_profile_trust(uuid) from public, anon, authenticated;
revoke execute on function private.sync_trust_from_review() from public, anon, authenticated;
revoke execute on function private.sync_trust_from_report() from public, anon, authenticated;

-- Auditable administrator dispute resolution and sanctions.
alter table public.profiles
    add column if not exists account_status text not null default 'active'
    check (account_status in ('active', 'warned', 'suspended'));

create table if not exists public.admin_actions (
    id bigint generated always as identity primary key,
    report_id uuid not null references public.reports(id) on delete restrict,
    admin_id uuid not null references public.profiles(id) on delete restrict,
    decision text not null check (decision in ('dismissed', 'partial', 'uphold')),
    deduction_amount integer not null default 0 check (deduction_amount >= 0),
    sanction text not null default 'none' check (sanction in ('none', 'warning', 'hide_item', 'suspend_user')),
    note text not null check (char_length(note) between 5 and 2000),
    created_at timestamptz not null default now()
);

-- Server-only audit data for PortOne/PASS verification. Raw CI/DI and phone numbers
-- are deliberately not stored; the backend writes a one-way identity hash.
create table private.mfa_verifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null unique references public.profiles(id) on delete cascade,
    portone_verification_id text not null unique,
    identity_hash char(64) not null unique,
    phone_last4 char(4) not null,
    trust_score_before integer not null check (trust_score_before between 0 and 1000),
    trust_score_after integer not null check (trust_score_after between 0 and 1000),
    verified_at timestamptz not null,
    created_at timestamptz not null default now(),
    check (trust_score_after >= trust_score_before)
);
revoke all on private.mfa_verifications from public, anon, authenticated;

alter table public.admin_actions enable row level security;
revoke all on public.admin_actions from anon, authenticated;
grant select, insert on public.admin_actions to authenticated;
grant usage, select on sequence public.admin_actions_id_seq to authenticated;
create policy admin_actions_admin_only on public.admin_actions
for all to authenticated
using (exists (select 1 from public.profiles p where p.id = (select auth.uid()) and p.role = 'admin'))
with check (admin_id = (select auth.uid()) and exists (
    select 1 from public.profiles p where p.id = (select auth.uid()) and p.role = 'admin'
));

create or replace function public.resolve_report_admin(
    p_report_id uuid,
    p_decision text,
    p_deduction_amount integer,
    p_note text,
    p_sanction text
) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    caller_id uuid := (select auth.uid());
    target public.reports;
    target_item_id uuid;
begin
    if not exists (select 1 from public.profiles p where p.id = caller_id and p.role = 'admin') then
        raise exception '관리자 권한이 필요합니다.' using errcode = '42501';
    end if;
    if p_decision not in ('dismissed', 'partial', 'uphold') or
       p_sanction not in ('none', 'warning', 'hide_item', 'suspend_user') or
       p_deduction_amount < 0 or char_length(trim(p_note)) < 5 then
        raise exception '분쟁 처리 입력값이 올바르지 않습니다.' using errcode = '22023';
    end if;
    select * into target from public.reports where id = p_report_id for update;
    if target.id is null or target.status not in ('open', 'reviewing') then
        raise exception '처리 가능한 신고가 아닙니다.' using errcode = 'P0001';
    end if;
    select r.item_id into target_item_id from public.reservations r where r.id = target.reservation_id;

    update public.reports set
        status = case when p_decision = 'dismissed' then 'dismissed' else 'resolved' end,
        resolution = p_note,
        resolved_at = now()
    where id = p_report_id;
    insert into public.admin_actions(report_id, admin_id, decision, deduction_amount, sanction, note)
    values (p_report_id, caller_id, p_decision, p_deduction_amount, p_sanction, trim(p_note));

    update public.rental_contracts set status = 'completed' where reservation_id = target.reservation_id;
    update public.reservations set status = 'completed' where id = target.reservation_id;
    update public.payments set
        status = case when p_decision = 'dismissed' or p_deduction_amount = 0 then 'refunded' else 'partially_refunded' end,
        refunded_at = now()
    where reservation_id = target.reservation_id;

    if p_sanction = 'hide_item' then
        update public.items set status = 'hidden' where id = target_item_id;
    elsif p_sanction = 'warning' then
        update public.profiles set account_status = 'warned' where id = target.reported_user_id;
    elsif p_sanction = 'suspend_user' then
        update public.profiles set account_status = 'suspended' where id = target.reported_user_id;
    end if;
end;
$$;

revoke all on function public.resolve_report_admin(uuid, text, integer, text, text) from public, anon;
grant execute on function public.resolve_report_admin(uuid, text, integer, text, text) to authenticated;
