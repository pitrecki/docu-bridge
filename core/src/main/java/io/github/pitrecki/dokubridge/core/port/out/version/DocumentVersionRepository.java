package io.github.pitrecki.dokubridge.core.port.out.version;

import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port; the lookup must scope the version to its document. */
public interface DocumentVersionRepository {
    Optional<DocumentVersion> findById(UUID documentId, UUID versionId);

    /** Returns every version of the document in number order; document visibility belongs to the facade. */
    default List<DocumentVersion> findByDocument(UUID documentId) {
        throw new UnsupportedOperationException("Version history is not supported by this repository");
    }

    /**
     * Returns a bounded page of all version states, scoped to the document and ordered by number.
     * Apply the exclusive non-negative number cursor before the limit (1..1000).
     * Document visibility belongs to the facade. Implementations must limit at the data source.
     */
    default List<DocumentVersion> findByDocument(UUID documentId, int limit, int afterNumber) {
        throw new UnsupportedOperationException("Paged version history is not supported by this repository");
    }
}
