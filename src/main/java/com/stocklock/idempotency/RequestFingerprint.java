package com.stocklock.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * SHA-256 over the semantically relevant request fields. Stored next to the Idempotency-Key so a key
 * reused with a different payload is rejected instead of silently returning an unrelated resource.
 */
public final class RequestFingerprint {

    private static final String SEPARATOR = "\u001F"; // ASCII unit separator: cannot appear in validated input

    private RequestFingerprint() {
    }

    public static String of(Object... parts) {
        String canonical = Arrays.stream(parts).map(String::valueOf).collect(Collectors.joining(SEPARATOR));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
