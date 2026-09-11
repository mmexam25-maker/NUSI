package com.nusi.dg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class GoogleStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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

    /*
     * Drive is authenticated using the user's Google OAuth refresh token.
     * This avoids the Service Account storage quota restriction.
     */
    private static final String GOOGLE_CLIENT_ID = env("GOOGLE_CLIENT_ID", "");
    private static final String GOOGLE_CLIENT_SECRET = env("GOOGLE_CLIENT_SECRET", "");
    private static final String GOOGLE_REFRESH_TOKEN = env("GOOGLE_REFRESH_TOKEN", "");

    private static final String DRIVE_FILES = "https://www.googleapis.com/drive/v3/files";
    private static final String DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String SHEETS_BASE = "https://sheets.googleapis.com/v4/spreadsheets/";
    private static final String GOOGLE_OAUTH_TOKEN = "https://oauth2.googleapis.com/token";

    private static final String SHEETS_SCOPE =
            "https://www.googleapis.com/auth/spreadsheets";

    private static final long LINK_LIFETIME_SECONDS = 24L * 60L * 60L;

    private static volatile Token cachedDriveToken;
    private static volatile Token cachedSheetsToken;
    private static volatile ServiceAccount cachedAccount;

    private GoogleStore() {}

    public record SliderImage(String id, String name, String mimeType) {}
    public record BinaryFile(byte[] bytes, String mimeType, String name) {}
    public record StoredFile(String id, String name, String mimeType) {}

    public record SavedMember(
            String photo,
            String cdc,
            String passport,
            String address,
            String expiresAt
    ) {}

    private record Token(String value, long expiresAtEpochSeconds) {}
    private record ServiceAccount(String clientEmail, String tokenUri, PrivateKey privateKey) {}
    private record DriveItem(String id, String name, String mimeType) {}

    /*************************************************
     * SLIDER
     *************************************************/

    public static List<SliderImage> listSliderImages() throws Exception {
        List<SliderImage> out = new ArrayList<>();
        String pageToken = "";

        do {
            String q = "'" + escapeDriveQuery(SLIDER_FOLDER_ID) + "' in parents and trashed=false";

            String url = DRIVE_FILES
                    + "?q=" + enc(q)
                    + "&fields=" + enc("nextPageToken,files(id,name,mimeType)")
                    + "&orderBy=name"
                    + "&pageSize=100"
                    + "&supportsAllDrives=true"
                    + "&includeItemsFromAllDrives=true";

            if (!pageToken.isBlank()) {
                url += "&pageToken=" + enc(pageToken);
            }

            JsonNode root = getDriveJson(url);
            JsonNode files = root.path("files");

            if (files.isArray()) {
                for (JsonNode f : files) {
                    String mime = f.path("mimeType").asText("");

                    if (mime.startsWith("image/")) {
                        out.add(new SliderImage(
                                f.path("id").asText(""),
                                f.path("name").asText(""),
                                mime
                        ));
                    }
                }
            }

            pageToken = root.path("nextPageToken").asText("");

        } while (!pageToken.isBlank());

        return out;
    }

    /*************************************************
     * READ DRIVE FILE
     *************************************************/

    public static BinaryFile readDriveFile(String fileId) throws Exception {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("File id is required.");
        }

        String metaUrl = DRIVE_FILES + "/" + encPath(fileId)
                + "?fields=" + enc("id,name,mimeType,trashed")
                + "&supportsAllDrives=true";

        JsonNode meta = getDriveJson(metaUrl);

        if (meta.path("trashed").asBoolean(false)) {
            throw new IllegalArgumentException("File is unavailable.");
        }

        String mediaUrl = DRIVE_FILES + "/" + encPath(fileId)
                + "?alt=media&supportsAllDrives=true";

        HttpResponse<byte[]> response = send(
                HttpRequest.newBuilder(URI.create(mediaUrl))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + driveAccessToken())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );

        require2xx(
                response.statusCode(),
                response.body(),
                "Drive file download failed"
        );

        return new BinaryFile(
                response.body(),
                meta.path("mimeType").asText("application/octet-stream"),
                meta.path("name").asText("")
        );
    }

    /*************************************************
     * SAVE MEMBER
     *************************************************/

    public static SavedMember saveMember(
            JsonNode data,
            byte[] photoBytes,
            String photoMime,
            byte[] cdcPdf,
            byte[] passportPdf,
            String baseUrl,
            String linkSecret
    ) throws Exception {

        Instant submitted = Instant.now();
        long expiresEpoch = submitted
                .plusSeconds(LINK_LIFETIME_SECONDS)
                .getEpochSecond();

        String surname = text(data, "surname");
        String givenName = text(data, "givenName");
        String indos = text(data, "indos")
                .toUpperCase(Locale.ROOT);

        String folderName = sanitize(surname)
                + "_" + sanitize(givenName)
                + "_" + sanitize(indos)
                + "_" + submitted.toEpochMilli();

        DriveItem folder = createTemporaryFolder(
                folderName,
                expiresEpoch
        );

        String photoFileName = photoMime.equalsIgnoreCase("image/png")
                ? "Photo.png"
                : "Photo.jpg";

        StoredFile photo = uploadFile(
                folder.id(),
                photoFileName,
                photoMime,
                photoBytes
        );

        StoredFile cdc = uploadFile(
                folder.id(),
                "CDC.pdf",
                "application/pdf",
                cdcPdf
        );

        StoredFile passport = uploadFile(
                folder.id(),
                "Passport.pdf",
                "application/pdf",
                passportPdf
        );

        String photoLink = TempLinks.create(
                baseUrl,
                photo.id(),
                expiresEpoch,
                photo.name(),
                linkSecret
        );

        String cdcLink = TempLinks.create(
                baseUrl,
                cdc.id(),
                expiresEpoch,
                cdc.name(),
                linkSecret
        );

        String passportLink = TempLinks.create(
                baseUrl,
                passport.id(),
                expiresEpoch,
                passport.name(),
                linkSecret
        );

        String address = buildAddress(data);
        String submittedAt = formatIst(submitted);
        String expiresAt = formatIst(
                Instant.ofEpochSecond(expiresEpoch)
        );

        // A:X = 24 columns
        List<Object> row = List.of(
                surname,                           // A Surname
                givenName,                         // B Given Name
                text(data, "cdc"),                // C CDC
                indos,                             // D INDoS
                text(data, "dob"),                // E DOB
                text(data, "age"),                // F Age
                text(data, "blood"),              // G Blood Group
                text(data, "rank"),               // H Rank
                photoLink,                         // I Photo temp link
                cdcLink,                           // J CDC temp link
                passportLink,                      // K Passport temp link
                text(data, "nominee"),            // L Nominee
                address,                           // M Address
                text(data, "mobile"),             // N Mobile
                text(data, "altmobile"),          // O Alternate Mobile
                text(data, "email"),              // P Email
                text(data, "altemail"),           // Q Alternate Email
                text(data, "city"),               // R City
                text(data, "state"),              // S State
                text(data, "pincode"),            // T Pincode
                submittedAt,                       // U Submitted At
                expiresAt,                         // V Link Expires At
                folder.id(),                       // W Temporary Drive Folder ID
                "ACTIVE - AUTO DELETE 24H"         // X Status
        );

        appendSheetRow(row);

        return new SavedMember(
                photoLink,
                cdcLink,
                passportLink,
                address,
                expiresAt
        );
    }

    /*************************************************
     * CLEANUP EXPIRED TEMP FOLDERS
     *************************************************/

    public static int cleanupExpired() throws Exception {
        long now = Instant.now().getEpochSecond();
        int deleted = 0;
        String pageToken = "";

        do {
            String q = "'" + escapeDriveQuery(MAIN_FOLDER_ID) + "' in parents"
                    + " and mimeType='application/vnd.google-apps.folder'"
                    + " and appProperties has { key='nusiTemp' and value='true' }"
                    + " and trashed=false";

            String url = DRIVE_FILES
                    + "?q=" + enc(q)
                    + "&fields=" + enc("nextPageToken,files(id,name,appProperties)")
                    + "&pageSize=100"
                    + "&supportsAllDrives=true"
                    + "&includeItemsFromAllDrives=true";

            if (!pageToken.isBlank()) {
                url += "&pageToken=" + enc(pageToken);
            }

            JsonNode root = getDriveJson(url);
            JsonNode files = root.path("files");

            if (files.isArray()) {
                for (JsonNode folder : files) {
                    String id = folder.path("id").asText("");
                    String expText = folder
                            .path("appProperties")
                            .path("nusiExpiresAt")
                            .asText("");

                    long exp;

                    try {
                        exp = Long.parseLong(expText);
                    } catch (Exception ignored) {
                        continue;
                    }

                    if (!id.isBlank() && exp <= now) {
                        trashDriveFile(id);
                        deleted++;
                    }
                }
            }

            pageToken = root.path("nextPageToken").asText("");

        } while (!pageToken.isBlank());

        return deleted;
    }

    /*************************************************
     * CREATE TEMP FOLDER
     *************************************************/

    private static DriveItem createTemporaryFolder(
            String folderName,
            long expiresEpoch
    ) throws Exception {

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", folderName);
        metadata.put("mimeType", "application/vnd.google-apps.folder");
        metadata.put("parents", List.of(MAIN_FOLDER_ID));
        metadata.put(
                "appProperties",
                Map.of(
                        "nusiTemp", "true",
                        "nusiExpiresAt", String.valueOf(expiresEpoch)
                )
        );

        String createUrl = DRIVE_FILES
                + "?supportsAllDrives=true"
                + "&fields=" + enc("id,name,mimeType");

        JsonNode created = sendDriveJsonRequest(
                "POST",
                createUrl,
                JSON.writeValueAsBytes(metadata)
        );

        DriveItem item = driveItem(created);

        if (item.id().isBlank()) {
            throw new IllegalStateException(
                    "Drive folder creation succeeded but folder id was missing."
            );
        }

        return item;
    }

    /*************************************************
     * UPLOAD FILE
     *************************************************/

    private static StoredFile uploadFile(
            String folderId,
            String fileName,
            String mimeType,
            byte[] bytes
    ) throws Exception {

        String boundary = "nusi_"
                + UUID.randomUUID()
                .toString()
                .replace("-", "");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", fileName);
        metadata.put("parents", List.of(folderId));

        byte[] multipart = multipartRelated(
                boundary,
                JSON.writeValueAsBytes(metadata),
                mimeType,
                bytes
        );

        String uploadUrl = DRIVE_UPLOAD
                + "?uploadType=multipart"
                + "&supportsAllDrives=true"
                + "&fields=" + enc("id,name,mimeType");

        HttpResponse<byte[]> response = send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .timeout(Duration.ofSeconds(90))
                        .header("Authorization", "Bearer " + driveAccessToken())
                        .header(
                                "Content-Type",
                                "multipart/related; boundary=" + boundary
                        )
                        .POST(
                                HttpRequest.BodyPublishers.ofByteArray(
                                        multipart
                                )
                        )
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );

        require2xx(
                response.statusCode(),
                response.body(),
                "Drive upload failed"
        );

        JsonNode created = JSON.readTree(response.body());
        String id = created.path("id").asText("");

        if (id.isBlank()) {
            throw new IllegalStateException(
                    "Drive upload succeeded but file id was missing."
            );
        }

        return new StoredFile(
                id,
                created.path("name").asText(fileName),
                created.path("mimeType").asText(mimeType)
        );
    }

    /*************************************************
     * TRASH DRIVE FILE/FOLDER
     *************************************************/

    private static void trashDriveFile(String id) throws Exception {
        String patchUrl = DRIVE_FILES
                + "/" + encPath(id)
                + "?supportsAllDrives=true";

        sendDriveJsonRequest(
                "PATCH",
                patchUrl,
                JSON.writeValueAsBytes(
                        Map.of("trashed", true)
                )
        );
    }

    /*************************************************
     * APPEND GOOGLE SHEET
     *************************************************/

    private static void appendSheetRow(
            List<Object> row
    ) throws Exception {

        String range = "'"
                + SHEET_NAME.replace("'", "''")
                + "'!A:X";

        String url = SHEETS_BASE
                + encPath(SPREADSHEET_ID)
                + "/values/"
                + encPath(range)
                + ":append?valueInputOption=USER_ENTERED"
                + "&insertDataOption=INSERT_ROWS";

        Map<String, Object> body = Map.of(
                "values",
                List.of(row)
        );

        sendSheetsJsonRequest(
                "POST",
                url,
                JSON.writeValueAsBytes(body)
        );
    }

    /*************************************************
     * MULTIPART BODY
     *************************************************/

    private static byte[] multipartRelated(
            String boundary,
            byte[] metadataJson,
            String mimeType,
            byte[] fileBytes
    ) throws Exception {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        String first = "--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n";

        out.write(first.getBytes(StandardCharsets.UTF_8));
        out.write(metadataJson);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));

        String second = "--" + boundary + "\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n";

        out.write(second.getBytes(StandardCharsets.UTF_8));
        out.write(fileBytes);
        out.write(
                ("\r\n--" + boundary + "--\r\n")
                        .getBytes(StandardCharsets.UTF_8)
        );

        return out.toByteArray();
    }

    /*************************************************
     * DRIVE GET JSON
     *************************************************/

    private static JsonNode getDriveJson(
            String url
    ) throws Exception {

        HttpResponse<byte[]> response = send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + driveAccessToken())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );

        require2xx(
                response.statusCode(),
                response.body(),
                "Google Drive API request failed"
        );

        return JSON.readTree(response.body());
    }

    /*************************************************
     * DRIVE JSON REQUEST
     *************************************************/

    private static JsonNode sendDriveJsonRequest(
            String method,
            String url,
            byte[] body
    ) throws Exception {

        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + driveAccessToken())
                .header(
                        "Content-Type",
                        "application/json; charset=utf-8"
                );

        addBody(builder, method, body);

        HttpResponse<byte[]> response = send(
                builder.build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );

        require2xx(
                response.statusCode(),
                response.body(),
                "Google Drive API request failed"
        );

        if (response.body() == null || response.body().length == 0) {
            return JSON.createObjectNode();
        }

        return JSON.readTree(response.body());
    }

    /*************************************************
     * SHEETS JSON REQUEST
     *************************************************/

    private static JsonNode sendSheetsJsonRequest(
            String method,
            String url,
            byte[] body
    ) throws Exception {

        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + sheetsAccessToken())
                .header(
                        "Content-Type",
                        "application/json; charset=utf-8"
                );

        addBody(builder, method, body);

        HttpResponse<byte[]> response = send(
                builder.build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );

        require2xx(
                response.statusCode(),
                response.body(),
                "Google Sheets API request failed"
        );

        if (response.body() == null || response.body().length == 0) {
            return JSON.createObjectNode();
        }

        return JSON.readTree(response.body());
    }

    private static void addBody(
            HttpRequest.Builder builder,
            String method,
            byte[] body
    ) {

        if ("POST".equals(method)) {
            builder.POST(
                    HttpRequest.BodyPublishers.ofByteArray(body)
            );

        } else if ("PATCH".equals(method)) {
            builder.method(
                    "PATCH",
                    HttpRequest.BodyPublishers.ofByteArray(body)
            );

        } else if ("PUT".equals(method)) {
            builder.PUT(
                    HttpRequest.BodyPublishers.ofByteArray(body)
            );

        } else {
            throw new IllegalArgumentException(
                    "Unsupported HTTP method: " + method
            );
        }
    }

    /*************************************************
     * HTTP
     *************************************************/

    private static <T> HttpResponse<T> send(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler
    ) throws Exception {
        return HTTP.send(request, handler);
    }

    private static void require2xx(
            int status,
            byte[] body,
            String prefix
    ) {

        if (status >= 200 && status < 300) {
            return;
        }

        String details = body == null
                ? ""
                : new String(body, StandardCharsets.UTF_8);

        if (details.length() > 900) {
            details = details.substring(0, 900);
        }

        throw new IllegalStateException(
                prefix
                        + " | HTTP "
                        + status
                        + " | "
                        + details
        );
    }

    /*************************************************
     * DRIVE OAUTH TOKEN
     *************************************************/

    private static String driveAccessToken() throws Exception {
        Token token = cachedDriveToken;
        long now = Instant.now().getEpochSecond();

        if (
                token != null &&
                token.expiresAtEpochSeconds() > now + 90
        ) {
            return token.value();
        }

        synchronized (GoogleStore.class) {
            token = cachedDriveToken;
            now = Instant.now().getEpochSecond();

            if (
                    token != null &&
                    token.expiresAtEpochSeconds() > now + 90
            ) {
                return token.value();
            }

            if (
                    GOOGLE_CLIENT_ID.isBlank() ||
                    GOOGLE_CLIENT_SECRET.isBlank() ||
                    GOOGLE_REFRESH_TOKEN.isBlank()
            ) {
                throw new IllegalStateException(
                        "Drive OAuth credentials missing. Add GOOGLE_CLIENT_ID, "
                                + "GOOGLE_CLIENT_SECRET and GOOGLE_REFRESH_TOKEN in Render Environment."
                );
            }

            String form = "client_id=" + enc(GOOGLE_CLIENT_ID)
                    + "&client_secret=" + enc(GOOGLE_CLIENT_SECRET)
                    + "&refresh_token=" + enc(GOOGLE_REFRESH_TOKEN)
                    + "&grant_type=" + enc("refresh_token");

            HttpRequest request = HttpRequest
                    .newBuilder(URI.create(GOOGLE_OAUTH_TOKEN))
                    .timeout(Duration.ofSeconds(30))
                    .header(
                            "Content-Type",
                            "application/x-www-form-urlencoded"
                    )
                    .POST(
                            HttpRequest.BodyPublishers.ofString(
                                    form,
                                    StandardCharsets.UTF_8
                            )
                    )
                    .build();

            HttpResponse<byte[]> response = send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            require2xx(
                    response.statusCode(),
                    response.body(),
                    "Google Drive OAuth refresh failed"
            );

            JsonNode root = JSON.readTree(response.body());
            String value = root.path("access_token").asText("");
            long expiresIn = root.path("expires_in").asLong(3600);

            if (value.isBlank()) {
                throw new IllegalStateException(
                        "Google Drive OAuth response did not contain access_token."
                );
            }

            cachedDriveToken = new Token(
                    value,
                    Instant.now().getEpochSecond()
                            + Math.max(300, expiresIn)
            );

            return value;
        }
    }

    /*************************************************
     * SHEETS SERVICE ACCOUNT TOKEN
     *************************************************/

    private static String sheetsAccessToken() throws Exception {
        Token token = cachedSheetsToken;
        long now = Instant.now().getEpochSecond();

        if (
                token != null &&
                token.expiresAtEpochSeconds() > now + 90
        ) {
            return token.value();
        }

        synchronized (GoogleStore.class) {
            token = cachedSheetsToken;
            now = Instant.now().getEpochSecond();

            if (
                    token != null &&
                    token.expiresAtEpochSeconds() > now + 90
            ) {
                return token.value();
            }

            ServiceAccount account = serviceAccount();
            long iat = Instant.now().getEpochSecond();
            long exp = iat + 3600;

            String header = base64Url(
                    JSON.writeValueAsBytes(
                            Map.of(
                                    "alg", "RS256",
                                    "typ", "JWT"
                            )
                    )
            );

            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", account.clientEmail());
            claims.put("scope", SHEETS_SCOPE);
            claims.put("aud", account.tokenUri());
            claims.put("iat", iat);
            claims.put("exp", exp);

            String payload = base64Url(
                    JSON.writeValueAsBytes(claims)
            );

            String signingInput = header + "." + payload;

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(account.privateKey());
            signature.update(
                    signingInput.getBytes(StandardCharsets.US_ASCII)
            );

            String assertion = signingInput
                    + "."
                    + base64Url(signature.sign());

            String form = "grant_type="
                    + enc("urn:ietf:params:oauth:grant-type:jwt-bearer")
                    + "&assertion="
                    + enc(assertion);

            HttpRequest tokenRequest = HttpRequest
                    .newBuilder(URI.create(account.tokenUri()))
                    .timeout(Duration.ofSeconds(30))
                    .header(
                            "Content-Type",
                            "application/x-www-form-urlencoded"
                    )
                    .POST(
                            HttpRequest.BodyPublishers.ofString(
                                    form,
                                    StandardCharsets.UTF_8
                            )
                    )
                    .build();

            HttpResponse<byte[]> tokenResponse = send(
                    tokenRequest,
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            require2xx(
                    tokenResponse.statusCode(),
                    tokenResponse.body(),
                    "Google Sheets OAuth token request failed"
            );

            JsonNode root = JSON.readTree(tokenResponse.body());
            String value = root.path("access_token").asText("");
            long expiresIn = root.path("expires_in").asLong(3600);

            if (value.isBlank()) {
                throw new IllegalStateException(
                        "Google Sheets OAuth response did not contain access_token."
                );
            }

            cachedSheetsToken = new Token(
                    value,
                    Instant.now().getEpochSecond()
                            + Math.max(300, expiresIn)
            );

            return value;
        }
    }

    /*************************************************
     * SERVICE ACCOUNT JSON
     *************************************************/

    private static ServiceAccount serviceAccount() throws Exception {
        ServiceAccount account = cachedAccount;

        if (account != null) {
            return account;
        }

        synchronized (GoogleStore.class) {
            if (cachedAccount != null) {
                return cachedAccount;
            }

            String raw = env(
                    "GOOGLE_SERVICE_ACCOUNT_JSON",
                    ""
            );

            if (raw.isBlank()) {
                throw new IllegalStateException(
                        "Google Sheets credentials missing. Add GOOGLE_SERVICE_ACCOUNT_JSON in Render Environment."
                );
            }

            byte[] jsonBytes;

            if (raw.trim().startsWith("{")) {
                jsonBytes = raw.getBytes(StandardCharsets.UTF_8);

            } else {
                try {
                    jsonBytes = Base64.getDecoder().decode(raw);

                } catch (IllegalArgumentException invalid) {
                    throw new IllegalStateException(
                            "GOOGLE_SERVICE_ACCOUNT_JSON is not valid JSON or base64 JSON."
                    );
                }
            }

            JsonNode root;

            try {
                root = JSON.readTree(
                        new ByteArrayInputStream(jsonBytes)
                );

            } catch (Exception e) {
                throw new IllegalStateException(
                        "GOOGLE_SERVICE_ACCOUNT_JSON is not valid JSON."
                );
            }

            String clientEmail = root
                    .path("client_email")
                    .asText("");

            String tokenUri = root
                    .path("token_uri")
                    .asText(GOOGLE_OAUTH_TOKEN);

            String privateKeyPem = root
                    .path("private_key")
                    .asText("");

            if (
                    clientEmail.isBlank() ||
                    privateKeyPem.isBlank()
            ) {
                throw new IllegalStateException(
                        "GOOGLE_SERVICE_ACCOUNT_JSON is missing client_email or private_key."
                );
            }

            String cleanPem = privateKeyPem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");

            byte[] der;

            try {
                der = Base64
                        .getDecoder()
                        .decode(cleanPem);

            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "Service account private_key is invalid."
                );
            }

            PrivateKey privateKey = KeyFactory
                    .getInstance("RSA")
                    .generatePrivate(
                            new PKCS8EncodedKeySpec(der)
                    );

            cachedAccount = new ServiceAccount(
                    clientEmail,
                    tokenUri,
                    privateKey
            );

            return cachedAccount;
        }
    }

    /*************************************************
     * HELPERS
     *************************************************/

    private static DriveItem driveItem(JsonNode node) {
        return new DriveItem(
                node.path("id").asText(""),
                node.path("name").asText(""),
                node.path("mimeType").asText("")
        );
    }

    private static String formatIst(Instant instant) {
        return DateTimeFormatter
                .ofPattern("dd/MM/yyyy hh:mm:ss a")
                .withZone(ZoneId.of("Asia/Kolkata"))
                .format(instant);
    }

    private static String base64Url(byte[] bytes) {
        return Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
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

        if (!pin.isBlank()) {
            address += (address.isBlank() ? "" : " - ") + pin;
        }

        return address;
    }

    private static void addIf(
            List<String> list,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            list.add(value.trim());
        }
    }

    private static String text(
            JsonNode node,
            String field
    ) {
        JsonNode value = node == null
                ? null
                : node.get(field);

        return value == null || value.isNull()
                ? ""
                : value.asText("").trim();
    }

    private static String sanitize(String value) {
        return safe(value)
                .replaceAll("[\\\\/:*?\"<>|#%]", "_")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String escapeDriveQuery(String value) {
        return safe(value)
                .replace("\\", "\\\\")
                .replace("'", "\\'");
    }

    private static String enc(String value) {
        return URLEncoder.encode(
                safe(value),
                StandardCharsets.UTF_8
        );
    }

    private static String encPath(String value) {
        return URLEncoder
                .encode(
                        safe(value),
                        StandardCharsets.UTF_8
                )
                .replace("+", "%20");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String env(
            String name,
            String fallback
    ) {
        String value = System.getenv(name);
        return value == null
                ? fallback
                : value.trim();
    }
}
