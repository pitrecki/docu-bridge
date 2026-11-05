package io.github.pitrecki.dokubridge.core.domain.exception;

import java.util.UUID;

public final class UploadRequestConflictException extends IllegalStateException {
    public UploadRequestConflictException(UUID requestId) {
        super("Upload request conflicts with an existing request: " + requestId);
    }
}
