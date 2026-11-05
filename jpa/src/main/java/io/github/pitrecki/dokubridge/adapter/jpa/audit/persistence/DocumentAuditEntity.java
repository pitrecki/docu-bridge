package io.github.pitrecki.dokubridge.adapter.jpa.audit.persistence;

import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dokubridge_audit")
public class DocumentAuditEntity {
    @Id private UUID id;
    @Column(name = "document_id", nullable = false) private UUID documentId;
    @Column(name = "version_id") private UUID versionId;
    @Column(name = "request_id") private UUID requestId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32) private DocumentAuditAction action;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(insertable = false, updatable = false) private long position;

    protected DocumentAuditEntity() {
    }

    public DocumentAuditEntity(UUID documentId, UUID versionId, UUID requestId, DocumentAuditAction action) {
        var entry = new DocumentAuditEntry(UUID.randomUUID(), documentId, versionId, requestId, action, Instant.now());
        this.id = entry.id();
        this.documentId = entry.documentId();
        this.versionId = entry.versionId();
        this.requestId = entry.requestId();
        this.action = entry.action();
        this.occurredAt = entry.occurredAt();
    }

    public DocumentAuditEntity(UUID documentId, DocumentAuditAction action) {
        this(documentId, null, null, action);
    }

    public DocumentAuditEntry snapshot() {
        return new DocumentAuditEntry(id, documentId, versionId, requestId, action, occurredAt);
    }
}
