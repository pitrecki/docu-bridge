package io.github.pitrecki.dokubridge.core.domain.exception;

import java.util.NoSuchElementException;
import java.util.UUID;

public final class DocumentNotFoundException extends NoSuchElementException {
    public DocumentNotFoundException(UUID documentId) {
        super("Document not found: " + documentId);
    }
}
