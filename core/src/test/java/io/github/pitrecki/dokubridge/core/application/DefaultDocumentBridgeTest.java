package io.github.pitrecki.dokubridge.core.application;

import io.github.pitrecki.dokubridge.core.domain.content.exception.DocumentContentUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.domain.version.exception.DocumentVersionNotFoundException;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
import io.github.pitrecki.dokubridge.core.port.out.version.DocumentVersionRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

class DefaultDocumentBridgeTest {
    private final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "42");
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final Map<UUID, BusinessDocument> documents = new HashMap<>();
    private final Map<UUID, DocumentVersion> versions = new LinkedHashMap<>();
    private final AtomicBoolean opened = new AtomicBoolean();
    private final BusinessDocumentRepository documentRepository = new BusinessDocumentRepository() {
        @Override
        public Optional<BusinessDocument> findById(UUID id) {
            return Optional.ofNullable(documents.get(id));
        }

        @Override
        public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) {
            return documents.values().stream().filter(document -> document.owner().equals(owner)).toList();
        }
    };
    private final DocumentVersionRepository versionRepository = new DocumentVersionRepository() {
        public Optional<DocumentVersion> findById(UUID document, UUID version) {
            return Optional.ofNullable(versions.get(version));
        }
        public List<DocumentVersion> findByDocument(UUID document) {
            return new ArrayList<>(versions.values());
        }
    };
    private final DocumentContentStore contentStore = new DocumentContentStore() {
        @Override
        public void store(UUID version, InputStream source) {
            fail("Read operations must not write content");
        }

        @Override
        public InputStream open(UUID version) {
            assertThat(version).isEqualTo(versionId);
            opened.set(true);
            return new ByteArrayInputStream("content".getBytes(UTF_8));
        }
    };
    private final DocumentBridge bridge = new DefaultDocumentBridge(documentRepository, versionRepository, contentStore);

    @ParameterizedTest
    @EnumSource(value = BusinessDocumentState.class, names = {"ACTIVE", "ARCHIVED"})
    void readsVisibleDocumentAndAvailableContent(BusinessDocumentState state) throws IOException {
        var document = putDocument(state);
        versions.put(versionId, new DocumentVersion(versionId, documentId, 1, DocumentVersionState.AVAILABLE));
        assertThat(bridge.get(documentId)).contains(document);
        try (var input = bridge.getContent(documentId, versionId)) {
            assertThat(new String(input.readAllBytes(), UTF_8)).isEqualTo("content");
        }
        assertThat(opened).isTrue();
    }

    @Test
    void treatsDeletedDocumentsAsAbsentBeforeLookingUpContent() {
        putDocument(BusinessDocumentState.DELETED);
        var guarded = new DefaultDocumentBridge(documentRepository, (document, version) -> {
            throw new AssertionError("Deleted documents must not trigger version lookup");
        }, contentStore);
        assertThat(guarded.get(documentId)).isEmpty();
        assertThatThrownBy(() -> guarded.getVersions(documentId)).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> guarded.getContent(documentId, versionId)).isInstanceOf(DocumentNotFoundException.class);
        assertThat(opened).isFalse();
    }

    @Test
    void reportsMissingDocumentWithoutOpeningContent() {
        assertThat(bridge.get(documentId)).isEmpty();
        assertThatThrownBy(() -> bridge.getVersions(documentId)).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> bridge.getContent(documentId, versionId)).isInstanceOf(DocumentNotFoundException.class);
        assertThat(opened).isFalse();
    }

    @Test
    void hidesAnAdapterSnapshotWithTheWrongDocumentId() {
        documents.put(documentId, new BusinessDocument(UUID.randomUUID(), owner, Map.of(), BusinessDocumentState.ACTIVE));
        assertThat(bridge.get(documentId)).isEmpty();
        assertThatThrownBy(() -> bridge.getVersions(documentId)).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> bridge.getContent(documentId, versionId)).isInstanceOf(DocumentNotFoundException.class);
        assertThat(opened).isFalse();
    }

    @Test
    void reportsMissingVersionWithoutOpeningContent() {
        putDocument(BusinessDocumentState.ACTIVE);
        assertThatThrownBy(() -> bridge.getContent(documentId, versionId)).isInstanceOf(DocumentVersionNotFoundException.class);
        assertThat(opened).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionState.class, names = {"PENDING", "FAILED"})
    void neverOpensContentForUnavailableVersions(DocumentVersionState state) {
        putDocument(BusinessDocumentState.ACTIVE);
        versions.put(versionId, new DocumentVersion(versionId, documentId, 1, state));
        assertThatThrownBy(() -> bridge.getContent(documentId, versionId)).isInstanceOf(DocumentContentUnavailableException.class);
        assertThat(opened).isFalse();
    }

    @Test
    void refusesAVersionBelongingToAnotherDocumentEvenIfAdapterReturnsIt() {
        putDocument(BusinessDocumentState.ACTIVE);
        versions.put(versionId, new DocumentVersion(versionId, UUID.randomUUID(), 1, DocumentVersionState.AVAILABLE));
        assertThatThrownBy(() -> bridge.getContent(documentId, versionId)).isInstanceOf(DocumentVersionNotFoundException.class);
        assertThat(opened).isFalse();
    }

    @Test
    void refusesAnAdapterSnapshotWithTheWrongVersionId() {
        putDocument(BusinessDocumentState.ACTIVE);
        versions.put(versionId, new DocumentVersion(UUID.randomUUID(), documentId, 1, DocumentVersionState.AVAILABLE));
        assertThatThrownBy(() -> bridge.getContent(documentId, versionId)).isInstanceOf(DocumentVersionNotFoundException.class);
        assertThat(opened).isFalse();
    }

    @Test
    void returnsAnImmutableListOfOnlyTheRequestedOwnersVisibleDocuments() {
        var active = putDocument(BusinessDocumentState.ACTIVE);
        var archived = new BusinessDocument(UUID.randomUUID(), owner, Map.of(), BusinessDocumentState.ARCHIVED);
        var deleted = new BusinessDocument(UUID.randomUUID(), owner, Map.of(), BusinessDocumentState.DELETED);
        var foreign = new BusinessDocument(UUID.randomUUID(), new DocumentOwnerRef("Order", "42"), Map.of(), BusinessDocumentState.ACTIVE);
        var adapterResults = new ArrayList<>(List.of(active, archived, deleted, foreign));
        var unfiltered = new BusinessDocumentRepository() {
            public Optional<BusinessDocument> findById(UUID id) { return Optional.empty(); }
            public List<BusinessDocument> findByOwner(DocumentOwnerRef ref) { return adapterResults; }
        };
        var facade = new DefaultDocumentBridge(unfiltered, versionRepository, contentStore);
        var result = facade.findByOwner(owner);
        adapterResults.clear();
        assertThat(result).containsExactly(active, archived);
        assertThatThrownBy(() -> result.add(deleted)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void returnsEmptyListForOwnerWithoutDocuments() {
        assertThat(bridge.findByOwner(owner)).isEmpty();
    }

    @Test
    void preservesStorageFailureWithoutReturningSuccess() {
        putDocument(BusinessDocumentState.ACTIVE);
        versions.put(versionId, new DocumentVersion(versionId, documentId, 1, DocumentVersionState.AVAILABLE));
        var failure = new IOException("Storage unavailable");
        var failingStore = new DocumentContentStore() {
            public void store(UUID id, InputStream source) { fail("Unexpected write"); }
            public InputStream open(UUID id) throws IOException { throw failure; }
        };
        var facade = new DefaultDocumentBridge(documentRepository, versionRepository, failingStore);
        assertThatThrownBy(() -> facade.getContent(documentId, versionId)).isSameAs(failure);
    }

    @Test
    void streamOwnershipRemainsWithCaller() throws IOException {
        putDocument(BusinessDocumentState.ACTIVE);
        versions.put(versionId, new DocumentVersion(versionId, documentId, 1, DocumentVersionState.AVAILABLE));
        var closed = new AtomicBoolean();
        var stream = new ByteArrayInputStream(new byte[]{42}) {
            public void close() { closed.set(true); }
        };
        var store = new DocumentContentStore() {
            public void store(UUID id, InputStream source) { fail("Unexpected write"); }
            public InputStream open(UUID id) { return stream; }
        };
        var facade = new DefaultDocumentBridge(documentRepository, versionRepository, store);
        try (var input = facade.getContent(documentId, versionId)) {
            assertThat(input).isSameAs(stream);
            assertThat(closed).isFalse();
            assertThat(input.read()).isEqualTo(42);
        }
        assertThat(closed).isTrue();
    }

    @Test
    void visibleDocumentWithoutVersionsHasAnEmptyHistory() {
        putDocument(BusinessDocumentState.ACTIVE);
        assertThat(bridge.getVersions(documentId)).isEmpty();
        assertThat(opened).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = BusinessDocumentState.class, names = {"ACTIVE", "ARCHIVED"})
    void returnsImmutableHistoryOfEveryStateScopedToDocumentInNumberOrder(BusinessDocumentState state) {
        putDocument(state);
        var first = new DocumentVersion(UUID.randomUUID(), documentId, 1, DocumentVersionState.AVAILABLE);
        var second = new DocumentVersion(UUID.randomUUID(), documentId, 2, DocumentVersionState.FAILED);
        var third = new DocumentVersion(UUID.randomUUID(), documentId, 3, DocumentVersionState.PENDING);
        var foreign = new DocumentVersion(UUID.randomUUID(), UUID.randomUUID(), 1, DocumentVersionState.AVAILABLE);
        for (var version : List.of(third, foreign, first, second)) versions.put(version.id(), version);
        var history = bridge.getVersions(documentId);
        versions.clear();
        assertThat(history).containsExactly(first, second, third);
        assertThatThrownBy(() -> history.add(foreign)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(opened).isFalse();
    }

    @Test
    void existingCustomFacadeAndSingleVersionRepositoryRemainUsableWithHistoryUnsupported() {
        var document = putDocument(BusinessDocumentState.ACTIVE);
        DocumentVersionRepository legacy = (doc, version) -> Optional.empty();
        assertThat(legacy.findById(documentId, versionId)).isEmpty();
        assertThatThrownBy(() -> new DefaultDocumentBridge(documentRepository, legacy, contentStore).getVersions(documentId))
                .isInstanceOf(UnsupportedOperationException.class);
        DocumentBridge custom = new DocumentBridge() {
            public Optional<BusinessDocument> get(UUID id) { return Optional.of(document); }
            public List<BusinessDocument> findByOwner(DocumentOwnerRef ref) { return List.of(document); }
            public InputStream getContent(UUID doc, UUID version) { throw new UnsupportedOperationException(); }
        };
        assertThat(custom.get(documentId)).contains(document);
        assertThatThrownBy(() -> custom.getVersions(documentId)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesDependenciesAndQueryArguments() {
        assertThatNullPointerException().isThrownBy(() -> new DefaultDocumentBridge(null, versionRepository, contentStore));
        assertThatNullPointerException().isThrownBy(() -> new DefaultDocumentBridge(documentRepository, null, contentStore));
        assertThatNullPointerException().isThrownBy(() -> new DefaultDocumentBridge(documentRepository, versionRepository, null));
        assertThatNullPointerException().isThrownBy(() -> bridge.get(null));
        assertThatNullPointerException().isThrownBy(() -> bridge.getVersions(null));
        assertThatNullPointerException().isThrownBy(() -> bridge.findByOwner(null));
        assertThatNullPointerException().isThrownBy(() -> bridge.getContent(null, versionId));
        assertThatNullPointerException().isThrownBy(() -> bridge.getContent(documentId, null));
    }

    private BusinessDocument putDocument(BusinessDocumentState state) {
        var document = new BusinessDocument(documentId, owner, Map.of(), state);
        documents.put(documentId, document);
        return document;
    }
}
