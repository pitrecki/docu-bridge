package io.github.pitrecki.dokubridge.core.domain.exception;

import java.io.IOException;
import java.util.UUID;

public final class ContentChecksumMismatchException extends IOException {
    public ContentChecksumMismatchException(UUID versionId) {
        super("Content checksum mismatch for version " + versionId);
    }
}
