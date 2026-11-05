package io.github.pitrecki.dokubridge.adapter.filesystem;

import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentContentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentContentUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.out.BusinessDocumentRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

class DocumentBridgeFilesystemTest {
    @TempDir
    Path root;
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "42");

    @ParameterizedTest
    @EnumSource(value = BusinessDocumentState.class, names = {"ACTIVE", "ARCHIVED"})
    void facadeReadsAvailableBytesThroughTheFilesystemPort(BusinessDocumentState state) throws IOException {
        var store = new FilesystemDocumentContentStore(root);
        store.store(versionId, new ByteArrayInputStream("invoice content".getBytes(UTF_8)));
        DocumentBridge bridge = bridge(state, DocumentVersionState.AVAILABLE, store);
        assertThat(bridge.get(documentId)).isPresent();
        assertThat(bridge.findByOwner(owner)).hasSize(1);
        try (var stream = bridge.getContent(documentId, versionId)) {
            assertThat(new String(stream.readAllBytes(), UTF_8)).isEqualTo("invoice content");
        }
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionState.class, names = {"PENDING", "FAILED"})
    void existingBytesDoNotMakeAnUnavailableVersionDownloadable(DocumentVersionState state) throws IOException {
        var store = new FilesystemDocumentContentStore(root);
        store.store(versionId, new ByteArrayInputStream(new byte[]{42}));
        DocumentBridge bridge = bridge(BusinessDocumentState.ACTIVE, state, store);
        try (var raw = store.open(versionId)) {
            assertThat(raw.read()).isEqualTo(42);
        }
        assertThatThrownBy(() -> bridge.getContent(documentId, versionId))
                .isInstanceOf(DocumentContentUnavailableException.class);
    }

    @Test
    void deletedDocumentRemainsHiddenEvenWhenAvailableContentExists() throws IOException {
        var store = new FilesystemDocumentContentStore(root);
        store.store(versionId, new ByteArrayInputStream(new byte[]{42}));
        var bridge = bridge(BusinessDocumentState.DELETED, DocumentVersionState.AVAILABLE, store);
        assertThat(bridge.get(documentId)).isEmpty();
        assertThat(bridge.findByOwner(owner)).isEmpty();
        assertThatThrownBy(() -> bridge.getContent(documentId, versionId))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void missingBlobForAnAvailableVersionIsReportedAsStorageFailure() throws IOException {
        var store = new FilesystemDocumentContentStore(root);
        var bridge = bridge(BusinessDocumentState.ACTIVE, DocumentVersionState.AVAILABLE, store);
        assertThatThrownBy(() -> bridge.getContent(documentId, versionId))
                .isInstanceOf(DocumentContentNotFoundException.class);
    }

    private DocumentBridge bridge(BusinessDocumentState documentState, DocumentVersionState versionState,
                                  FilesystemDocumentContentStore store) {
        var document = new BusinessDocument(documentId, owner, Map.of("category", "invoice"), documentState);
        var version = new DocumentVersion(versionId, documentId, 1, versionState);
        var documents = new BusinessDocumentRepository() {
            public Optional<BusinessDocument> findById(UUID id) {
                return documentId.equals(id) ? Optional.of(document) : Optional.empty();
            }
            public List<BusinessDocument> findByOwner(DocumentOwnerRef ref) {
                return owner.equals(ref) ? List.of(document) : List.of();
            }
        };
        return new DefaultDocumentBridge(documents,
                (documentKey, versionKey) -> documentId.equals(documentKey) && versionId.equals(versionKey)
                        ? Optional.of(version) : Optional.empty(), store);
    }
}
