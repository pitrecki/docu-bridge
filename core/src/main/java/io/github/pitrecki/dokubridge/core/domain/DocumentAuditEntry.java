package io.github.pitrecki.dokubridge.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DocumentAuditEntry(UUID id, UUID documentId, UUID versionId, UUID requestId,
                                 DocumentAuditAction action, Instant occurredAt) {
    public DocumentAuditEntry {
        Objects.requireNonNull(id, "Audit id must not be null");
        Objects.requireNonNull(documentId, "Document id must not be null");
        Objects.requireNonNull(versionId, "Version id must not be null");
        Objects.requireNonNull(requestId, "Request id must not be null");
        Objects.requireNonNull(action, "Audit action must not be null");
        Objects.requireNonNull(occurredAt, "Audit timestamp must not be null");
    }
}
