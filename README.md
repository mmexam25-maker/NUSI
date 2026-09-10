# NUSI Membership 2026 — Render version

Mobile-first Node.js/Express app for NUSI membership registration on Render.

## Added in this version

- Membership Type radio buttons: **New Member / Existing**
- **SID No.**
- **Nominee: Spouse / Mother**
- **Spouse Phone No.** (shown when Spouse is selected)
- Blood Group retained
- Second **Members** tab with Sl. No., Name, Member Type and **New Card** button
- Existing-member lookup by **INDoS No.**
- Existing details open in a dialog before loading the form
- **New Card** button fetches the member and pre-fills the Existing form
- Existing member document uploads are optional; saved Drive links are reused if no new file is uploaded
- Responsive mobile layout, mobile table cards and bottom-sheet style details dialog

## Google Sheet columns

The existing important columns are preserved. New values use previously blank columns where possible.

- A Name
- B CDC
- C INDoS
- D DOB
- E Age
- F Blood Group
- G Rank
- H Photo URL
- I CDC PDF URL
- J Passport PDF URL
- K Signature URL
- L SID No.
- M Address
- N Nominee (Spouse/Mother)
- O Mobile
- P Spouse Phone
- Q Email
- R Membership Type
- S Submitted Date
- T Alternate Mobile
- U Alternate Email
- V City
- W State
- X Pincode

Older rows are still readable. If Membership Type is blank, they are shown as **Existing**.

## Google setup

1. Enable **Google Drive API** and **Google Sheets API** in Google Cloud.
2. Create a service account and download its JSON key.
3. Share the target Google Sheet with the service-account email as **Editor**.
4. Share the MAIN_FOLDER and SLIDER_FOLDER with the same service-account email.

## Render

1. Push this folder to GitHub.
2. Render → **New → Web Service** → connect the repository.
3. Build Command: `npm install`
4. Start Command: `npm start`
5. Add the secret Environment Variable `GOOGLE_SERVICE_ACCOUNT_JSON` and paste the complete service-account JSON.
6. Deploy.

The spreadsheet and folder IDs are already present in `render.yaml`.

## Existing member flow

1. Select **Existing**.
2. Enter INDoS No.
3. Tap **Fetch Details**.
4. Existing details are displayed in a dialog.
5. Tap **Use Details / New Card**.
6. The form is pre-filled.
7. Update any changed details and submit.

You can also go to the **Members** tab and tap **New Card** beside a member.
