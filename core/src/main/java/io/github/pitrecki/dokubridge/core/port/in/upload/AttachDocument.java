package io.github.pitrecki.dokubridge.core.port.in.upload;

import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentMetadata;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** The caller owns content and must close it, including on replay. */
public record AttachDocument(UUID requestId, DocumentOwnerRef owner, Map<String, Object> metadata,
                             ContentSha256 checksum, InputStream content) {
    public AttachDocument {
        Objects.requireNonNull(requestId, "Request id must not be null");
        Objects.requireNonNull(owner, "Owner must not be null");
        metadata = new DocumentMetadata(metadata).values();
        Objects.requireNonNull(checksum, "Checksum must not be null");
        Objects.requireNonNull(content, "Content must not be null");
    }
}
