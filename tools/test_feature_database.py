"""Run the migration and behavioral checks in an isolated local PostgreSQL cluster.

Uses no Supabase credentials and never connects to the production database.
"""
from pathlib import Path
import os
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
BIN = Path(r'C:\Program Files\PostgreSQL\18\bin')
WORK = ROOT / 'ShareGuardAndroid/build/feature-db-tests'
DATA = WORK / ('cluster-' + str(int(time.time())))
PORT = '55435'
WORK.mkdir(parents=True, exist_ok=True)
ENV = {**os.environ, 'PGCLIENTENCODING': 'UTF8'}

def command(args, **kwargs):
    if Path(args[0]).name == 'pg_ctl.exe':
        # PostgreSQL children can inherit stdout on Windows. A file avoids a
        # capture_output pipe remaining open after pg_ctl itself has exited.
        with (WORK/'pg_ctl.log').open('a', encoding='utf-8') as log:
            r = subprocess.run([str(x) for x in args], stdout=log, stderr=log, env=ENV, timeout=90, **kwargs)
        if r.returncode:
            raise RuntimeError((WORK/'pg_ctl.log').read_text(encoding='utf-8', errors='replace'))
        return ''
    r = subprocess.run([str(x) for x in args], capture_output=True, text=True, encoding='utf-8', errors='replace', env=ENV, **kwargs)
    if r.returncode:
        raise RuntimeError(r.stdout + '\n' + r.stderr)
    return r.stdout

PSQL = [BIN/'psql.exe','-h','127.0.0.1','-p',PORT,'-U','postgres','-d','postgres','-X','-v','ON_ERROR_STOP=1','-A','-t']
def sql(source):
    return command(PSQL, input=source).strip()
def file(path):
    return command(PSQL+['-f',path])

owner = '10000000-0000-0000-0000-000000000001'
borrower = '20000000-0000-0000-0000-000000000002'
outsider = '30000000-0000-0000-0000-000000000003'
item = '40000000-0000-0000-0000-000000000004'
checks = []
def as_user(user, query):
    out = sql(f"set role authenticated; set request.jwt.claim.sub='{user}'; {query}")
    return '\n'.join(line for line in out.splitlines() if line != 'SET')
def check(label, value):
    assert value, label
    checks.append(label)
    print('PASS:', label, flush=True)
def denied(label, user, query):
    try:
        as_user(user, query)
    except RuntimeError:
        check(label, True)
    else:
        raise AssertionError('Unexpectedly allowed: '+label)

