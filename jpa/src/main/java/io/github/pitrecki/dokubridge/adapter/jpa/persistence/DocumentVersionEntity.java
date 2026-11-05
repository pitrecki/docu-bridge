package io.github.pitrecki.dokubridge.adapter.jpa.persistence;

import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersionState;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "dokubridge_versions")
public class DocumentVersionEntity {
    @Id private UUID id;
    @Column(name = "document_id", nullable = false) private UUID documentId;
    @Column(name = "version_number", nullable = false) private int number;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private DocumentVersionState state;

    protected DocumentVersionEntity() {
    }

    public DocumentVersionEntity(UUID id, UUID documentId, int number) {
        this.id = id;
        this.documentId = documentId;
        this.number = number;
        this.state = DocumentVersionState.PENDING;
    }

    public UUID id() {
        return id;
    }

    public UUID documentId() {
        return documentId;
    }

    public void finalizeAs(DocumentVersionState outcome) {
        var current = snapshot();
        state = switch (outcome) {
            case AVAILABLE -> current.markAvailable().state();
            case FAILED -> current.markFailed().state();
            case PENDING -> throw new IllegalArgumentException("Final outcome must not be pending");
        };
    }

    public DocumentVersion snapshot() {
        return new DocumentVersion(id, documentId, number, state);
    }
}
