# NUSI DG Photo Service (Render + Java)

This service receives an INDoS number and DG/eSamudra password, logs into DG Shipping with Selenium, opens **Update Seafarer Profile**, downloads the authenticated profile PDF, extracts the candidate photograph, and returns a JPEG as Base64.

## Endpoints

- `GET /health`
- `POST /dg/photo`

Request:

```json
{
  "indos": "12AB3456",
  "password": "candidate-password"
}
```

Response:

```json
{
  "success": true,
  "mimeType": "image/jpeg",
  "base64": "...",
  "width": 600,
  "height": 600
}
```

## Render

1. Push this project to GitHub.
2. In Render, create a **Blueprint** from the repo (or a Docker Web Service).
3. `render.yaml` creates an `API_KEY` automatically.
4. After deploy, open `/health`.
5. Copy the Render service URL and API key into your Apps Script.

## Apps Script caller

```javascript
const DG_PHOTO_API_URL =
  "https://YOUR-SERVICE.onrender.com/dg/photo";

const DG_PHOTO_API_KEY =
  "PASTE_RENDER_API_KEY";

function fetchDgPhoto_(indos, password) {

  const response = UrlFetchApp.fetch(
    DG_PHOTO_API_URL,
    {
      method: "post",
      contentType: "application/json",
      headers: {
        "X-API-Key": DG_PHOTO_API_KEY
      },
      payload: JSON.stringify({
        indos: indos,
        password: password
      }),
      muteHttpExceptions: true
    }
  );

  const status = response.getResponseCode();
  const text = response.getContentText();
  const result = JSON.parse(text);

  if (status < 200 || status >= 300 || !result.success) {
    throw new Error(
      result.message || "DG Photo service failed."
    );
  }

  return result;
}
```

The password is not stored by this Java service and is not written to logs.
