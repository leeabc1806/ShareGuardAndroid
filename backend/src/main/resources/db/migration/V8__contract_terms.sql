-- ShareGuard contract v1.0 (2026-10-07). Safe after the core schema and V1-V7.
-- Financial rules below are contract text only; this migration never charges or refunds.
create extension if not exists pgcrypto with schema extensions;

alter table public.items add column if not exists components text;
alter table public.items add column if not exists known_defects text;
alter table public.items add column if not exists item_value integer;
do $$ begin
    if not exists (select 1 from pg_constraint where conrelid = 'public.items'::regclass and conname = 'items_components_length') then
        alter table public.items add constraint items_components_length check (char_length(components) <= 500);
    end if;
    if not exists (select 1 from pg_constraint where conrelid = 'public.items'::regclass and conname = 'items_known_defects_length') then
        alter table public.items add constraint items_known_defects_length check (char_length(known_defects) <= 500);
    end if;
    if not exists (select 1 from pg_constraint where conrelid = 'public.items'::regclass and conname = 'items_item_value_nonnegative') then
        alter table public.items add constraint items_item_value_nonnegative check (item_value >= 0);
    end if;
end $$;

alter table public.rental_contracts add column if not exists special_terms text;
do $$ begin
    if not exists (select 1 from pg_constraint where conrelid = 'public.rental_contracts'::regclass and conname = 'rental_contracts_special_terms_length') then
        alter table public.rental_contracts add constraint rental_contracts_special_terms_length check (char_length(special_terms) <= 500);
    end if;
end $$;

-- A normalized, recursively key-sorted JSON representation. The top-level hash is excluded.
create or replace function private.contract_canonical_json(p_value jsonb)
returns text language plpgsql immutable security definer set search_path = '' as $$
declare result text;
begin
    if jsonb_typeof(p_value) = 'object' then
        select '{' || coalesce(string_agg(to_jsonb(entry.key)::text || ':' || private.contract_canonical_json(entry.value), ',' order by entry.key collate "C"), '') || '}'
          into result from jsonb_each(p_value) entry;
    elsif jsonb_typeof(p_value) = 'array' then
        select '[' || coalesce(string_agg(private.contract_canonical_json(entry.value), ',' order by entry.ordinality), '') || ']'
          into result from jsonb_array_elements(p_value) with ordinality entry(value, ordinality);
    else
        result := coalesce(p_value::text, 'null');
    end if;
    return result;
end;
$$;
revoke all on function private.contract_canonical_json(jsonb) from public, anon, authenticated;

create or replace function private.contract_snapshot_sha256(p_snapshot jsonb)
returns text language sql immutable security definer set search_path = '' as $$
    select encode(extensions.digest(private.contract_canonical_json(coalesce(p_snapshot, '{}'::jsonb) - 'hash'), 'sha256'), 'hex')
$$;
revoke all on function private.contract_snapshot_sha256(jsonb) from public, anon, authenticated;

-- Server-side freeze: neither a stale client nor another SECURITY DEFINER RPC can silently
-- change agreed terms after the first signature. The second signature may still be added.
create or replace function private.guard_signed_contract_snapshot()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    if (old.owner_signed_at is not null or old.borrower_signed_at is not null) and
       (new.contract_snapshot is distinct from old.contract_snapshot or
        new.special_terms is distinct from old.special_terms or
        (old.owner_signed_at is not null and new.owner_signed_at is distinct from old.owner_signed_at) or
        (old.borrower_signed_at is not null and new.borrower_signed_at is distinct from old.borrower_signed_at)) then
        raise exception '이미 서명된 계약 내용은 변경할 수 없습니다.' using errcode = '42501';
    end if;
    -- V4's accepted date-change RPC updates the dates and rental fee in the draft
    -- snapshot. Keep the new day-count field synchronized without editing V4.
    if old.owner_signed_at is null and old.borrower_signed_at is null
       and (new.contract_snapshot->>'start_date' is distinct from old.contract_snapshot->>'start_date'
            or new.contract_snapshot->>'end_date' is distinct from old.contract_snapshot->>'end_date')
       and new.contract_snapshot ? 'start_date' and new.contract_snapshot ? 'end_date' then
        new.contract_snapshot := jsonb_set(new.contract_snapshot, '{rental_days}',
            to_jsonb((new.contract_snapshot->>'end_date')::date -
                     (new.contract_snapshot->>'start_date')::date + 1), true);
    end if;
    return new;
