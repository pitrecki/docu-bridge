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

    public DocumentResult<BusinessDocument> save() {
        return DocumentResult.changed(document, bridge.updateMetadata(command));
    }

    /** Runs the callback after replacement; earlier committed changes remain if it fails. */
    public DocumentResult<BusinessDocument> saveAndThen(DocumentAction action) throws IOException {
        Objects.requireNonNull(action, "Document action must not be null");
        return save().andThen(action);
    }
}
