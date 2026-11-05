package io.github.pitrecki.dokubridge.core.port.in;

import java.util.Objects;
import java.util.UUID;

/** Explicitly checks an upload's existing immutable content; it does not retry a content write. */
public record ReconcileUpload(UUID requestId, UUID versionId) {
    public ReconcileUpload {
        Objects.requireNonNull(requestId, "Request id must not be null");
        Objects.requireNonNull(versionId, "Version id must not be null");
    }
}
