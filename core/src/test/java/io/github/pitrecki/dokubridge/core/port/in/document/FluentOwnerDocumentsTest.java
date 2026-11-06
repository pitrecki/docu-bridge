package io.github.pitrecki.dokubridge.core.port.in.document;

import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FluentOwnerDocumentsTest {
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "42");
    final List<BusinessDocument> snapshots = new ArrayList<>();
    final AtomicInteger queries = new AtomicInteger();
    RuntimeException queryFailure;
    final DocumentBridge bridge = new DefaultDocumentBridge(new BusinessDocumentRepository() {
        public Optional<BusinessDocument> findById(UUID id) { throw new AssertionError("Unexpected document lookup"); }
        public List<BusinessDocument> findByOwner(DocumentOwnerRef requested) {
            assertThat(requested).isEqualTo(owner);
            queries.incrementAndGet();
            if (queryFailure != null) throw queryFailure;
            return snapshots;
        }
    }, (document, version) -> { throw new AssertionError("Unexpected version lookup"); }, new DocumentContentStore() {
        public void store(UUID version, InputStream source) { throw new AssertionError("Unexpected content write"); }
        public InputStream open(UUID version) { throw new AssertionError("Unexpected content read"); }
    });

    @Test
    void queryReturnsImmutableVisibleSnapshotsAndContinuationsWithoutIndividualLookups() {
        var active = document(owner, BusinessDocumentState.ACTIVE);
        var archived = document(owner, BusinessDocumentState.ARCHIVED);
        var deleted = document(owner, BusinessDocumentState.DELETED);
        var foreign = document(new DocumentOwnerRef("Order", "42"), BusinessDocumentState.ACTIVE);
        snapshots.addAll(List.of(archived, deleted, active, foreign));

        var found = bridge.documentsForOwner("Invoice", "42");
        snapshots.clear();
        assertThat(found).extracting(DocumentResult::result).containsExactly(archived, active);
        assertThat(found).extracting(result -> result.and().id()).containsExactly(archived.id(), active.id());
        assertThat(found.getFirst().result()).isSameAs(archived);
        assertThatThrownBy(() -> found.getFirst().result().metadata().put("changed", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> found.add(found.getFirst())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(found.getFirst().and()::content).isInstanceOf(IllegalStateException.class);
        assertThat(queries).hasValue(1);
    }

    @Test
    void absentOwnerReturnsAnImmutableEmptyList() {
        var found = bridge.documentsForOwner(owner);
        assertThat(found).isEmpty();
        assertThatThrownBy(() -> found.add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(queries).hasValue(1);
    }

    @Test
    void repositoryFailurePropagatesInsteadOfBecomingAnEmptyList() {
        queryFailure = new IllegalStateException("Owner query failed");
        assertThatThrownBy(() -> bridge.documentsForOwner(owner)).isSameAs(queryFailure);
        assertThat(queries).hasValue(1);
    }

    @Test
    void invalidOwnersAreRejectedBeforeQuerying() {
        assertThatNullPointerException().isThrownBy(() -> bridge.documentsForOwner(null));
        assertThatIllegalArgumentException().isThrownBy(() -> bridge.documentsForOwner(" ", "42"));
        assertThatIllegalArgumentException().isThrownBy(() -> bridge.documentsForOwner("Invoice", ""));
        assertThat(queries).hasValue(0);
    }

    @Test
    void existingCustomFacadeProvidesTheQueryAndAllSubsequentOperations() throws Exception {
        var original = document(owner, BusinessDocumentState.ACTIVE);
        var current = new AtomicReference<>(original);
        DocumentBridge custom = new DocumentBridge() {
            public List<BusinessDocument> findByOwner(DocumentOwnerRef requested) {
                assertThat(requested).isEqualTo(owner);
                queries.incrementAndGet();
                return List.of(current.get());
            }
            public Optional<BusinessDocument> get(UUID id) {
                assertThat(id).isEqualTo(original.id());
                return Optional.of(current.get());
            }
            public BusinessDocument archive(Archive command) {
                assertThat(command.documentId()).isEqualTo(original.id());
                return current.updateAndGet(BusinessDocument::archive);
            }
            public InputStream getContent(UUID document, UUID version) { throw new AssertionError("Unexpected content read"); }
        };

        var found = custom.documentsForOwner(owner).getFirst();
        assertThat(found.andThen(document -> document.archive())).isSameAs(found);
        assertThat(found.result()).isSameAs(original);
        assertThat(found.result().state()).isEqualTo(BusinessDocumentState.ACTIVE);
        assertThat(found.and().get().orElseThrow().state()).isEqualTo(BusinessDocumentState.ARCHIVED);
        assertThat(queries).hasValue(1);
    }

    private BusinessDocument document(DocumentOwnerRef documentOwner, BusinessDocumentState state) {
        return new BusinessDocument(UUID.randomUUID(), documentOwner, Map.of("category", "invoice"), state);
    }
}
