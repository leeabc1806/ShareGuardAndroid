-- Run with a DB administrator connection after Spring startup. Read-only checks.
select version, description, success
from public.flyway_schema_history order by installed_rank;

select to_regclass('private.payment_orders') as payment_orders,
       to_regclass('private.qr_tokens') as qr_tokens,
       to_regclass('private.mfa_verifications') as mfa_verifications;

-- Each demo RPC privilege should be false after V3.
select has_function_privilege('authenticated', 'public.pay_demo_reservation(uuid)', 'EXECUTE') as demo_payment_allowed,
       has_function_privilege('authenticated', 'public.verify_rental_transition(uuid,text)', 'EXECUTE') as local_qr_allowed,
       has_function_privilege('authenticated', 'public.resolve_report_admin(uuid,text,integer,text,text)', 'EXECUTE') as legacy_refund_allowed;

select has_table_privilege('authenticated', 'public.payments', 'UPDATE') as direct_payment_update,
       has_table_privilege('authenticated', 'public.rental_contracts', 'UPDATE') as direct_contract_update,
       has_function_privilege('authenticated', 'public.sign_rental_contract(uuid)', 'EXECUTE') as signing_allowed;
