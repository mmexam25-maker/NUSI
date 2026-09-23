package com.nusi.dg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern INDOS =
            Pattern.compile("^[0-9]{2}[A-Z]{2}[0-9]{4}$");

    private static final Pattern TEN_DIGITS =
            Pattern.compile("^[0-9]{10}$");

    private static final Pattern PINCODE =
            Pattern.compile("^[0-9]{6}$");

    private static final String API_KEY = env("API_KEY", "");

    private static final int MAX_BODY_BYTES = 60 * 1024 * 1024;
    private static final int MAX_PDF_BYTES = 20 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private static final ScheduledExecutorService CLEANER =
            Executors.newSingleThreadScheduledExecutor();

    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = intEnv("PORT", 10000);

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", port),
                0
        );

        server.createContext("/", Main::route);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        CLEANER.scheduleWithFixedDelay(
                Main::cleanupExpiredQuietly,
                1,
                60,
                TimeUnit.MINUTES
        );

        System.out.println("NUSI Membership 2026 started on port " + port);
    }

    private static void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();

        try {
            if ("/health".equals(path)) {
                health(ex);
                return;
            }

            if ("/api/slider".equals(path)) {
                slider(ex);
                return;
            }

            if ("/api/slider-image".equals(path)) {
                sliderImage(ex);
                return;
            }

            if ("/api/submit".equals(path)) {
                submit(ex);
                return;
            }

            if ("/temp-file".equals(path)) {
                temporaryFile(ex);
                return;
            }

            if ("/".equals(path) || "/index.html".equals(path)) {
                serveResource(ex, "/public/index.html", "text/html; charset=utf-8", false);
                return;
            }

            if ("/style.css".equals(path)) {
                serveResource(ex, "/public/style.css", "text/css; charset=utf-8", true);
                return;
            }

            if ("/script.js".equals(path)) {
                serveResource(ex, "/public/script.js", "application/javascript; charset=utf-8", true);
                return;
            }

            if ("/logo.png".equals(path)) {
                serveResource(ex, "/public/logo.png", "image/png", true);
                return;
            }

            sendText(ex, 404, "404 Not Found");

        } catch (Exception e) {
            e.printStackTrace(System.err);
            try {
                sendJson(ex, 500, Map.of(
                        "success", false,
                        "message", safeMessage(e)
                ));
            } catch (Exception ignored) {
                try {
                    ex.close();
                } catch (Exception ignoredAgain) {}
            }
        }
    }

    private static void health(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of(
                    "success", false,
                    "message", "Method not allowed"
            ));
            return;
        }

        sendJson(ex, 200, Map.of(
                "success", true,
                "service", "nusi-membership-2026",
                "time", Instant.now().toString()
        ));
    }

    private static void slider(HttpExchange ex) throws Exception {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }

        List<GoogleStore.SliderImage> images = GoogleStore.listSliderImages();

        List<Map<String, String>> response = images.stream()
                .map(img -> Map.of(
                        "id", img.id(),
                        "name", img.name(),
                        "url", "/api/slider-image?id=" + urlEncode(img.id())
                ))
                .toList();

        sendJson(ex, 200, response);
    }

    private static void sliderImage(HttpExchange ex) throws Exception {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "Method not allowed");
            return;
        }

        String id = queryParam(ex, "id");

        if (id.isBlank()) {
            sendText(ex, 404, "Not found");
            return;
        }

        GoogleStore.BinaryFile file = GoogleStore.readDriveFile(id);

        if (!file.mimeType().startsWith("image/")) {
            sendText(ex, 404, "Not found");
            return;
        }

        sendBinary(ex, file, false);
    }

    private static void submit(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of(
                    "success", false,
                    "message", "POST required"
            ));
            return;
        }

        try {
            if (API_KEY.isBlank()) {
                throw new IllegalStateException(
                        "API_KEY is missing in Render Environment."
                );
            }

            byte[] bodyBytes = readLimited(
                    ex.getRequestBody(),
                    MAX_BODY_BYTES
            );

            JsonNode data = JSON.readTree(bodyBytes);
            validateSubmission(data);

            UploadedFile photo = decodeImage(
                    data.get("photo"),
                    "Photo"
            );

            byte[] cdc = decodePdf(
                    data.get("cdcfile"),
                    "CDC"
            );

            byte[] passport = decodePdf(
                    data.get("passport"),
                    "Passport"
            );

            String baseUrl = publicBaseUrl(ex);

            GoogleStore.SavedMember saved = GoogleStore.saveMember(
                    data,
                    photo.bytes(),
                    photo.mimeType(),
                    cdc,
                    passport,
                    baseUrl,
                    API_KEY
            );

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("surname", text(data, "surname"));
            response.put("givenName", text(data, "givenName"));
            response.put("cdc", text(data, "cdc"));
            response.put("indos", text(data, "indos").toUpperCase(Locale.ROOT));
            response.put("dob", text(data, "dob"));
            response.put("age", text(data, "age"));
            response.put("blood", text(data, "blood"));
            response.put("rank", text(data, "rank"));
            response.put("nominee", text(data, "nominee"));
            response.put("mobile", text(data, "mobile"));
            response.put("altmobile", text(data, "altmobile"));
            response.put("email", text(data, "email"));
            response.put("altemail", text(data, "altemail"));
            response.put("address", saved.address());
            response.put("photo", saved.photo());
            response.put("cdcfile", saved.cdc());
            response.put("passport", saved.passport());
            response.put("expiresAt", saved.expiresAt());

            sendJson(ex, 200, response);

            cleanupExpiredQuietly();

        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of(
                    "success", false,
                    "message", safeMessage(e)
            ));

        } catch (Exception e) {
            e.printStackTrace(System.err);
            sendJson(ex, 500, Map.of(
                    "success", false,
                    "message", safeMessage(e)
            ));
        }
    }

    private static void temporaryFile(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "Method not allowed");
            return;
        }

        try {
            String id = queryParam(ex, "id");
            String expText = queryParam(ex, "exp");
            String name = queryParam(ex, "name");
            String sig = queryParam(ex, "sig");

            if (id.isBlank() || expText.isBlank() || name.isBlank() || sig.isBlank()) {
                sendText(ex, 404, "Invalid link");
                return;
            }

            long exp;

            try {
                exp = Long.parseLong(expText);
            } catch (NumberFormatException invalid) {
                sendText(ex, 404, "Invalid link");
                return;
            }

            if (Instant.now().getEpochSecond() > exp) {
                sendText(ex, 410, "This temporary link has expired.");
                return;
            }

            if (!TempLinks.verify(id, exp, name, sig, API_KEY)) {
                sendText(ex, 403, "Invalid link");
                return;
            }

            LocalTempStore.BinaryFile local = LocalTempStore.read(id);
            GoogleStore.BinaryFile file = new GoogleStore.BinaryFile(local.bytes(), local.mimeType(), local.name());
            sendBinary(ex, file, true);

        } catch (IllegalArgumentException unavailable) {
            sendText(ex, 410, "This temporary file is no longer available.");

        } catch (Exception e) {
            e.printStackTrace(System.err);
            sendText(ex, 500, "Unable to open temporary file.");
        }
    }

    private static void validateSubmission(JsonNode data) {
        if (data == null || !data.isObject()) {
            throw new IllegalArgumentException("No membership data received.");
        }

        require(data, "surname", "Surname");
        require(data, "givenName", "Given Name");
        require(data, "cdc", "CDC Number");
        require(data, "indos", "INDoS Number");
        require(data, "dob", "Date of Birth");
        require(data, "age", "Age");
        require(data, "blood", "Blood Group");
        require(data, "rank", "Rank");
        require(data, "nominee", "Nominee");
        require(data, "mobile", "Mobile Number");
        require(data, "email", "Email Address");
        require(data, "address1", "Address Line 1");
        require(data, "city", "City");
        require(data, "state", "State");
        require(data, "pincode", "Pincode");

        String indos = text(data, "indos")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "");

        if (!INDOS.matcher(indos).matches()) {
            throw new IllegalArgumentException(
                    "Invalid INDoS format. Expected 00AA0000."
            );
        }

        String nominee = text(data, "nominee").toUpperCase(Locale.ROOT);
        if (!nominee.equals("WIFE") && !nominee.equals("MOTHER")) {
            throw new IllegalArgumentException("Nominee must be Wife or Mother.");
        }

        String mobile = text(data, "mobile");
        if (!TEN_DIGITS.matcher(mobile).matches()) {
            throw new IllegalArgumentException(
                    "Mobile number must contain exactly 10 digits."
            );
        }

        String alt = text(data, "altmobile");
        if (!alt.isBlank() && !TEN_DIGITS.matcher(alt).matches()) {
            throw new IllegalArgumentException(
                    "Alternate mobile number must contain exactly 10 digits."
            );
        }

        if (!PINCODE.matcher(text(data, "pincode")).matches()) {
            throw new IllegalArgumentException(
                    "Pincode must contain exactly 6 digits."
            );
        }

        int age;
        try {
            age = Integer.parseInt(text(data, "age"));
        } catch (Exception e) {
            throw new IllegalArgumentException("Age is invalid.");
        }

        if (age < 18 || age > 60) {
            throw new IllegalArgumentException(
                    "Not Eligible. Age should be between 18 and 60 years."
            );
        }
    }

    private static UploadedFile decodeImage(JsonNode node, String label) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(label + " file is required.");
        }

        String mime = text(node, "mime").toLowerCase(Locale.ROOT);
        String data = text(node, "data");

        if (!mime.equals("image/jpeg") && !mime.equals("image/png")) {
            throw new IllegalArgumentException(
                    label + " must be JPG/JPEG or PNG."
            );
        }

        byte[] bytes = decodeBase64File(data, label, MAX_IMAGE_BYTES);

        boolean validJpeg = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;

        boolean validPng = bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47;

        if ((mime.equals("image/jpeg") && !validJpeg)
                || (mime.equals("image/png") && !validPng)) {
            throw new IllegalArgumentException(label + " is not a valid image file.");
        }

        return new UploadedFile(bytes, mime);
    }

    private static byte[] decodePdf(JsonNode node, String label) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(label + " file is required.");
        }

        String mime = text(node, "mime");
        String data = text(node, "data");

        if (!"application/pdf".equalsIgnoreCase(mime)) {
            throw new IllegalArgumentException(label + " must be a PDF file.");
        }

        byte[] bytes = decodeBase64File(data, label, MAX_PDF_BYTES);

        if (bytes.length < 4
                || bytes[0] != '%'
                || bytes[1] != 'P'
                || bytes[2] != 'D'
                || bytes[3] != 'F') {
            throw new IllegalArgumentException(label + " is not a valid PDF file.");
        }

        return bytes;
    }

    private static byte[] decodeBase64File(String data, String label, int maxBytes) {
        if (data.isBlank()) {
            throw new IllegalArgumentException(label + " file is empty.");
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(data);

            if (bytes.length == 0 || bytes.length > maxBytes) {
                throw new IllegalArgumentException(
                        label + " file is too large."
                );
            }

            return bytes;

        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith(label)) {
                throw e;
            }
            throw new IllegalArgumentException(
                    label + " file could not be decoded."
            );
        }
    }

    private static void require(JsonNode data, String field, String label) {
        if (text(data, field).isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
    }

    private static void serveResource(
            HttpExchange ex,
            String resource,
            String contentType,
            boolean cache
    ) throws IOException {

        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "Method not allowed");
            return;
        }

        try (InputStream in = Main.class.getResourceAsStream(resource)) {
            if (in == null) {
                sendText(ex, 404, "Not found");
                return;
            }

            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.getResponseHeaders().set(
                    "Cache-Control",
                    cache ? "public, max-age=300" : "no-store"
            );
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.sendResponseHeaders(200, bytes.length);

            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static void sendBinary(
            HttpExchange ex,
            GoogleStore.BinaryFile file,
            boolean temporary
    ) throws IOException {

        ex.getResponseHeaders().set("Content-Type", file.mimeType());
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set(
                "Cache-Control",
                temporary ? "private, no-store, max-age=0" : "public, max-age=300"
        );
        ex.getResponseHeaders().set(
                "Content-Disposition",
                "inline; filename=\"" + safeHeaderFileName(file.name()) + "\""
        );

        ex.sendResponseHeaders(200, file.bytes().length);

        try (OutputStream out = ex.getResponseBody()) {
            out.write(file.bytes());
        }
    }

    private static String safeHeaderFileName(String value) {
        return value == null
                ? "file"
                : value.replace("\"", "").replace("\r", "").replace("\n", "");
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;

        for (int n; (n = in.read(buffer)) != -1;) {
            total += n;
            if (total > maxBytes) {
                throw new IllegalArgumentException("Upload is too large.");
            }
            out.write(buffer, 0, n);
        }

        return out.toByteArray();
    }

    private static String queryParam(HttpExchange ex, String name) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return "";
        }

        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);

            if (name.equals(key)) {
                return kv.length > 1
                        ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8)
                        : "";
            }
        }

        return "";
    }

    private static String publicBaseUrl(HttpExchange ex) {
        String configured = env("PUBLIC_BASE_URL", "");
        if (!configured.isBlank()) {
            return configured.replaceAll("/+$", "");
        }

        String proto = firstHeader(ex, "X-Forwarded-Proto", "https");
        String host = firstHeader(ex, "X-Forwarded-Host", "");

        if (host.isBlank()) {
            host = firstHeader(ex, "Host", "");
        }

        if (host.isBlank()) {
            throw new IllegalStateException(
                    "Could not determine public service URL. Add PUBLIC_BASE_URL in Render Environment."
            );
        }

        return proto + "://" + host;
    }

    private static String firstHeader(HttpExchange ex, String name, String fallback) {
        String value = ex.getRequestHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        int comma = value.indexOf(',');
        return (comma >= 0 ? value.substring(0, comma) : value).trim();
    }

    private static void cleanupExpiredQuietly() {
        try {
            int count = GoogleStore.cleanupExpired();
            if (count > 0) {
                System.out.println("NUSI TEMP CLEANUP | deleted folders: " + count);
            }
        } catch (Exception e) {
            System.err.println("NUSI TEMP CLEANUP ERROR | " + safeMessage(e));
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull()
                ? ""
                : value.asText("").trim();
    }

    private static void sendJson(
            HttpExchange ex,
            int status,
            Object body
    ) throws IOException {

        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);

        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendText(
            HttpExchange ex,
            int status,
            String text
    ) throws IOException {

        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);

        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String safeMessage(Throwable e) {
        String message = e == null ? "Unknown error" : e.getMessage();
        if (message == null || message.isBlank()) {
            return "Request failed.";
        }
        return message.length() > 320
                ? message.substring(0, 320)
                : message;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value.trim();
    }

    private static int intEnv(String name, int fallback) {
        try {
            return Integer.parseInt(env(name, String.valueOf(fallback)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record UploadedFile(byte[] bytes, String mimeType) {}
}
