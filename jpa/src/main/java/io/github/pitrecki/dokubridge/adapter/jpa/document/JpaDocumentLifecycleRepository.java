package io.github.pitrecki.dokubridge.adapter.jpa.document;

import io.github.pitrecki.dokubridge.adapter.jpa.audit.persistence.DocumentAuditEntity;
import io.github.pitrecki.dokubridge.adapter.jpa.document.persistence.BusinessDocumentEntity;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentMetadata;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.port.out.document.DocumentLifecycleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JpaDocumentLifecycleRepository implements DocumentLifecycleRepository {
    private final EntityManager entities;
    private final TransactionTemplate write;

    public JpaDocumentLifecycleRepository(EntityManager entities, PlatformTransactionManager transactions) {
        this.entities = Objects.requireNonNull(entities);
        this.write = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public BusinessDocument updateMetadata(UUID documentId, long expectedRevision, Map<String, Object> metadata) {
        requireIndependentCall();
        Objects.requireNonNull(documentId);
        if (expectedRevision < 0) throw new IllegalArgumentException("Expected revision must not be negative");
        var replacement = new DocumentMetadata(metadata).values();
        return write.execute(status -> {
            var entity = lock(documentId);
            var before = entity.snapshot();
            var after = before.updateMetadata(expectedRevision, replacement);
            persistChange(entity, before, after, DocumentAuditAction.METADATA_UPDATED);
            return after;
        });
    }

    @Override
    public BusinessDocument archive(UUID documentId) {
        requireIndependentCall();
        Objects.requireNonNull(documentId);
        return write.execute(status -> {
            var entity = lock(documentId);
            var before = entity.snapshot();
            var after = before.archive();
            persistChange(entity, before, after, DocumentAuditAction.DOCUMENT_ARCHIVED);
            return after;
        });
    }

    @Override
    public void delete(UUID documentId) {
        requireIndependentCall();
        Objects.requireNonNull(documentId);
        write.executeWithoutResult(status -> {
            var entity = lock(documentId);
            var before = entity.snapshot();
            persistChange(entity, before, before.delete(), DocumentAuditAction.DOCUMENT_DELETED);
        });
    }

    private BusinessDocumentEntity lock(UUID documentId) {
        var entity = entities.find(BusinessDocumentEntity.class, documentId, LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) throw new DocumentNotFoundException(documentId);
        // A pre-bound host EntityManager can retain an older snapshot despite acquiring the row lock.
        entities.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return entity;
    }

    private void persistChange(BusinessDocumentEntity entity, BusinessDocument before, BusinessDocument after,
                               DocumentAuditAction action) {
        if (after.revision() == before.revision()) return;
        entity.applyDocumentChange(after);
        entities.persist(new DocumentAuditEntity(after.id(), action));
    }

    private void requireIndependentCall() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Lifecycle operations must not run inside a host transaction");
        }
    }
}
