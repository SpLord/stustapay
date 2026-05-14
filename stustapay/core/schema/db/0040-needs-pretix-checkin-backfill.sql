-- migration: f6a7b8c9
-- requires: e5f6a7b8

-- Backfill for migration 0036 (pretix-checkin-and-cancellation).
--
-- An earlier version of 0036 contained only the `cancelled` and
-- `externally_checked_in` ALTERs without `needs_pretix_checkin`. Installations
-- that ran that earlier version recorded `schema_revision = b2c3d4e5` and
-- subsequently skipped 0036 even after the file was extended, leaving the
-- `needs_pretix_checkin` column missing from `ticket_voucher`. This breaks
-- the pretix-checkin sync loop in `stustapay/ticket_shop/pretix.py`
-- (`_sync_pending_checkins`).
--
-- `IF NOT EXISTS` makes this idempotent: installations that ran the complete
-- 0036 are unaffected; installations missing the column get it backfilled.

alter table ticket_voucher add column if not exists needs_pretix_checkin boolean not null default false;
