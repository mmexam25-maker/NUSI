const express = require("express");
const path = require("path");
const fs = require("fs");
const { Readable } = require("stream");
const { google } = require("googleapis");

const app = express();
const PORT = process.env.PORT || 10000;

app.use(express.json({ limit: "60mb" }));
app.use(express.static(path.join(__dirname, "public")));

const CONFIG = {
  spreadsheetId:
    process.env.SPREADSHEET_ID ||
    "1BCovd-XYwFto5oelzmgDvYjCNpQ45WP1pFk7Xd0Fqok",
  sheetName: process.env.SHEET_NAME || "Sheet1",
  mainFolderId:
    process.env.MAIN_FOLDER_ID || "13UBnaaMa27-qTf6NvBXe5Vt6jkCCYFaZ",
  sliderFolderId:
    process.env.SLIDER_FOLDER_ID || "1tZp5paf-Vju9w1e2Z4naKHQ3VKu-KFcM"
};

function getGoogleCredentials() {
  if (process.env.GOOGLE_SERVICE_ACCOUNT_JSON) {
    const raw = process.env.GOOGLE_SERVICE_ACCOUNT_JSON.trim();
    try {
      return JSON.parse(raw);
    } catch (e) {
      try {
        return JSON.parse(Buffer.from(raw, "base64").toString("utf8"));
      } catch (_) {
        throw new Error(
          "GOOGLE_SERVICE_ACCOUNT_JSON is not valid JSON or base64 JSON."
        );
      }
    }
  }

  if (process.env.GOOGLE_CLIENT_EMAIL && process.env.GOOGLE_PRIVATE_KEY) {
    return {
      client_email: process.env.GOOGLE_CLIENT_EMAIL,
      private_key: process.env.GOOGLE_PRIVATE_KEY.replace(/\\n/g, "\n")
    };
  }

  const localFile = path.join(__dirname, "service-account.json");
  if (fs.existsSync(localFile)) {
    return JSON.parse(fs.readFileSync(localFile, "utf8"));
  }

  throw new Error(
    "Google credentials missing. Add GOOGLE_SERVICE_ACCOUNT_JSON in Render Environment."
  );
}

function getGoogleClients() {
  const auth = new google.auth.GoogleAuth({
    credentials: getGoogleCredentials(),
    scopes: [
      "https://www.googleapis.com/auth/drive",
      "https://www.googleapis.com/auth/spreadsheets"
    ]
  });

  return {
    drive: google.drive({ version: "v3", auth }),
    sheets: google.sheets({ version: "v4", auth })
  };
}

