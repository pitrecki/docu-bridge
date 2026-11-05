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
