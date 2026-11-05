package io.github.pitrecki.dokubridge.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Upload actions require version/request ids; document-only actions have null ids for both. */
public record DocumentAuditEntry(UUID id, UUID documentId, UUID versionId, UUID requestId,
                                 DocumentAuditAction action, Instant occurredAt) {
    public DocumentAuditEntry {
        Objects.requireNonNull(id, "Audit id must not be null");
        Objects.requireNonNull(documentId, "Document id must not be null");
        Objects.requireNonNull(action, "Audit action must not be null");
        if (action.requiresUploadContext()) {
            Objects.requireNonNull(versionId, "Upload audit version id must not be null");
            Objects.requireNonNull(requestId, "Upload audit request id must not be null");
        } else if (versionId != null || requestId != null) {
            throw new IllegalArgumentException("Document audit must not carry upload identifiers");
        }
        Objects.requireNonNull(occurredAt, "Audit timestamp must not be null");
    }
}
