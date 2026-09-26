-- migration: c9d0e1f2
-- requires: b8c9d0e1

-- Fork (SpLord): additional branding logo fields on event_design.
-- Formerly fork migration 0039.
alter table event_design add column if not exists app_logo_blob_id uuid references blob(id);
alter table event_design add column if not exists customer_logo_blob_id uuid references blob(id);
alter table event_design add column if not exists wristband_guide_blob_id uuid references blob(id);
