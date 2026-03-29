# Event Branding — Design Spec

## Overview

Add per-event branding with 4 image fields: App-Logo, Customer Portal Logo, Wristband Guide, and Bon-Logo (existing). Images are stored in the existing `blob` table, referenced from `event_design`, and served via the existing `/media/blob/{id}` endpoint.

## Image Fields

| Field | DB Column | Usage | Format | Recommended Size | Default |
|-------|-----------|-------|--------|-----------------|---------|
| **App-Logo** | `app_logo_blob_id` | Terminal-App + chip_debug Startseite | PNG/JPG/SVG | 512 x 512 px | "StuStaPay" Text |
| **Customer Logo** | `customer_logo_blob_id` | Customer Portal Login-Seite oben (ersetzt Lock-Icon) | PNG/JPG/SVG | 300 x 100 px | Lock-Icon |
| **Wristband Guide** | `wristband_guide_blob_id` | Customer Portal Login-Seite unten (ersetzt pin_uid_howto.svg) | PNG/JPG/SVG | 600 x 400 px | Statisches pin_uid_howto.svg |
| **Bon-Logo** | `bon_logo_blob_id` | Thermodrucker-Bons + Reports | SVG s/w | besteht bereits | — |

## Changes

### 1. Database

**Migration 0039-event-branding.sql:**
```sql
-- Add new logo fields to event_design
alter table event_design add column app_logo_blob_id uuid references blob(id);
alter table event_design add column customer_logo_blob_id uuid references blob(id);
alter table event_design add column wristband_guide_blob_id uuid references blob(id);
```

**Extend MimeType enum** in `media.py`:
```python
class MimeType(enum.Enum):
    svg = "image/svg+xml"
    png = "image/png"
    jpeg = "image/jpeg"
```

### 2. Backend Schema

**`media.py` — EventDesign:**
```python
class EventDesign(BaseModel):
    bon_logo_blob_id: UUID | None
    app_logo_blob_id: UUID | None
    customer_logo_blob_id: UUID | None
    wristband_guide_blob_id: UUID | None
```

**`terminal.py` — TerminalConfig:**
Add `app_logo_url: str | None = None` to TerminalConfig so the app knows where to load the logo from.

### 3. Backend Service

**`tree/service.py` — New endpoints:**
- `update_app_logo(node_id, blob)` — same pattern as `update_bon_logo()`
- `update_customer_logo(node_id, blob)` — same pattern
- `update_wristband_guide(node_id, blob)` — same pattern

**Mime type validation:** Accept PNG, JPG, SVG (not just SVG like bon_logo).

**`terminal.py` — get_terminal_config():**
- Fetch `event_design` for the event node
- If `app_logo_blob_id` is set, include URL in TerminalConfig as `app_logo_url`
- URL format: `{base_url}/media/blob/{blob_id}`

### 4. Backend Routes

**Administration API (`routers/tree.py`):**
```
POST /events/{node_id}/event-design/app-logo
POST /events/{node_id}/event-design/customer-logo
POST /events/{node_id}/event-design/wristband-guide
```
Same pattern as existing `bon-logo` endpoint.

**Terminal API — media serving:**
The terminal server needs a `/media/blob/{id}` endpoint too (currently only admin API has it), so the Android app can fetch logos without admin credentials.

### 5. Admin UI

**`TabDesign.tsx` — Extend with 3 new upload sections:**
Each section has:
- Title + recommended size hint text
- File input (accept PNG/JPG/SVG)
- Preview of current image
- Upload button

Layout:
```
┌─────────────────────────────────┐
│ App-Logo (Terminal-App)         │
│ Empfohlen: 512 x 512 px, PNG   │
│ [Bild wählen] [Preview]        │
├─────────────────────────────────┤
│ Customer Portal Logo            │
│ Empfohlen: 300 x 100 px, PNG   │
│ [Bild wählen] [Preview]        │
├─────────────────────────────────┤
│ Wristband Guide                 │
│ Empfohlen: 600 x 400 px, PNG   │
│ Foto vom Band mit PIN-Hinweis   │
│ [Bild wählen] [Preview]        │
├─────────────────────────────────┤
│ Bon-Logo (Thermodrucker)        │
│ Nur SVG, schwarz-weiß          │
│ [Bild wählen] [Preview]        │
│ (existiert bereits)             │
└─────────────────────────────────┘
```

### 6. Customer Portal

**`Login.tsx`:**
- Fetch event design from API (new endpoint or embed in customer config)
- If `customer_logo_blob_id` set: show logo image instead of Lock-Icon Avatar
- If `wristband_guide_blob_id` set: show custom image instead of `pin_uid_howto.svg`
- Fallback to defaults if not set

**New Customer Portal endpoint needed:**
`GET /event-design` — returns EventDesign with blob IDs (public, no auth needed since it's for the login page).

### 7. Android App

**TerminalConfig API model (`TerminalConfig.kt`):**
Add `app_logo_url: String? = null`

**Startpage (`StartpageView.kt`):**
- If `app_logo_url` is set in config: load and display with `AsyncImage` (Coil library) or simple `BitmapFactory` from URL
- Replace "StuStaPay" header text area with the logo image
- Fallback: show text if no logo configured

**chip_debug Startpage:**
- Same: show logo if available from a shared config or hardcoded URL

**Dependency:** Add `io.coil-kt:coil-compose` for async image loading, or use simple `HttpURLConnection` + `BitmapFactory` to avoid new deps.

### 8. Generated API

After adding backend endpoints, regenerate the TypeScript API client for admin UI and customer portal to include new endpoints and types.

## Implementation Order

1. DB migration (0039)
2. Backend schema + service + routes
3. Admin UI upload sections
4. Terminal API: media serving + app_logo_url in config
5. Customer Portal: logo + wristband guide on login page
6. Android App: logo on startpage

## Not In Scope

- App launcher icon (static, build-time only)
- Custom color themes per event
- Custom fonts per event
- Splash screen customization
