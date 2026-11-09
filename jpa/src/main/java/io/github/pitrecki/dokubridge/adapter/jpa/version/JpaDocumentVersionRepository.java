package io.github.pitrecki.dokubridge.adapter.jpa.version;

import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.port.out.version.DocumentVersionRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
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
        return read.execute(status -> entities.createQuery("""
                select new io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion(v.id, v.documentId, v.number, v.state)
                from DocumentVersionEntity v where v.documentId = :document and v.id = :version
                """, DocumentVersion.class).setParameter("document", documentId).setParameter("version", versionId)
                .getResultList().stream().findFirst());
    }

    @Override
    public List<DocumentVersion> findByDocument(UUID documentId) {
        Objects.requireNonNull(documentId);
        return read.execute(status -> entities.createQuery("""
                select new io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion(v.id, v.documentId, v.number, v.state)
                from DocumentVersionEntity v where v.documentId = :document order by v.number
                """, DocumentVersion.class).setParameter("document", documentId).getResultList().stream().toList());
    }

    @Override
    public List<DocumentVersion> findByDocument(UUID documentId, int limit, int afterNumber) {
        Objects.requireNonNull(documentId);
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Version history limit must be between 1 and 1000");
        if (afterNumber < 0) throw new IllegalArgumentException("Version number cursor must not be negative");
        return read.execute(status -> List.copyOf(entities.createQuery("""
                select new io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion(v.id, v.documentId, v.number, v.state)
                from DocumentVersionEntity v
                where v.documentId = :document and v.number > :after order by v.number
                """, DocumentVersion.class).setParameter("document", documentId).setParameter("after", afterNumber)
                .setMaxResults(limit).getResultList()));
    }
}
