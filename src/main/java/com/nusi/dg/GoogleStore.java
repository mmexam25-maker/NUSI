package com.nusi.dg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public final class GoogleStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SPREADSHEET_ID = env(
            "SPREADSHEET_ID",
            "1BCovd-XYwFto5oelzmgDvYjCNpQ45WP1pFk7Xd0Fqok"
    );

    private static final String SHEET_NAME = env("SHEET_NAME", "Sheet1");

    private static final String MAIN_FOLDER_ID = env(
            "MAIN_FOLDER_ID",
            "13UBnaaMa27-qTf6NvBXe5Vt6jkCCYFaZ"
    );

    private static final String SLIDER_FOLDER_ID = env(
            "SLIDER_FOLDER_ID",
            "1tZp5paf-Vju9w1e2Z4naKHQ3VKu-KFcM"
    );

    private static volatile Clients clients;

    private GoogleStore() {}

    public record SliderImage(String id, String name, String mimeType) {}
    public record BinaryFile(byte[] bytes, String mimeType, String name) {}
    public record StoredFile(String id, String link) {}
    public record SavedMember(String photo, String cdc, String passport, String folder, String address) {}

    private record Clients(Drive drive, Sheets sheets) {}

    public static List<SliderImage> listSliderImages() throws Exception {
        Drive drive = clients().drive();
        List<SliderImage> out = new ArrayList<>();
        String pageToken = null;

        do {
            FileList list = drive.files().list()
                    .setQ("'" + escapeQuery(SLIDER_FOLDER_ID) + "' in parents and trashed=false")
                    .setFields("nextPageToken,files(id,name,mimeType)")
                    .setOrderBy("name")
                    .setPageSize(100)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setPageToken(pageToken)
                    .execute();

            if (list.getFiles() != null) {
                for (File f : list.getFiles()) {
                    String mime = safe(f.getMimeType());
                    if (mime.startsWith("image/")) {
                        out.add(new SliderImage(f.getId(), safe(f.getName()), mime));
                    }
                }
            }
            pageToken = list.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank());

        return out;
    }

    public static BinaryFile readDriveFile(String fileId) throws Exception {
        Drive drive = clients().drive();
        File meta = drive.files().get(fileId)
                .setFields("id,name,mimeType,trashed")
                .setSupportsAllDrives(true)
                .execute();

        if (Boolean.TRUE.equals(meta.getTrashed())) {
            throw new IllegalArgumentException("File is unavailable.");
        }

        try (InputStream in = drive.files().get(fileId)
                .setAlt("media")
                .setSupportsAllDrives(true)
                .executeMediaAsInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return new BinaryFile(out.toByteArray(), safe(meta.getMimeType()), safe(meta.getName()));
        }
    }

    public static SavedMember saveMember(
            JsonNode data,
            byte[] dgPhotoJpg,
            byte[] cdcPdf,
            byte[] passportPdf
    ) throws Exception {

        Clients c = clients();
        String name = text(data, "name");
        String indos = text(data, "indos").toUpperCase(Locale.ROOT);
        String folderName = sanitize(name) + "_" + sanitize(indos);

        File folder = findOrCreateFolder(c.drive(), folderName);

        StoredFile photo = replaceFile(c.drive(), folder.getId(), "DG_Profile_Photo.jpg", "image/jpeg", dgPhotoJpg);
        StoredFile cdc = replaceFile(c.drive(), folder.getId(), "CDC.pdf", "application/pdf", cdcPdf);
        StoredFile passport = replaceFile(c.drive(), folder.getId(), "Passport.pdf", "application/pdf", passportPdf);

        String address = buildAddress(data);
        String submittedAt = Instant.now().toString();

        List<Object> row = List.of(
                text(data, "name"),                 // A Name
                text(data, "cdc"),                  // B CDC
                indos,                              // C INDoS
                text(data, "dob"),                  // D DOB
                text(data, "age"),                  // E Age
                text(data, "blood"),                // F Blood Group
                text(data, "rank"),                 // G Rank
                photo.link(),                        // H DG Photo
                cdc.link(),                          // I CDC PDF
                passport.link(),                     // J Passport PDF
                "",                                 // K Signature (not used)
                "",                                 // L SID
                address,                            // M Address
                "",                                 // N Nominee
                text(data, "mobile"),               // O Mobile
                "",                                 // P Spouse phone
                text(data, "email"),                // Q Email
                "",                                 // R Membership type
                submittedAt,                        // S Submitted
                text(data, "altmobile"),            // T Alternate mobile
                text(data, "altemail"),             // U Alternate email
                text(data, "city"),                 // V City
                text(data, "state"),                // W State
                text(data, "pincode")               // X Pincode
        );

        String safeSheet = "'" + SHEET_NAME.replace("'", "''") + "'!A:X";
        ValueRange body = new ValueRange().setValues(List.of(row));

        c.sheets().spreadsheets().values()
                .append(SPREADSHEET_ID, safeSheet, body)
                .setValueInputOption("USER_ENTERED")
                .setInsertDataOption("INSERT_ROWS")
                .execute();

        String folderLink = folder.getWebViewLink();
        if (folderLink == null || folderLink.isBlank()) {
            folderLink = "https://drive.google.com/drive/folders/" + folder.getId();
        }

        return new SavedMember(photo.link(), cdc.link(), passport.link(), folderLink, address);
    }

    private static File findOrCreateFolder(Drive drive, String folderName) throws Exception {
        String q = "'" + escapeQuery(MAIN_FOLDER_ID) + "' in parents"
                + " and name='" + escapeQuery(folderName) + "'"
                + " and mimeType='application/vnd.google-apps.folder'"
                + " and trashed=false";

        FileList found = drive.files().list()
                .setQ(q)
                .setFields("files(id,name,webViewLink)")
                .setPageSize(1)
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .execute();

        if (found.getFiles() != null && !found.getFiles().isEmpty()) {
            return found.getFiles().get(0);
        }

        File meta = new File()
                .setName(folderName)
                .setMimeType("application/vnd.google-apps.folder")
                .setParents(List.of(MAIN_FOLDER_ID));

        return drive.files().create(meta)
                .setFields("id,name,webViewLink")
                .setSupportsAllDrives(true)
                .execute();
    }

    private static StoredFile replaceFile(
            Drive drive,
            String folderId,
            String fileName,
            String mimeType,
            byte[] bytes
    ) throws Exception {

        String q = "'" + escapeQuery(folderId) + "' in parents"
                + " and name='" + escapeQuery(fileName) + "'"
                + " and trashed=false";

        FileList old = drive.files().list()
                .setQ(q)
                .setFields("files(id)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .execute();

        if (old.getFiles() != null) {
            for (File f : old.getFiles()) {
                try {
                    drive.files().update(f.getId(), new File().setTrashed(true))
                            .setSupportsAllDrives(true)
                            .execute();
                } catch (Exception ignored) {}
            }
        }

        File meta = new File().setName(fileName).setParents(List.of(folderId));
        File created = drive.files().create(meta, new ByteArrayContent(mimeType, bytes))
                .setFields("id,name,webViewLink")
                .setSupportsAllDrives(true)
                .execute();

        String link = created.getWebViewLink();
        if (link == null || link.isBlank()) {
            link = "https://drive.google.com/file/d/" + created.getId() + "/view";
        }
        return new StoredFile(created.getId(), link);
    }

    private static Clients clients() throws Exception {
        Clients ready = clients;
        if (ready != null) return ready;

        synchronized (GoogleStore.class) {
            if (clients != null) return clients;

            String raw = env("GOOGLE_SERVICE_ACCOUNT_JSON", "");
            if (raw.isBlank()) {
                throw new IllegalStateException(
                        "Google credentials missing. Add GOOGLE_SERVICE_ACCOUNT_JSON in Render Environment."
                );
            }

            byte[] jsonBytes;
            if (raw.startsWith("{")) {
                jsonBytes = raw.getBytes(StandardCharsets.UTF_8);
            } else {
                try {
                    jsonBytes = Base64.getDecoder().decode(raw);
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalStateException("GOOGLE_SERVICE_ACCOUNT_JSON is not valid JSON or base64 JSON.");
                }
            }

            // Validate early so Render logs show a clear error.
            try {
                JSON.readTree(jsonBytes);
            } catch (Exception e) {
                throw new IllegalStateException("GOOGLE_SERVICE_ACCOUNT_JSON is not valid JSON.");
            }

            GoogleCredentials credentials = GoogleCredentials
                    .fromStream(new ByteArrayInputStream(jsonBytes))
                    .createScoped(List.of(
                            "https://www.googleapis.com/auth/drive",
                            "https://www.googleapis.com/auth/spreadsheets"
                    ));

            HttpCredentialsAdapter adapter = new HttpCredentialsAdapter(credentials);
            var transport = GoogleNetHttpTransport.newTrustedTransport();
            var jsonFactory = GsonFactory.getDefaultInstance();

            Drive drive = new Drive.Builder(transport, jsonFactory, adapter)
                    .setApplicationName("NUSI Membership 2026")
                    .build();

            Sheets sheets = new Sheets.Builder(transport, jsonFactory, adapter)
                    .setApplicationName("NUSI Membership 2026")
                    .build();

            clients = new Clients(drive, sheets);
            return clients;
        }
    }

    private static String buildAddress(JsonNode data) {
        List<String> parts = new ArrayList<>();
        addIf(parts, text(data, "address1"));
        addIf(parts, text(data, "address2"));
        addIf(parts, text(data, "address3"));
        addIf(parts, text(data, "city"));
        addIf(parts, text(data, "state"));
        String address = String.join(", ", parts);
        String pin = text(data, "pincode");
        if (!pin.isBlank()) address += (address.isBlank() ? "" : " - ") + pin;
        return address;
    }

    private static void addIf(List<String> list, String value) {
        if (value != null && !value.isBlank()) list.add(value.trim());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    private static String sanitize(String value) {
        return safe(value)
                .replaceAll("[\\\\/:*?\"<>|#%]", "_")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String escapeQuery(String value) {
        return safe(value).replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : v.trim();
    }
}
