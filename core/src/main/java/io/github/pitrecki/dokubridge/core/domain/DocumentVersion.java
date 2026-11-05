package io.github.pitrecki.dokubridge.core.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable version snapshot. Changing this snapshot does not persist it or write content.
 * The facade may serve content only for a persisted AVAILABLE version.
 */
public record DocumentVersion(UUID id, UUID documentId, int number, DocumentVersionState state) {
    public DocumentVersion {
        Objects.requireNonNull(id, "Version id must not be null");
        Objects.requireNonNull(documentId, "Document id must not be null");
        Objects.requireNonNull(state, "Version state must not be null");
        if (number < 1) {
            throw new IllegalArgumentException("Version number must be positive");
        }
    }

    public static DocumentVersion pending(UUID id, UUID documentId, int number) {
        return new DocumentVersion(id, documentId, number, DocumentVersionState.PENDING);
    }

    public DocumentVersion markAvailable() {
        return finalizeAs(DocumentVersionState.AVAILABLE);
    }

    public DocumentVersion markFailed() {
        return finalizeAs(DocumentVersionState.FAILED);
    }

    private DocumentVersion finalizeAs(DocumentVersionState target) {
        if (state != DocumentVersionState.PENDING) {
            throw new IllegalStateException("Cannot finalize version " + id + " in state " + state);
        }
        return new DocumentVersion(id, documentId, number, target);
    }
}
