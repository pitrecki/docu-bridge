package io.github.pitrecki.dokubridge.core.application.version;

import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
import io.github.pitrecki.dokubridge.core.port.out.version.DocumentVersionRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class VersionHistoryPageTest {
    final UUID id = UUID.randomUUID();
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "42");
    BusinessDocument document = new BusinessDocument(id, owner, Map.of(), BusinessDocumentState.ACTIVE);
    final List<DocumentVersion> rows = new ArrayList<>();
    int documentQueries;
    int pageQueries;
    int suppliedLimit;
    int suppliedCursor;
    RuntimeException pageFailure;
    final BusinessDocumentRepository documents = new BusinessDocumentRepository() {
        public Optional<BusinessDocument> findById(UUID requested) {
            assertThat(requested).isEqualTo(id);
            documentQueries++;
            return Optional.ofNullable(document);
        }
        public List<BusinessDocument> findByOwner(DocumentOwnerRef ref) { throw new AssertionError("Unexpected owner query"); }
    };
    final DocumentContentStore content = new DocumentContentStore() {
        public void store(UUID version, InputStream source) { throw new AssertionError("Unexpected content write"); }
        public InputStream open(UUID version) { throw new AssertionError("History must not open content"); }
    };
    final DocumentVersionRepository versions = new DocumentVersionRepository() {
        public Optional<DocumentVersion> findById(UUID document, UUID version) { throw new AssertionError("Unexpected version lookup"); }
        public List<DocumentVersion> findByDocument(UUID document) { throw new AssertionError("Paged reads must not load full history"); }
        public List<DocumentVersion> findByDocument(UUID requested, int limit, int afterNumber) {
            assertThat(requested).isEqualTo(id);
            pageQueries++;
            suppliedLimit = limit;
            suppliedCursor = afterNumber;
            if (pageFailure != null) throw pageFailure;
            return rows;
        }
    };
    final DefaultDocumentBridge bridge = new DefaultDocumentBridge(documents, versions, content);

    @ParameterizedTest
    @EnumSource(value = BusinessDocumentState.class, names = {"ACTIVE", "ARCHIVED"})
    void returnsImmutableScopedOrderedPagesOfEveryVersionState(BusinessDocumentState state) {
        document = new BusinessDocument(id, owner, Map.of(), state);
        var first = version(1, DocumentVersionState.AVAILABLE);
        var second = version(2, DocumentVersionState.FAILED);
        var third = version(3, DocumentVersionState.PENDING);
        var fourth = version(4, DocumentVersionState.AVAILABLE);
        var foreign = new DocumentVersion(UUID.randomUUID(), UUID.randomUUID(), 5, DocumentVersionState.AVAILABLE);
        rows.addAll(List.of(foreign, fourth, second, first, third));
        var page = bridge.document(id).versions(2);
        var next = bridge.document(id).versions(2, 2);
        rows.clear();
        assertThat(page).containsExactly(first, second);
        assertThat(next).containsExactly(third, fourth);
        assertThatThrownBy(() -> page.add(first)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(suppliedLimit).isEqualTo(2);
        assertThat(suppliedCursor).isEqualTo(2);
        assertThat(pageQueries).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMissingOrDeletedDocumentsBeforeVersionLookup(boolean missing) {
        document = missing ? null : document.delete();
        assertThatThrownBy(() -> bridge.document(id).versions(10)).isInstanceOf(DocumentNotFoundException.class);
        assertThat(pageQueries).isZero();
    }

    @Test
    void rejectsAForeignDocumentSnapshotBeforeVersionLookup() {
        document = new BusinessDocument(UUID.randomUUID(), owner, Map.of(), BusinessDocumentState.ACTIVE);
        assertThatThrownBy(() -> bridge.getVersions(id, 10)).isInstanceOf(DocumentNotFoundException.class);
        assertThat(pageQueries).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1001, Integer.MAX_VALUE})
    void invalidLimitsFailBeforeAnyQuery(int limit) {
        assertThatIllegalArgumentException().isThrownBy(() -> bridge.getVersions(id, limit));
        assertThat(documentQueries).isZero();
        assertThat(pageQueries).isZero();
    }

    @Test
    void invalidIdAndCursorFailBeforeAnyQuery() {
        assertThatNullPointerException().isThrownBy(() -> bridge.getVersions(null, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> bridge.getVersions(id, 10, -1));
        assertThat(documentQueries).isZero();
        assertThat(pageQueries).isZero();
    }

    @Test
    void validBoundaryParametersCanReturnAnEmptyPage() {
        assertThat(bridge.getVersions(id, 1000, Integer.MAX_VALUE)).isEmpty();
        assertThat(suppliedLimit).isEqualTo(1000);
        assertThat(suppliedCursor).isEqualTo(Integer.MAX_VALUE);
        assertThat(bridge.getVersions(id, 1)).isEmpty();
        assertThat(suppliedCursor).isZero();
    }

    @Test
    void repositoryFailurePropagatesInsteadOfBecomingAnEmptyPage() {
        pageFailure = new IllegalStateException("Page query failed");
        assertThatThrownBy(() -> bridge.getVersions(id, 10)).isSameAs(pageFailure);
    }

    @Test
    void legacyUnpagedRepositoriesRemainUsableWithoutFallingBackToFullHistory() {
        var first = version(1, DocumentVersionState.AVAILABLE);
        DocumentVersionRepository legacy = new DocumentVersionRepository() {
            public Optional<DocumentVersion> findById(UUID doc, UUID version) { return Optional.empty(); }
            public List<DocumentVersion> findByDocument(UUID doc) { return List.of(first); }
        };
        var facade = new DefaultDocumentBridge(documents, legacy, content);
        assertThat(facade.getVersions(id)).containsExactly(first);
        assertThatThrownBy(() -> facade.getVersions(id, 10)).isInstanceOf(UnsupportedOperationException.class);
    }

    private DocumentVersion version(int number, DocumentVersionState state) {
        return new DocumentVersion(UUID.randomUUID(), id, number, state);
    }
}
