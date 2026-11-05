package io.github.pitrecki.dokubridge.adapter.jpa.document.persistence;

import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import java.util.UUID;

/** Query projection that bypasses managed entity state while keeping JSON mapping inside persistence. */
public record BusinessDocumentRow(UUID id, String ownerType, String ownerId, String metadata,
                                  BusinessDocumentState state, long revision) {
    public BusinessDocument snapshot() {
        return new BusinessDocument(id, new DocumentOwnerRef(ownerType, ownerId),
                DocumentMetadataJson.decode(metadata), state, revision);
    }
}
