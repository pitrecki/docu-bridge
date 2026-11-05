package io.github.pitrecki.dokubridge.core.application;

import io.github.pitrecki.dokubridge.core.domain.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.domain.exception.ContentChecksumMismatchException;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentContentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentContentUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentVersionNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.exception.UploadChecksumUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.exception.UploadRequestNotFoundException;
import io.github.pitrecki.dokubridge.core.port.in.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.Archive;
import io.github.pitrecki.dokubridge.core.port.in.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.in.Delete;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.in.ReconcileUpload;
import io.github.pitrecki.dokubridge.core.port.in.UpdateMetadata;
import io.github.pitrecki.dokubridge.core.port.out.BusinessDocumentRepository;
import io.github.pitrecki.dokubridge.core.port.out.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.DocumentLifecycleRepository;
import io.github.pitrecki.dokubridge.core.port.out.DocumentUploadRepository;
import io.github.pitrecki.dokubridge.core.port.out.DocumentVersionRepository;
import io.github.pitrecki.dokubridge.core.port.out.UploadReservation;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DefaultDocumentBridge implements DocumentBridge {
    private final BusinessDocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentContentStore content;
    private final DocumentUploadRepository uploads;
    private final DocumentLifecycleRepository lifecycle;

    public DefaultDocumentBridge(BusinessDocumentRepository documents, DocumentVersionRepository versions,
                                 DocumentContentStore content) {
        this.documents = Objects.requireNonNull(documents, "Document repository must not be null");
        this.versions = Objects.requireNonNull(versions, "Version repository must not be null");
        this.content = Objects.requireNonNull(content, "Content store must not be null");
        this.uploads = null;
        this.lifecycle = null;
    }

    public DefaultDocumentBridge(BusinessDocumentRepository documents, DocumentVersionRepository versions,
                                 DocumentContentStore content, DocumentUploadRepository uploads) {
        this.documents = Objects.requireNonNull(documents, "Document repository must not be null");
        this.versions = Objects.requireNonNull(versions, "Version repository must not be null");
        this.content = Objects.requireNonNull(content, "Content store must not be null");
        this.uploads = Objects.requireNonNull(uploads, "Upload repository must not be null");
        this.lifecycle = null;
    }

    public DefaultDocumentBridge(BusinessDocumentRepository documents, DocumentVersionRepository versions,
                                 DocumentContentStore content, DocumentUploadRepository uploads,
                                 DocumentLifecycleRepository lifecycle) {
        this.documents = Objects.requireNonNull(documents, "Document repository must not be null");
        this.versions = Objects.requireNonNull(versions, "Version repository must not be null");
        this.content = Objects.requireNonNull(content, "Content store must not be null");
        this.uploads = Objects.requireNonNull(uploads, "Upload repository must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "Lifecycle repository must not be null");
    }

    @Override
    public DocumentVersion reconcileUpload(ReconcileUpload command) throws IOException {
        Objects.requireNonNull(command, "Reconciliation command must not be null");
        var repository = uploadRepository();
        var candidate = repository.inspectForRecovery(command.requestId(), command.versionId());
        var version = candidate.version();
        if (!version.id().equals(command.versionId())) {
            throw new UploadRequestNotFoundException(command.requestId(), command.versionId());
        }
        if (version.state() != DocumentVersionState.PENDING) return version;
        var expected = candidate.expectedChecksum()
                .orElseThrow(() -> new UploadChecksumUnavailableException(command.requestId()));
        InputStream stored;
        try {
            stored = content.open(version.id());
        } catch (DocumentContentNotFoundException missing) {
            return repository.inspectForRecovery(command.requestId(), command.versionId()).version();
        }
        var digest = UploadFingerprint.sha256();
        try (var input = stored) {
            new DigestInputStream(input, digest).transferTo(OutputStream.nullOutputStream());
        }
        var outcome = expected.value().equals(HexFormat.of().formatHex(digest.digest()))
                ? DocumentVersionState.AVAILABLE : DocumentVersionState.FAILED;
        return repository.completeRecovery(command.requestId(), version.id(), outcome);
    }

    @Override
    public BusinessDocument updateMetadata(UpdateMetadata command) {
        Objects.requireNonNull(command, "Metadata command must not be null");
        return lifecycleRepository().updateMetadata(command.documentId(), command.expectedRevision(), command.metadata());
    }

    @Override
    public BusinessDocument archive(Archive command) {
        Objects.requireNonNull(command, "Archive command must not be null");
        return lifecycleRepository().archive(command.documentId());
    }

    @Override
    public void delete(Delete command) {
        Objects.requireNonNull(command, "Delete command must not be null");
        lifecycleRepository().delete(command.documentId());
    }

    private DocumentLifecycleRepository lifecycleRepository() {
        if (lifecycle == null) throw new UnsupportedOperationException("Document lifecycle operations require a DocumentLifecycleRepository");
        return lifecycle;
    }

    @Override
    public DocumentVersion attach(AttachDocument command) throws IOException {
        Objects.requireNonNull(command, "Attach command must not be null");
        var repository = uploadRepository();
        var reservation = repository.reserveAttach(command.requestId(), UploadFingerprint.attach(command),
                command.owner(), command.metadata(), command.checksum());
        return upload(command.requestId(), reservation, command.checksum(), command.content());
    }

    @Override
    public DocumentVersion addVersion(AddDocumentVersion command) throws IOException {
        Objects.requireNonNull(command, "Version command must not be null");
        var repository = uploadRepository();
        var reservation = repository.reserveVersion(command.requestId(), UploadFingerprint.version(command), command.documentId(), command.checksum());
        return upload(command.requestId(), reservation, command.checksum(), command.content());
    }

    private DocumentUploadRepository uploadRepository() {
        if (uploads == null) {
            throw new UnsupportedOperationException("Uploads require a DocumentUploadRepository");
        }
        return uploads;
    }

    private DocumentVersion upload(UUID requestId, UploadReservation reservation,
                                   ContentSha256 checksum, InputStream source) throws IOException {
        if (!reservation.acquired()) {
            return reservation.version();
        }
        var version = reservation.version();
        var digest = UploadFingerprint.sha256();
        try {
            content.store(version.id(), new DigestInputStream(source, digest));
            if (!HexFormat.of().formatHex(digest.digest()).equals(checksum.value())) {
                throw new ContentChecksumMismatchException(version.id());
            }
        } catch (IOException | RuntimeException failure) {
            try {
                uploads.complete(requestId, version.id(), DocumentVersionState.FAILED);
            } catch (RuntimeException finalizationFailure) {
                if (failure != finalizationFailure) failure.addSuppressed(finalizationFailure);
            }
            throw failure;
        }
        // A failed finalization must not trigger another content write or a guessed FAILED transition.
        return uploads.complete(requestId, version.id(), DocumentVersionState.AVAILABLE);
    }

    @Override
    public Optional<BusinessDocument> get(UUID documentId) {
        Objects.requireNonNull(documentId, "Document id must not be null");
        return documents.findById(documentId)
                .filter(document -> document.id().equals(documentId))
                .filter(document -> document.state() != BusinessDocumentState.DELETED);
    }

    @Override
    public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) {
        Objects.requireNonNull(owner, "Document owner must not be null");
        return documents.findByOwner(owner).stream()
                .filter(document -> document.owner().equals(owner))
                .filter(document -> document.state() != BusinessDocumentState.DELETED)
                .toList();
    }

    @Override
    public InputStream getContent(UUID documentId, UUID versionId) throws IOException {
        Objects.requireNonNull(documentId, "Document id must not be null");
        Objects.requireNonNull(versionId, "Version id must not be null");
        get(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        var version = versions.findById(documentId, versionId)
                .filter(candidate -> candidate.documentId().equals(documentId) && candidate.id().equals(versionId))
                .orElseThrow(() -> new DocumentVersionNotFoundException(documentId, versionId));
        if (version.state() != DocumentVersionState.AVAILABLE) {
            throw new DocumentContentUnavailableException(versionId, version.state());
        }
        return content.open(versionId);
    }
}