end;
$$;
revoke all on function private.guard_signed_contract_snapshot() from public, anon, authenticated;
drop trigger if exists guard_signed_contract_snapshot on public.rental_contracts;
create trigger guard_signed_contract_snapshot before update on public.rental_contracts
for each row execute function private.guard_signed_contract_snapshot();

-- Retain the original owner-only reservation approval and status transition. Only the
-- immutable-at-signing snapshot fields are extended; financial values come from the reservation.
create or replace function public.approve_reservation(p_reservation_id uuid)
returns public.rental_contracts language plpgsql security definer set search_path = '' as $$
declare
    approved_reservation public.reservations;
    created_contract public.rental_contracts;
    item_record public.items;
    owner_profile public.profiles;
    borrower_profile public.profiles;
    discount_percent integer;
begin
    update public.reservations r set status = 'approved'
    where r.id = p_reservation_id and r.status = 'pending'
      and exists (select 1 from public.items i where i.id = r.item_id and i.owner_id = (select auth.uid()))
    returning r.* into approved_reservation;
    if approved_reservation.id is null then
        raise exception '예약이 없거나 승인 권한이 없습니다.' using errcode = 'P0001';
    end if;

    select * into item_record from public.items where id = approved_reservation.item_id;
    select * into owner_profile from public.profiles where id = item_record.owner_id;
    select * into borrower_profile from public.profiles where id = approved_reservation.borrower_id;
    discount_percent := case when item_record.deposit > 0
        then round((item_record.deposit - approved_reservation.deposit) * 100.0 / item_record.deposit)::integer
        else 0 end;

    insert into public.rental_contracts (reservation_id, contract_snapshot)
    values (approved_reservation.id, jsonb_build_object(
        'terms_version', '1.0', 'terms_effective_date', '2026-10-07',
        'item_id', approved_reservation.item_id, 'owner_id', item_record.owner_id,
        'borrower_id', approved_reservation.borrower_id,
        'item_title', item_record.title, 'item_category', item_record.category,
        'components', item_record.components, 'known_defects', item_record.known_defects,
        'item_value', item_record.item_value, 'daily_price', item_record.daily_price,
        'location', item_record.address, 'before_photo_count', 0,
        'owner_nickname', owner_profile.display_name, 'owner_verified', owner_profile.verified,
        'owner_trust_score', owner_profile.trust_score,
        'borrower_nickname', borrower_profile.display_name, 'borrower_verified', borrower_profile.verified,
        'borrower_trust_score', borrower_profile.trust_score,
        'start_date', approved_reservation.start_date, 'end_date', approved_reservation.end_date,
        'rental_days', approved_reservation.end_date - approved_reservation.start_date + 1,
        'rental_fee', approved_reservation.rental_fee,
        'original_deposit', item_record.deposit, 'discount_percent', discount_percent,
        'deposit', approved_reservation.deposit, 'approved_at', now(), 'special_terms', ''
    )) returning * into created_contract;
    return created_contract;
end;
$$;
revoke all on function public.approve_reservation(uuid) from public, anon;
grant execute on function public.approve_reservation(uuid) to authenticated;

-- Existing unsigned drafts can still be signed under v1.0. Do not rewrite a
-- historical signed contract: its snapshot is already an agreed record.
update public.rental_contracts c set contract_snapshot = c.contract_snapshot || jsonb_build_object(
    'terms_version', '1.0', 'terms_effective_date', '2026-10-07',
    'owner_id', i.owner_id, 'item_title', i.title, 'item_category', i.category,
    'components', i.components, 'known_defects', i.known_defects, 'item_value', i.item_value,
    'daily_price', i.daily_price, 'location', i.address, 'before_photo_count', 0,
    'owner_nickname', op.display_name, 'owner_verified', op.verified, 'owner_trust_score', op.trust_score,
    'borrower_nickname', bp.display_name, 'borrower_verified', bp.verified, 'borrower_trust_score', bp.trust_score,
    'rental_days', r.end_date - r.start_date + 1, 'original_deposit', i.deposit,
    'discount_percent', case when i.deposit > 0 then round((i.deposit - r.deposit) * 100.0 / i.deposit)::integer else 0 end,
    'special_terms', coalesce(c.special_terms, '')
)
from public.reservations r
join public.items i on i.id = r.item_id
join public.profiles op on op.id = i.owner_id
join public.profiles bp on bp.id = r.borrower_id
where c.reservation_id = r.id and c.status = 'draft'
  and c.owner_signed_at is null and c.borrower_signed_at is null
  and not (c.contract_snapshot ? 'terms_version');

