package io.github.pitrecki.dokubridge.core.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record BusinessDocument(UUID id, DocumentOwnerRef owner, Map<String, Object> metadata,
                               BusinessDocumentState state) {
    public BusinessDocument {
        Objects.requireNonNull(id, "Document id must not be null");
        Objects.requireNonNull(owner, "Document owner must not be null");
        Objects.requireNonNull(state, "Document state must not be null");
        metadata = new DocumentMetadata(metadata).values();
    }
}
