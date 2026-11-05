package io.github.pitrecki.dokubridge.core;

import java.util.Optional;
import java.util.UUID;

/** Persistence port; the lookup must scope the version to its document. */
public interface DocumentVersionRepository {
    Optional<DocumentVersion> findById(UUID documentId, UUID versionId);
}