print(command([BIN/'initdb.exe','-D',DATA,'-U','postgres','-A','trust','--encoding=UTF8','--locale=C']), flush=True)
command([BIN/'pg_ctl.exe','-D',DATA,'-l',WORK/'postgres.log','-o',f'-h 127.0.0.1 -p {PORT}','-w','start'])
try:
    sql("""
create role anon; create role authenticated;
create schema auth; create schema storage; create schema extensions;
grant usage on schema auth,public,storage to anon,authenticated;
create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}');
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
create table storage.objects(id uuid primary key default gen_random_uuid(),bucket_id text,name text,owner_id text);
alter table storage.objects enable row level security;
create function storage.foldername(text) returns text[] language sql as $$select string_to_array($1,'/')$$;
grant select,insert,update,delete on storage.objects to anon,authenticated;
""")
    file(ROOT/'ShareGuardAndroid/database/schema.sql')
    file(ROOT/'backend/src/main/resources/db/migration/V1__backend_transaction_tables.sql')
    for version in ('V2__*', 'V3__*'):
        file(next((ROOT/'backend/src/main/resources/db/migration').glob(version)))
    migration = ROOT/'ShareGuardAndroid/database/migrations/20261005_transactions_chat_notifications.sql'
    file(migration)
    file(migration)
    check('migration is repeatable', True)
    sql(f"""
insert into auth.users(id,email,raw_user_meta_data) values
('{owner}','owner@example.test','{{"display_name":"Owner One"}}'),
('{borrower}','borrower@example.test','{{"display_name":"Borrower Two"}}'),
('{outsider}','outsider@example.test','{{"display_name":"Third User"}}');
update public.profiles set verified=true,trust_score=800;
insert into public.items(id,owner_id,title,category,daily_price,deposit,address) values('{item}','{owner}','Test Camera','camera',10000,100000,'Test Place');
""")
    r1=as_user(borrower, f"select (public.create_rental_reservation('{item}',current_date+10,current_date+12)).id;")
    check('server calculates fee and seven-percent trust discount', sql(f"select rental_fee=30000 and deposit=93000 from public.reservations where id='{r1}'")=='t')
    r2=as_user(borrower, f"select (public.create_rental_reservation('{item}',current_date+20,current_date+22)).id;")
    check('another reservation allowed for the same item', r1 != r2)
    check('owner receives rental notification', as_user(owner, 'select count(*) from public.app_notifications;')=='2')
    check('outsider cannot read notifications', as_user(outsider, 'select count(*) from public.app_notifications;')=='0')
    denied('duplicate identical pending request rejected',borrower,f"select public.create_rental_reservation('{item}',current_date+10,current_date+12)")
    as_user(owner,f"select public.approve_reservation('{r1}')")
    denied('confirmed overlapping dates rejected',outsider,f"select public.create_rental_reservation('{item}',current_date+11,current_date+13)")
    proposal=as_user(borrower,f"select (public.propose_reservation_dates('{r1}',current_date+13,current_date+16)).id")
    denied('requester cannot accept own date proposal',borrower,f"select public.respond_reservation_dates('{proposal}',true)")
    denied('outsider cannot accept date proposal',outsider,f"select public.respond_reservation_dates('{proposal}',true)")
    as_user(owner,f"select public.respond_reservation_dates('{proposal}',true)")
    check('accepted dates and fee committed together', sql(f"select start_date=current_date+13 and end_date=current_date+16 and rental_fee=40000 from public.reservations where id='{r1}'")=='t')
    check('contract snapshot tracks accepted schedule', sql(f"select (contract_snapshot->>'rental_fee')::int=40000 from public.rental_contracts where reservation_id='{r1}'")=='t')
    denied('date request cannot be replayed',owner,f"select public.respond_reservation_dates('{proposal}',true)")
    room=sql(f"select id from public.chat_rooms where reservation_id='{r1}'")
    as_user(borrower,f"select public.hide_chat_room('{room}')")
    check('chat hiding affects only the caller',sql(f"select count(*)=1 from public.chat_room_members where room_id='{room}' and hidden_at is not null")=='t')
    as_user(owner,f"insert into public.chat_messages(room_id,body) values('{room}','new message')")
    check('incoming chat reopens a hidden room',sql(f"select hidden_at is null from public.chat_room_members where room_id='{room}' and user_id='{borrower}'")=='t')
    check('chat notification is sent only to the recipient',sql(f"select count(*)=1 and bool_and(user_id='{borrower}') from public.app_notifications where kind='chat'")=='t')
    check('chat list preview includes latest message', 'new message' in as_user(borrower,'select public.list_my_chat_rooms()'))
    denied('outsider cannot hide a room',outsider,f"select public.hide_chat_room('{room}')")
    denied('outsider cannot delete a reservation',outsider,f"select public.remove_rental_reservation('{r2}')")
    as_user(borrower,f"select public.remove_rental_reservation('{r2}')")
    check('unpaid deletion cancels and archives only for caller',sql(f"select status='cancelled' and exists(select 1 from public.reservation_archives where reservation_id='{r2}' and user_id='{borrower}') from public.reservations where id='{r2}'")=='t')
    sql(f"insert into private.payment_orders(reservation_id,payer_id,order_id,expected_amount,idempotency_key,status) values('{r1}','{borrower}','test-order-123456',120000,gen_random_uuid(),'confirming')")
    denied('cannot cancel during a payment confirmation',borrower,f"select public.remove_rental_reservation('{r1}')")
    denied('cannot propose dates during payment',owner,f"select public.propose_reservation_dates('{r1}',current_date+30,current_date+31)")
    sql(f"update public.reservations set status='paid' where id='{r1}'")
    denied('paid transaction cannot be deleted',borrower,f"select public.remove_rental_reservation('{r1}')")
    as_user(owner,"select public.update_my_display_name('Updated Owner')")
    check('nickname updates Auth metadata too', sql(f"select raw_user_meta_data->>'display_name'='Updated Owner' from auth.users where id='{owner}'")=='t')
    check('nickname remains account specific', sql(f"select display_name='Borrower Two' from public.profiles where id='{borrower}'")=='t')
    sql(f"update public.items set status='hidden' where id='{item}'")
    check('borrower can still read item for an existing transaction',as_user(borrower,f"select count(*) from public.items where id='{item}'")=='1')
    check('unrelated account cannot read hidden item',as_user(outsider,f"select count(*) from public.items where id='{item}'")=='0')
    print(f'{len(checks)} database integration checks passed.', flush=True)
    (WORK/'result.txt').write_text('\n'.join(checks)+'\n',encoding='utf-8')
finally:
    command([BIN/'pg_ctl.exe','-D',DATA,'-m','fast','-w','stop'])
