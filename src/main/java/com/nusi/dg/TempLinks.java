package com.nusi.dg;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class TempLinks {

    private TempLinks() {}

    public static String create(
            String baseUrl,
            String fileId,
            long expiresAtEpochSeconds,
            String fileName,
            String secret
    ) throws Exception {

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("API_KEY is required for 24-hour temporary links.");
        }

        String cleanBase = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        String payload = payload(fileId, expiresAtEpochSeconds, fileName);
        String sig = sign(payload, secret);

        return cleanBase
                + "/temp-file?id=" + enc(fileId)
                + "&exp=" + expiresAtEpochSeconds
                + "&name=" + enc(fileName)
                + "&sig=" + enc(sig);
    }

    public static boolean verify(
            String fileId,
            long expiresAtEpochSeconds,
            String fileName,
            String suppliedSignature,
            String secret
    ) throws Exception {

        if (secret == null || secret.isBlank() || suppliedSignature == null) {
            return false;
        }

        String expected = sign(
                payload(fileId, expiresAtEpochSeconds, fileName),
                secret
        );

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                suppliedSignature.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static String payload(String fileId, long exp, String fileName) {
        return safe(fileId) + "\n" + exp + "\n" + safe(fileName);
    }

    private static String sign(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        ));

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String enc(String value) {
        return URLEncoder.encode(safe(value), StandardCharsets.UTF_8);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
