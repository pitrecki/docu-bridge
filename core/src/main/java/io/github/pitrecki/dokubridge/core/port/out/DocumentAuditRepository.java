package io.github.pitrecki.dokubridge.core.port.out;

import io.github.pitrecki.dokubridge.core.domain.DocumentAuditEntry;
import java.util.List;
import java.util.UUID;

/** Returns committed audit entries in insertion order. Authorization belongs to the host. */
public interface DocumentAuditRepository {
    List<DocumentAuditEntry> findByDocument(UUID documentId);
}
