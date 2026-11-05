package io.github.pitrecki.dokubridge.core.port.out.audit;

import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import java.util.List;
import java.util.UUID;

/** Returns committed audit entries in insertion order. Authorization belongs to the host. */
public interface DocumentAuditRepository {
    List<DocumentAuditEntry> findByDocument(UUID documentId);
}
