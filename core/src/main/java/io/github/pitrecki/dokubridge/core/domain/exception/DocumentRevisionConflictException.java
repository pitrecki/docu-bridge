package io.github.pitrecki.dokubridge.core.domain.exception;

import java.util.UUID;

public final class DocumentRevisionConflictException extends IllegalStateException {
    public DocumentRevisionConflictException(UUID documentId, long expected, long actual) {
        super("Revision conflict for document " + documentId + ": expected " + expected + ", actual " + actual);
    }
}
