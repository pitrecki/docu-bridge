package io.github.pitrecki.dokubridge.core.port.in.document;

import io.github.pitrecki.dokubridge.core.domain.document.DocumentMetadata;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Replaces the complete metadata map, guarded by the last observed document revision. */
public record UpdateMetadata(UUID documentId, long expectedRevision, Map<String, Object> metadata) {
    public UpdateMetadata {
        Objects.requireNonNull(documentId, "Document id must not be null");
        if (expectedRevision < 0) throw new IllegalArgumentException("Expected revision must not be negative");
        metadata = new DocumentMetadata(metadata).values();
    }
}
