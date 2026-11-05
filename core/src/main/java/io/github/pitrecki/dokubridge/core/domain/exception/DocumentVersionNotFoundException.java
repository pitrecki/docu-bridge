package io.github.pitrecki.dokubridge.core.domain.exception;

import java.util.NoSuchElementException;
import java.util.UUID;

public final class DocumentVersionNotFoundException extends NoSuchElementException {
    public DocumentVersionNotFoundException(UUID documentId, UUID versionId) {
        super("Version " + versionId + " not found for document " + documentId);
    }
}
