package io.github.pitrecki.dokubridge.adapter.jpa.document.persistence;

import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import jakarta.persistence.*;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dokubridge_documents")
public class BusinessDocumentEntity {
    @Id private UUID id;
    @Column(name = "owner_type", nullable = false, columnDefinition = "text") private String ownerType;
    @Column(name = "owner_id", nullable = false, columnDefinition = "text") private String ownerId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private String metadata;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private BusinessDocumentState state;
    @Column(name = "last_version", nullable = false) private int lastVersion;
    @Column(nullable = false) private long revision;

    protected BusinessDocumentEntity() {
    }

    public BusinessDocumentEntity(UUID id, DocumentOwnerRef owner, Map<String, Object> metadata) {
        this.id = id;
        this.ownerType = owner.type();
        this.ownerId = owner.id();
        this.metadata = DocumentMetadataJson.encode(
                new BusinessDocument(id, owner, metadata, BusinessDocumentState.ACTIVE).metadata());
        this.state = BusinessDocumentState.ACTIVE;
        this.lastVersion = 1;
    }

    public BusinessDocumentState state() {
        return state;
    }

    public int allocateVersionNumber() {
        lastVersion = Math.addExact(lastVersion, 1);
        return lastVersion;
    }

    public void applyDocumentChange(BusinessDocument changed) {
        if (!id.equals(changed.id()) || !ownerType.equals(changed.owner().type()) || !ownerId.equals(changed.owner().id())) {
            throw new IllegalArgumentException("Document identity and owner cannot be changed");
        }
        metadata = DocumentMetadataJson.encode(changed.metadata());
        state = changed.state();
        revision = changed.revision();
    }

    public BusinessDocument snapshot() {
        return new BusinessDocument(id, new DocumentOwnerRef(ownerType, ownerId), DocumentMetadataJson.decode(metadata), state, revision);
    }
}
