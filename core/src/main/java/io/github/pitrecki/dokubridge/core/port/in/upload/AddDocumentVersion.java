package io.github.pitrecki.dokubridge.core.port.in.upload;

import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/** Adds content without changing document metadata. The caller owns content. */
public record AddDocumentVersion(UUID requestId, UUID documentId, ContentSha256 checksum, InputStream content) {
    public AddDocumentVersion {
        Objects.requireNonNull(requestId, "Request id must not be null");
        Objects.requireNonNull(documentId, "Document id must not be null");
        Objects.requireNonNull(checksum, "Checksum must not be null");
        Objects.requireNonNull(content, "Content must not be null");
    }
}
