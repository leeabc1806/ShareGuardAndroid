alter table public.profiles
    add column if not exists verified_at timestamptz,
    add column if not exists verification_provider text;

create table if not exists private.mfa_verifications (
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

create index if not exists mfa_verifications_user_verified_idx
    on private.mfa_verifications(user_id, verified_at desc);

revoke all on private.mfa_verifications from public, anon, authenticated;

-- Android currently writes ITEM-02 through Supabase Data API, so the database must
-- enforce MFA in addition to the Spring Security filter.
drop policy if exists items_insert_own on public.items;
create policy items_insert_own on public.items
for insert to authenticated
with check (
    owner_id = (select auth.uid())
    and exists (
        select 1 from public.profiles p
        where p.id = (select auth.uid()) and p.verified
    )
);

drop policy if exists payments_insert_demo_borrower on public.payments;
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

revoke all on function public.pay_demo_reservation(uuid) from public, anon;
grant execute on function public.pay_demo_reservation(uuid) to authenticated;
