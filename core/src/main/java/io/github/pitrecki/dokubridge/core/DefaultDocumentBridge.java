package io.github.pitrecki.dokubridge.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DefaultDocumentBridge implements DocumentBridge {
    private final BusinessDocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentContentStore content;

    public DefaultDocumentBridge(BusinessDocumentRepository documents, DocumentVersionRepository versions,
                                 DocumentContentStore content) {
        this.documents = Objects.requireNonNull(documents, "Document repository must not be null");
        this.versions = Objects.requireNonNull(versions, "Version repository must not be null");
        this.content = Objects.requireNonNull(content, "Content store must not be null");
    }

    @Override
    public Optional<BusinessDocument> get(UUID documentId) {
        Objects.requireNonNull(documentId, "Document id must not be null");
        return documents.findById(documentId)
                .filter(document -> document.id().equals(documentId))
                .filter(document -> document.state() != BusinessDocumentState.DELETED);
    }

    @Override
    public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) {
        Objects.requireNonNull(owner, "Document owner must not be null");
        return documents.findByOwner(owner).stream()
                .filter(document -> document.owner().equals(owner))
                .filter(document -> document.state() != BusinessDocumentState.DELETED)
                .toList();
    }

    @Override
    public InputStream getContent(UUID documentId, UUID versionId) throws IOException {
        Objects.requireNonNull(documentId, "Document id must not be null");
        Objects.requireNonNull(versionId, "Version id must not be null");
        get(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        var version = versions.findById(documentId, versionId)
                .filter(candidate -> candidate.documentId().equals(documentId) && candidate.id().equals(versionId))
                .orElseThrow(() -> new DocumentVersionNotFoundException(documentId, versionId));
        if (version.state() != DocumentVersionState.AVAILABLE) {
            throw new DocumentContentUnavailableException(versionId, version.state());
        }
        return content.open(versionId);
    }
}
