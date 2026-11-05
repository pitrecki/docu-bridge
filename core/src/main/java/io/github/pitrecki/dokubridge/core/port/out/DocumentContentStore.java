package io.github.pitrecki.dokubridge.core.port.out;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Binary storage boundary; document state and authorization belong to the facade. */
public interface DocumentContentStore {
    /**
     * Stores immutable content under a version id, rejecting an existing key.
     * Does not close the caller's source. A failed call may leave stored content
     * after publication; callers must not infer availability from blob existence.
     * Content under a published version key must be complete and immutable, including
     * while store is still finishing. Reconciliation relies on that publication contract.
     */
    void store(UUID versionId, InputStream source) throws IOException;

    /** Opens stored content. The caller must close the returned stream. */
    InputStream open(UUID versionId) throws IOException;
}
