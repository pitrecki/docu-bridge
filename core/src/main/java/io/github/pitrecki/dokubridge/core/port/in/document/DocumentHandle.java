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
    private final UUID contentVersionId;

    public DocumentHandle(DocumentBridge bridge, UUID documentId) {
        this(bridge, documentId, null);
    }

    private DocumentHandle(DocumentBridge bridge, UUID documentId, UUID contentVersionId) {
        this.bridge = Objects.requireNonNull(bridge);
        this.documentId = Objects.requireNonNull(documentId, "Document id must not be null");
        this.contentVersionId = contentVersionId;
    }

    static DocumentHandle forVersion(DocumentBridge bridge, DocumentVersion version) {
        Objects.requireNonNull(version, "Version must not be null");
        return new DocumentHandle(bridge, version.documentId(), version.id());
    }

    public UUID id() {
        return documentId;
    }

    /**
     * Opens the version selected by an upload result; the caller closes the returned stream.
     * Later uploads do not change this selection. Metadata and archive continuations
     * preserve it. Every open checks current document visibility and version availability.
     *
     * @throws IllegalStateException if this context was created from a document id alone;
     *         use {@link #content(UUID)} to select a version explicitly
     * @throws IOException if opening content fails
     */
    public InputStream content() throws IOException {
        if (contentVersionId == null) {
            throw new IllegalStateException("No version selected; use an upload continuation or content(versionId)");
        }
        return content(contentVersionId);
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

    /**
     * Reads the selected upload version and closes its stream before returning the value.
     * For example, {@code readContent(InputStream::readAllBytes)} returns its bytes.
     * The reader runs once after content opens successfully, using the same visibility
     * and availability checks as {@link #content()}. It must use the stream within its call.
     *
     * @throws IOException if opening, reading or closing content fails; a distinct close failure
     *         is suppressed when the reader already threw an exception
     * @throws IllegalStateException if this context has no selected version
     * @throws NullPointerException if the reader is null, before performing I/O
     */
    public <T> T readContent(DocumentContentReader<T> reader) throws IOException {
        Objects.requireNonNull(reader, "Content reader must not be null");
        return readAndClose(content(), reader);
    }

    /**
     * Reads an explicit version and closes its stream before returning the value.
     * Uses the same visibility and availability checks as {@link #content(UUID)}.
     * The reader runs once after a successful open and must use the stream within its call.
     * No buffering or transaction is added around the reader.
     *
     * @throws IOException if opening, reading or closing content fails; a distinct close failure
     *         is suppressed when the reader already threw an exception
     * @throws NullPointerException if the reader is null, before performing I/O
     */
    public <T> T readContent(UUID versionId, DocumentContentReader<T> reader) throws IOException {
        Objects.requireNonNull(reader, "Content reader must not be null");
        return readAndClose(content(versionId), reader);
    }

    // A stream can reuse an exception for read and close; try-with-resources would mask it with self-suppression.
    private static <T> T readAndClose(InputStream input, DocumentContentReader<T> reader) throws IOException {
        T value;
        try {
            value = reader.read(input);
        } catch (Throwable failure) {
            try {
                input.close();
            } catch (Throwable closeFailure) {
                if (closeFailure != failure) failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        input.close();
        return value;
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
        return MetadataReplacement.forDocument(bridge, documentId, metadata, this);
    }

    public DocumentResult<BusinessDocument> archive() {
        return DocumentResult.changed(this, bridge.archive(new Archive(documentId)));
    }

    public void delete() {
        bridge.delete(new Delete(documentId));
    }
}
