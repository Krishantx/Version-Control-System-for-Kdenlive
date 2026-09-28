package com.vvcs.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 over raw bytes, and nothing else.
 *
 * <p>Deliberately knows nothing about VVCS objects. The rules about what a commit identity
 * means belong to {@link CommitHasher}; this class only digests bytes, so there is one
 * place where the algorithm and the output encoding are decided.
 */
public final class ContentHash {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ContentHash() {
    }

    /**
     * Digests bytes, returning 64 lowercase hex characters.
     */
    public static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform; a missing provider is a broken JRE.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static String sha256Utf8(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }
}
