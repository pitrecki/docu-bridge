package io.github.pitrecki.dokubridge.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Application-facing read facade. The host application owns authorization. */
public interface DocumentBridge {
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
