package io.github.pitrecki.dokubridge.core.port.in.document;

import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import java.io.IOException;
import java.util.Objects;

/** Keeps an operation's immutable result separate from its document continuation. */
public final class DocumentResult<T> {
    private final T result;
    private final DocumentHandle document;

    private DocumentResult(T result, DocumentHandle document) {
        this.result = Objects.requireNonNull(result, "Operation result must not be null");
        this.document = Objects.requireNonNull(document);
    }

    public static DocumentResult<DocumentVersion> uploaded(DocumentBridge bridge, DocumentVersion version) {
        return new DocumentResult<>(version, DocumentHandle.forVersion(bridge, version));
    }

    static DocumentResult<BusinessDocument> changed(DocumentHandle document, BusinessDocument snapshot) {
        Objects.requireNonNull(snapshot, "Document snapshot must not be null");
        if (!document.id().equals(snapshot.id())) {
            throw new IllegalArgumentException("Operation result belongs to a different document");
        }
        return new DocumentResult<>(snapshot, document);
    }

    public T result() {
        return result;
    }

    /** Continues without executing the completed operation again or loading document state. */
    public DocumentHandle and() {
        return document;
    }

    /** Invokes the callback once; failures propagate and earlier committed changes are retained. */
    public DocumentResult<T> andThen(DocumentAction action) throws IOException {
        Objects.requireNonNull(action, "Document action must not be null").accept(document);
        return this;
    }
}
