-- migration: c3d4e5f6
-- requires: b2c3d4e5

alter table ticket_voucher add column pretix_product_name text;
