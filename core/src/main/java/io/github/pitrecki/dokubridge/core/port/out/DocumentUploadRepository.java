package io.github.pitrecki.dokubridge.core.port.out;

import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersionState;
import java.util.Map;
import java.util.UUID;

/**
 * Each operation commits one short transaction including its audit entries.
 * Reservations atomically claim a global request id and allocate a pending version.
 * Matching duplicates return acquired=false; mismatching fingerprints are conflicts.
 * Methods must not return before committing or perform content I/O.
 */
public interface DocumentUploadRepository {
    UploadReservation reserveAttach(UUID requestId, String fingerprint, DocumentOwnerRef owner,
                                    Map<String, Object> metadata);

    UploadReservation reserveVersion(UUID requestId, String fingerprint, UUID documentId);

    /** Finalizes only a pending version belonging to the request, with its audit. */
    DocumentVersion complete(UUID requestId, UUID versionId, DocumentVersionState outcome);
}
