package io.github.pitrecki.dokubridge.core.domain.upload.exception;

import java.util.UUID;

public final class UploadChecksumUnavailableException extends IllegalStateException {
    public UploadChecksumUnavailableException(UUID requestId) {
        super("Original checksum is not recorded for upload request " + requestId);
    }
}