create or replace function public.set_contract_special_terms(p_contract_id uuid, p_special_terms text)
returns public.rental_contracts language plpgsql security definer set search_path = '' as $$
declare target public.rental_contracts; caller_id uuid := (select auth.uid());
begin
    if p_special_terms is not null and char_length(p_special_terms) > 500 then
        raise exception '특약은 500자 이하여야 합니다.' using errcode = '22001';
    end if;
    select c.* into target from public.rental_contracts c
    join public.reservations r on r.id = c.reservation_id
    join public.items i on i.id = r.item_id
    where c.id = p_contract_id and caller_id in (r.borrower_id, i.owner_id)
    for update of c;
    if target.id is null then raise exception '계약이 없거나 권한이 없습니다.' using errcode = '42501'; end if;
    if target.status <> 'draft' or target.owner_signed_at is not null or target.borrower_signed_at is not null then
        raise exception '첫 서명 후 특약을 변경할 수 없습니다.' using errcode = '42501';
    end if;
    update public.rental_contracts set special_terms = nullif(trim(p_special_terms), ''),
        contract_snapshot = jsonb_set(contract_snapshot, '{special_terms}', to_jsonb(coalesce(trim(p_special_terms), '')), true)
    where id = p_contract_id returning * into target;
    return target;
end;
$$;
revoke all on function public.set_contract_special_terms(uuid, text) from public, anon;
grant execute on function public.set_contract_special_terms(uuid, text) to authenticated;

