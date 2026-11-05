package io.github.pitrecki.dokubridge.core.domain;

import io.github.pitrecki.dokubridge.core.domain.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentRevisionConflictException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record BusinessDocument(UUID id, DocumentOwnerRef owner, Map<String, Object> metadata,
                               BusinessDocumentState state, long revision) {
    public BusinessDocument(UUID id, DocumentOwnerRef owner, Map<String, Object> metadata, BusinessDocumentState state) {
        this(id, owner, metadata, state, 0);
    }

    public BusinessDocument {
        Objects.requireNonNull(id, "Document id must not be null");
        Objects.requireNonNull(owner, "Document owner must not be null");
        Objects.requireNonNull(state, "Document state must not be null");
        if (revision < 0) throw new IllegalArgumentException("Document revision must not be negative");
        metadata = new DocumentMetadata(metadata).values();
    }

    public BusinessDocument updateMetadata(long expectedRevision, Map<String, Object> replacement) {
        if (expectedRevision < 0) throw new IllegalArgumentException("Expected revision must not be negative");
        if (state == BusinessDocumentState.DELETED) throw new DocumentNotFoundException(id);
        if (expectedRevision != revision) throw new DocumentRevisionConflictException(id, expectedRevision, revision);
        return new BusinessDocument(id, owner, replacement, state, Math.incrementExact(revision));
    }

    public BusinessDocument archive() {
        return switch (state) {
            case ACTIVE -> new BusinessDocument(id, owner, metadata, BusinessDocumentState.ARCHIVED, Math.incrementExact(revision));
            case ARCHIVED -> this;
            case DELETED -> throw new DocumentNotFoundException(id);
        };
    }

    public BusinessDocument delete() {
        return state == BusinessDocumentState.DELETED ? this
                : new BusinessDocument(id, owner, metadata, BusinessDocumentState.DELETED, Math.incrementExact(revision));
    }
}
