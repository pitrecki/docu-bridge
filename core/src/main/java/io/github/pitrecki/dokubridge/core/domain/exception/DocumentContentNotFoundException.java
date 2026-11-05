package io.github.pitrecki.dokubridge.core.domain.exception;

import java.io.IOException;
import java.util.UUID;

public final class DocumentContentNotFoundException extends IOException {
    public DocumentContentNotFoundException(UUID versionId, Throwable cause) {
        super("Content not found for version " + versionId, cause);
    }
}
