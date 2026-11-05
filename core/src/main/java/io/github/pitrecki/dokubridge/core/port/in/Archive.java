package io.github.pitrecki.dokubridge.core.port.in;

import java.util.Objects;
import java.util.UUID;

public record Archive(UUID documentId) {
    public Archive {
        Objects.requireNonNull(documentId, "Document id must not be null");
    }
}
