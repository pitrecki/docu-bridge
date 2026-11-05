package io.github.pitrecki.dokubridge.adapter.jpa;

import io.github.pitrecki.dokubridge.adapter.jpa.persistence.DocumentVersionEntity;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import io.github.pitrecki.dokubridge.core.port.out.DocumentVersionRepository;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JpaDocumentVersionRepository implements DocumentVersionRepository {
    private final EntityManager entities;
    private final TransactionTemplate read;

    public JpaDocumentVersionRepository(EntityManager entities, PlatformTransactionManager transactions) {
        this.entities = Objects.requireNonNull(entities);
        this.read = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.read.setReadOnly(true);
    }

    @Override
    public Optional<DocumentVersion> findById(UUID documentId, UUID versionId) {
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(versionId);
        return read.execute(status -> Optional.ofNullable(entities.find(DocumentVersionEntity.class, versionId))
                .filter(version -> version.documentId().equals(documentId)).map(DocumentVersionEntity::snapshot));
    }
}
