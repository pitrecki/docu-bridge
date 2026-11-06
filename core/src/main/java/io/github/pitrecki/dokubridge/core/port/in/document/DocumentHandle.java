package io.github.pitrecki.dokubridge.core.port.in.document;

import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.in.upload.VersionUpload;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Binds operations to an id without loading the document or caching its state. */
public final class DocumentHandle {
    private final DocumentBridge bridge;
    private final UUID documentId;

    public DocumentHandle(DocumentBridge bridge, UUID documentId) {
        this.bridge = Objects.requireNonNull(bridge);
        this.documentId = Objects.requireNonNull(documentId, "Document id must not be null");
    }

    public Optional<BusinessDocument> get() {
        return bridge.get(documentId);
    }

    public List<DocumentVersion> versions() {
        return bridge.getVersions(documentId);
    }

    public List<DocumentAuditEntry> audit() {
        return bridge.getAudit(documentId);
    }

    /** The caller closes the returned stream, as with DocumentBridge.getContent. */
    public InputStream content(UUID versionId) throws IOException {
        return bridge.getContent(documentId, versionId);
    }

    public VersionUpload upload(byte[] content) {
        return VersionUpload.fromBytes(bridge, documentId, content);
    }

    public VersionUpload upload(InputStream content, ContentSha256 checksum) {
        return VersionUpload.fromStream(bridge, documentId, content, checksum);
    }

    public VersionUpload upload(InputStream content, String sha256) {
        return upload(content, new ContentSha256(sha256));
    }

    public MetadataReplacement.RevisionStep replaceMetadata(Map<String, ?> metadata) {
        return MetadataReplacement.forDocument(bridge, documentId, metadata);
    }

    public BusinessDocument archive() {
        return bridge.archive(new Archive(documentId));
    }

    public void delete() {
        bridge.delete(new Delete(documentId));
    }
}