function sheetRange(range) {
  const safeName = CONFIG.sheetName.replace(/'/g, "''");
  return `'${safeName}'!${range}`;
}

function escapeDriveQuery(value) {
  return String(value).replace(/\\/g, "\\\\").replace(/'/g, "\\'");
}

function parseBase64File(file, label, required = true) {
  if (!file || !file.data || !file.mime) {
    if (required) throw new Error(`${label} file is missing.`);
    return null;
  }
  return {
    mimeType: file.mime,
    buffer: Buffer.from(file.data, "base64")
  };
}

function imageExtension(mime) {
  if (mime === "image/png") return ".png";
  if (mime === "image/webp") return ".webp";
  if (mime === "image/avif") return ".avif";
  return ".jpg";
}

async function findOrCreateMemberFolder(drive, folderName) {
  const q = [
    `'${CONFIG.mainFolderId}' in parents`,
    `name='${escapeDriveQuery(folderName)}'`,
    `mimeType='application/vnd.google-apps.folder'`,
    "trashed=false"
  ].join(" and ");

  const found = await drive.files.list({
    q,
    fields: "files(id,name,webViewLink)",
    pageSize: 1,
    supportsAllDrives: true,
    includeItemsFromAllDrives: true
  });

  if (found.data.files && found.data.files.length) {
    return found.data.files[0];
  }

  const created = await drive.files.create({
    requestBody: {
      name: folderName,
      mimeType: "application/vnd.google-apps.folder",
      parents: [CONFIG.mainFolderId]
    },
    fields: "id,name,webViewLink",
    supportsAllDrives: true
  });

  return created.data;
}

async function uploadBuffer(drive, folderId, name, mimeType, buffer) {
  const created = await drive.files.create({
    requestBody: {
      name,
      parents: [folderId]
    },
    media: {
      mimeType,
      body: Readable.from(buffer)
    },
    fields: "id,name,webViewLink",
    supportsAllDrives: true
  });

  return created.data;
}

function normalizeIndos(value) {
  return String(value || "").trim().toUpperCase();
}

function normalizeMemberType(value) {
  return String(value || "").toLowerCase() === "existing"
    ? "Existing"
    : "New Member";
}

function hasHeaderRow(row) {
  const joined = (row || []).map((v) => String(v || "").toLowerCase()).join(" ");
  return joined.includes("indos") && joined.includes("name");
}

function parseAddress(fullAddress) {
  const value = String(fullAddress || "").trim();
  const result = {
    address1: value,
    address2: "",
    address3: "",
    city: "",
    state: "",
    pincode: ""
  };

  if (!value) return result;

  const match = value.match(/^(.*),\s*([^,]+),\s*([^,]+)\s*-\s*(\d{6})\s*$/);
  if (match) {
    result.address1 = match[1].trim();
    result.city = match[2].trim();
    result.state = match[3].trim();
    result.pincode = match[4].trim();
  }

  return result;
}

function rowToMember(row, sheetRowNumber) {
  const fallbackAddress = parseAddress(row[12]);
  const memberType = String(row[17] || "").trim() || "Existing";

  return {
    sheetRow: sheetRowNumber,
    name: String(row[0] || "").trim(),                    // A
    cdc: String(row[1] || "").trim(),                     // B
    indos: normalizeIndos(row[2]),                         // C
    dob: String(row[3] || "").trim(),                     // D
    age: String(row[4] || "").trim(),                     // E
    blood: String(row[5] || "").trim(),                   // F
    rank: String(row[6] || "").trim(),                    // G
    photo: String(row[7] || "").trim(),                   // H
    cdcfile: String(row[8] || "").trim(),                 // I
    passport: String(row[9] || "").trim(),                // J
    sign: String(row[10] || "").trim(),                   // K
    sid: String(row[11] || "").trim(),                    // L
    fullAddress: String(row[12] || "").trim(),            // M
    nominee: String(row[13] || "").trim(),                // N
    mobile: String(row[14] || "").trim(),                 // O
    spousePhone: String(row[15] || "").trim(),            // P
    email: String(row[16] || "").trim(),                  // Q
    memberType,
    submitted: String(row[18] || "").trim(),              // S
    altmobile: String(row[19] || "").trim(),              // T
    altemail: String(row[20] || "").trim(),               // U
    city: String(row[21] || "").trim() || fallbackAddress.city,      // V
    state: String(row[22] || "").trim() || fallbackAddress.state,    // W
    pincode: String(row[23] || "").trim() || fallbackAddress.pincode,// X
    address1: fallbackAddress.address1,
    address2: "",
    address3: ""
  };
}

async function readMembers(sheets) {
  const result = await sheets.spreadsheets.values.get({
    spreadsheetId: CONFIG.spreadsheetId,
    range: sheetRange("A:X"),
    valueRenderOption: "FORMATTED_VALUE"
  });

  const rows = result.data.values || [];
  const startIndex = rows.length && hasHeaderRow(rows[0]) ? 1 : 0;

  return rows
    .slice(startIndex)
    .map((row, i) => rowToMember(row, startIndex + i + 1))
    .filter((m) => m.name || m.indos);
}

async function findMemberByIndos(sheets, indos) {
  const clean = normalizeIndos(indos);
  if (!clean) return null;

  const members = await readMembers(sheets);
  const matches = members.filter((m) => m.indos === clean);
  return matches.length ? matches[matches.length - 1] : null;
}

function validateMember(data) {
  const required = [
    "name",
    "cdc",
    "indos",
    "dob",
    "blood",
    "rank",
    "email",
    "address1",
    "city",
    "state",
    "pincode",
    "memberType",
    "nominee"
  ];

  for (const key of required) {
    if (!String(data[key] || "").trim()) {
      throw new Error(`${key} is required.`);
    }
  }

  if (!/^\d{2}[A-Z]{2}\d{4}$/.test(normalizeIndos(data.indos))) {
    throw new Error("INDoS format must be 00AA0000.");
  }

  if (!/^\d{6}$/.test(String(data.pincode))) {
    throw new Error("Pincode must contain exactly 6 digits.");
  }

  if (data.mobile && !/^\d{10}$/.test(String(data.mobile))) {
    throw new Error("Mobile number must contain exactly 10 digits.");
  }

  if (data.altmobile && !/^\d{10}$/.test(String(data.altmobile))) {
    throw new Error("Alternate mobile number must contain exactly 10 digits.");
  }

  if (data.spousePhone && !/^\d{10}$/.test(String(data.spousePhone))) {
    throw new Error("Spouse phone number must contain exactly 10 digits.");
  }

  if (String(data.nominee).toLowerCase() === "spouse" && !data.spousePhone) {
    throw new Error("Spouse phone number is required when nominee is Spouse.");
  }

  const age = Number(data.age);
  if (!Number.isFinite(age) || age < 18 || age > 60) {
    throw new Error("Not Eligible. Age should be between 18 and 60 years.");
  }
}

app.get("/health", (req, res) => {
  res.json({ ok: true });
});

app.get("/api/slider", async (req, res) => {
  try {
    const { drive } = getGoogleClients();
    const q = `'${CONFIG.sliderFolderId}' in parents and trashed=false`;

    const result = await drive.files.list({
      q,
      fields: "files(id,name,mimeType,createdTime)",
      orderBy: "name",
      pageSize: 100,
      supportsAllDrives: true,
      includeItemsFromAllDrives: true
    });

    const images = (result.data.files || [])
      .filter((f) => (f.mimeType || "").startsWith("image/"))
      .map((f) => ({
        id: f.id,
        name: f.name,
        url: `/api/slider-image/${encodeURIComponent(f.id)}`
      }));

    res.json(images);
  } catch (err) {
    console.error("Slider error:", err);
    res.status(500).json({ error: err.message || "Unable to load slider." });
  }
});

app.get("/api/slider-image/:id", async (req, res) => {
  try {
    const { drive } = getGoogleClients();
    const id = req.params.id;

    const meta = await drive.files.get({
      fileId: id,
      fields: "mimeType,name",
      supportsAllDrives: true
    });

    if (!(meta.data.mimeType || "").startsWith("image/")) {
      return res.status(404).end();
    }

    const image = await drive.files.get(
      { fileId: id, alt: "media", supportsAllDrives: true },
      { responseType: "stream" }
    );

    res.setHeader("Content-Type", meta.data.mimeType);
    res.setHeader("Cache-Control", "public, max-age=300");
    image.data.pipe(res);
  } catch (err) {
    console.error("Slider image error:", err.message);
    res.status(404).end();
  }
});

app.get("/api/members", async (req, res) => {
  try {
    const { sheets } = getGoogleClients();
    const members = await readMembers(sheets);

    const response = members
      .slice()
      .reverse()
      .map((m, index) => ({
        slNo: members.length - index,
        name: m.name,
        indos: m.indos,
        memberType: m.memberType || "Existing"
      }));

    res.json(response);
  } catch (err) {
    console.error("Members list error:", err);
    res.status(500).json({ error: err.message || "Unable to load members." });
  }
});

app.get("/api/member/:indos", async (req, res) => {
  try {
    const indos = normalizeIndos(req.params.indos);
    if (!/^\d{2}[A-Z]{2}\d{4}$/.test(indos)) {
      return res.status(400).json({ error: "Enter a valid INDoS No. Example: 12AB3456" });
    }

    const { sheets } = getGoogleClients();
    const member = await findMemberByIndos(sheets, indos);

    if (!member) {
      return res.status(404).json({ error: "No existing member found for this INDoS No." });
    }

    res.json(member);
  } catch (err) {
    console.error("Member fetch error:", err);
    res.status(500).json({ error: err.message || "Unable to fetch member." });
  }
});

app.post("/api/member", async (req, res) => {
  try {
    const data = req.body || {};
    data.memberType = normalizeMemberType(data.memberType);
    data.indos = normalizeIndos(data.indos);
    data.nominee = String(data.nominee || "").trim();
    validateMember(data);

    const { drive, sheets } = getGoogleClients();

    const cleanName = String(data.name).trim();
    const cleanIndos = normalizeIndos(data.indos);
    const existingMember = await findMemberByIndos(sheets, cleanIndos);

    if (data.memberType === "New Member" && existingMember) {
      return res.status(409).json({
        error: "This INDoS No. already exists. Select Existing Member instead."
      });
    }

    if (data.memberType === "Existing" && !existingMember) {
      return res.status(404).json({
        error: "Existing member not found for this INDoS No. Select New Member if this is a new registration."
      });
    }

    const folderName = `${cleanName}_${cleanIndos}`;
    const folder = await findOrCreateMemberFolder(drive, folderName);

    const filesRequired = data.memberType === "New Member";
    const photo = parseBase64File(data.photo, "Photo", filesRequired);
    const cdc = parseBase64File(data.cdcfile, "CDC", filesRequired);
    const passport = parseBase64File(data.passport, "Passport", filesRequired);
    const sign = parseBase64File(data.sign, "Signature", filesRequired);

    if (photo && !photo.mimeType.startsWith("image/")) {
      throw new Error("Photo must be an image file.");
    }
    if (cdc && cdc.mimeType !== "application/pdf") {
      throw new Error("CDC must be a PDF file.");
    }
    if (passport && passport.mimeType !== "application/pdf") {
      throw new Error("Passport must be a PDF file.");
    }
    if (sign && !sign.mimeType.startsWith("image/")) {
      throw new Error("Signature must be an image file.");
    }

    let photoLink = existingMember?.photo || "";
    let cdcLink = existingMember?.cdcfile || "";
    let passportLink = existingMember?.passport || "";
    let signLink = existingMember?.sign || "";

    if (photo) {
      const photoFile = await uploadBuffer(
        drive,
        folder.id,
        `Photo${imageExtension(photo.mimeType)}`,
        photo.mimeType,
        photo.buffer
      );
      photoLink = photoFile.webViewLink || `https://drive.google.com/file/d/${photoFile.id}/view`;
    }

    if (cdc) {
      const cdcFile = await uploadBuffer(
        drive,
        folder.id,
        "CDC.pdf",
        cdc.mimeType,
        cdc.buffer
      );
      cdcLink = cdcFile.webViewLink || `https://drive.google.com/file/d/${cdcFile.id}/view`;
    }

    if (passport) {
      const passportFile = await uploadBuffer(
        drive,
        folder.id,
        "Passport.pdf",
        passport.mimeType,
        passport.buffer
      );
      passportLink = passportFile.webViewLink || `https://drive.google.com/file/d/${passportFile.id}/view`;
    }

    if (sign) {
      const signFile = await uploadBuffer(
        drive,
        folder.id,
        `Signature${imageExtension(sign.mimeType)}`,
        sign.mimeType,
        sign.buffer
      );
      signLink = signFile.webViewLink || `https://drive.google.com/file/d/${signFile.id}/view`;
    }

    if (!photoLink || !cdcLink || !passportLink || !signLink) {
      throw new Error("Photo, CDC, Passport and Signature are required for this member.");
    }

    const fullAddress =
      [data.address1, data.address2, data.address3].filter(Boolean).join(" ") +
      `, ${data.city}, ${data.state} - ${data.pincode}`;

    const submittedAt = new Date().toISOString();

    await sheets.spreadsheets.values.append({
      spreadsheetId: CONFIG.spreadsheetId,
      range: sheetRange("A:X"),
      valueInputOption: "USER_ENTERED",
      insertDataOption: "INSERT_ROWS",
      requestBody: {
        values: [[
          cleanName,                          // A Name
          String(data.cdc).trim(),            // B CDC
          cleanIndos,                         // C INDoS
          data.dob,                           // D DOB
          data.age,                           // E Age
          data.blood,                         // F Blood Group
          data.rank,                          // G Rank
          photoLink,                          // H Photo
          cdcLink,                            // I CDC PDF
          passportLink,                       // J Passport PDF
          signLink,                           // K Signature
          data.sid || "",                    // L SID No
          fullAddress,                        // M Address
          data.nominee || "",                // N Nominee (Spouse/Mother)
          data.mobile || "",                 // O Mobile
          data.spousePhone || "",            // P Spouse Phone
          data.email,                         // Q Email
          data.memberType,                    // R New Member / Existing
          submittedAt,                        // S Submitted
          data.altmobile || "",              // T Alternate Mobile
          data.altemail || "",               // U Alternate Email
          data.city || "",                   // V City
          data.state || "",                  // W State
          data.pincode || ""                  // X Pincode
        ]]
      }
    });

    res.json({
      name: cleanName,
      cdc: String(data.cdc).trim(),
      indos: cleanIndos,
      sid: data.sid || "",
      memberType: data.memberType,
      dob: data.dob,
      age: data.age,
      blood: data.blood,
      rank: data.rank,
      nominee: data.nominee || "",
      spousePhone: data.spousePhone || "",
      mobile: data.mobile || "",
      altmobile: data.altmobile || "",
      email: data.email,
      altemail: data.altemail || "",
      address1: data.address1,
      address2: data.address2 || "",
      address3: data.address3 || "",
      city: data.city,
      state: data.state,
      pincode: data.pincode,
      photo: photoLink,
      cdcfile: cdcLink,
      passport: passportLink,
      sign: signLink,
      folder: folder.webViewLink || `https://drive.google.com/drive/folders/${folder.id}`
    });
  } catch (err) {
    console.error("Save member error:", err);
    res.status(500).json({ error: err.message || "Membership submission failed." });
  }
});

app.use((req, res) => {
  res.sendFile(path.join(__dirname, "public", "index.html"));
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`NUSI Membership running on port ${PORT}`);
});
