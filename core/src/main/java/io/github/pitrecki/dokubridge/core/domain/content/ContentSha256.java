package io.github.pitrecki.dokubridge.core.domain.content;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public record ContentSha256(String value) {
    public ContentSha256 {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("SHA-256 must contain 64 hexadecimal characters");
        }
        value = value.toLowerCase(Locale.ROOT);
    }

    public static ContentSha256 of(byte[] content) {
        Objects.requireNonNull(content, "Content must not be null");
        try {
            return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JVM does not provide SHA-256", unavailable);
        }
    }
}
