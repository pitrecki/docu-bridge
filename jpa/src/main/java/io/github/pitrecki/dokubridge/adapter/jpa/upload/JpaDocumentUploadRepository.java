package io.github.pitrecki.dokubridge.adapter.jpa.upload;

import io.github.pitrecki.dokubridge.adapter.jpa.audit.persistence.DocumentAuditEntity;
import io.github.pitrecki.dokubridge.adapter.jpa.document.persistence.BusinessDocumentEntity;
import io.github.pitrecki.dokubridge.adapter.jpa.upload.persistence.UploadRequestEntity;
import io.github.pitrecki.dokubridge.adapter.jpa.version.persistence.DocumentVersionEntity;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.upload.exception.UploadChecksumUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.upload.exception.UploadRequestConflictException;
import io.github.pitrecki.dokubridge.core.domain.upload.exception.UploadRequestNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.out.upload.DocumentUploadRepository;
import io.github.pitrecki.dokubridge.core.port.out.upload.UploadRecoveryCandidate;
import io.github.pitrecki.dokubridge.core.port.out.upload.UploadReservation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JpaDocumentUploadRepository implements DocumentUploadRepository {
    private final EntityManager entities;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public JpaDocumentUploadRepository(EntityManager entities, PlatformTransactionManager transactions) {
        this.entities = Objects.requireNonNull(entities);
        this.write = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.read = new TransactionTemplate(transactions);
        this.read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.read.setReadOnly(true);
    }

    @Override
    public UploadReservation reserveAttach(UUID requestId, String fingerprint, DocumentOwnerRef owner,
                                           Map<String, Object> metadata) {
        return reserveAttachment(requestId, fingerprint, owner, metadata, null);
    }

    @Override
    public UploadReservation reserveAttach(UUID requestId, String fingerprint, DocumentOwnerRef owner,
                                           Map<String, Object> metadata, ContentSha256 checksum) {
        return reserveAttachment(requestId, fingerprint, owner, metadata, Objects.requireNonNull(checksum).value());
    }

    private UploadReservation reserveAttachment(UUID requestId, String fingerprint, DocumentOwnerRef owner,
                                                Map<String, Object> metadata, String checksum) {
        requireIndependentCall();
        Objects.requireNonNull(owner);
        Objects.requireNonNull(metadata);
        return write.execute(status -> {
            UUID versionId = UUID.randomUUID();
            if (!claim(requestId, fingerprint, versionId, checksum)) {
                return replay(requestId, fingerprint, checksum);
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
        return reserveNextVersion(requestId, fingerprint, documentId, null);
    }

    @Override
    public UploadReservation reserveVersion(UUID requestId, String fingerprint, UUID documentId, ContentSha256 checksum) {
        return reserveNextVersion(requestId, fingerprint, documentId, Objects.requireNonNull(checksum).value());
    }

    private UploadReservation reserveNextVersion(UUID requestId, String fingerprint, UUID documentId, String checksum) {
        requireIndependentCall();
        Objects.requireNonNull(documentId);
        return write.execute(status -> {
            UUID versionId = UUID.randomUUID();
            if (!claim(requestId, fingerprint, versionId, checksum)) {
                return replay(requestId, fingerprint, checksum);
            }
            var document = entities.find(BusinessDocumentEntity.class, documentId, LockModeType.PESSIMISTIC_WRITE);
            if (document == null) throw new DocumentNotFoundException(documentId);
            // Locking a first-level-cached entity does not reload its fields.
            entities.refresh(document, LockModeType.PESSIMISTIC_WRITE);
            if (document.state() == BusinessDocumentState.DELETED) {
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
        return finalizeVersion(requestId, versionId, outcome, false);
    }

    @Override
    public UploadRecoveryCandidate inspectForRecovery(UUID requestId, UUID versionId) {
        requireIndependentCall();
        return read.execute(status -> {
            var request = requestForVersion(requestId, versionId);
            var version = entities.find(DocumentVersionEntity.class, versionId);
            entities.refresh(version);
            return new UploadRecoveryCandidate(version.snapshot(),
                    Optional.ofNullable(request.expectedSha256()).map(ContentSha256::new));
        });
    }

    @Override
    public DocumentVersion completeRecovery(UUID requestId, UUID versionId, DocumentVersionState outcome) {
        return finalizeVersion(requestId, versionId, outcome, true);
    }

    private DocumentVersion finalizeVersion(UUID requestId, UUID versionId, DocumentVersionState outcome, boolean recovery) {
        requireIndependentCall();
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(versionId);
        Objects.requireNonNull(outcome);
        if (outcome == DocumentVersionState.PENDING) {
            throw new IllegalArgumentException("Final outcome must be AVAILABLE or FAILED");
        }
        return write.execute(status -> {
            var request = requestForVersion(requestId, versionId);
            var version = entities.find(DocumentVersionEntity.class, versionId, LockModeType.PESSIMISTIC_WRITE);
            entities.refresh(version, LockModeType.PESSIMISTIC_WRITE);
            if (version.snapshot().state() == outcome) return version.snapshot();
            if (recovery && request.expectedSha256() == null) {
                throw new UploadChecksumUnavailableException(requestId);
            }
            version.finalizeAs(outcome);
            var action = outcome == DocumentVersionState.AVAILABLE
                    ? (recovery ? DocumentAuditAction.UPLOAD_RECOVERED : DocumentAuditAction.UPLOAD_SUCCEEDED)
                    : (recovery ? DocumentAuditAction.UPLOAD_RECOVERY_FAILED : DocumentAuditAction.UPLOAD_FAILED);
            audit(version, requestId, action);
            return version.snapshot();
        });
    }

    private UploadRequestEntity requestForVersion(UUID requestId, UUID versionId) {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(versionId);
        var request = entities.find(UploadRequestEntity.class, requestId);
        if (request == null || !request.versionId().equals(versionId)) {
            throw new UploadRequestNotFoundException(requestId, versionId);
        }
        return request;
    }

    private boolean claim(UUID requestId, String fingerprint, UUID versionId, String checksum) {
        Objects.requireNonNull(requestId);
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Request fingerprint must be a SHA-256 hex string");
        }
        // PostgreSQL waits for an in-flight claim, then resolves uniqueness without aborting this transaction.
        return entities.createNativeQuery("""
                insert into dokubridge_upload_requests (id, fingerprint, version_id, expected_sha256)
                values (:request, :fingerprint, :version, cast(:checksum as varchar)) on conflict (id) do nothing
                """).setParameter("request", requestId).setParameter("fingerprint", fingerprint)
                .setParameter("version", versionId).setParameter("checksum", checksum).executeUpdate() == 1;
    }

    private UploadReservation replay(UUID requestId, String fingerprint, String checksum) {
        var request = entities.find(UploadRequestEntity.class, requestId);
        if (!request.fingerprint().equals(fingerprint)
                || (checksum != null && request.expectedSha256() != null && !checksum.equals(request.expectedSha256()))) {
            throw new UploadRequestConflictException(requestId);
        }
        var version = entities.find(DocumentVersionEntity.class, request.versionId());
        entities.refresh(version);
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
