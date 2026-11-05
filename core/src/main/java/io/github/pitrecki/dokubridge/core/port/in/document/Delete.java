package io.github.pitrecki.dokubridge.core.port.in.document;

import java.util.Objects;
import java.util.UUID;

public record Delete(UUID documentId) {
    public Delete {
        Objects.requireNonNull(documentId, "Document id must not be null");
    }
}
