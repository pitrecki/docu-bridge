package io.github.pitrecki.dokubridge.core.port.in.document;

import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentMetadata;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
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

    private MetadataReplacement(DocumentBridge bridge, UpdateMetadata command) {
        this.bridge = bridge;
        this.command = command;
    }

    public static RevisionStep forDocument(DocumentBridge bridge, UUID documentId, Map<String, ?> metadata) {
        Objects.requireNonNull(bridge);
        Objects.requireNonNull(documentId, "Document id must not be null");
        Objects.requireNonNull(metadata, "Metadata must not be null");
        var snapshot = new DocumentMetadata(new LinkedHashMap<>(metadata)).values();
        return revision -> new MetadataReplacement(bridge, new UpdateMetadata(documentId, revision, snapshot));
    }

    public BusinessDocument save() {
        return bridge.updateMetadata(command);
    }
}
