package io.github.pitrecki.dokubridge.core.port.in;

import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.content.exception.DocumentContentUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.exception.DocumentVersionNotFoundException;
import io.github.pitrecki.dokubridge.core.port.in.document.Archive;
import io.github.pitrecki.dokubridge.core.port.in.document.Delete;
import io.github.pitrecki.dokubridge.core.port.in.document.DocumentHandle;
import io.github.pitrecki.dokubridge.core.port.in.document.DocumentResult;
import io.github.pitrecki.dokubridge.core.port.in.document.UpdateMetadata;
import io.github.pitrecki.dokubridge.core.port.in.upload.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.upload.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.in.upload.DocumentUpload;
import io.github.pitrecki.dokubridge.core.port.in.upload.ReconcileUpload;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Application-facing document facade. The host application owns authorization. */
public interface DocumentBridge {
    /** Starts an upload from an immutable byte snapshot, with SHA-256 computed automatically. */
    default DocumentUpload.OwnerStep upload(byte[] content) {
        return DocumentUpload.fromBytes(this, content);
    }

    /** Starts an upload without reading or closing the caller-owned stream. */
    default DocumentUpload.OwnerStep upload(InputStream content, ContentSha256 checksum) {
        return DocumentUpload.fromStream(this, content, checksum);
    }

    default DocumentUpload.OwnerStep upload(InputStream content, String sha256) {
        return upload(content, new ContentSha256(sha256));
    }

    /** Creates a document context without loading its state. */
    default DocumentHandle document(UUID documentId) {
        return new DocumentHandle(this, documentId);
    }

    /**
     * Reads a visible document once and binds its snapshot to a fluent continuation.
     * Missing or deleted documents return an empty Optional, as with {@link #get(UUID)}.
     * {@code result()} retains the query snapshot; subsequent operations through
     * {@code and()} use current state. No content version is selected or opened.
     * The host application authorizes access.
     */
    default Optional<DocumentResult<BusinessDocument>> findDocument(UUID documentId) {
        return get(Objects.requireNonNull(documentId, "Document id must not be null"))
                .map(document -> DocumentResult.found(this, document));
    }

    default List<BusinessDocument> findByOwner(String type, String id) {
        return findByOwner(new DocumentOwnerRef(type, id));
    }

    /** Fluent owner lookup with the same visibility rules as {@link #findByOwner(DocumentOwnerRef)}. */
    default List<DocumentResult<BusinessDocument>> documentsForOwner(String type, String id) {
        return documentsForOwner(new DocumentOwnerRef(type, id));
    }

    /**
     * Returns immutable query results with document continuations, without loading each document again.
     * {@code result()} retains the query snapshot; {@code and()} binds subsequent operations to its id.
     * No content version is selected. The query retains the order of {@link #findByOwner(DocumentOwnerRef)}
     * and performs no content I/O. The host application authorizes access.
     */
    default List<DocumentResult<BusinessDocument>> documentsForOwner(DocumentOwnerRef owner) {
        return findByOwner(Objects.requireNonNull(owner, "Document owner must not be null")).stream()
                .map(document -> DocumentResult.found(this, document))
                .toList();
    }

    default DocumentVersion reconcileUpload(UUID requestId, UUID versionId) throws IOException {
        return reconcileUpload(new ReconcileUpload(requestId, versionId));
    }

    /**
     * Reconciles PENDING content using its recorded checksum. Missing content stays pending.
     * Terminal versions are returned unchanged; storage errors leave the version unmodified.
     * The host must authorize this operation just like other document writes.
     */
    default DocumentVersion reconcileUpload(ReconcileUpload command) throws IOException {
        throw new UnsupportedOperationException("Upload reconciliation is not configured");
    }

    /** Replaces metadata with revision conflict detection; each accepted update advances the revision. */
    default BusinessDocument updateMetadata(UpdateMetadata command) {
        throw new UnsupportedOperationException("Document lifecycle operations are not configured");
    }

    /** Archives an active document; an already archived document is returned unchanged. */
    default BusinessDocument archive(Archive command) {
        throw new UnsupportedOperationException("Document lifecycle operations are not configured");
    }

    /** Hides a document while retaining content/history. Repeating deletion does not add another audit. */
    default void delete(Delete command) {
        throw new UnsupportedOperationException("Document lifecycle operations are not configured");
    }

    /** Reserves and uploads a first version; matching replays return its current state without consuming content. */
    default DocumentVersion attach(AttachDocument command) throws IOException {
        throw new UnsupportedOperationException("Uploads are not configured");
    }

    /** Adds a version without changing metadata; matching replays do not consume content. */
    default DocumentVersion addVersion(AddDocumentVersion command) throws IOException {
        throw new UnsupportedOperationException("Uploads are not configured");
    }

    /** Returns active or archived documents; deleted documents appear absent. */
    Optional<BusinessDocument> get(UUID documentId);

    /** Returns an immutable list of the owner's non-deleted documents. */
    List<BusinessDocument> findByOwner(DocumentOwnerRef owner);

    /** Returns an immutable version history in number order, including pending and failed versions. */
    default List<DocumentVersion> getVersions(UUID documentId) {
        throw new UnsupportedOperationException("Version history is not configured");
    }

    /** Returns an immutable audit in insertion order; missing/deleted documents report not found. */
    default List<DocumentAuditEntry> getAudit(UUID documentId) {
        throw new UnsupportedOperationException("Audit history is not configured");
    }

    /**
     * Opens an AVAILABLE version belonging to a non-deleted document.
     * The caller must close the returned stream.
     *
     * @throws DocumentNotFoundException if the document is absent or deleted
     * @throws DocumentVersionNotFoundException if the version is absent or belongs to another document
     * @throws DocumentContentUnavailableException if the version is pending or failed
     * @throws IOException if the content store cannot open the content
     */
    InputStream getContent(UUID documentId, UUID versionId) throws IOException;
}
