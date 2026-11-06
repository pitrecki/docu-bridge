package io.github.pitrecki.dokubridge.core.port.in.upload;

import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentMetadata;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable attachment draft. Only save performs I/O; an explicit request id enables retries. */
public final class DocumentUpload {
    /** An owner is required before metadata, request identity and save become available. */
    public interface OwnerStep {
        DocumentUpload toOwner(String type, String id);
        DocumentUpload toOwner(DocumentOwnerRef owner);
    }

    private final DocumentBridge bridge;
    private final UploadContent content;
    private final DocumentOwnerRef owner;
    private final Map<String, Object> metadata;
    private final UUID requestId;

    private DocumentUpload(DocumentBridge bridge, UploadContent content, DocumentOwnerRef owner,
                           Map<String, Object> metadata, UUID requestId) {
        this.bridge = bridge;
        this.content = content;
        this.owner = owner;
        this.metadata = metadata;
        this.requestId = requestId;
    }

    public static OwnerStep fromBytes(DocumentBridge bridge, byte[] content) {
        return new Unassigned(Objects.requireNonNull(bridge), UploadContent.bytes(content));
    }

    public static OwnerStep fromStream(DocumentBridge bridge, InputStream content, ContentSha256 checksum) {
        return new Unassigned(Objects.requireNonNull(bridge), UploadContent.stream(content, checksum));
    }

    /** Replaces the draft's complete metadata map with a deep immutable snapshot. */
    public DocumentUpload metadata(Map<String, ?> values) {
        Objects.requireNonNull(values, "Metadata must not be null");
        return new DocumentUpload(bridge, content, owner, new DocumentMetadata(new LinkedHashMap<>(values)).values(), requestId);
    }

    /** Adds or replaces one metadata entry, including a JSON null value. */
    public DocumentUpload metadata(String key, Object value) {
        var values = new LinkedHashMap<>(metadata);
        values.put(key, value);
        return metadata(values);
    }

    public DocumentUpload requestId(UUID key) {
        return new DocumentUpload(bridge, content, owner, metadata, Objects.requireNonNull(key, "Request id must not be null"));
    }

    /** Each call uses a new key unless requestId was supplied; caller-owned streams remain open. */
    public DocumentVersion save() throws IOException {
        UUID key = requestId == null ? UUID.randomUUID() : requestId;
        try (var input = content.open()) {
            return bridge.attach(new AttachDocument(key, owner, metadata, content.checksum(), input.stream()));
        }
    }

    private record Unassigned(DocumentBridge bridge, UploadContent content) implements OwnerStep {
        @Override
        public DocumentUpload toOwner(String type, String id) {
            return toOwner(new DocumentOwnerRef(type, id));
        }

        @Override
        public DocumentUpload toOwner(DocumentOwnerRef owner) {
            return new DocumentUpload(bridge, content, Objects.requireNonNull(owner, "Owner must not be null"), Map.of(), null);
        }
    }
}
