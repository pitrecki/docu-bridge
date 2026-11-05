package io.github.pitrecki.dokubridge.core.port.out;

import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import java.util.Optional;
import java.util.UUID;

/** Persistence port; the lookup must scope the version to its document. */
public interface DocumentVersionRepository {
    Optional<DocumentVersion> findById(UUID documentId, UUID versionId);
}
