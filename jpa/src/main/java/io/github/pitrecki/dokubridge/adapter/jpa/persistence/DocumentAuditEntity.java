package io.github.pitrecki.dokubridge.adapter.jpa.persistence;

import io.github.pitrecki.dokubridge.core.domain.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.DocumentAuditEntry;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dokubridge_audit")
public class DocumentAuditEntity {
    @Id private UUID id;
    @Column(name = "document_id", nullable = false) private UUID documentId;
    @Column(name = "version_id", nullable = false) private UUID versionId;
    @Column(name = "request_id", nullable = false) private UUID requestId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32) private DocumentAuditAction action;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(insertable = false, updatable = false) private long position;

    protected DocumentAuditEntity() {
    }

    public DocumentAuditEntity(UUID documentId, UUID versionId, UUID requestId, DocumentAuditAction action) {
        this.id = UUID.randomUUID();
        this.documentId = documentId;
        this.versionId = versionId;
        this.requestId = requestId;
        this.action = action;
        this.occurredAt = Instant.now();
    }

    public DocumentAuditEntry snapshot() {
        return new DocumentAuditEntry(id, documentId, versionId, requestId, action, occurredAt);
    }
}
