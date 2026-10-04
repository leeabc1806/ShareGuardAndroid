-- Never trust the Android quote for a reservation's financial amounts.
-- Existing reservations keep their agreed amount; only new inserts are recalculated.
create or replace function private.set_reservation_quote()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    item_price integer;
    item_deposit integer;
    borrower_trust integer;
    discount_percent integer;
begin
    select daily_price, deposit into item_price, item_deposit
    from public.items where id = new.item_id and status = 'active';
    if not found then
        raise exception 'Item is not available';
    end if;
    select trust_score into borrower_trust from public.profiles where id = new.borrower_id;
    if not found then
        raise exception 'Borrower profile not found';
    end if;
    discount_percent := case
        when borrower_trust >= 900 then 10
        when borrower_trust >= 800 then 7
        when borrower_trust >= 700 then 5
        when borrower_trust >= 600 then 3
        else 0
    end;
    new.rental_fee := item_price * (new.end_date - new.start_date + 1);
    new.deposit := item_deposit * (100 - discount_percent) / 100;
    return new;
end;
$$;

drop trigger if exists set_reservation_quote on public.reservations;
create trigger set_reservation_quote
before insert on public.reservations
for each row execute function private.set_reservation_quote();
