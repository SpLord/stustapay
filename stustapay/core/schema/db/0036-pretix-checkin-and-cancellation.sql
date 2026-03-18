-- migration: b2c3d4e5
-- requires: a1b2c3d4

-- Track whether a ticket voucher has been cancelled in the source system
alter table ticket_voucher add column cancelled boolean not null default false;

-- Track checkin status from external system (e.g. pretixSCAN)
alter table ticket_voucher add column externally_checked_in boolean not null default false;

-- Flag to sync checkin back to Pretix (set when NFC band is assigned)
alter table ticket_voucher add column needs_pretix_checkin boolean not null default false;
