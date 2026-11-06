package io.github.pitrecki.dokubridge.core.port.in.document;

import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class FluentDocumentLookupTest {
    final UUID id = UUID.randomUUID();
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "42");
    final AtomicInteger queries = new AtomicInteger();
    BusinessDocument snapshot;
    RuntimeException queryFailure;
    final DocumentBridge bridge = new DefaultDocumentBridge(new BusinessDocumentRepository() {
        public Optional<BusinessDocument> findById(UUID requested) {
            assertThat(requested).isEqualTo(id);
            queries.incrementAndGet();
            if (queryFailure != null) throw queryFailure;
            return Optional.ofNullable(snapshot);
        }
        public List<BusinessDocument> findByOwner(DocumentOwnerRef ref) { throw new AssertionError("Unexpected owner lookup"); }
    }, (document, version) -> { throw new AssertionError("Unexpected version lookup"); }, new DocumentContentStore() {
        public void store(UUID version, InputStream source) { throw new AssertionError("Unexpected content write"); }
        public InputStream open(UUID version) { throw new AssertionError("Unexpected content read"); }
    });

    @ParameterizedTest
    @EnumSource(value = BusinessDocumentState.class, names = {"ACTIVE", "ARCHIVED"})
    void retainsTheImmutableQuerySnapshotWithoutReadingAgain(BusinessDocumentState state) {
        var original = new BusinessDocument(id, owner, Map.of("category", "original"), state);
        snapshot = original;
        var found = bridge.findDocument(id).orElseThrow();
        snapshot = original.delete();
        assertThat(found.result()).isSameAs(original);
        assertThat(found.and().id()).isEqualTo(id);
        assertThatThrownBy(() -> found.result().metadata().put("changed", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(found.and()::content).isInstanceOf(IllegalStateException.class);
        assertThat(queries).hasValue(1);
        assertThat(found.and().get()).isEmpty();
        assertThat(queries).hasValue(2);
        assertThat(found.result()).isSameAs(original);
    }

    @Test
    void missingDeletedAndForeignSnapshotsAreAbsent() {
        assertThat(bridge.findDocument(id)).isEmpty();
        snapshot = new BusinessDocument(id, owner, Map.of(), BusinessDocumentState.DELETED);
        assertThat(bridge.findDocument(id)).isEmpty();
        snapshot = new BusinessDocument(UUID.randomUUID(), owner, Map.of(), BusinessDocumentState.ACTIVE);
        assertThat(bridge.findDocument(id)).isEmpty();
        assertThat(queries).hasValue(3);
    }

    @Test
    void nullIdIsRejectedBeforeQuerying() {
        assertThatNullPointerException().isThrownBy(() -> bridge.findDocument(null));
        assertThat(queries).hasValue(0);
    }

    @Test
    void queryFailurePropagatesInsteadOfBecomingAnEmptyOptional() {
        queryFailure = new IllegalStateException("Document query failed");
        assertThatThrownBy(() -> bridge.findDocument(id)).isSameAs(queryFailure);
        assertThat(queries).hasValue(1);
    }

    @Test
    void existingCustomFacadeHandlesTheQueryAndContinuation() throws Exception {
        snapshot = new BusinessDocument(id, owner, Map.of(), BusinessDocumentState.ACTIVE);
        var original = snapshot;
        DocumentBridge custom = new DocumentBridge() {
            public Optional<BusinessDocument> get(UUID requested) {
                assertThat(requested).isEqualTo(id);
                queries.incrementAndGet();
                return Optional.of(snapshot);
            }
            public BusinessDocument archive(Archive command) {
                assertThat(command.documentId()).isEqualTo(id);
                snapshot = snapshot.archive();
                return snapshot;
            }
            public List<BusinessDocument> findByOwner(DocumentOwnerRef ref) { throw new AssertionError("Unexpected owner lookup"); }
            public InputStream getContent(UUID document, UUID version) { throw new AssertionError("Unexpected content read"); }
        };
        var found = custom.findDocument(id).orElseThrow();
        assertThat(found.andThen(document -> document.archive())).isSameAs(found);
        assertThat(found.result()).isSameAs(original);
        assertThat(queries).hasValue(1);
        assertThat(found.and().get().orElseThrow().state()).isEqualTo(BusinessDocumentState.ARCHIVED);
        assertThat(queries).hasValue(2);
    }
}
