package io.github.pitrecki.dokubridge.core.application;

import io.github.pitrecki.dokubridge.core.application.upload.DocumentUploads;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.domain.content.exception.DocumentContentUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.upload.PendingUpload;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.domain.version.exception.DocumentVersionNotFoundException;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.in.document.Archive;
import io.github.pitrecki.dokubridge.core.port.in.document.Delete;
import io.github.pitrecki.dokubridge.core.port.in.document.UpdateMetadata;
import io.github.pitrecki.dokubridge.core.port.in.upload.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.upload.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.in.upload.ReconcileUpload;
import io.github.pitrecki.dokubridge.core.port.out.audit.DocumentAuditRepository;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
import io.github.pitrecki.dokubridge.core.port.out.document.DocumentLifecycleRepository;
import io.github.pitrecki.dokubridge.core.port.out.upload.DocumentUploadRepository;
import io.github.pitrecki.dokubridge.core.port.out.version.DocumentVersionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DefaultDocumentBridge implements DocumentBridge {
    private final BusinessDocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentContentStore content;
    private final DocumentUploads uploads;
    private final DocumentLifecycleRepository lifecycle;
    private final DocumentAuditRepository audits;

    public DefaultDocumentBridge(BusinessDocumentRepository documents, DocumentVersionRepository versions,
                                 DocumentContentStore content) {
        this.documents = Objects.requireNonNull(documents, "Document repository must not be null");
        this.versions = Objects.requireNonNull(versions, "Version repository must not be null");
        this.content = Objects.requireNonNull(content, "Content store must not be null");
        this.uploads = null;
        this.lifecycle = null;
        this.audits = null;
    }

    /** Read-only composition with audit history; repository arguments precede content storage. */
    public DefaultDocumentBridge(BusinessDocumentRepository documents, DocumentVersionRepository versions,
                                 DocumentAuditRepository audits, DocumentContentStore content) {
        this.documents = Objects.requireNonNull(documents, "Document repository must not be null");
        this.versions = Objects.requireNonNull(versions, "Version repository must not be null");
        this.content = Objects.requireNonNull(content, "Content store must not be null");
        this.audits = Objects.requireNonNull(audits, "Audit repository must not be null");
        this.uploads = null;
        this.lifecycle = null;
    }

    public DefaultDocumentBridge(BusinessDocumentRepository documents, DocumentVersionRepository versions,
                                 DocumentContentStore content, DocumentUploadRepository uploads) {
        this.documents = Objects.requireNonNull(documents, "Document repository must not be null");
        this.versions = Objects.requireNonNull(versions, "Version repository must not be null");
        this.content = Objects.requireNonNull(content, "Content store must not be null");
        this.uploads = new DocumentUploads(this.content, Objects.requireNonNull(uploads, "Upload repository must not be null"));
        this.lifecycle = null;
        this.audits = null;
    }

    public DefaultDocumentBridge(BusinessDocumentRepository documents, DocumentVersionRepository versions,
                                 DocumentContentStore content, DocumentUploadRepository uploads,
                                 DocumentLifecycleRepository lifecycle) {
        this.documents = Objects.requireNonNull(documents, "Document repository must not be null");
        this.versions = Objects.requireNonNull(versions, "Version repository must not be null");
        this.content = Objects.requireNonNull(content, "Content store must not be null");
        this.uploads = new DocumentUploads(this.content, Objects.requireNonNull(uploads, "Upload repository must not be null"));
        this.lifecycle = Objects.requireNonNull(lifecycle, "Lifecycle repository must not be null");
        this.audits = null;
    }

    public DefaultDocumentBridge(BusinessDocumentRepository documents, DocumentVersionRepository versions,
                                 DocumentContentStore content, DocumentUploadRepository uploads,
                                 DocumentLifecycleRepository lifecycle, DocumentAuditRepository audits) {
        this.documents = Objects.requireNonNull(documents, "Document repository must not be null");
        this.versions = Objects.requireNonNull(versions, "Version repository must not be null");
        this.content = Objects.requireNonNull(content, "Content store must not be null");
        this.uploads = new DocumentUploads(this.content, Objects.requireNonNull(uploads, "Upload repository must not be null"));
        this.lifecycle = Objects.requireNonNull(lifecycle, "Lifecycle repository must not be null");
        this.audits = Objects.requireNonNull(audits, "Audit repository must not be null");
    }

    @Override
    public DocumentVersion reconcileUpload(ReconcileUpload command) throws IOException {
        Objects.requireNonNull(command, "Reconciliation command must not be null");
        return uploadOperations().reconcileUpload(command);
    }

    @Override
    public List<PendingUpload> pendingUploads(int limit, UUID afterRequestId) {
        return uploadOperations().pendingUploads(limit, afterRequestId);
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
        return uploadOperations().attach(command);
    }

    @Override
    public DocumentVersion addVersion(AddDocumentVersion command) throws IOException {
        Objects.requireNonNull(command, "Version command must not be null");
        return uploadOperations().addVersion(command);
    }

    private DocumentUploads uploadOperations() {
        if (uploads == null) {
            throw new UnsupportedOperationException("Uploads require a DocumentUploadRepository");
        }
        return uploads;
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
    public List<DocumentVersion> getVersions(UUID documentId) {
        Objects.requireNonNull(documentId, "Document id must not be null");
        get(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        return versions.findByDocument(documentId).stream()
                .filter(version -> version.documentId().equals(documentId))
                .sorted(Comparator.comparingInt(DocumentVersion::number))
                .toList();
    }

    @Override
    public List<DocumentVersion> getVersions(UUID documentId, int limit, int afterNumber) {
        Objects.requireNonNull(documentId, "Document id must not be null");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Version history limit must be between 1 and 1000");
        if (afterNumber < 0) throw new IllegalArgumentException("Version number cursor must not be negative");
        get(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        return versions.findByDocument(documentId, limit, afterNumber).stream()
                .filter(version -> version.documentId().equals(documentId) && version.number() > afterNumber)
                .sorted(Comparator.comparingInt(DocumentVersion::number))
                .limit(limit)
                .toList();
    }

    @Override
    public List<DocumentAuditEntry> getAudit(UUID documentId) {
        Objects.requireNonNull(documentId, "Document id must not be null");
        get(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (audits == null) throw new UnsupportedOperationException("Audit history requires a DocumentAuditRepository");
        return audits.findByDocument(documentId).stream()
                .filter(entry -> entry.documentId().equals(documentId))
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
