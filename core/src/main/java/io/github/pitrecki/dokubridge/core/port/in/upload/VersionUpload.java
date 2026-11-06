package io.github.pitrecki.dokubridge.core.port.in.upload;

import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.in.document.DocumentAction;
import io.github.pitrecki.dokubridge.core.port.in.document.DocumentResult;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/** Immutable version draft; save delegates to the existing upload use case. */
public final class VersionUpload {
    private final DocumentBridge bridge;
    private final UUID documentId;
    private final UploadContent content;
    private final UUID requestId;

    private VersionUpload(DocumentBridge bridge, UUID documentId, UploadContent content, UUID requestId) {
        this.bridge = Objects.requireNonNull(bridge);
        this.documentId = Objects.requireNonNull(documentId, "Document id must not be null");
        this.content = content;
        this.requestId = requestId;
    }

    public static VersionUpload fromBytes(DocumentBridge bridge, UUID documentId, byte[] content) {
        return new VersionUpload(bridge, documentId, UploadContent.bytes(content), null);
    }

    public static VersionUpload fromStream(DocumentBridge bridge, UUID documentId, InputStream content, ContentSha256 checksum) {
        return new VersionUpload(bridge, documentId, UploadContent.stream(content, checksum), null);
    }

    public VersionUpload requestId(UUID key) {
        return new VersionUpload(bridge, documentId, content, Objects.requireNonNull(key, "Request id must not be null"));
    }

    /**
     * Adds one content version without changing document metadata or revision.
     * Each call uses a new key unless {@link #requestId(UUID)} was supplied.
     * Matching keyed replays may return pending or failed snapshots without consuming
     * content. Caller-owned input streams remain open. The returned continuation
     * selects this upload's version without changing earlier document contexts.
     *
     * @throws IOException if uploading or verifying content fails
     */
    public DocumentResult<DocumentVersion> save() throws IOException {
        UUID key = requestId == null ? UUID.randomUUID() : requestId;
        try (var input = content.open()) {
            return DocumentResult.uploaded(bridge, bridge.addVersion(new AddDocumentVersion(key, documentId, content.checksum(), input.stream())));
        }
    }

    /**
     * Saves once and invokes the callback on that version's document continuation.
     * Runs on normally returning pending/failed replays too; save exceptions skip it.
     * Callback exceptions propagate without undoing earlier commits. Replaying an
     * upload repeats its callback. Returns the original version snapshot and context.
     *
     * @throws IOException if saving or the callback fails with an I/O error
     * @throws NullPointerException if the callback is null, before saving
     */
    public DocumentResult<DocumentVersion> saveAndThen(DocumentAction action) throws IOException {
        Objects.requireNonNull(action, "Document action must not be null");
        return save().andThen(action);
    }
}
