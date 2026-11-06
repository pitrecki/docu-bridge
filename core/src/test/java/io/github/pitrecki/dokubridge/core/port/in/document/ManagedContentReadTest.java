package io.github.pitrecki.dokubridge.core.port.in.document;

import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.content.exception.DocumentContentUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

class ManagedContentReadTest {
    final UUID documentId = UUID.randomUUID();
    final UUID versionId = UUID.randomUUID();
    final BusinessDocument document = new BusinessDocument(documentId, new DocumentOwnerRef("Invoice", "42"),
            Map.of(), BusinessDocumentState.ACTIVE);
    DocumentVersion version = new DocumentVersion(versionId, documentId, 1, DocumentVersionState.AVAILABLE);
    final TrackedStream stream = new TrackedStream();
    final AtomicInteger documentQueries = new AtomicInteger();
    final AtomicInteger versionQueries = new AtomicInteger();
    final AtomicInteger opens = new AtomicInteger();
    IOException openFailure;
    final DefaultDocumentBridge bridge = new DefaultDocumentBridge(new BusinessDocumentRepository() {
        public Optional<BusinessDocument> findById(UUID id) {
            assertThat(id).isEqualTo(documentId);
            documentQueries.incrementAndGet();
            return Optional.of(document);
        }
        public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) { throw new AssertionError("Unexpected owner lookup"); }
    }, (document, id) -> {
        assertThat(document).isEqualTo(documentId);
        assertThat(id).isEqualTo(versionId);
        versionQueries.incrementAndGet();
        return Optional.of(version);
    }, new DocumentContentStore() {
        public void store(UUID id, InputStream source) { throw new AssertionError("Unexpected content write"); }
        public InputStream open(UUID id) throws IOException {
            assertThat(id).isEqualTo(versionId);
            opens.incrementAndGet();
            if (openFailure != null) throw openFailure;
            return stream;
        }
    });

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsAValueOnceAndClosesTheSelectedOrExplicitVersion(boolean selected) throws Exception {
        var captured = new AtomicReference<InputStream>();
        var calls = new AtomicInteger();
        DocumentContentReader<String> reader = input -> {
            calls.incrementAndGet();
            captured.set(input);
            assertThat(stream.closed).isFalse();
            return new String(input.readAllBytes(), UTF_8);
        };
        String value = selected ? selected().readContent(reader)
                : bridge.document(documentId).readContent(versionId, reader);
        assertThat(value).isEqualTo("content");
        assertThat(calls).hasValue(1);
        assertThat(documentQueries).hasValue(1);
        assertThat(versionQueries).hasValue(1);
        assertThat(opens).hasValue(1);
        assertThat(stream.closes).isEqualTo(1);
        assertThatIOException().isThrownBy(() -> captured.get().read()).withMessage("Stream closed");
    }

    @Test
    void readerFailureIsPreservedAndCloseFailureIsSuppressed() {
        var failure = new IOException("Parsing failed");
        stream.closeFailure = new IOException("Close failed");
        assertThatThrownBy(() -> selected().readContent(input -> { throw failure; })).isSameAs(failure)
                .satisfies(error -> assertThat(error.getSuppressed()).containsExactly(stream.closeFailure));
        assertThat(stream.closes).isEqualTo(1);
    }

    @Test
    void runtimeReaderFailureAlsoClosesTheStreamAndPropagatesUnchanged() {
        var failure = new IllegalStateException("Parser failed");
        assertThatThrownBy(() -> selected().readContent(input -> { throw failure; })).isSameAs(failure);
        assertThat(stream.closes).isEqualTo(1);
    }

    @Test
    void streamReadFailurePropagatesAndStillClosesTheStream() {
        stream.readFailure = new IOException("Download interrupted");
        assertThatThrownBy(() -> selected().readContent(InputStream::readAllBytes)).isSameAs(stream.readFailure);
        assertThat(stream.closes).isEqualTo(1);
    }

    @Test
    void closeFailurePreventsReturningAnOtherwiseSuccessfulRead() {
        stream.closeFailure = new IOException("Close failed");
        assertThatThrownBy(() -> selected().readContent(InputStream::readAllBytes)).isSameAs(stream.closeFailure);
        assertThat(stream.closes).isEqualTo(1);
    }

    @Test
    void openFailureSkipsTheReaderAndDoesNotCloseAnUnopenedStream() {
        openFailure = new IOException("Storage unavailable");
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> selected().readContent(input -> calls.incrementAndGet())).isSameAs(openFailure);
        assertThat(calls).hasValue(0);
        assertThat(stream.closes).isZero();
    }

    @Test
    void nullReadersAndUnselectedContentAreRejectedBeforeAnyQuery() {
        assertThatNullPointerException().isThrownBy(() -> selected().readContent(null));
        assertThatNullPointerException().isThrownBy(() -> bridge.document(documentId).readContent(versionId, null));
        assertThatIllegalStateException().isThrownBy(() -> bridge.document(documentId).readContent(InputStream::readAllBytes));
        assertThat(documentQueries).hasValue(0);
        assertThat(versionQueries).hasValue(0);
        assertThat(opens).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionState.class, names = {"PENDING", "FAILED"})
    void unavailableContentNeverOpensTheStoreOrInvokesTheReader(DocumentVersionState state) {
        version = new DocumentVersion(versionId, documentId, 1, state);
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> selected().readContent(input -> calls.incrementAndGet()))
                .isInstanceOf(DocumentContentUnavailableException.class);
        assertThat(calls).hasValue(0);
        assertThat(opens).hasValue(0);
        assertThat(stream.closes).isZero();
    }

    private DocumentHandle selected() {
        return DocumentResult.uploaded(bridge, version).and();
    }

    private static class TrackedStream extends InputStream {
        final InputStream bytes = new ByteArrayInputStream("content".getBytes(UTF_8));
        boolean closed;
        int closes;
        IOException readFailure;
        IOException closeFailure;

        public int read() throws IOException {
            if (closed) throw new IOException("Stream closed");
            if (readFailure != null) throw readFailure;
            return bytes.read();
        }

        public void close() throws IOException {
            closed = true;
            closes++;
            bytes.close();
            if (closeFailure != null) throw closeFailure;
        }
    }
}
