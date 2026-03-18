-- migration: b2c3d4e5
-- requires: a1b2c3d4

-- Track whether a ticket voucher has been cancelled in the source system
alter table ticket_voucher add column cancelled boolean not null default false;

-- Track checkin status from external system (e.g. pretixSCAN)
alter table ticket_voucher add column externally_checked_in boolean not null default false;
