package io.github.pitrecki.dokubridge.adapter.jpa.upload;

import io.github.pitrecki.dokubridge.adapter.filesystem.content.FilesystemDocumentContentStore;
import io.github.pitrecki.dokubridge.adapter.jpa.audit.JpaDocumentAuditRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.document.JpaBusinessDocumentRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.document.JpaDocumentLifecycleRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.version.JpaDocumentVersionRepository;
import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentRevisionConflictException;
import io.github.pitrecki.dokubridge.core.domain.upload.exception.UploadRequestConflictException;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.upload.AttachDocument;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@Timeout(45)
class FluentDocumentBridgeTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager entities;
    static JpaTransactionManager transactions;
    @TempDir Path root;
    final byte[] bytes = {42};
    JpaDocumentUploadRepository uploads;
    JpaDocumentAuditRepository audits;
    FilesystemDocumentContentStore files;
    DefaultDocumentBridge bridge;

    @BeforeAll
    static void initializePersistence() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/dokubridge")
                .table("dokubridge_flyway_history").load().migrate();
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan("io.github.pitrecki.dokubridge.adapter.jpa");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
        factory.afterPropertiesSet();
        transactions = new JpaTransactionManager(factory.getObject());
        entities = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
    }

    @AfterAll
    static void closePersistence() {
        if (factory != null) factory.destroy();
    }

    @BeforeEach
    void reset() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> entities.createNativeQuery("""
                truncate dokubridge_audit, dokubridge_upload_requests, dokubridge_versions, dokubridge_documents
                restart identity cascade
                """).executeUpdate());
        uploads = new JpaDocumentUploadRepository(entities, transactions);
        audits = new JpaDocumentAuditRepository(entities, transactions);
        files = new FilesystemDocumentContentStore(root);
        bridge = new DefaultDocumentBridge(new JpaBusinessDocumentRepository(entities, transactions),
                new JpaDocumentVersionRepository(entities, transactions), files, uploads,
                new JpaDocumentLifecycleRepository(entities, transactions), audits);
    }

    @Test
    void eachUnkeyedSaveCreatesANewAttachmentWithItsOwnChecksumAndRequest() throws Exception {
        var draft = bridge.upload(bytes).toOwner("Invoice", "42");
        assertThat(bridge.findByOwner("Invoice", "42")).isEmpty();
        var first = draft.save();
        var second = draft.save();
        assertThat(first.documentId()).isNotEqualTo(second.documentId());
        assertThat(first.state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(first.number()).isEqualTo(1);
        assertThat(request(first)).isNotEqualTo(request(second));
        assertThat(uploads.inspectForRecovery(request(first), first.id()).expectedChecksum()).contains(ContentSha256.of(bytes));
        assertThat(bridge.findByOwner("Invoice", "42")).hasSize(2);
    }

    @Test
    void byteContentAndMetadataAreSnapshotsAndDraftBranchesRemainIndependent() throws Exception {
        byte[] mutable = {42};
        var nested = new ArrayList<>(List.of("original"));
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("nested", nested);
        var original = bridge.upload(mutable).toOwner("Invoice", "42").metadata(metadata);
        var branch = original.metadata("category", "invoice").metadata("nullable", null);
        mutable[0] = 7;
        nested.clear();
        metadata.clear();
        var first = original.save();
        var second = branch.save();
        assertThat(download(first)).containsExactly((byte) 42);
        assertThat(download(second)).containsExactly((byte) 42);
        assertThat(bridge.document(first.documentId()).get().orElseThrow().metadata())
                .containsOnlyKeys("nested").containsEntry("nested", List.of("original"));
        assertThat(bridge.document(second.documentId()).get().orElseThrow().metadata())
                .containsEntry("category", "invoice").containsEntry("nullable", null);
        var replaced = branch.metadata(Map.of("only", "replacement")).save();
        assertThat(bridge.document(replaced.documentId()).get().orElseThrow().metadata()).containsOnlyKeys("only");
    }

    @Test
    void explicitKeysReplayAcrossBothApisAndChangedMetadataStillConflicts() throws Exception {
        UUID key = UUID.randomUUID();
        var owner = new DocumentOwnerRef("Invoice", "42");
        var draft = bridge.upload(bytes).toOwner(owner).metadata("kind", "invoice").requestId(key);
        var first = draft.save();
        assertThat(draft.save()).isEqualTo(first);
        assertThat(bridge.attach(new AttachDocument(key, owner, Map.of("kind", "invoice"), ContentSha256.of(bytes),
                new ByteArrayInputStream(bytes)))).isEqualTo(first);
        assertThatThrownBy(() -> draft.metadata("kind", "changed").save()).isInstanceOf(UploadRequestConflictException.class);
        assertThat(bridge.document(first.documentId()).versions()).containsExactly(first);
        assertThat(bridge.document(first.documentId()).audit()).hasSize(2);
    }

    @Test
    void versionDraftsDistinguishExplicitRetriesFromNewUnkeyedSaves() throws Exception {
        var first = bridge.upload(bytes).toOwner("Invoice", "42").save();
        var document = bridge.document(first.documentId());
        UUID key = UUID.randomUUID();
        var explicit = document.upload(new byte[]{7}).requestId(key);
        var second = explicit.save();
        assertThat(explicit.save()).isEqualTo(second);
        var unkeyed = document.upload(new byte[]{8});
        var third = unkeyed.save();
        var fourth = unkeyed.save();
        assertThat(document.versions()).containsExactly(first, second, third, fourth);
        assertThat(request(second)).isEqualTo(key);
        assertThat(request(third)).isNotEqualTo(request(fourth));
        assertThat(download(second)).containsExactly((byte) 7);
    }

    @Test
    void callerStreamIsUntouchedDuringConfigurationAndRemainsOpenAfterSaveAndReplay() throws Exception {
        var closed = new AtomicBoolean();
        var reads = new AtomicInteger();
        var source = new ByteArrayInputStream(bytes) {
            public synchronized int read(byte[] buffer, int offset, int length) {
                reads.incrementAndGet();
                return super.read(buffer, offset, length);
            }
            public void close() { closed.set(true); }
        };
        var draft = bridge.upload(source, ContentSha256.of(bytes).value()).toOwner("Invoice", "42").requestId(UUID.randomUUID());
        assertThat(reads).hasValue(0);
        assertThat(closed).isFalse();
        var first = draft.save();
        int consumed = reads.get();
        assertThat(consumed).isPositive();
        assertThat(draft.save()).isEqualTo(first);
        assertThat(reads).hasValue(consumed);
        assertThat(closed).isFalse();
        source.close();
        assertThat(closed).isTrue();
    }

    @Test
    void versionStreamFailurePreservesItsCauseAndCallerOwnershipAndFailedReplayDoesNotReadAgain() throws Exception {
        var first = bridge.upload(bytes).toOwner("Invoice", "42").save();
        var closed = new AtomicBoolean();
        var reads = new AtomicInteger();
        var failure = new IOException("Interrupted input");
        var source = new InputStream() {
            public int read() throws IOException { reads.incrementAndGet(); throw failure; }
            public void close() { closed.set(true); }
        };
        var draft = bridge.document(first.documentId()).upload(source, ContentSha256.of(bytes)).requestId(UUID.randomUUID());
        assertThatThrownBy(draft::save).isSameAs(failure);
        int attempted = reads.get();
        var failed = draft.save();
        assertThat(failed.state()).isEqualTo(DocumentVersionState.FAILED);
        assertThat(reads).hasValue(attempted);
        assertThat(closed).isFalse();
        source.close();
        assertThat(closed).isTrue();
    }

    @Test
    void emptyByteContentIsAvailableAndDownloadable() throws Exception {
        var version = bridge.upload(new byte[0]).toOwner("Invoice", "42").save();
        assertThat(version.state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(download(version)).isEmpty();
    }

    @Test
    void metadataReplacementKeepsItsSnapshotAndRequiresTheObservedRevision() throws Exception {
        var first = bridge.upload(bytes).toOwner("Invoice", "42").save();
        var document = bridge.document(first.documentId());
        var nested = new ArrayList<>(List.of("original"));
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("nested", nested);
        var replacement = document.replaceMetadata(metadata);
        nested.clear();
        metadata.clear();
        var ready = replacement.atRevision(0);
        assertThat(document.get().orElseThrow().revision()).isZero();
        assertThat(document.audit()).hasSize(2);
        assertThat(ready.save().metadata()).containsEntry("nested", List.of("original"));
        assertThatThrownBy(ready::save).isInstanceOf(DocumentRevisionConflictException.class);
        assertThat(document.audit()).hasSize(3);
        assertThat(replacement.atRevision(1).save().revision()).isEqualTo(2);
        assertThat(document.replaceMetadata(Map.of()).atRevision(2).save().metadata()).isEmpty();
        assertThat(document.versions()).containsExactly(first);
    }

    @Test
    void documentHandleUsesCurrentVisibilityAndDelegatesLifecycleWithoutCachingState() throws Exception {
        var original = bridge.upload(bytes).toOwner("Invoice", "42").requestId(UUID.randomUUID());
        var first = original.save();
        var document = bridge.document(first.documentId());
        var archived = document.archive();
        assertThat(document.archive()).isEqualTo(archived);
        assertThat(document.get().orElseThrow().state()).isEqualTo(BusinessDocumentState.ARCHIVED);
        var second = document.upload(new byte[]{7}).save();
        assertThat(document.versions()).containsExactly(first, second);
        document.delete();
        document.delete();
        assertThat(document.get()).isEmpty();
        assertThatThrownBy(document::versions).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(document::audit).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> document.content(first.id())).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> document.upload(bytes).save()).isInstanceOf(DocumentNotFoundException.class);
        assertThat(original.save()).isEqualTo(first);
        assertThat(audits.findByDocument(first.documentId())).hasSize(6);
    }

    @Test
    void recoveryCanBeInvokedWithoutConstructingACommand() throws Exception {
        UUID key = UUID.randomUUID();
        var pending = uploads.reserveAttach(key, "a".repeat(64), new DocumentOwnerRef("Invoice", "42"), Map.of(),
                ContentSha256.of(bytes)).version();
        files.store(pending.id(), new ByteArrayInputStream(bytes));
        var recovered = bridge.reconcileUpload(key, pending.id());
        assertThat(recovered.state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(bridge.document(pending.documentId()).audit().getLast().action()).isEqualTo(DocumentAuditAction.UPLOAD_RECOVERED);
        assertThat(download(recovered)).containsExactly(bytes);
    }

    private UUID request(DocumentVersion version) {
        return bridge.document(version.documentId()).audit().stream()
                .filter(event -> version.id().equals(event.versionId())).findFirst().orElseThrow().requestId();
    }

    private byte[] download(DocumentVersion version) throws IOException {
        try (var input = bridge.document(version.documentId()).content(version.id())) {
            return input.readAllBytes();
        }
    }
}
