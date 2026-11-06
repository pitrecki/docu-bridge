package io.github.pitrecki.dokubridge.core.port.in.upload;

import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentMetadata;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.in.document.DocumentAction;
import io.github.pitrecki.dokubridge.core.port.in.document.DocumentResult;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable attachment draft. Save operations perform I/O; an explicit request id enables retries. */
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

    /**
     * Attaches one document and returns its version snapshot with a document continuation.
     * Each call uses a new request key unless {@link #requestId(UUID)} was supplied.
     * A matching keyed replay returns its current state, which may be pending or failed,
     * without consuming content. Caller-owned input streams remain open on every path.
     *
     * @throws IOException if uploading or verifying content fails
     */
    public DocumentResult<DocumentVersion> save() throws IOException {
        UUID key = requestId == null ? UUID.randomUUID() : requestId;
        try (var input = content.open()) {
            return DocumentResult.uploaded(bridge, bridge.attach(new AttachDocument(key, owner, metadata, content.checksum(), input.stream())));
        }
    }

    /**
     * Saves once, then passes its document to the callback without extracting an id:
     * <pre>{@code
     * bridge.upload(bytes).toOwner("Invoice", "42")
     *     .saveAndThen(document -> document.archive());
     * }</pre>
     * The callback runs whenever save returns normally, including keyed pending/failed
     * replays. A save exception skips it; a callback exception does not undo the upload
     * or other committed operations. Replaying an upload repeats its callback.
     * The returned result is the original upload snapshot.
     *
     * @throws IOException if saving or the callback fails with an I/O error
     * @throws NullPointerException if the callback is null, before saving
     */
    public DocumentResult<DocumentVersion> saveAndThen(DocumentAction action) throws IOException {
        Objects.requireNonNull(action, "Document action must not be null");
        return save().andThen(action);
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
