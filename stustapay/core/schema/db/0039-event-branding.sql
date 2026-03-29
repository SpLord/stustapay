-- migration: e5f6a7b8
-- requires: d4e5f6a7

-- Add new branding logo fields to event_design
alter table event_design add column app_logo_blob_id uuid references blob(id);
alter table event_design add column customer_logo_blob_id uuid references blob(id);
alter table event_design add column wristband_guide_blob_id uuid references blob(id);
