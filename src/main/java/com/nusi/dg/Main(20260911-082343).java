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
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INDOS = Pattern.compile("^[0-9]{2}[A-Z]{2}[0-9]{4}$");
    private static final Pattern TEN_DIGITS = Pattern.compile("^[0-9]{10}$");
    private static final Pattern PINCODE = Pattern.compile("^[0-9]{6}$");
    private static final String API_KEY = env("API_KEY", "");
    private static final int MAX_CONCURRENT = intEnv("MAX_CONCURRENT_DG", 1);
    private static final Semaphore DG_SLOTS = new Semaphore(Math.max(1, MAX_CONCURRENT));
    private static final int MAX_BODY_BYTES = 60 * 1024 * 1024;
    private static final int MAX_PDF_BYTES = 20 * 1024 * 1024;

    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = intEnv("PORT", 10000);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        server.createContext("/", Main::route);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("NUSI Membership 2026 started on port " + port);
    }

    private static void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            if ("/health".equals(path)) { health(ex); return; }
            if ("/dg/photo".equals(path)) { dgPhoto(ex); return; }
            if ("/api/slider".equals(path)) { slider(ex); return; }
            if ("/api/slider-image".equals(path)) { sliderImage(ex); return; }
            if ("/api/submit".equals(path)) { submit(ex); return; }
            if ("/".equals(path) || "/index.html".equals(path)) { serveResource(ex, "/public/index.html", "text/html; charset=utf-8", false); return; }
            if ("/style.css".equals(path)) { serveResource(ex, "/public/style.css", "text/css; charset=utf-8", true); return; }
            if ("/script.js".equals(path)) { serveResource(ex, "/public/script.js", "application/javascript; charset=utf-8", true); return; }
            if ("/logo.png".equals(path)) { serveResource(ex, "/public/logo.png", "image/png", true); return; }
            sendText(ex, 404, "404 Not Found");
        } catch (Exception e) {
            e.printStackTrace(System.err);
            if (!headersSent(ex)) {
                sendJson(ex, 500, Map.of("success", false, "message", safeMessage(e)));
            } else {
                try { ex.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void health(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("success", false, "message", "Method not allowed"));
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
        List<Map<String, String>> response = images.stream().map(img -> Map.of(
                "id", img.id(),
                "name", img.name(),
                "url", "/api/slider-image?id=" + urlEncode(img.id())
        )).toList();
        sendJson(ex, 200, response);
    }

    private static void sliderImage(HttpExchange ex) throws Exception {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "Method not allowed");
            return;
        }
        String id = queryParam(ex, "id");
        if (id.isBlank()) { sendText(ex, 404, "Not found"); return; }

        GoogleStore.BinaryFile file = GoogleStore.readDriveFile(id);
        if (!file.mimeType().startsWith("image/")) { sendText(ex, 404, "Not found"); return; }

        ex.getResponseHeaders().set("Content-Type", file.mimeType());
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=300");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, file.bytes().length);
        try (OutputStream out = ex.getResponseBody()) { out.write(file.bytes()); }
    }

    private static void submit(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("success", false, "message", "POST required"));
            return;
        }

        boolean acquired = false;
        try {
            byte[] bodyBytes = readLimited(ex.getRequestBody(), MAX_BODY_BYTES);
            JsonNode data = JSON.readTree(bodyBytes);
            validateSubmission(data);

            acquired = DG_SLOTS.tryAcquire();
            if (!acquired) {
                sendJson(ex, 429, Map.of("success", false, "message", "DG photo service is busy. Please retry in a few seconds."));
                return;
            }

            String indos = text(data, "indos").toUpperCase(Locale.ROOT);
            String password = text(data, "indosPassword");
            System.out.println("NUSI SUBMIT | DG PHOTO START | INDoS " + indos);

            DgPhotoService.PhotoResult photo = DgPhotoService.fetch(indos, password);
            byte[] photoBytes = Base64.getDecoder().decode(photo.base64());

            GoogleStore.SavedMember saved = GoogleStore.saveMember(data, photoBytes);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("name", text(data, "name"));
            response.put("surname", text(data, "surname"));
            response.put("givenName", text(data, "givenName"));
            response.put("cdc", text(data, "cdc"));
            response.put("indos", indos);
            response.put("dob", text(data, "dob"));
            response.put("age", text(data, "age"));
            response.put("blood", text(data, "blood"));
            response.put("rank", text(data, "rank"));
            response.put("mobile", text(data, "mobile"));
            response.put("altmobile", text(data, "altmobile"));
            response.put("email", text(data, "email"));
            response.put("altemail", text(data, "altemail"));
            response.put("nomineeType", text(data, "nomineeType"));
            response.put("nomineeName", text(data, "nomineeName"));
            response.put("nomineePhone", text(data, "nomineePhone"));
            response.put("address", saved.address());
            response.put("city", text(data, "city"));
            response.put("state", text(data, "state"));
            response.put("pincode", text(data, "pincode"));
            response.put("photo", saved.photo());
            response.put("folder", saved.folder());

            sendJson(ex, 200, response);
            System.out.println("NUSI SUBMIT SUCCESS | INDoS " + indos);

        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("success", false, "message", safeMessage(e)));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            sendJson(ex, 500, Map.of("success", false, "message", safeMessage(e)));
        } finally {
            if (acquired) DG_SLOTS.release();
        }
    }

    private static void dgPhoto(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("success", false, "message", "POST required"));
            return;
        }

        if (!API_KEY.isBlank()) {
            String supplied = ex.getRequestHeaders().getFirst("X-API-Key");
            if (supplied == null || !constantTimeEquals(API_KEY, supplied)) {
                sendJson(ex, 401, Map.of("success", false, "message", "Unauthorized"));
                return;
            }
        }

        boolean acquired = false;
        try {
            JsonNode request = JSON.readTree(readLimited(ex.getRequestBody(), 1024 * 1024));
            String indos = text(request, "indos").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            String password = text(request, "password");

            if (!INDOS.matcher(indos).matches()) throw new IllegalArgumentException("Invalid INDoS format. Expected 00AA0000.");
            if (password.isBlank()) throw new IllegalArgumentException("Password is required.");

            acquired = DG_SLOTS.tryAcquire();
            if (!acquired) {
                sendJson(ex, 429, Map.of("success", false, "message", "DG photo service is busy. Please retry in a few seconds."));
                return;
            }

            DgPhotoService.PhotoResult result = DgPhotoService.fetch(indos, password);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("mimeType", "image/jpeg");
            response.put("base64", result.base64());
            response.put("width", result.width());
            response.put("height", result.height());
            sendJson(ex, 200, response);

        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("success", false, "message", safeMessage(e)));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            sendJson(ex, 502, Map.of("success", false, "message", safeMessage(e)));
        } finally {
            if (acquired) DG_SLOTS.release();
        }
    }

    private static void validateSubmission(JsonNode data) {
        if (data == null || !data.isObject()) throw new IllegalArgumentException("No membership data received.");
        require(data, "surname", "Surname");
        require(data, "givenName", "Given Name");
        require(data, "name", "Name");
        require(data, "cdc", "CDC Number");
        require(data, "indos", "INDoS Number");
        require(data, "indosPassword", "INDoS Password");
        require(data, "dob", "Date of Birth");
        require(data, "age", "Age");
        require(data, "blood", "Blood Group");
        require(data, "rank", "Rank");
        require(data, "mobile", "Mobile Number");
        require(data, "email", "Email Address");
        require(data, "nomineeType", "Nominee");
        require(data, "nomineeName", "Nominee Name");
        require(data, "nomineePhone", "Nominee Phone Number");
        require(data, "address1", "Address Line 1");
        require(data, "city", "City");
        require(data, "state", "State");
        require(data, "pincode", "Pincode");

        String indos = text(data, "indos").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (!INDOS.matcher(indos).matches()) throw new IllegalArgumentException("Invalid INDoS format. Expected 00AA0000.");
        if (!TEN_DIGITS.matcher(text(data, "mobile")).matches()) throw new IllegalArgumentException("Mobile number must contain exactly 10 digits.");
        String alt = text(data, "altmobile");
        if (!alt.isBlank() && !TEN_DIGITS.matcher(alt).matches()) throw new IllegalArgumentException("Alternate mobile number must contain exactly 10 digits.");
        if (!TEN_DIGITS.matcher(text(data, "nomineePhone")).matches()) throw new IllegalArgumentException("Nominee phone number must contain exactly 10 digits.");
        String nomineeType = text(data, "nomineeType").toUpperCase(Locale.ROOT);
        if (!("WIFE".equals(nomineeType) || "MOTHER".equals(nomineeType))) throw new IllegalArgumentException("Nominee must be Wife or Mother.");
        if (!PINCODE.matcher(text(data, "pincode")).matches()) throw new IllegalArgumentException("Pincode must contain exactly 6 digits.");

        int age;
        try { age = Integer.parseInt(text(data, "age")); }
        catch (Exception e) { throw new IllegalArgumentException("Age is invalid."); }
        if (age < 18 || age > 60) throw new IllegalArgumentException("Not Eligible. Age should be between 18 and 60 years.");
    }

    private static byte[] decodeFile(JsonNode node, String label, String expectedMime) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(label + " file is required.");
        String mime = text(node, "mime");
        String data = text(node, "data");
        if (!expectedMime.equalsIgnoreCase(mime)) throw new IllegalArgumentException(label + " must be a PDF file.");
        if (data.isBlank()) throw new IllegalArgumentException(label + " file is empty.");
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            if (bytes.length == 0 || bytes.length > MAX_PDF_BYTES) throw new IllegalArgumentException(label + " PDF must be under 20 MB.");
            if (bytes.length < 4 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F') {
                throw new IllegalArgumentException(label + " is not a valid PDF file.");
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith(label)) throw e;
            throw new IllegalArgumentException(label + " file could not be decoded.");
        }
    }

    private static void require(JsonNode data, String field, String label) {
        if (text(data, field).isBlank()) throw new IllegalArgumentException(label + " is required.");
    }

    private static void serveResource(HttpExchange ex, String resource, String contentType, boolean cache) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { sendText(ex, 405, "Method not allowed"); return; }
        try (InputStream in = Main.class.getResourceAsStream(resource)) {
            if (in == null) { sendText(ex, 404, "Not found"); return; }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.getResponseHeaders().set("Cache-Control", cache ? "public, max-age=300" : "no-store");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
        }
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int n; (n = in.read(buffer)) != -1;) {
            total += n;
            if (total > maxBytes) throw new IllegalArgumentException("Upload is too large.");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String queryParam(HttpExchange ex, String name) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return "";
        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            if (name.equals(key)) return kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
        }
        return "";
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private static void sendText(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private static boolean headersSent(HttpExchange ex) {
        // HttpExchange does not expose response state. Keep this false and let the caller close on double-write failures.
        return false;
    }

    private static String safeMessage(Throwable e) {
        String m = e == null ? "Unknown error" : e.getMessage();
        if (m == null || m.isBlank()) return "Request failed.";
        String lower = m.toLowerCase(Locale.ROOT);
        if (lower.contains("password") && lower.contains("http")) return "DG request failed. Please check the entered details.";
        return m.length() > 320 ? m.substring(0, 320) : m;
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            byte xb = i < x.length ? x[i] : 0;
            byte yb = i < y.length ? y[i] : 0;
            diff |= xb ^ yb;
        }
        return diff == 0;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : v.trim();
    }

    private static int intEnv(String name, int fallback) {
        try { return Integer.parseInt(env(name, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }
}
