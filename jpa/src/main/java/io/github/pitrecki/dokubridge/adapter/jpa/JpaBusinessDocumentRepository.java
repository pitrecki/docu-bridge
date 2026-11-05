package io.github.pitrecki.dokubridge.adapter.jpa;

import io.github.pitrecki.dokubridge.adapter.jpa.persistence.BusinessDocumentEntity;
import io.github.pitrecki.dokubridge.core.domain.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.port.out.BusinessDocumentRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JpaBusinessDocumentRepository implements BusinessDocumentRepository {
    private final EntityManager entities;
    private final TransactionTemplate read;

    public JpaBusinessDocumentRepository(EntityManager entities, PlatformTransactionManager transactions) {
        this.entities = Objects.requireNonNull(entities);
        this.read = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.read.setReadOnly(true);
    }

    @Override
    public Optional<BusinessDocument> findById(UUID documentId) {
        Objects.requireNonNull(documentId);
        return read.execute(status -> Optional.ofNullable(entities.find(BusinessDocumentEntity.class, documentId))
                .map(BusinessDocumentEntity::snapshot));
    }

    @Override
    public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) {
        Objects.requireNonNull(owner);
        return read.execute(status -> entities.createQuery(
                "select d from BusinessDocumentEntity d where d.ownerType = :type and d.ownerId = :id order by d.id",
                BusinessDocumentEntity.class).setParameter("type", owner.type()).setParameter("id", owner.id())
                .getResultList().stream().map(BusinessDocumentEntity::snapshot).toList());
    }
}
