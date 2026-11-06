package io.github.pitrecki.dokubridge.core.port.in.document;

import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import java.io.IOException;
import java.util.Objects;

/**
 * Keeps an operation's immutable result separate from its document continuation.
 * An upload save writes one content version; {@link #and()} continues on its document:
 * <pre>{@code
 * var saved = bridge.upload(bytes).toOwner("Invoice", "42").save();
 * var version = saved.result();
 * saved.and().archive();
 * }</pre>
 * Each subsequent operation retains its own transaction boundary. A later failure
 * does not undo earlier committed operations.
 *
 * @param <T> the domain snapshot returned by the completed operation
 */
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

    /** Returns the original snapshot, even if a continuation has since changed the document. */
    public T result() {
        return result;
    }

    /**
     * Continues without executing the completed operation again or loading document state.
     * For an upload result, the context selects that version for
     * {@link DocumentHandle#content()}, including when the result is pending or failed.
     * This does not wait for availability; subsequent reads check current state.
     */
    public DocumentHandle and() {
        return document;
    }

    /**
     * Invokes the callback once for this call and returns this original result.
     * Repeating this call repeats the callback, including for pending or failed
     * upload snapshots. No operation or callback is retried automatically.
     *
     * @throws IOException if the callback fails with an I/O error; the same exception
     *         propagates and earlier committed changes are retained
     * @throws NullPointerException if the callback is null
     */
    public DocumentResult<T> andThen(DocumentAction action) throws IOException {
        Objects.requireNonNull(action, "Document action must not be null").accept(document);
        return this;
    }
}
