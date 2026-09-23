package com.nusi.dg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

/** Persistent temporary upload storage. Mount a Railway Volume at NUSI_UPLOAD_DIR (default /data/nusi-uploads). */
public final class LocalTempStore {
    private static final Path ROOT = Paths.get(env("NUSI_UPLOAD_DIR", "/data/nusi-uploads")).toAbsolutePath().normalize();
    private LocalTempStore() {}

    public record StoredFile(String id, String name, String mimeType) {}
    public record BinaryFile(byte[] bytes, String mimeType, String name) {}

    public static StoredFile save(String name, String mimeType, byte[] bytes, long expiresEpoch) throws IOException {
        Files.createDirectories(ROOT);
        String id = UUID.randomUUID().toString();
        Path dir = ROOT.resolve(id);
        Files.createDirectories(dir);
        Files.write(dir.resolve("content.bin"), bytes, StandardOpenOption.CREATE_NEW);
        Files.writeString(dir.resolve("meta.txt"), expiresEpoch + "\n" + safeLine(mimeType) + "\n" + safeLine(name), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return new StoredFile(id, name, mimeType);
    }

    public static BinaryFile read(String id) throws IOException {
        Path dir = safeDir(id);
        Path meta = dir.resolve("meta.txt");
        Path content = dir.resolve("content.bin");
        if (!Files.isRegularFile(meta) || !Files.isRegularFile(content)) throw new IllegalArgumentException("Temporary file not found.");
        var lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
        if (lines.size() < 3) throw new IllegalArgumentException("Temporary file metadata is invalid.");
        long exp = Long.parseLong(lines.get(0));
        if (Instant.now().getEpochSecond() > exp) {
            deleteTree(dir);
            throw new IllegalArgumentException("Temporary file expired.");
        }
        return new BinaryFile(Files.readAllBytes(content), lines.get(1), lines.get(2));
    }

    public static int cleanupExpired() throws IOException {
        if (!Files.isDirectory(ROOT)) return 0;
        int count = 0;
        try (var dirs = Files.list(ROOT)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                try {
                    Path meta = dir.resolve("meta.txt");
                    if (!Files.isRegularFile(meta)) continue;
                    String first = Files.readAllLines(meta, StandardCharsets.UTF_8).get(0);
                    if (Instant.now().getEpochSecond() > Long.parseLong(first)) { deleteTree(dir); count++; }
                } catch (Exception ignored) {}
            }
        }
        return count;
    }

    private static Path safeDir(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("Invalid temporary file id.");
        Path p = ROOT.resolve(id).normalize();
        if (!p.startsWith(ROOT)) throw new IllegalArgumentException("Invalid temporary file id.");
        return p;
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
    private static String safeLine(String s) { return (s == null ? "" : s).replace("\r", " ").replace("\n", " "); }
    private static String env(String key, String fallback) { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : v.trim(); }
}
