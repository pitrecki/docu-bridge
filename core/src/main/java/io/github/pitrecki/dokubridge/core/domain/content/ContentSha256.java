package io.github.pitrecki.dokubridge.core.domain.content;

import java.util.Locale;

public record ContentSha256(String value) {
    public ContentSha256 {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("SHA-256 must contain 64 hexadecimal characters");
        }
        value = value.toLowerCase(Locale.ROOT);
    }
}
