package io.github.pitrecki.dokubridge.core.port.out.audit;

import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import java.util.List;
import java.util.UUID;

/** Returns committed audit entries in insertion order. Authorization belongs to the host. */
public interface DocumentAuditRepository {
    List<DocumentAuditEntry> findByDocument(UUID documentId);

    /**
     * Returns a bounded page scoped to the document, including retained deleted documents.
     * Order by insertion position, after the exclusive event-id cursor; null starts the first page.
     * Reject unknown/foreign cursors with IllegalArgumentException. Limit must be 1..1000.
     * Apply scope, cursor and limit at the data source, without loading the full audit.
     */
    default List<DocumentAuditEntry> findByDocument(UUID documentId, int limit, UUID afterEventId) {
        throw new UnsupportedOperationException("Paged audit history is not supported by this repository");
    }
}
