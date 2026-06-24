package com.codefromheaven.mcp.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class HashUtils {

    private HashUtils() {
        // Utility class
    }

    /**
     * Calculates SHA-256 hash for the given data string.
     * @param data The input string to hash
     * @return Hex-encoded SHA-256 hash, or empty string if it fails.
     */
    public static String calculateSha256(String data) {
        if (data == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(data.hashCode());
        }
    }
}