-- Expose only the order number and approval time to the two parties for the PDF.
-- Never expose payment_key or provider payload from private.payment_orders.
create or replace function public.get_contract_payment_details(p_contract_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare details jsonb;
begin
    if not exists (
        select 1 from public.rental_contracts c
        join public.reservations r on r.id = c.reservation_id
        join public.items i on i.id = r.item_id
        where c.id = p_contract_id and (select auth.uid()) in (r.borrower_id, i.owner_id)
    ) then
        raise exception '계약이 없거나 권한이 없습니다.' using errcode = '42501';
    end if;
    select jsonb_build_object('order_id', po.order_id, 'paid_at', po.paid_at)
      into details from public.rental_contracts c
      join private.payment_orders po on po.reservation_id = c.reservation_id
      where c.id = p_contract_id and po.paid_at is not null;
    return coalesce(details, '{}'::jsonb);
end;
$$;
revoke all on function public.get_contract_payment_details(uuid) from public, anon;
grant execute on function public.get_contract_payment_details(uuid) to authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('contract-signatures', 'contract-signatures', false, 1048576, array['image/png'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists contract_signatures_read_parties on storage.objects;
create policy contract_signatures_read_parties on storage.objects for select to authenticated using (
    bucket_id = 'contract-signatures' and exists (
        select 1 from public.rental_contracts c
        join public.reservations r on r.id = c.reservation_id
        join public.items i on i.id = r.item_id
        where name in (c.id::text || '/owner.png', c.id::text || '/borrower.png')
          and (select auth.uid()) in (i.owner_id, r.borrower_id)
    )
);
drop policy if exists contract_signatures_insert_own on storage.objects;
create policy contract_signatures_insert_own on storage.objects for insert to authenticated with check (
    bucket_id = 'contract-signatures' and exists (
        select 1 from public.rental_contracts c
        join public.reservations r on r.id = c.reservation_id
        join public.items i on i.id = r.item_id
        where c.status = 'draft' and r.status = 'paid'
          and c.contract_snapshot->>'terms_version' = '1.0'
          and ((name = c.id::text || '/owner.png' and i.owner_id = (select auth.uid()) and c.owner_signed_at is null)
            or (name = c.id::text || '/borrower.png' and r.borrower_id = (select auth.uid()) and c.borrower_signed_at is null))
    )
);
drop policy if exists contract_signatures_delete_unsigned_own on storage.objects;
create policy contract_signatures_delete_unsigned_own on storage.objects for delete to authenticated using (
    bucket_id = 'contract-signatures' and exists (
        select 1 from public.rental_contracts c
        join public.reservations r on r.id = c.reservation_id
        join public.items i on i.id = r.item_id
        where c.status = 'draft'
          and ((name = c.id::text || '/owner.png' and i.owner_id = (select auth.uid()) and c.owner_signed_at is null)
            or (name = c.id::text || '/borrower.png' and r.borrower_id = (select auth.uid()) and c.borrower_signed_at is null))
    )
);

-- Existing signature RPC is replaced in place; V3 payment/QR/admin grants stay revoked.
create or replace function public.sign_rental_contract(p_contract_id uuid)
returns public.rental_contracts language plpgsql security definer set search_path = '' as $$
declare
    caller_id uuid := (select auth.uid());
    target public.rental_contracts;
    reservation_status text;
    borrower_id uuid;
    owner_id uuid;
    role_name text;
    computed_hash text;
begin
    select c.* into target
    from public.rental_contracts c
    join public.reservations r on r.id = c.reservation_id
    join public.items i on i.id = r.item_id
    where c.id = p_contract_id for update of c;
    select r.status, r.borrower_id, i.owner_id
      into reservation_status, borrower_id, owner_id
    from public.reservations r join public.items i on i.id = r.item_id
    where r.id = target.reservation_id;
    if target.id is null or caller_id is null or caller_id not in (borrower_id, owner_id) then
        raise exception '계약이 없거나 서명 권한이 없습니다.' using errcode = '42501';
    end if;
    if not exists (select 1 from public.profiles p where p.id = caller_id and p.verified) then
        raise exception '본인인증을 완료한 계정만 서명할 수 있습니다.' using errcode = '42501';
    end if;
    if reservation_status <> 'paid' or target.status not in ('draft', 'signed') then
        raise exception '결제 완료 후에만 서명할 수 있습니다.' using errcode = 'P0001';
    end if;
    if target.contract_snapshot->>'terms_version' is distinct from '1.0' then
        raise exception '기존 서명 계약에는 새 조항을 소급 적용할 수 없습니다.' using errcode = '42501';
    end if;
    role_name := case when caller_id = owner_id then 'owner' else 'borrower' end;
    if not exists (select 1 from storage.objects
        where bucket_id = 'contract-signatures' and name = p_contract_id::text || '/' || role_name || '.png') then
        raise exception '서명 PNG를 먼저 저장하세요.' using errcode = 'P0001';
    end if;
    computed_hash := private.contract_snapshot_sha256(target.contract_snapshot);
    if target.contract_snapshot ? 'hash' and target.contract_snapshot->>'hash' <> computed_hash then
        raise exception '서명 후 계약 내용이 변경되었습니다.' using errcode = '42501';
    end if;
    update public.rental_contracts set
        contract_snapshot = case when contract_snapshot ? 'hash' then contract_snapshot
            else jsonb_set(contract_snapshot, '{hash}', to_jsonb(computed_hash), true) end,
        owner_signed_at = case when caller_id = owner_id then coalesce(owner_signed_at, now()) else owner_signed_at end,
        borrower_signed_at = case when caller_id = borrower_id then coalesce(borrower_signed_at, now()) else borrower_signed_at end
    where id = p_contract_id returning * into target;
    if target.owner_signed_at is not null and target.borrower_signed_at is not null then
        update public.rental_contracts set status = 'signed' where id = p_contract_id returning * into target;
    end if;
    return target;
end;
$$;
revoke all on function public.sign_rental_contract(uuid) from public, anon;
grant execute on function public.sign_rental_contract(uuid) to authenticated;
