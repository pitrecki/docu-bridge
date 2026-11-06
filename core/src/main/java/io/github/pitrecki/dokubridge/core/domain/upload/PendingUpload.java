package io.github.pitrecki.dokubridge.core.domain.upload;

import java.util.Objects;
import java.util.UUID;

/** Identifies a pending upload snapshot; its state can change before reconciliation. */
public record PendingUpload(UUID requestId, UUID documentId, UUID versionId) {
    public PendingUpload {
        Objects.requireNonNull(requestId, "Request id must not be null");
        Objects.requireNonNull(documentId, "Document id must not be null");
        Objects.requireNonNull(versionId, "Version id must not be null");
    }
}
