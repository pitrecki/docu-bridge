package io.github.pitrecki.dokubridge.core.port.in.document;

import java.io.IOException;

/**
 * Runs after an operation returns, using the same document and ordinary transaction boundaries.
 * Checked I/O is supported for document uploads and downloads inside the callback.
 * A normally returning upload replay also invokes its callback, including for pending
 * or failed versions. Upload idempotency does not make callback effects idempotent.
 */
@FunctionalInterface
public interface DocumentAction {
    void accept(DocumentHandle document) throws IOException;
}
