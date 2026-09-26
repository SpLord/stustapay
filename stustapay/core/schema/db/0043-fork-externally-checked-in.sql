-- migration: a7b8c9d0
-- requires: 8ac08b65

-- Fork (SpLord): track checkin status from external system (e.g. pretixSCAN).
-- Was part of fork migration 0036 before the upstream merge; upstream 0036
-- (pretix-topup-integration) contains everything else from our 0035–0037.
-- IF NOT EXISTS keeps this a no-op on databases migrated with the old fork chain.
alter table ticket_voucher add column if not exists externally_checked_in boolean not null default false;
alter table ticket_voucher add column if not exists needs_pretix_checkin boolean not null default false;
