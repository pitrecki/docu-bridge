package io.github.pitrecki.dokubridge.core.domain.content.exception;

import java.io.IOException;
import java.util.UUID;

public final class ContentChecksumMismatchException extends IOException {
    public ContentChecksumMismatchException(UUID versionId) {
        super("Content checksum mismatch for version " + versionId);
    }
}
