package io.github.pitrecki.dokubridge.core.domain.exception;

import java.util.UUID;

public final class UploadRequestNotFoundException extends IllegalArgumentException {
    public UploadRequestNotFoundException(UUID requestId, UUID versionId) {
        super("Upload request/version association not found: " + requestId + "/" + versionId);
    }
}
