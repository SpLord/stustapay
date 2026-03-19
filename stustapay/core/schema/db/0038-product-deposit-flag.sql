-- migration: d4e5f6a7
-- requires: c3d4e5f6

-- Mark products as deposit products (e.g. cup deposit)
alter table product add column is_deposit boolean not null default false;
