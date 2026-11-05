package io.github.pitrecki.dokubridge.core.domain.content.exception;

import java.io.IOException;
import java.util.UUID;

public final class DocumentContentAlreadyExistsException extends IOException {
    public DocumentContentAlreadyExistsException(UUID versionId, Throwable cause) {
        super("Content already exists for version " + versionId, cause);
    }
}
