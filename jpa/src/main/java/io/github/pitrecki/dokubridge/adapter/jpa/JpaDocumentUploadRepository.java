package io.github.pitrecki.dokubridge.adapter.jpa;

import io.github.pitrecki.dokubridge.adapter.jpa.persistence.BusinessDocumentEntity;
import io.github.pitrecki.dokubridge.adapter.jpa.persistence.DocumentAuditEntity;
import io.github.pitrecki.dokubridge.adapter.jpa.persistence.DocumentVersionEntity;
import io.github.pitrecki.dokubridge.adapter.jpa.persistence.UploadRequestEntity;
import io.github.pitrecki.dokubridge.core.domain.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.exception.UploadRequestConflictException;
import io.github.pitrecki.dokubridge.core.port.out.DocumentUploadRepository;
import io.github.pitrecki.dokubridge.core.port.out.UploadReservation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JpaDocumentUploadRepository implements DocumentUploadRepository {
    private final EntityManager entities;
    private final TransactionTemplate write;

    public JpaDocumentUploadRepository(EntityManager entities, PlatformTransactionManager transactions) {
        this.entities = Objects.requireNonNull(entities);
        this.write = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public UploadReservation reserveAttach(UUID requestId, String fingerprint, DocumentOwnerRef owner,
                                           Map<String, Object> metadata) {
        requireIndependentCall();
        Objects.requireNonNull(owner);
        Objects.requireNonNull(metadata);
        return write.execute(status -> {
            UUID versionId = UUID.randomUUID();
            if (!claim(requestId, fingerprint, versionId)) {
                return replay(requestId, fingerprint);
            }
            UUID documentId = UUID.randomUUID();
            entities.persist(new BusinessDocumentEntity(documentId, owner, metadata));
            entities.flush();
            var version = new DocumentVersionEntity(versionId, documentId, 1);
            entities.persist(version);
            entities.flush();
            audit(version, requestId, DocumentAuditAction.DOCUMENT_ATTACHED);
            return new UploadReservation(version.snapshot(), true);
        });
    }

    @Override
    public UploadReservation reserveVersion(UUID requestId, String fingerprint, UUID documentId) {
        requireIndependentCall();
        Objects.requireNonNull(documentId);
        return write.execute(status -> {
            UUID versionId = UUID.randomUUID();
            if (!claim(requestId, fingerprint, versionId)) {
                return replay(requestId, fingerprint);
            }
            var document = entities.find(BusinessDocumentEntity.class, documentId, LockModeType.PESSIMISTIC_WRITE);
            if (document == null || document.state() == BusinessDocumentState.DELETED) {
                throw new DocumentNotFoundException(documentId);
            }
            var version = new DocumentVersionEntity(versionId, documentId, document.allocateVersionNumber());
            entities.persist(version);
            entities.flush();
            audit(version, requestId, DocumentAuditAction.VERSION_ADDED);
            return new UploadReservation(version.snapshot(), true);
        });
    }

    @Override
    public DocumentVersion complete(UUID requestId, UUID versionId, DocumentVersionState outcome) {
        requireIndependentCall();
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(versionId);
        Objects.requireNonNull(outcome);
        if (outcome == DocumentVersionState.PENDING) {
            throw new IllegalArgumentException("Final outcome must be AVAILABLE or FAILED");
        }
        return write.execute(status -> {
            var request = entities.find(UploadRequestEntity.class, requestId);
            if (request == null || !request.versionId().equals(versionId)) {
                throw new IllegalArgumentException("Version does not belong to the upload request");
            }
            var version = entities.find(DocumentVersionEntity.class, versionId, LockModeType.PESSIMISTIC_WRITE);
            version.finalizeAs(outcome);
            audit(version, requestId, outcome == DocumentVersionState.AVAILABLE
                    ? DocumentAuditAction.UPLOAD_SUCCEEDED : DocumentAuditAction.UPLOAD_FAILED);
            return version.snapshot();
        });
    }

    private boolean claim(UUID requestId, String fingerprint, UUID versionId) {
        Objects.requireNonNull(requestId);
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Request fingerprint must be a SHA-256 hex string");
        }
        // PostgreSQL waits for an in-flight claim, then resolves uniqueness without aborting this transaction.
        return entities.createNativeQuery("""
                insert into dokubridge_upload_requests (id, fingerprint, version_id)
                values (:request, :fingerprint, :version) on conflict (id) do nothing
                """).setParameter("request", requestId).setParameter("fingerprint", fingerprint)
                .setParameter("version", versionId).executeUpdate() == 1;
    }

    private UploadReservation replay(UUID requestId, String fingerprint) {
        var request = entities.find(UploadRequestEntity.class, requestId);
        if (!request.fingerprint().equals(fingerprint)) {
            throw new UploadRequestConflictException(requestId);
        }
        var version = entities.find(DocumentVersionEntity.class, request.versionId());
        return new UploadReservation(version.snapshot(), false);
    }

    private void audit(DocumentVersionEntity version, UUID requestId, DocumentAuditAction action) {
        entities.persist(new DocumentAuditEntity(version.documentId(), version.id(), requestId, action));
    }

    private void requireIndependentCall() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Upload operations must not run inside a host transaction");
        }
    }
}
