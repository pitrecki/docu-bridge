package io.github.pitrecki.dokubridge.core.port.in;

import io.github.pitrecki.dokubridge.core.domain.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentContentUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentVersionNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Application-facing document facade. The host application owns authorization. */
public interface DocumentBridge {
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
