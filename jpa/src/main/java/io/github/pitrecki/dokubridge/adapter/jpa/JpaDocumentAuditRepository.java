package io.github.pitrecki.dokubridge.adapter.jpa;

import io.github.pitrecki.dokubridge.adapter.jpa.persistence.DocumentAuditEntity;
import io.github.pitrecki.dokubridge.core.domain.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.port.out.DocumentAuditRepository;
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
        return read.execute(status -> entities.createQuery(
                "select a from DocumentAuditEntity a where a.documentId = :id order by a.position",
                DocumentAuditEntity.class).setParameter("id", documentId).getResultList().stream()
                .map(DocumentAuditEntity::snapshot).toList());
    }
}
