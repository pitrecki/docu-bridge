package io.github.pitrecki.dokubridge.core.port.out;

import io.github.pitrecki.dokubridge.core.domain.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port returning document snapshots, including deleted records. */
public interface BusinessDocumentRepository {
    Optional<BusinessDocument> findById(UUID documentId);

    List<BusinessDocument> findByOwner(DocumentOwnerRef owner);
}
