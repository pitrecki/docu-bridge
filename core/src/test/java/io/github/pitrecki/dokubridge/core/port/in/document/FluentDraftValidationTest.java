package io.github.pitrecki.dokubridge.core.port.in.document;

import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FluentDraftValidationTest {
    final UUID id = UUID.randomUUID();
    final DefaultDocumentBridge bridge = new DefaultDocumentBridge(new BusinessDocumentRepository() {
        public Optional<BusinessDocument> findById(UUID document) { throw new AssertionError("Unexpected lookup"); }
        public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) { throw new AssertionError("Unexpected lookup"); }
    }, (document, version) -> { throw new AssertionError("Unexpected version query"); }, new DocumentContentStore() {
        public void store(UUID version, InputStream source) { throw new AssertionError("Unexpected write"); }
        public InputStream open(UUID version) { throw new AssertionError("Unexpected open"); }
    });
    final InputStream untouched = new InputStream() {
        public int read() { throw new AssertionError("Draft configuration must not read streams"); }
        public void close() { throw new AssertionError("Draft configuration must not close caller streams"); }
    };

    @Test
    void configuringEveryDraftPerformsNoRepositoryOrStreamIo() {
        bridge.upload(new byte[]{42}).toOwner("Invoice", "42").metadata("nullable", null).requestId(id);
        bridge.upload(untouched, "a".repeat(64)).toOwner(new DocumentOwnerRef("Invoice", "42")).requestId(id);
        var document = bridge.document(id);
        document.upload(new byte[]{42}).requestId(id);
        document.upload(untouched, new ContentSha256("a".repeat(64))).requestId(id);
        document.replaceMetadata(Map.of("valid", true)).atRevision(0);
    }

    @Test
    void rejectsInvalidDraftsBeforeAnyIo() {
        assertThatNullPointerException().isThrownBy(() -> bridge.upload((byte[]) null));
        assertThatNullPointerException().isThrownBy(() -> bridge.upload(null, new ContentSha256("a".repeat(64))));
        assertThatNullPointerException().isThrownBy(() -> bridge.document(null));
        assertThatNullPointerException().isThrownBy(() -> bridge.upload(new byte[0]).toOwner(null));
        assertThatIllegalArgumentException().isThrownBy(() -> bridge.upload(new byte[0]).toOwner(" ", "42"));
        var draft = bridge.upload(new byte[0]).toOwner("Invoice", "42");
        assertThatNullPointerException().isThrownBy(() -> draft.requestId(null));
        assertThatIllegalArgumentException().isThrownBy(() -> draft.metadata("invalid", new Object()));
        assertThatIllegalArgumentException().isThrownBy(() -> bridge.document(id).replaceMetadata(Map.of()).atRevision(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> bridge.upload(untouched, "invalid"));
        assertThatNullPointerException().isThrownBy(() -> bridge.document(id).upload(new byte[0]).requestId(null));
    }
}
