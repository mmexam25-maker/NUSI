# NUSI Membership 2026

Render Java app for NUSI membership registration.

## Current flow

- Surname + Given Name (no Full Name field)
- Nominee: Wife / Mother
- Photo attachment (JPG/JPEG/PNG)
- CDC PDF
- Passport PDF
- No DG Profile / no INDoS password
- Files are stored temporarily in Google Drive
- Sheet receives 24-hour temporary links
- Temporary links expire exactly 24 hours after submission
- Expired temporary folders are automatically trashed by the app cleanup job when the Render service is running
- NUSI logo left, ITF logo right
- Slider images remain from Google Drive

## Render Environment

Required:

- API_KEY
- GOOGLE_SERVICE_ACCOUNT_JSON
- SPREADSHEET_ID
- SHEET_NAME=Sheet1
- MAIN_FOLDER_ID
- SLIDER_FOLDER_ID
- PUBLIC_BASE_URL=https://YOUR-SERVICE.onrender.com

The Google service-account email must have Editor access to the spreadsheet and both Drive folders.

## Sheet columns A:X

A Surname
B Given Name
C CDC
D INDoS
E DOB
F Age
G Blood Group
H Rank
I Photo temporary link
J CDC temporary link
K Passport temporary link
L Nominee
M Address
N Mobile
O Alternate Mobile
P Email
Q Alternate Email
R City
S State
T Pincode
U Submitted At
V Link Expires At
W Temporary Drive Folder ID
X Status
