package io.github.pitrecki.dokubridge.core.application.audit;

import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.port.out.audit.DocumentAuditRepository;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
import io.github.pitrecki.dokubridge.core.port.out.version.DocumentVersionRepository;
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

class AuditPageReadTest {
    final UUID id = UUID.randomUUID();
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "audit-page-42");
    BusinessDocument document = new BusinessDocument(id, owner, Map.of(), BusinessDocumentState.ACTIVE);
    final List<DocumentAuditEntry> rows = new ArrayList<>();
    int documentQueries;
    int pageQueries;
    int suppliedLimit;
    UUID suppliedCursor;
    RuntimeException pageFailure;
    final BusinessDocumentRepository documents = new BusinessDocumentRepository() {
        public Optional<BusinessDocument> findById(UUID requested) {
            assertThat(requested).isEqualTo(id);
            documentQueries++;
            return Optional.ofNullable(document);
        }
        public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) { throw new AssertionError("Unexpected owner lookup"); }
    };
    final DocumentVersionRepository versions = (document, version) -> { throw new AssertionError("Unexpected version lookup"); };
    final DocumentContentStore content = new DocumentContentStore() {
        public void store(UUID id, InputStream source) { throw new AssertionError("Unexpected content write"); }
        public InputStream open(UUID id) { throw new AssertionError("Audit must not open content"); }
    };
    final DocumentAuditRepository audits = new DocumentAuditRepository() {
        public List<DocumentAuditEntry> findByDocument(UUID document) { throw new AssertionError("Paged reads must not load the full audit"); }
        public List<DocumentAuditEntry> findByDocument(UUID document, int limit, UUID after) {
            assertThat(document).isEqualTo(id);
            pageQueries++;
            suppliedLimit = limit;
            suppliedCursor = after;
            if (pageFailure != null) throw pageFailure;
            return rows;
        }
    };
    final DefaultDocumentBridge bridge = new DefaultDocumentBridge(documents, versions, audits, content);

    @ParameterizedTest
    @EnumSource(value = BusinessDocumentState.class, names = {"ACTIVE", "ARCHIVED"})
    void pagesAreImmutableScopedAndPreserveInsertionOrder(BusinessDocumentState state) {
        document = new BusinessDocument(id, owner, Map.of(), state);
        var first = entry("ffffffff-ffff-ffff-ffff-ffffffffffff", id, "2030-01-01T00:00:00Z");
        var second = entry("00000000-0000-0000-0000-000000000001", id, "2000-01-01T00:00:00Z");
        var third = entry("80000000-0000-0000-0000-000000000000", id, "1990-01-01T00:00:00Z");
        var foreign = entry(UUID.randomUUID().toString(), UUID.randomUUID(), "2020-01-01T00:00:00Z");
        rows.addAll(List.of(first, foreign, second, third));
        var page = bridge.document(id).audit(2);
        assertThat(suppliedCursor).isNull();
        rows.clear();
        rows.add(third);
        var next = bridge.document(id).audit(2, second.id());
        rows.clear();
        assertThat(page).containsExactly(first, second);
        assertThat(next).containsExactly(third);
        assertThatThrownBy(page::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(suppliedLimit).isEqualTo(2);
        assertThat(suppliedCursor).isEqualTo(second.id());
        assertThat(pageQueries).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deleted", "foreign"})
    void inaccessibleDocumentsFailBeforeLookingUpTheCursor(String scenario) {
        if (scenario.equals("missing")) document = null;
        if (scenario.equals("deleted")) document = document.delete();
        if (scenario.equals("foreign")) document = new BusinessDocument(UUID.randomUUID(), owner, Map.of(), BusinessDocumentState.ACTIVE);
        assertThatThrownBy(() -> bridge.getAudit(id, 10, UUID.randomUUID())).isInstanceOf(DocumentNotFoundException.class);
        assertThat(pageQueries).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1001, Integer.MAX_VALUE})
    void invalidLimitsFailBeforeAnyQuery(int limit) {
        assertThatIllegalArgumentException().isThrownBy(() -> bridge.getAudit(id, limit));
        assertThat(documentQueries).isZero();
        assertThat(pageQueries).isZero();
    }

    @Test
    void nullDocumentIdFailsBeforeAnyQuery() {
        assertThatNullPointerException().isThrownBy(() -> bridge.getAudit(null, 1));
        assertThat(documentQueries).isZero();
        assertThat(pageQueries).isZero();
    }

    @Test
    void aVisibleDocumentCanHaveAnImmutableEmptyPageAtTheMaximumLimit() {
        var page = bridge.getAudit(id, 1000);
        assertThat(page).isEmpty();
        assertThatThrownBy(page::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(suppliedLimit).isEqualTo(1000);
    }

    @Test
    void invalidCursorsAndQueryFailuresPropagateInsteadOfBecomingEmptyPages() {
        pageFailure = new IllegalArgumentException("Unknown cursor");
        assertThatThrownBy(() -> bridge.getAudit(id, 1, UUID.randomUUID())).isSameAs(pageFailure);
        pageFailure = new IllegalStateException("Query failed");
        assertThatThrownBy(() -> bridge.getAudit(id, 1)).isSameAs(pageFailure);
    }

    @Test
    void legacyCompositionsKeepUnpagedAuditWithoutAPagingFallback() {
        var first = entry(UUID.randomUUID().toString(), id, "2020-01-01T00:00:00Z");
        var legacy = new DefaultDocumentBridge(documents, versions, document -> List.of(first), content);
        assertThat(legacy.getAudit(id)).containsExactly(first);
        assertThatThrownBy(() -> legacy.getAudit(id, 10)).isInstanceOf(UnsupportedOperationException.class);
        var disabled = new DefaultDocumentBridge(documents, versions, content);
        assertThatThrownBy(() -> disabled.getAudit(id, 10)).isInstanceOf(UnsupportedOperationException.class);
    }

    private DocumentAuditEntry entry(String eventId, UUID documentId, String timestamp) {
        return new DocumentAuditEntry(UUID.fromString(eventId), documentId, null, null,
                DocumentAuditAction.METADATA_UPDATED, Instant.parse(timestamp));
    }
}
