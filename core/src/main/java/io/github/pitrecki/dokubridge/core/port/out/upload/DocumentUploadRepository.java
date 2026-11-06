package io.github.pitrecki.dokubridge.core.port.out.upload;

import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.upload.PendingUpload;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Each call uses one short independent transaction; mutations include their audit entries.
 * Reservations atomically claim a global request id and allocate a pending version.
 * Matching duplicates return acquired=false; mismatching fingerprints are conflicts.
 * Methods must not return before committing or perform content I/O.
 */
public interface DocumentUploadRepository {
    /** Compatibility overload; the JPA adapter cannot reconcile reservations created without a checksum. */
    UploadReservation reserveAttach(UUID requestId, String fingerprint, DocumentOwnerRef owner,
                                    Map<String, Object> metadata);

    /** Compatibility overload; prefer the checksum-bearing reservation for new uploads. */
    UploadReservation reserveVersion(UUID requestId, String fingerprint, UUID documentId);

    /** New implementations record the declared checksum in the same reservation transaction. */
    default UploadReservation reserveAttach(UUID requestId, String fingerprint, DocumentOwnerRef owner,
                                            Map<String, Object> metadata, ContentSha256 checksum) {
        Objects.requireNonNull(checksum, "Checksum must not be null");
        return reserveAttach(requestId, fingerprint, owner, metadata);
    }

    default UploadReservation reserveVersion(UUID requestId, String fingerprint, UUID documentId, ContentSha256 checksum) {
        Objects.requireNonNull(checksum, "Checksum must not be null");
        return reserveVersion(requestId, fingerprint, documentId);
    }

    /** Reads a fresh committed snapshot, checks the request/version association, and closes its transaction. */
    default UploadRecoveryCandidate inspectForRecovery(UUID requestId, UUID versionId) {
        throw new UnsupportedOperationException("Upload reconciliation is not supported by this repository");
    }

    /**
     * Reads a fresh page of PENDING uploads whose documents are ACTIVE or ARCHIVED.
     * Filter before applying the limit (1..1000); order by canonical request UUID
     * lexicographically and exclude ids at or before the cursor. Null starts the first page.
     * Legacy requests without a checksum are included. No content I/O or mutation is allowed.
     */
    default List<PendingUpload> findPendingUploads(int limit, UUID afterRequestId) {
        throw new UnsupportedOperationException("Pending upload discovery is not supported by this repository");
    }

    /** Finalizes a verified recovery with its own audit; a matching terminal outcome is a no-op. */
    default DocumentVersion completeRecovery(UUID requestId, UUID versionId, DocumentVersionState outcome) {
        throw new UnsupportedOperationException("Upload reconciliation is not supported by this repository");
    }

    /** Finalizes a pending version with its audit; an already matching terminal outcome is a no-op. */
    DocumentVersion complete(UUID requestId, UUID versionId, DocumentVersionState outcome);
}
