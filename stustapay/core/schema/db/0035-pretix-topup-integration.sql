-- migration: a1b2c3d4
-- requires: 6dc876fd

-- Add top-up product IDs to event settings (which Pretix products are top-up/credit products)
alter table event add column pretix_topup_ids int array not null default '{}';

-- Add fields to ticket_voucher for better Pretix data tracking
alter table ticket_voucher add column initial_top_up_amount numeric not null default 0;
alter table ticket_voucher add column pretix_item_id int;
