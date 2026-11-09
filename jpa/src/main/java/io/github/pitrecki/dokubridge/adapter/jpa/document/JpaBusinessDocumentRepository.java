package io.github.pitrecki.dokubridge.adapter.jpa.document;

import io.github.pitrecki.dokubridge.adapter.jpa.document.persistence.BusinessDocumentRow;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
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
        return read.execute(status -> entities.createQuery("""
                select new io.github.pitrecki.dokubridge.adapter.jpa.document.persistence.BusinessDocumentRow(
                    d.id, d.ownerType, d.ownerId, d.metadata, d.state, d.revision)
                from BusinessDocumentEntity d where d.id = :id
                """, BusinessDocumentRow.class).setParameter("id", documentId).getResultList().stream()
                .findFirst().map(BusinessDocumentRow::snapshot));
    }

    @Override
    public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) {
        Objects.requireNonNull(owner);
        return read.execute(status -> entities.createQuery("""
                select new io.github.pitrecki.dokubridge.adapter.jpa.document.persistence.BusinessDocumentRow(
                    d.id, d.ownerType, d.ownerId, d.metadata, d.state, d.revision)
                from BusinessDocumentEntity d where d.ownerType = :type and d.ownerId = :id order by d.id
                """, BusinessDocumentRow.class).setParameter("type", owner.type()).setParameter("id", owner.id())
                .getResultList().stream().map(BusinessDocumentRow::snapshot).toList());
    }

    @Override
    public List<BusinessDocument> findVisibleByOwner(DocumentOwnerRef owner, int limit, UUID afterDocumentId) {
        Objects.requireNonNull(owner, "Document owner must not be null");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Owner lookup limit must be between 1 and 1000");
        return read.execute(status -> {
            var query = entities.createQuery("""
                    select new io.github.pitrecki.dokubridge.adapter.jpa.document.persistence.BusinessDocumentRow(
                        d.id, d.ownerType, d.ownerId, d.metadata, d.state, d.revision)
                    from BusinessDocumentEntity d
                    where d.ownerType = :type and d.ownerId = :id and d.state in :visible
                    """ + (afterDocumentId == null ? "" : " and d.id > :after")
                    + " order by d.id", BusinessDocumentRow.class)
                    .setParameter("type", owner.type()).setParameter("id", owner.id())
                    .setParameter("visible", List.of(BusinessDocumentState.ACTIVE, BusinessDocumentState.ARCHIVED))
                    .setMaxResults(limit);
            if (afterDocumentId != null) query.setParameter("after", afterDocumentId);
            return query.getResultList().stream().map(BusinessDocumentRow::snapshot).toList();
        });
    }
}
