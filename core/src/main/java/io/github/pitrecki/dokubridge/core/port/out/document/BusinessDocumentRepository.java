package io.github.pitrecki.dokubridge.core.port.out.document;

import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port returning document snapshots, including deleted records. */
public interface BusinessDocumentRepository {
    Optional<BusinessDocument> findById(UUID documentId);

    List<BusinessDocument> findByOwner(DocumentOwnerRef owner);
}
