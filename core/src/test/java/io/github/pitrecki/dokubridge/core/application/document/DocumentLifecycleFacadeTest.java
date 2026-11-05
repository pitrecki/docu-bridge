package io.github.pitrecki.dokubridge.core.application.document;

import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.document.Archive;
import io.github.pitrecki.dokubridge.core.port.in.document.Delete;
import io.github.pitrecki.dokubridge.core.port.in.document.UpdateMetadata;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
import io.github.pitrecki.dokubridge.core.port.out.document.DocumentLifecycleRepository;
import io.github.pitrecki.dokubridge.core.port.out.upload.DocumentUploadRepository;
import io.github.pitrecki.dokubridge.core.port.out.upload.UploadReservation;
import io.github.pitrecki.dokubridge.core.port.out.version.DocumentVersionRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DocumentLifecycleFacadeTest {
    final UUID id = UUID.randomUUID();
    final BusinessDocument result = new BusinessDocument(id, new DocumentOwnerRef("Invoice", "42"), Map.of(), BusinessDocumentState.ACTIVE, 1);
    final List<String> operations = new ArrayList<>();
    final BusinessDocumentRepository documents = new BusinessDocumentRepository() {
        public Optional<BusinessDocument> findById(UUID id) { throw new AssertionError("Mutation must use its atomic port"); }
        public List<BusinessDocument> findByOwner(DocumentOwnerRef ref) { throw new AssertionError("Unexpected read"); }
    };
    final DocumentVersionRepository versions = (document, version) -> { throw new AssertionError("Unexpected version read"); };
    final DocumentContentStore content = new DocumentContentStore() {
        public void store(UUID id, InputStream source) { throw new AssertionError("Lifecycle must not write bytes"); }
        public InputStream open(UUID id) { throw new AssertionError("Lifecycle must not read bytes"); }
    };
    final DocumentUploadRepository uploads = new DocumentUploadRepository() {
        public UploadReservation reserveAttach(UUID id, String hash, DocumentOwnerRef owner, Map<String, Object> metadata) { throw new AssertionError("Unexpected upload"); }
        public UploadReservation reserveVersion(UUID id, String hash, UUID document) { throw new AssertionError("Unexpected upload"); }
        public DocumentVersion complete(UUID request, UUID version, DocumentVersionState outcome) { throw new AssertionError("Unexpected upload"); }
    };
    final DocumentLifecycleRepository lifecycle = new DocumentLifecycleRepository() {
        public BusinessDocument updateMetadata(UUID document, long revision, Map<String, Object> metadata) {
            assertThat(document).isEqualTo(id);
            assertThat(revision).isEqualTo(0);
            assertThat(metadata).containsEntry("new", true);
            operations.add("metadata");
            return result;
        }
        public BusinessDocument archive(UUID document) {
            assertThat(document).isEqualTo(id);
            operations.add("archive");
            return result;
        }
        public void delete(UUID document) {
            assertThat(document).isEqualTo(id);
            operations.add("delete");
        }
    };

    @Test
    void mutationsDelegateToTheAtomicPortWithoutReadingOrTouchingContent() {
        var bridge = new DefaultDocumentBridge(documents, versions, content, uploads, lifecycle);
        assertThat(bridge.updateMetadata(new UpdateMetadata(id, 0, Map.of("new", true)))).isSameAs(result);
        assertThat(bridge.archive(new Archive(id))).isSameAs(result);
        bridge.delete(new Delete(id));
        assertThat(operations).containsExactly("metadata", "archive", "delete");
    }

    @Test
    void oldConstructorsKeepLifecycleOperationsDisabled() {
        for (var bridge : List.of(new DefaultDocumentBridge(documents, versions, content),
                new DefaultDocumentBridge(documents, versions, content, uploads))) {
            assertThatThrownBy(() -> bridge.updateMetadata(new UpdateMetadata(id, 0, Map.of())))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> bridge.archive(new Archive(id))).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> bridge.delete(new Delete(id))).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void validatesCommandsAndLifecycleDependency() {
        var bridge = new DefaultDocumentBridge(documents, versions, content, uploads, lifecycle);
        assertThatNullPointerException().isThrownBy(() -> bridge.updateMetadata(null));
        assertThatNullPointerException().isThrownBy(() -> bridge.archive(null));
        assertThatNullPointerException().isThrownBy(() -> bridge.delete(null));
        assertThatNullPointerException().isThrownBy(() -> new DefaultDocumentBridge(documents, versions, content, uploads, null));
        assertThatNullPointerException().isThrownBy(() -> new Archive(null));
        assertThatNullPointerException().isThrownBy(() -> new Delete(null));
        assertThatNullPointerException().isThrownBy(() -> new UpdateMetadata(null, 0, Map.of()));
        assertThatNullPointerException().isThrownBy(() -> new UpdateMetadata(id, 0, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new UpdateMetadata(id, -1, Map.of()));
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("new", true);
        var command = new UpdateMetadata(id, 0, mutable);
        mutable.clear();
        assertThat(command.metadata()).containsEntry("new", true);
    }
}
