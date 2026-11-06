package io.github.pitrecki.dokubridge.core.port.in.document;

import java.io.IOException;

/** Runs after an operation returns, using the same document and ordinary transaction boundaries. */
@FunctionalInterface
public interface DocumentAction {
    void accept(DocumentHandle document) throws IOException;
}
