package io.github.pitrecki.dokubridge.core.domain.audit;

public enum DocumentAuditAction {
    DOCUMENT_ATTACHED(true),
    VERSION_ADDED(true),
    UPLOAD_SUCCEEDED(true),
    UPLOAD_FAILED(true),
    UPLOAD_RECOVERED(true),
    UPLOAD_RECOVERY_FAILED(true),
    METADATA_UPDATED(false),
    DOCUMENT_ARCHIVED(false),
    DOCUMENT_DELETED(false);

    private final boolean requiresUploadContext;

    DocumentAuditAction(boolean requiresUploadContext) {
        this.requiresUploadContext = requiresUploadContext;
    }

    public boolean requiresUploadContext() {
        return requiresUploadContext;
    }
}
