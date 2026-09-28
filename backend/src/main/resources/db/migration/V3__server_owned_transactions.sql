-- Some team databases have the core public schema but not its final admin
-- section. The backend owns dispute decisions; complete the required table
-- and column without replacing or deleting existing team data.
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
alter table public.admin_actions enable row level security;
revoke all on public.admin_actions from public, anon, authenticated;
revoke all on sequence public.admin_actions_id_seq from public, anon, authenticated;

-- Existing core public schema is installed from ShareGuardAndroid/database/schema.sql.
do $$ begin
    if to_regclass('public.profiles') is null or to_regclass('public.reservations') is null then
        raise exception 'Install the core ShareGuard public schema before backend migrations';
    end if;
end $$;

-- A hidden item must remain readable by its borrower for past trades and refunds.
-- Definer avoids recursion between the items and reservations RLS policies.
create or replace function public.can_read_trade_item(p_item_id uuid)
returns boolean language sql stable security definer set search_path = '' as $$
    select exists(select 1 from public.reservations r
                  where r.item_id = p_item_id and r.borrower_id = auth.uid())
$$;
revoke all on function public.can_read_trade_item(uuid) from public, anon;
grant execute on function public.can_read_trade_item(uuid) to authenticated;
create policy items_read_trade_history on public.items for select to authenticated
using (public.can_read_trade_item(id));

-- Payment/QR/admin transitions must not be callable through the old demo RPCs.
revoke all on function public.pay_demo_reservation(uuid) from public, anon, authenticated;
revoke all on function public.verify_rental_transition(uuid, text) from public, anon, authenticated;
do $$ begin
    if to_regprocedure('public.resolve_report_admin(uuid,text,integer,text,text)') is not null then
        execute 'revoke all on function public.resolve_report_admin(uuid, text, integer, text, text) from public, anon, authenticated';
    end if;
end $$;

revoke insert, update, delete on public.payments from anon, authenticated;
revoke update (status, refunded_at) on public.payments from anon, authenticated;
revoke insert, update, delete on public.qr_verifications from anon, authenticated;
revoke update, delete on public.reservations from anon, authenticated;
revoke insert, update, delete on public.rental_contracts from anon, authenticated;
revoke update, delete on public.reports from anon, authenticated;

-- These narrow RPCs retain their explicit auth.uid()/participant/state checks.
-- Their callers no longer have general status UPDATE privileges.
alter function public.approve_reservation(uuid) security definer;
alter function public.reject_reservation(uuid) security definer;
alter function public.sign_rental_contract(uuid) security definer;
revoke all on function public.approve_reservation(uuid) from public, anon;
revoke all on function public.reject_reservation(uuid) from public, anon;
revoke all on function public.sign_rental_contract(uuid) from public, anon;

-- Replace the invoker function: a definer must explicitly reproduce its former RLS checks.
create or replace function public.open_rental_dispute(
    p_reservation_id uuid, p_reported_user_id uuid, p_report_type text, p_reason text
) returns public.reports
language plpgsql security definer set search_path = '' as $$
declare
    caller_id uuid := auth.uid();
    borrower uuid;
    owner_user uuid;
    reservation_status text;
    created_report public.reports;
begin
    select r.borrower_id, i.owner_id, r.status into borrower, owner_user, reservation_status
      from public.reservations r join public.items i on i.id = r.item_id
     where r.id = p_reservation_id for update of r;
    if caller_id is null or borrower is null or caller_id not in (borrower, owner_user)
       or p_reported_user_id is null or p_reported_user_id = caller_id
       or p_reported_user_id not in (borrower, owner_user) then
        raise exception '거래 당사자만 상대방을 신고할 수 있습니다.' using errcode = '42501';
    end if;
    if reservation_status not in ('renting', 'completed') then
        raise exception '대여중 또는 반납 완료 거래만 신고할 수 있습니다.';
    end if;
    if exists(select 1 from private.payment_orders po where po.reservation_id = p_reservation_id
              and po.status = 'refund_pending') then
        raise exception '환급 처리 상태를 먼저 확인해주세요.';
    end if;
    insert into public.reports(reservation_id, reporter_id, reported_user_id, report_type, reason)
    values(p_reservation_id, caller_id, p_reported_user_id, p_report_type, p_reason)
    returning * into created_report;
    update public.reservations set status = 'disputed' where id = p_reservation_id;
    update public.rental_contracts set status = 'disputed' where reservation_id = p_reservation_id;
    return created_report;
end;
$$;
revoke all on function public.open_rental_dispute(uuid, uuid, text, text) from public, anon;
grant execute on function public.open_rental_dispute(uuid, uuid, text, text) to authenticated;

-- A deal is a completed reservation, not the number of received reviews.
create or replace function private.recalculate_profile_trust(p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
    review_average numeric(2,1);
    completed_deals integer;
    open_disputes integer;
    is_verified boolean;
    calculated integer;
begin
    select coalesce(avg(rating), 0)::numeric(2,1) into review_average
      from public.reviews where reviewee_id = p_user_id;
    select count(*)::integer into completed_deals
      from public.reservations r join public.items i on i.id = r.item_id
     where r.status = 'completed' and (r.borrower_id = p_user_id or i.owner_id = p_user_id);
    select count(*)::integer into open_disputes from public.reports
     where reported_user_id = p_user_id and status in ('open', 'reviewing');
    select verified into is_verified from public.profiles where id = p_user_id;
    calculated := 500 + case when is_verified then 150 else 0 end
        + least(completed_deals * 12, 180)
        + least(greatest(round((review_average - 3.0) * 80), 0), 160)::integer
        - least(open_disputes * 80, 320);
    update public.profiles set rating = review_average, deal_count = completed_deals,
           trust_score = greatest(0, least(1000, calculated)), updated_at = now()
     where id = p_user_id;
end;
$$;

create or replace function private.sync_trust_from_reservation()
returns trigger language plpgsql security definer set search_path = '' as $$
declare owner_user uuid;
begin
    if old.status is not distinct from new.status then return new; end if;
    select owner_id into owner_user from public.items where id = new.item_id;
    perform private.recalculate_profile_trust(new.borrower_id);
    perform private.recalculate_profile_trust(owner_user);
    return new;
end;
$$;
drop trigger if exists reservations_sync_trust on public.reservations;
create trigger reservations_sync_trust after update of status on public.reservations
for each row execute function private.sync_trust_from_reservation();
revoke all on function private.sync_trust_from_reservation() from public, anon, authenticated;
revoke all on function private.recalculate_profile_trust(uuid) from public, anon, authenticated;

-- Apply the same rule to accounts which already have completed trades.
select private.recalculate_profile_trust(id) from public.profiles;
