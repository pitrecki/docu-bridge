package io.github.pitrecki.dokubridge.core.port.out.document;

import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import java.util.Map;
import java.util.UUID;

/** Each operation atomically commits its document change and audit, without content I/O. */
public interface DocumentLifecycleRepository {
    BusinessDocument updateMetadata(UUID documentId, long expectedRevision, Map<String, Object> metadata);

    BusinessDocument archive(UUID documentId);

    /** Logical deletion; repeat deletion of an existing deleted document is a no-op. */
    void delete(UUID documentId);
}
