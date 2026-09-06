create schema if not exists private;

create table if not exists private.payment_orders (
    id uuid primary key default gen_random_uuid(),
    reservation_id uuid not null unique references public.reservations(id) on delete restrict,
    payer_id uuid not null references public.profiles(id) on delete restrict,
    order_id text not null unique check (char_length(order_id) between 10 and 100),
    payment_key text unique,
    expected_amount integer not null check (expected_amount > 0),
    approved_amount integer check (approved_amount >= 0),
    refunded_amount integer not null default 0 check (refunded_amount >= 0),
    status text not null default 'ready' check (
        status in ('ready', 'confirming', 'paid', 'refund_pending', 'partially_refunded', 'refunded', 'failed')
    ),
    idempotency_key uuid not null unique,
    error_code text,
    provider_payload jsonb not null default '{}'::jsonb,
    version integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    paid_at timestamptz,
    refunded_at timestamptz,
    check (approved_amount is null or approved_amount = expected_amount),
    check (refunded_amount <= coalesce(approved_amount, expected_amount))
);

create index if not exists payment_orders_payer_created_idx
    on private.payment_orders(payer_id, created_at desc);
create index if not exists payment_orders_status_updated_idx
    on private.payment_orders(status, updated_at);

create table if not exists private.payment_events (
    id bigint generated always as identity primary key,
    payment_order_id uuid not null references private.payment_orders(id) on delete cascade,
    event_type text not null,
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);
create index if not exists payment_events_order_created_idx
    on private.payment_events(payment_order_id, created_at desc);

create table if not exists private.qr_tokens (
    id uuid primary key default gen_random_uuid(),
    contract_id uuid not null references public.rental_contracts(id) on delete cascade,
    action text not null check (action in ('start', 'end')),
    token_hash text not null unique check (char_length(token_hash) = 64),
    issued_by uuid not null references public.profiles(id) on delete restrict,
    verified_by uuid references public.profiles(id) on delete restrict,
    expires_at timestamptz not null,
    used_at timestamptz,
    created_at timestamptz not null default now()
);
create index if not exists qr_tokens_contract_action_idx
    on private.qr_tokens(contract_id, action, created_at desc);
create index if not exists qr_tokens_expiry_unused_idx
    on private.qr_tokens(expires_at) where used_at is null;

create table if not exists private.webhook_events (
    transmission_id text primary key,
    event_type text not null,
    payload jsonb not null,
    status text not null default 'received' check (status in ('received', 'processed', 'failed')),
    attempts integer not null default 1 check (attempts > 0),
    received_at timestamptz not null default now(),
    processed_at timestamptz,
    error_code text
);

create table if not exists private.audit_log (
    id bigint generated always as identity primary key,
    actor_id uuid,
    action text not null,
    target_type text not null,
    target_id text not null,
    details jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);
create index if not exists audit_log_target_created_idx
    on private.audit_log(target_type, target_id, created_at desc);
create index if not exists audit_log_actor_created_idx
    on private.audit_log(actor_id, created_at desc);

revoke all on all tables in schema private from public, anon, authenticated;
revoke all on all sequences in schema private from public, anon, authenticated;
