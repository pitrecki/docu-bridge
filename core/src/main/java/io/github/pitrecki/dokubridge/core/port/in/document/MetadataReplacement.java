package io.github.pitrecki.dokubridge.core.port.in.document;

import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentMetadata;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Full metadata replacement; the caller must select the observed revision before saving. */
public final class MetadataReplacement {
    public interface RevisionStep {
        MetadataReplacement atRevision(long expectedRevision);
    }

    private final DocumentBridge bridge;
    private final UpdateMetadata command;
    private final DocumentHandle document;

    private MetadataReplacement(DocumentBridge bridge, UpdateMetadata command, DocumentHandle document) {
        this.bridge = bridge;
        this.command = command;
        this.document = document;
    }

    public static RevisionStep forDocument(DocumentBridge bridge, UUID documentId, Map<String, ?> metadata) {
        return forDocument(bridge, documentId, metadata, new DocumentHandle(bridge, documentId));
    }

    static RevisionStep forDocument(DocumentBridge bridge, UUID documentId, Map<String, ?> metadata, DocumentHandle document) {
        Objects.requireNonNull(bridge);
        Objects.requireNonNull(documentId, "Document id must not be null");
        Objects.requireNonNull(metadata, "Metadata must not be null");
        var snapshot = new DocumentMetadata(new LinkedHashMap<>(metadata)).values();
        return revision -> new MetadataReplacement(bridge, new UpdateMetadata(documentId, revision, snapshot), document);
    }

    /**
     * Replaces the entire metadata map at the selected revision, including for archived documents.
     * The result carries the new revision; the continuation retains any selected content version.
     * A stale revision conflicts without changing metadata or appending an audit.
     */
    public DocumentResult<BusinessDocument> save() {
        return DocumentResult.changed(document, bridge.updateMetadata(command));
    }

    /**
     * Runs the callback after replacement and returns the replacement's original snapshot.
     * A stale revision skips the callback. Callback failure leaves the replacement committed;
     * repeating the draft with its old revision still conflicts.
     *
     * @throws IOException if the callback fails with an I/O error
     * @throws NullPointerException if the callback is null, before replacement
     */
    public DocumentResult<BusinessDocument> saveAndThen(DocumentAction action) throws IOException {
        Objects.requireNonNull(action, "Document action must not be null");
        return save().andThen(action);
    }
}
