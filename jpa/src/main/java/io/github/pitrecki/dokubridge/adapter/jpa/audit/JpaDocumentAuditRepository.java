package io.github.pitrecki.dokubridge.adapter.jpa.audit;

import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.port.out.audit.DocumentAuditRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JpaDocumentAuditRepository implements DocumentAuditRepository {
    private final EntityManager entities;
    private final TransactionTemplate read;

    public JpaDocumentAuditRepository(EntityManager entities, PlatformTransactionManager transactions) {
        this.entities = Objects.requireNonNull(entities);
        this.read = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.read.setReadOnly(true);
    }

    @Override
    public List<DocumentAuditEntry> findByDocument(UUID documentId) {
        Objects.requireNonNull(documentId);
        return read.execute(status -> entities.createQuery("""
                select new io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry(
                    a.id, a.documentId, a.versionId, a.requestId, a.action, a.occurredAt)
                from DocumentAuditEntity a where a.documentId = :id order by a.position
                """, DocumentAuditEntry.class).setParameter("id", documentId).getResultList().stream().toList());
    }

    @Override
    public List<DocumentAuditEntry> findByDocument(UUID documentId, int limit, UUID afterEventId) {
        Objects.requireNonNull(documentId);
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Audit history limit must be between 1 and 1000");
        return read.execute(status -> {
            Long afterPosition = null;
            if (afterEventId != null) {
                afterPosition = entities.createQuery("""
                        select a.position from DocumentAuditEntity a
                        where a.documentId = :document and a.id = :event
                        """, Long.class).setParameter("document", documentId).setParameter("event", afterEventId)
                        .getResultList().stream().findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Audit cursor does not belong to the requested document"));
            }
            var query = entities.createQuery("""
                    select new io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry(
                        a.id, a.documentId, a.versionId, a.requestId, a.action, a.occurredAt)
                    from DocumentAuditEntity a where a.documentId = :document
                    """ + (afterPosition == null ? "" : " and a.position > :after")
                    + " order by a.position", DocumentAuditEntry.class)
                    .setParameter("document", documentId).setMaxResults(limit);
            if (afterPosition != null) query.setParameter("after", afterPosition);
            return query.getResultList().stream().toList();
        });
    }
}
