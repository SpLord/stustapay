-- migration: b8c9d0e1
-- requires: a7b8c9d0

-- Fork (SpLord): mark products as deposit products (e.g. cup deposit).
-- Formerly fork migration 0038.
alter table product add column if not exists is_deposit boolean not null default false;
