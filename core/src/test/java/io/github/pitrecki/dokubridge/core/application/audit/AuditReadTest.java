package io.github.pitrecki.dokubridge.core.application.audit;

import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.in.document.Archive;
import io.github.pitrecki.dokubridge.core.port.in.upload.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.out.audit.DocumentAuditRepository;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
import io.github.pitrecki.dokubridge.core.port.out.version.DocumentVersionRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
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

class AuditReadTest {
    final UUID documentId = UUID.randomUUID();
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "audit-42");
    BusinessDocument document;
    final List<DocumentAuditEntry> entries = new ArrayList<>();
    int queries;
    RuntimeException failure;
    final BusinessDocumentRepository documents = new BusinessDocumentRepository() {
        public Optional<BusinessDocument> findById(UUID id) { return Optional.ofNullable(document); }
        public List<BusinessDocument> findByOwner(DocumentOwnerRef ref) { return List.of(); }
    };
    final DocumentVersionRepository versions = (doc, version) -> {
        throw new AssertionError("Audit must not query versions");
    };
    final DocumentContentStore content = new DocumentContentStore() {
        public void store(UUID id, InputStream source) { throw new AssertionError("Audit must not store content"); }
        public InputStream open(UUID id) { throw new AssertionError("Audit must not open content"); }
    };
    final DocumentAuditRepository audits = id -> {
        assertThat(id).isEqualTo(documentId);
        queries++;
        if (failure != null) throw failure;
        return entries;
    };
    final DocumentBridge bridge = new DefaultDocumentBridge(documents, versions, audits, content);

    @ParameterizedTest
    @EnumSource(value = BusinessDocumentState.class, names = {"ACTIVE", "ARCHIVED"})
    void returnsImmutableScopedAuditInRepositoryOrderRatherThanTimestampOrder(BusinessDocumentState state) {
        visible(state);
        var upload = uploadEntry(documentId, Instant.parse("2030-01-01T00:00:00Z"));
        var metadata = new DocumentAuditEntry(UUID.randomUUID(), documentId, null, null,
                DocumentAuditAction.METADATA_UPDATED, Instant.parse("2000-01-01T00:00:00Z"));
        var foreign = uploadEntry(UUID.randomUUID(), Instant.EPOCH);
        entries.addAll(List.of(upload, foreign, metadata));
        var result = bridge.getAudit(documentId);
        entries.clear();
        assertThat(result).containsExactly(upload, metadata);
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(queries).isEqualTo(1);
    }

    @Test
    void visibleDocumentWithoutEventsHasAnEmptyAudit() {
        visible(BusinessDocumentState.ACTIVE);
        assertThat(bridge.getAudit(documentId)).isEmpty();
        assertThat(queries).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deleted", "foreign"})
    void inaccessibleDocumentsAreRejectedBeforeQueryingAudit(String scenario) {
        if (scenario.equals("deleted")) visible(BusinessDocumentState.DELETED);
        if (scenario.equals("foreign")) {
            document = new BusinessDocument(UUID.randomUUID(), owner, Map.of(), BusinessDocumentState.ACTIVE);
        }
        assertThatThrownBy(() -> bridge.getAudit(documentId)).isInstanceOf(DocumentNotFoundException.class);
        assertThat(queries).isZero();
    }

    @Test
    void auditReadOnlyCompositionDoesNotEnableWrites() {
        visible(BusinessDocumentState.ACTIVE);
        assertThat(bridge.getAudit(documentId)).isEmpty();
        var command = new AttachDocument(UUID.randomUUID(), owner, Map.of(), new ContentSha256("a".repeat(64)),
                new ByteArrayInputStream(new byte[]{42}));
        assertThatThrownBy(() -> bridge.attach(command)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> bridge.archive(new Archive(documentId))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void existingCompositionAndCustomFacadeKeepUnsupportedAuditDefaults() {
        visible(BusinessDocumentState.ACTIVE);
        var legacy = new DefaultDocumentBridge(documents, versions, content);
        assertThat(legacy.get(documentId)).contains(document);
        assertThatThrownBy(() -> legacy.getAudit(documentId)).isInstanceOf(UnsupportedOperationException.class);
        DocumentBridge custom = new DocumentBridge() {
            public Optional<BusinessDocument> get(UUID id) { return Optional.of(document); }
            public List<BusinessDocument> findByOwner(DocumentOwnerRef ref) { return List.of(document); }
            public InputStream getContent(UUID doc, UUID version) { throw new UnsupportedOperationException(); }
        };
        assertThatThrownBy(() -> custom.getAudit(documentId)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(queries).isZero();
    }

    @Test
    void repositoryFailurePropagatesWithoutReturningAnEmptyHistory() {
        visible(BusinessDocumentState.ACTIVE);
        failure = new IllegalStateException("Audit query failed");
        assertThatThrownBy(() -> bridge.getAudit(documentId)).isSameAs(failure);
    }

    @Test
    void validatesAuditCompositionAndQueryArguments() {
        assertThatNullPointerException().isThrownBy(() -> bridge.getAudit(null));
        assertThatNullPointerException().isThrownBy(() -> new DefaultDocumentBridge(null, versions, audits, content));
        assertThatNullPointerException().isThrownBy(() -> new DefaultDocumentBridge(documents, null, audits, content));
        assertThatNullPointerException().isThrownBy(() -> new DefaultDocumentBridge(documents, versions, null, content));
        assertThatNullPointerException().isThrownBy(() -> new DefaultDocumentBridge(documents, versions, audits, null));
        assertThat(queries).isZero();
    }

    private void visible(BusinessDocumentState state) {
        document = new BusinessDocument(documentId, owner, Map.of(), state);
    }

    private DocumentAuditEntry uploadEntry(UUID document, Instant time) {
        return new DocumentAuditEntry(UUID.randomUUID(), document, UUID.randomUUID(), UUID.randomUUID(),
                DocumentAuditAction.UPLOAD_SUCCEEDED, time);
    }
}
