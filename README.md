# NUSI Membership 2026 - Java/Render

This project serves the NUSI membership form and DG/eSamudra photo automation from the same Render service.

## Render Environment

Required:

- `GOOGLE_SERVICE_ACCOUNT_JSON` = the complete Google service-account JSON (same credential used by the previous NUSI Render project). The service account must have Editor access to the target Sheet and Drive folders.
- `API_KEY` = generated secret for the optional `/dg/photo` API.

Already configured by `render.yaml` / code defaults:

- `SPREADSHEET_ID=1BCovd-XYwFto5oelzmgDvYjCNpQ45WP1pFk7Xd0Fqok`
- `SHEET_NAME=Sheet1`
- `MAIN_FOLDER_ID=13UBnaaMa27-qTf6NvBXe5Vt6jkCCYFaZ`
- `SLIDER_FOLDER_ID=1tZp5paf-Vju9w1e2Z4naKHQ3VKu-KFcM`

## URLs

- `/` - NUSI Membership form
- `/health` - health check
- `/api/slider` - slider list
- `/api/submit` - form submission
- `/dg/photo` - DG photo API (requires `X-API-Key` when API_KEY is configured)

No candidate INDoS password is written to Google Sheets or Drive.
