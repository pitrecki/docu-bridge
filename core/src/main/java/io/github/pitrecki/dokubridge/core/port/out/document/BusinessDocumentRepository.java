package io.github.pitrecki.dokubridge.core.port.out.document;

import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port returning document snapshots; unpaged reads include retained deleted records. */
public interface BusinessDocumentRepository {
    Optional<BusinessDocument> findById(UUID documentId);

    List<BusinessDocument> findByOwner(DocumentOwnerRef owner);

    /**
     * Returns a bounded page of ACTIVE/ARCHIVED documents scoped to the owner.
     * Filter visibility and the exclusive cursor before limiting at the data source.
     * Order by canonical UUID text; null starts the first page and limit must be 1..1000.
     */
    default List<BusinessDocument> findVisibleByOwner(DocumentOwnerRef owner, int limit, UUID afterDocumentId) {
        throw new UnsupportedOperationException("Paged owner lookup is not supported by this repository");
    }
}
