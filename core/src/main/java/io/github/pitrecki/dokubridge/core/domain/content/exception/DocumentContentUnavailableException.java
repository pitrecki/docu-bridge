package io.github.pitrecki.dokubridge.core.domain.content.exception;

import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import java.util.UUID;

public final class DocumentContentUnavailableException extends IllegalStateException {
    public DocumentContentUnavailableException(UUID versionId, DocumentVersionState state) {
        super("Content is unavailable for version " + versionId + " in state " + state);
    }
}
