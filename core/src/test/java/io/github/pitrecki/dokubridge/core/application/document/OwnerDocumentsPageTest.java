package io.github.pitrecki.dokubridge.core.application.document;

import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.in.document.Archive;
import io.github.pitrecki.dokubridge.core.port.in.document.DocumentResult;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class OwnerDocumentsPageTest {
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "42");
    final List<BusinessDocument> rows = new ArrayList<>();
    int queries;
    int suppliedLimit;
    UUID suppliedCursor;
    RuntimeException queryFailure;
    final BusinessDocumentRepository documents = new BusinessDocumentRepository() {
        public Optional<BusinessDocument> findById(UUID id) { throw new AssertionError("Unexpected document lookup"); }
        public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) { throw new AssertionError("Paged reads must not load all documents"); }
        public List<BusinessDocument> findVisibleByOwner(DocumentOwnerRef requested, int limit, UUID after) {
            assertThat(requested).isEqualTo(owner);
            queries++;
            suppliedLimit = limit;
            suppliedCursor = after;
            if (queryFailure != null) throw queryFailure;
            return rows;
        }
    };
    final DocumentContentStore content = new DocumentContentStore() {
        public void store(UUID id, InputStream input) { throw new AssertionError("Unexpected content write"); }
        public InputStream open(UUID id) { throw new AssertionError("Owner lookup must not open content"); }
    };
    final DocumentBridge bridge = using(documents);

    @Test
    void immutablePagesHaveVisibleSnapshotsAndContinuationsInCanonicalUuidOrder() {
        var first = document("7fffffff-ffff-ffff-ffff-ffffffffffff", BusinessDocumentState.ACTIVE);
        var second = document("80000000-0000-0000-0000-000000000000", BusinessDocumentState.ARCHIVED);
        var third = document("ffffffff-ffff-ffff-ffff-ffffffffffff", BusinessDocumentState.ACTIVE);
        var deleted = document("00000000-0000-0000-0000-000000000001", BusinessDocumentState.DELETED);
        var foreign = new BusinessDocument(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                new DocumentOwnerRef("Order", "42"), Map.of(), BusinessDocumentState.ACTIVE);
        rows.addAll(List.of(third, second, foreign, deleted, first));

        var page = bridge.documentsForOwner("Invoice", "42", 2);
        assertThat(suppliedLimit).isEqualTo(2);
        assertThat(suppliedCursor).isNull();
        var next = bridge.documentsForOwner(owner, 2, page.getLast().result().id());
        assertThat(suppliedCursor).isEqualTo(second.id());
        rows.clear();

        assertThat(page).extracting(DocumentResult::result).containsExactly(first, second);
        assertThat(page).extracting(result -> result.and().id()).containsExactly(first.id(), second.id());
        assertThat(next).extracting(DocumentResult::result).containsExactly(third);
        assertThat(page.getFirst().result()).isSameAs(first);
        assertThatThrownBy(page::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> page.getFirst().result().metadata().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(page.getFirst().and()::content).isInstanceOf(IllegalStateException.class);
        assertThat(queries).isEqualTo(2);
    }

    @Test
    void firstPageOverloadsAndAnExclusiveCursorCanReturnEmptyPages() {
        assertThat(bridge.documentsForOwner(owner, 1000)).isEmpty();
        assertThat(suppliedLimit).isEqualTo(1000);
        assertThat(suppliedCursor).isNull();
        var last = document("ffffffff-ffff-ffff-ffff-ffffffffffff", BusinessDocumentState.ACTIVE);
        rows.add(last);
        assertThat(bridge.documentsForOwner("Invoice", "42", 1, last.id())).isEmpty();
        var raw = bridge.findByOwner(owner, 1);
        assertThat(raw).containsExactly(last);
        assertThatThrownBy(raw::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1001, Integer.MAX_VALUE})
    void invalidLimitsFailBeforeQuerying(int limit) {
        assertThatIllegalArgumentException().isThrownBy(() -> bridge.documentsForOwner(owner, limit));
        assertThat(queries).isZero();
    }

    @Test
    void invalidOwnersFailBeforeQuerying() {
        assertThatNullPointerException().isThrownBy(() -> bridge.documentsForOwner((DocumentOwnerRef) null, 1));
        assertThatNullPointerException().isThrownBy(() -> bridge.findByOwner(null, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> bridge.documentsForOwner(" ", "42", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> bridge.documentsForOwner("Invoice", "", 1, null));
        assertThat(queries).isZero();
    }

    @Test
    void queryFailurePropagatesInsteadOfBecomingAnEmptyPage() {
        queryFailure = new IllegalStateException("Owner query failed");
        assertThatThrownBy(() -> bridge.documentsForOwner(owner, 1)).isSameAs(queryFailure);
    }

    @Test
    void legacyRepositoriesKeepUnpagedReadsWithoutLoadingThemAsAPagingFallback() {
        var first = document("00000000-0000-0000-0000-000000000001", BusinessDocumentState.ACTIVE);
        var legacy = using(new BusinessDocumentRepository() {
            public Optional<BusinessDocument> findById(UUID id) { return Optional.of(first); }
            public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) { return List.of(first); }
        });
        assertThat(legacy.documentsForOwner(owner)).extracting(DocumentResult::result).containsExactly(first);
        assertThatThrownBy(() -> legacy.documentsForOwner(owner, 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void customFacadeProvidesPagingAndContinuationOperationsWithoutIndividualLookups() {
        var first = document("00000000-0000-0000-0000-000000000001", BusinessDocumentState.ACTIVE);
        UUID cursor = UUID.fromString("00000000-0000-0000-0000-000000000000");
        DocumentBridge custom = new DocumentBridge() {
            public Optional<BusinessDocument> get(UUID id) { throw new AssertionError("Unexpected lookup"); }
            public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) { return List.of(first); }
            public List<BusinessDocument> findByOwner(DocumentOwnerRef requested, int limit, UUID after) {
                assertThat(requested).isEqualTo(owner);
                assertThat(limit).isEqualTo(1);
                assertThat(after).isEqualTo(cursor);
                queries++;
                return List.of(first);
            }
            public BusinessDocument archive(Archive command) {
                assertThat(command.documentId()).isEqualTo(first.id());
                return first.archive();
            }
            public InputStream getContent(UUID document, UUID version) { throw new AssertionError("Unexpected content read"); }
        };
        var found = custom.documentsForOwner(owner, 1, cursor).getFirst();
        assertThat(found.and().archive().result().state()).isEqualTo(BusinessDocumentState.ARCHIVED);
        assertThat(found.result()).isSameAs(first);
        assertThat(queries).isEqualTo(1);
    }

    private DocumentBridge using(BusinessDocumentRepository repository) {
        return new DefaultDocumentBridge(repository, (document, version) -> {
            throw new AssertionError("Unexpected version lookup");
        }, content);
    }

    private BusinessDocument document(String id, BusinessDocumentState state) {
        return new BusinessDocument(UUID.fromString(id), owner, Map.of("category", "invoice"), state);
    }
}
