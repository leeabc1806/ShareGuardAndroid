-- Apply once to the existing Supabase project, after schema.sql and PASS migration.
-- No existing users, messages, transactions or payment records are deleted.
begin;

alter table public.chat_room_members add column if not exists hidden_at timestamptz;
create table if not exists public.reservation_archives (
    reservation_id uuid not null references public.reservations(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    primary key (reservation_id, user_id)
);
create table if not exists public.reservation_date_requests (
    id uuid primary key default gen_random_uuid(),
    reservation_id uuid not null references public.reservations(id) on delete cascade,
    requested_by uuid not null references public.profiles(id),
    start_date date not null,
    end_date date not null check (end_date >= start_date),
    original_start date not null,
    original_end date not null,
    original_total integer not null,
    rental_fee integer not null check (rental_fee >= 0),
    status text not null default 'pending' check (status in ('pending','accepted','rejected','superseded')),
    created_at timestamptz not null default now()
);
create unique index if not exists one_pending_date_request on public.reservation_date_requests(reservation_id) where status = 'pending';
create table if not exists public.app_notifications (
    id bigint generated always as identity primary key,
    user_id uuid not null references public.profiles(id) on delete cascade,
    reservation_id uuid references public.reservations(id) on delete cascade,
    room_id uuid references public.chat_rooms(id) on delete cascade,
    kind text not null check (kind in ('chat','reservation','schedule')),
    title text not null,
    body text not null,
    created_at timestamptz not null default now(),
    read_at timestamptz
);
create index if not exists notifications_user_id_idx on public.app_notifications(user_id,id desc);
alter table public.reservation_archives enable row level security;
alter table public.reservation_date_requests enable row level security;
alter table public.app_notifications enable row level security;
revoke all on public.reservation_archives, public.reservation_date_requests, public.app_notifications from public, anon, authenticated;
grant select on public.reservation_archives, public.reservation_date_requests, public.app_notifications to authenticated;
grant update(read_at) on public.app_notifications to authenticated;
drop policy if exists archives_self on public.reservation_archives;
create policy archives_self on public.reservation_archives for select to authenticated using(user_id = (select auth.uid()));
drop policy if exists notifications_self on public.app_notifications;
create policy notifications_self on public.app_notifications for all to authenticated using(user_id = (select auth.uid())) with check(user_id = (select auth.uid()));
drop policy if exists date_requests_participant on public.reservation_date_requests;
create policy date_requests_participant on public.reservation_date_requests for select to authenticated using(
    exists(select 1 from public.reservations r where r.id = reservation_id)
);

create or replace function public.update_my_display_name(p_display_name text) returns void
language plpgsql security definer set search_path = '' as $$
declare chosen_name text := btrim(p_display_name);
begin
    if auth.uid() is null then raise exception '로그인이 필요합니다.'; end if;
    if chosen_name is null or char_length(chosen_name) not between 2 and 30 then raise exception '닉네임은 2~30자로 입력하세요.'; end if;
    update public.profiles set display_name=chosen_name where id=auth.uid();
    if not found then raise exception '프로필을 찾을 수 없습니다.'; end if;
    update auth.users set raw_user_meta_data=jsonb_set(coalesce(raw_user_meta_data,'{}'::jsonb),'{display_name}',to_jsonb(chosen_name)) where id=auth.uid();
end;
$$;
revoke all on function public.update_my_display_name(text) from public,anon;
grant execute on function public.update_my_display_name(text) to authenticated;

-- Definer helper avoids a reservations -> items -> reservations RLS cycle.
create or replace function private.is_item_participant(p_item_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists(select 1 from public.reservations r where r.item_id=p_item_id and r.borrower_id=auth.uid());
$$;
revoke all on function private.is_item_participant(uuid) from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.is_item_participant(uuid) to authenticated;
drop policy if exists items_read_transaction_participant on public.items;
create policy items_read_transaction_participant on public.items for select to authenticated
using(private.is_item_participant(id));

create or replace function public.create_rental_reservation(p_item_id uuid,p_start_date date,p_end_date date)
returns public.reservations language plpgsql security definer set search_path = '' as $$
declare i public.items; r public.reservations; score integer; discount integer;
begin
    if auth.uid() is null then raise exception '로그인이 필요합니다.'; end if;
    select trust_score into score from public.profiles where id=auth.uid() and verified;
    if not found then raise exception 'PASS 본인인증이 필요합니다.'; end if;
    if p_start_date is null or p_end_date is null or p_start_date < (now() at time zone 'Asia/Seoul')::date or p_end_date < p_start_date then
        raise exception '대여 날짜를 확인하세요.';
    end if;
    select * into i from public.items where id=p_item_id for update;
    if not found or i.status <> 'active' or i.owner_id=auth.uid() then raise exception '예약 가능한 다른 회원의 물품을 선택하세요.'; end if;
    if exists(select 1 from public.reservations where item_id=i.id and status in ('approved','paid','renting')
        and rental_period && daterange(p_start_date,p_end_date,'[]')) then raise exception '선택한 기간에 확정된 예약이 있습니다.'; end if;
    if exists(select 1 from public.reservations where item_id=i.id and borrower_id=auth.uid() and status='pending'
        and start_date=p_start_date and end_date=p_end_date) then raise exception '같은 기간의 예약을 이미 신청했습니다.'; end if;
    discount := case when score >= 900 then 30 when score >= 800 then 20 when score >= 700 then 10 else 0 end;
    insert into public.reservations(item_id,borrower_id,start_date,end_date,rental_fee,deposit)
    values(i.id,auth.uid(),p_start_date,p_end_date,i.daily_price::bigint*(p_end_date-p_start_date+1),
        floor(i.deposit::numeric*(100-discount)/100)::integer) returning * into r;
    return r;
end;
$$;

-- Cancellation/date changes must not race a payment already submitted to Toss.
create or replace function private.assert_unpaid(p_reservation_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare payment_busy boolean;
begin
    if exists(select 1 from public.payments where reservation_id=p_reservation_id and status in ('ready','paid','held','partially_refunded')) then
        raise exception '결제가 진행되었거나 처리 중입니다. 결제 내역을 먼저 확인하세요.';
    end if;
    if to_regclass('private.payment_orders') is not null then
        execute 'select exists(select 1 from private.payment_orders where reservation_id=$1 and status not in (''failed'',''refunded''))'
            into payment_busy using p_reservation_id;
        if payment_busy then raise exception '결제가 진행 중입니다. 결제 처리 결과를 먼저 확인하세요.'; end if;
    end if;
end;
$$;
revoke all on function private.assert_unpaid(uuid) from public,anon,authenticated;

create or replace function public.remove_rental_reservation(p_reservation_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare r public.reservations; owner uuid;
begin
    select * into r from public.reservations where id=p_reservation_id for update;
    select owner_id into owner from public.items where id=r.item_id;
    if r.id is null or auth.uid() is null or auth.uid() not in (r.borrower_id,owner) then raise exception '거래에 접근할 수 없습니다.'; end if;
    if r.status in ('pending','approved') then
        perform private.assert_unpaid(r.id);
        update public.reservations set status='cancelled' where id=r.id;
        update public.reservation_date_requests set status='superseded' where reservation_id=r.id and status='pending';
    elsif r.status not in ('cancelled','rejected','completed') then
        raise exception '결제·대여·분쟁 진행 중인 거래는 삭제할 수 없습니다. 반납 또는 환불 처리를 먼저 완료하세요.';
    end if;
    insert into public.reservation_archives values(r.id,auth.uid()) on conflict do nothing;
end;
$$;

create or replace function public.propose_reservation_dates(p_reservation_id uuid,p_start_date date,p_end_date date)
returns public.reservation_date_requests language plpgsql security definer set search_path = '' as $$
declare r public.reservations; i public.items; proposal public.reservation_date_requests; recipient uuid;
begin
    select * into r from public.reservations where id=p_reservation_id for update;
    select * into i from public.items where id=r.item_id;
    if r.id is null or auth.uid() is null or auth.uid() not in (r.borrower_id,i.owner_id) then raise exception '거래에 접근할 수 없습니다.'; end if;
    if r.status not in ('pending','approved') then raise exception '결제 전에만 날짜를 변경할 수 있습니다.'; end if;
    perform private.assert_unpaid(r.id);
    if p_start_date is null or p_end_date is null or p_start_date < (now() at time zone 'Asia/Seoul')::date or p_end_date < p_start_date then raise exception '대여 날짜를 확인하세요.'; end if;
    if (p_start_date,p_end_date)=(r.start_date,r.end_date) then raise exception '기존 일정과 같습니다.'; end if;
    if exists(select 1 from public.reservations where item_id=r.item_id and id<>r.id and status in ('approved','paid','renting')
        and rental_period && daterange(p_start_date,p_end_date,'[]')) then raise exception '선택한 기간에 확정된 예약이 있습니다.'; end if;
    update public.reservation_date_requests set status='superseded' where reservation_id=r.id and status='pending';
    insert into public.reservation_date_requests(reservation_id,requested_by,start_date,end_date,original_start,original_end,original_total,rental_fee)
    values(r.id,auth.uid(),p_start_date,p_end_date,r.start_date,r.end_date,r.total_amount,i.daily_price::bigint*(p_end_date-p_start_date+1)) returning * into proposal;
    recipient := case when auth.uid()=r.borrower_id then i.owner_id else r.borrower_id end;
    insert into public.app_notifications(user_id,reservation_id,room_id,kind,title,body)
    select recipient,r.id,id,'schedule','예약 날짜 변경 요청',p_start_date || ' ~ ' || p_end_date || ' · 채팅에서 확인해주세요.' from public.chat_rooms where reservation_id=r.id;
    update public.chat_room_members set hidden_at=null where room_id in(select id from public.chat_rooms where reservation_id=r.id);
    return proposal;
end;
$$;

create or replace function public.respond_reservation_dates(p_request_id uuid,p_accept boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare proposal public.reservation_date_requests; r public.reservations; owner uuid; reservation_uuid uuid;
begin
    select reservation_id into reservation_uuid from public.reservation_date_requests where id=p_request_id;
    -- All mutations lock the reservation first, then its request.
    select * into r from public.reservations where id=reservation_uuid for update;
    select * into proposal from public.reservation_date_requests where id=p_request_id for update;
    select owner_id into owner from public.items where id=r.item_id;
    if r.id is null or auth.uid() is null or auth.uid() not in (r.borrower_id,owner) or auth.uid()=proposal.requested_by then raise exception '상대방만 날짜 변경에 응답할 수 있습니다.'; end if;
    if proposal.status <> 'pending' then raise exception '이미 처리된 요청입니다.'; end if;
    if p_accept is null then raise exception '응답을 선택하세요.'; end if;
    if p_accept then
        if r.status not in ('pending','approved') or (r.start_date,r.end_date,r.total_amount)<>(proposal.original_start,proposal.original_end,proposal.original_total) then raise exception '거래 상태가 변경되었습니다. 일정을 다시 제안하세요.'; end if;
        perform private.assert_unpaid(r.id);
        if proposal.start_date < (now() at time zone 'Asia/Seoul')::date then raise exception '과거 일정은 수락할 수 없습니다.'; end if;
        if exists(select 1 from public.reservations where item_id=r.item_id and id<>r.id and status in ('approved','paid','renting')
            and rental_period && daterange(proposal.start_date,proposal.end_date,'[]')) then raise exception '선택한 기간에 확정된 예약이 있습니다.'; end if;
        update public.reservations set start_date=proposal.start_date,end_date=proposal.end_date,rental_fee=proposal.rental_fee where id=r.id;
        update public.rental_contracts set owner_signed_at=null,borrower_signed_at=null,
            contract_snapshot=contract_snapshot || jsonb_build_object('start_date',proposal.start_date,'end_date',proposal.end_date,'rental_fee',proposal.rental_fee)
            where reservation_id=r.id and status='draft';
    end if;
    update public.reservation_date_requests set status=case when p_accept then 'accepted' else 'rejected' end where id=proposal.id;
    insert into public.app_notifications(user_id,reservation_id,room_id,kind,title,body)
    select proposal.requested_by,r.id,id,'schedule',case when p_accept then '예약 날짜 변경 수락' else '예약 날짜 변경 거절' end,
        proposal.start_date || ' ~ ' || proposal.end_date from public.chat_rooms where reservation_id=r.id;
end;
$$;

create or replace function public.hide_chat_room(p_room_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
    update public.chat_room_members set hidden_at=now() where room_id=p_room_id and user_id=auth.uid();
    if not found then raise exception '채팅방에 접근할 수 없습니다.'; end if;
end;
$$;

create or replace function public.list_my_chat_rooms() returns jsonb
language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_agg(row_to_json(t) order by t.updated_at desc),'[]'::jsonb) from (
        select c.id,c.reservation_id,m.hidden_at,last_message.body as last_body,
            coalesce(last_message.created_at,c.created_at) as updated_at,
            (select count(*) from public.chat_messages x where x.room_id=c.id and x.sender_id<>auth.uid()
                and (m.last_read_at is null or x.created_at>m.last_read_at)) as unread_count
        from public.chat_rooms c join public.chat_room_members m on m.room_id=c.id and m.user_id=auth.uid()
        left join lateral(select body,created_at from public.chat_messages where room_id=c.id order by id desc limit 1) last_message on true
    ) t;
$$;

create or replace function private.notify_chat_message() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    insert into public.app_notifications(user_id,reservation_id,room_id,kind,title,body)
    select m.user_id,c.reservation_id,new.room_id,'chat','새 채팅 메시지',left(new.body,150)
        from public.chat_room_members m join public.chat_rooms c on c.id=m.room_id
        where m.room_id=new.room_id and m.user_id<>new.sender_id;
    update public.chat_room_members set hidden_at=null where room_id=new.room_id and user_id<>new.sender_id;
    return new;
end;
$$;
drop trigger if exists notify_chat_message on public.chat_messages;
create trigger notify_chat_message after insert on public.chat_messages for each row execute function private.notify_chat_message();

create or replace function private.notify_reservation_event() returns trigger
language plpgsql security definer set search_path = '' as $$
declare owner uuid; item_title text; notification_title text;
begin
    select owner_id,title into owner,item_title from public.items where id=new.item_id;
    if tg_op='INSERT' then notification_title := '새 대여 신청이 도착했어요';
    elsif old.status is distinct from new.status then
        notification_title := case new.status when 'approved' then '예약이 승인되었어요' when 'rejected' then '예약이 거절되었어요'
            when 'cancelled' then '예약이 취소되었어요' when 'paid' then '결제가 완료되었어요' when 'renting' then '대여가 시작되었어요'
            when 'completed' then '반납이 완료되었어요' when 'disputed' then '거래 분쟁이 접수되었어요' else '거래 상태가 변경되었어요' end;
    else return new; end if;
    insert into public.app_notifications(user_id,reservation_id,kind,title,body)
    select recipient,new.id,'reservation',notification_title,item_title || ' · ' || new.start_date || ' ~ ' || new.end_date
    from (select owner as recipient union select new.borrower_id) recipients
    where recipient is distinct from auth.uid();
    return new;
end;
$$;
drop trigger if exists notify_reservation_event on public.reservations;
create trigger notify_reservation_event after insert or update on public.reservations for each row execute function private.notify_reservation_event();
revoke all on function private.notify_chat_message(),private.notify_reservation_event() from public,anon,authenticated;

revoke all on function public.create_rental_reservation(uuid,date,date),public.remove_rental_reservation(uuid),
    public.propose_reservation_dates(uuid,date,date),public.respond_reservation_dates(uuid,boolean),
    public.hide_chat_room(uuid),public.list_my_chat_rooms() from public,anon;
grant execute on function public.create_rental_reservation(uuid,date,date),public.remove_rental_reservation(uuid),
    public.propose_reservation_dates(uuid,date,date),public.respond_reservation_dates(uuid,boolean),
    public.hide_chat_room(uuid),public.list_my_chat_rooms() to authenticated;
notify pgrst, 'reload schema';
commit;
