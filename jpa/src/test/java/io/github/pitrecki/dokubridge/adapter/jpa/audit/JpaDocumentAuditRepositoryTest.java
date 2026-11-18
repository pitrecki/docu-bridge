package io.github.pitrecki.dokubridge.adapter.jpa.audit;

import io.github.pitrecki.dokubridge.adapter.filesystem.content.FilesystemDocumentContentStore;
import io.github.pitrecki.dokubridge.adapter.jpa.document.JpaBusinessDocumentRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.document.JpaDocumentLifecycleRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.document.persistence.BusinessDocumentEntity;
import io.github.pitrecki.dokubridge.adapter.jpa.upload.JpaDocumentUploadRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.version.JpaDocumentVersionRepository;
import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.content.exception.ContentChecksumMismatchException;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.document.Archive;
import io.github.pitrecki.dokubridge.core.port.in.document.Delete;
import io.github.pitrecki.dokubridge.core.port.in.document.UpdateMetadata;
import io.github.pitrecki.dokubridge.core.port.in.upload.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.upload.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.in.upload.ReconcileUpload;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@Timeout(45)
class JpaDocumentAuditRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager entities;
    static JpaTransactionManager transactions;
    @TempDir Path root;
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "audit-42");
    final byte[] bytes = {42};
    ContentSha256 checksum;
    JpaDocumentUploadRepository uploads;
    JpaDocumentAuditRepository audits;
    FilesystemDocumentContentStore files;
    DefaultDocumentBridge bridge;
    DefaultDocumentBridge reader;

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
        checksum = new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var documents = new JpaBusinessDocumentRepository(entities, transactions);
        var versions = new JpaDocumentVersionRepository(entities, transactions);
        uploads = new JpaDocumentUploadRepository(entities, transactions);
        audits = new JpaDocumentAuditRepository(entities, transactions);
        files = new FilesystemDocumentContentStore(root);
        bridge = new DefaultDocumentBridge(documents, versions, files, uploads,
                new JpaDocumentLifecycleRepository(entities, transactions), audits);
        reader = new DefaultDocumentBridge(documents, versions, audits, new DocumentContentStore() {
            public void store(UUID id, InputStream source) { throw new AssertionError("Audit must not write content"); }
            public InputStream open(UUID id) { throw new AssertionError("Audit must not open content"); }
        });
    }

    @Test
    void exposesUploadAndDocumentEventsInImmutableScopedHistoryWithoutContentIo() throws Exception {
        UUID request = UUID.randomUUID();
        var first = bridge.attach(command(request));
        assertThat(bridge.attach(command(request))).isEqualTo(first);
        UUID failedRequest = UUID.randomUUID();
        assertThatThrownBy(() -> bridge.addVersion(new AddDocumentVersion(failedRequest, first.documentId(),
                new ContentSha256("0".repeat(64)), new ByteArrayInputStream(bytes))))
                .isInstanceOf(ContentChecksumMismatchException.class);
        var pending = uploads.reserveVersion(UUID.randomUUID(), "a".repeat(64), first.documentId(), checksum).version();
        var other = bridge.attach(command(UUID.randomUUID()));
        assertThat(reader.getAudit(other.documentId())).hasSize(2);
        bridge.updateMetadata(new UpdateMetadata(first.documentId(), 0, Map.of("updated", true)));
        assertThat(reader.getAudit(first.documentId())).hasSize(6);
        bridge.archive(new Archive(first.documentId()));
        bridge.archive(new Archive(first.documentId()));
        var result = reader.getAudit(first.documentId());
        assertThat(result).extracting(DocumentAuditEntry::action).containsExactly(
                DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_SUCCEEDED,
                DocumentAuditAction.VERSION_ADDED, DocumentAuditAction.UPLOAD_FAILED,
                DocumentAuditAction.VERSION_ADDED, DocumentAuditAction.METADATA_UPDATED,
                DocumentAuditAction.DOCUMENT_ARCHIVED);
        assertThat(result).allSatisfy(entry -> {
            assertThat(entry.documentId()).isEqualTo(first.documentId());
            if (entry.action().requiresUploadContext()) {
                assertThat(entry.versionId()).isNotNull();
                assertThat(entry.requestId()).isNotNull();
            } else {
                assertThat(entry.versionId()).isNull();
                assertThat(entry.requestId()).isNull();
            }
        });
        assertThat(result.getFirst().requestId()).isEqualTo(request);
        assertThat(result.get(3).requestId()).isEqualTo(failedRequest);
        assertThat(result.get(4).versionId()).isEqualTo(pending.id());
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(reader.getAudit(first.documentId())).isEqualTo(result);
        var page = reader.document(first.documentId()).audit(3);
        assertThat(page).containsExactlyElementsOf(result.subList(0, 3));
        var next = reader.document(first.documentId()).audit(3, page.getLast().id());
        assertThat(next).containsExactlyElementsOf(result.subList(3, 6));
        var tail = reader.getAudit(first.documentId(), 3, next.getLast().id());
        assertThat(tail).containsExactly(result.getLast());
        assertThat(reader.getAudit(first.documentId(), 3, tail.getLast().id())).isEmpty();
        assertThatThrownBy(page::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(bridge.getVersions(first.documentId())).extracting(version -> version.state()).containsExactly(
                DocumentVersionState.AVAILABLE, DocumentVersionState.FAILED, DocumentVersionState.PENDING);
        assertThat(bridge.get(first.documentId()).orElseThrow().revision()).isEqualTo(2);
    }

    @Test
    void deletionHidesAuditEvenWithARetainedHostContextWhileRepositoryRetainsAllEvents() throws Exception {
        var version = bridge.attach(command(UUID.randomUUID()));
        var retained = factory.getObject().createEntityManager();
        try {
            retained.find(BusinessDocumentEntity.class, version.documentId());
            TransactionSynchronizationManager.bindResource(factory.getObject(), new EntityManagerHolder(retained));
            assertThat(reader.getAudit(version.documentId())).hasSize(2);
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> bridge.delete(new Delete(version.documentId()))).get(20, TimeUnit.SECONDS);
            }
            assertThatThrownBy(() -> reader.getAudit(version.documentId())).isInstanceOf(DocumentNotFoundException.class);
            assertThatThrownBy(() -> reader.getAudit(version.documentId(), 1, UUID.randomUUID()))
                    .isInstanceOf(DocumentNotFoundException.class);
        } finally {
            if (TransactionSynchronizationManager.hasResource(factory.getObject())) {
                TransactionSynchronizationManager.unbindResource(factory.getObject());
            }
            retained.close();
        }
        assertThat(audits.findByDocument(version.documentId())).extracting(DocumentAuditEntry::action).containsExactly(
                DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_SUCCEEDED, DocumentAuditAction.DOCUMENT_DELETED);
        assertThat(audits.findByDocument(version.documentId(), 1, null)).singleElement()
                .satisfies(entry -> assertThat(entry.action()).isEqualTo(DocumentAuditAction.DOCUMENT_ATTACHED));
        UUID missing = UUID.randomUUID();
        assertThat(audits.findByDocument(missing)).isEmpty();
        assertThatThrownBy(() -> reader.getAudit(missing)).isInstanceOf(DocumentNotFoundException.class);
        assertThatNullPointerException().isThrownBy(() -> audits.findByDocument(null));
    }

    @Test
    void insertionOrderSurvivesNonMonotonicEventTimestamps() throws Exception {
        var version = bridge.attach(command(UUID.randomUUID()));
        bridge.updateMetadata(new UpdateMetadata(version.documentId(), 0, Map.of()));
        bridge.archive(new Archive(version.documentId()));
        // Simulate producer clocks moving backwards; timestamps cannot establish write order.
        new TransactionTemplate(transactions).executeWithoutResult(status -> entities.createNativeQuery("""
                update dokubridge_audit set occurred_at = case action
                    when 'DOCUMENT_ATTACHED' then timestamp with time zone '2030-01-01 00:00:00+00'
                    when 'UPLOAD_SUCCEEDED' then timestamp with time zone '2020-01-01 00:00:00+00'
                    when 'METADATA_UPDATED' then timestamp with time zone '2000-01-01 00:00:00+00'
                    else timestamp with time zone '1990-01-01 00:00:00+00' end,
                id = case action
                    when 'DOCUMENT_ATTACHED' then 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
                    when 'UPLOAD_SUCCEEDED' then '00000000-0000-0000-0000-000000000001'::uuid
                    when 'METADATA_UPDATED' then '80000000-0000-0000-0000-000000000000'::uuid
                    else '70000000-0000-0000-0000-000000000000'::uuid end
                where document_id = :document
                """).setParameter("document", version.documentId()).executeUpdate());
        var result = reader.getAudit(version.documentId());
        assertThat(result).extracting(DocumentAuditEntry::action).containsExactly(
                DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_SUCCEEDED,
                DocumentAuditAction.METADATA_UPDATED, DocumentAuditAction.DOCUMENT_ARCHIVED);
        assertThat(result.getFirst().occurredAt()).isAfter(result.getLast().occurredAt());
        var page = reader.getAudit(version.documentId(), 2);
        assertThat(page).containsExactlyElementsOf(result.subList(0, 2));
        assertThat(reader.getAudit(version.documentId(), 2, page.getLast().id()))
                .containsExactlyElementsOf(result.subList(2, 4));
    }

    @Test
    void unknownAndForeignCursorsAreRejectedInsteadOfEndingTheScan() throws Exception {
        var first = bridge.attach(command(UUID.randomUUID()));
        var other = bridge.attach(command(UUID.randomUUID()));
        UUID foreign = reader.getAudit(other.documentId()).getFirst().id();
        assertThatIllegalArgumentException().isThrownBy(() -> reader.getAudit(first.documentId(), 1, UUID.randomUUID()));
        assertThatIllegalArgumentException().isThrownBy(() -> reader.getAudit(first.documentId(), 1, foreign));
        assertThatIllegalArgumentException().isThrownBy(() -> audits.findByDocument(UUID.randomUUID(), 1, foreign));
        assertThat(reader.getAudit(first.documentId(), 1000)).hasSize(2);
    }

    @Test
    void pagedRepositoryValidatesItsBoundsAndAcceptsAnEmptyFirstPage() {
        UUID document = UUID.randomUUID();
        for (int limit : new int[]{-1, 0, 1001, Integer.MAX_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> audits.findByDocument(document, limit, null));
        }
        assertThatNullPointerException().isThrownBy(() -> audits.findByDocument(null, 1, null));
        assertThat(audits.findByDocument(document, 1000, null)).isEmpty();
    }

    @Test
    void pagesJoinAHostRepeatableReadTransactionAndLaterReadsSeeNewEvents() throws Exception {
        var version = bridge.attach(command(UUID.randomUUID()));
        var original = reader.getAudit(version.documentId());
        var read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.executeWithoutResult(status -> {
            var page = reader.getAudit(version.documentId(), 1);
            assertThat(page).containsExactly(original.getFirst());
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> bridge.updateMetadata(new UpdateMetadata(version.documentId(), 0, Map.of("new", true))))
                        .get(20, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError("Concurrent metadata update failed", failure);
            }
            assertThat(reader.getAudit(version.documentId(), 10, page.getLast().id())).containsExactly(original.getLast());
        });
        assertThat(reader.getAudit(version.documentId(), 10, original.getFirst().id())).extracting(DocumentAuditEntry::action)
                .containsExactly(DocumentAuditAction.UPLOAD_SUCCEEDED, DocumentAuditAction.METADATA_UPDATED);
    }

    @Test
    void recoveryEventsAreIncludedWithTheirUploadIdentifiers() throws Exception {
        UUID firstRequest = UUID.randomUUID();
        var first = uploads.reserveAttach(firstRequest, "a".repeat(64), owner, Map.of(), checksum).version();
        files.store(first.id(), new ByteArrayInputStream(bytes));
        bridge.reconcileUpload(new ReconcileUpload(firstRequest, first.id()));
        UUID failedRequest = UUID.randomUUID();
        var failed = uploads.reserveVersion(failedRequest, "b".repeat(64), first.documentId(), checksum).version();
        files.store(failed.id(), new ByteArrayInputStream(new byte[]{7}));
        bridge.reconcileUpload(new ReconcileUpload(failedRequest, failed.id()));
        var result = reader.getAudit(first.documentId());
        assertThat(result).extracting(DocumentAuditEntry::action).containsExactly(
                DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_RECOVERED,
                DocumentAuditAction.VERSION_ADDED, DocumentAuditAction.UPLOAD_RECOVERY_FAILED);
        assertThat(result.get(1).requestId()).isEqualTo(firstRequest);
        assertThat(result.get(1).versionId()).isEqualTo(first.id());
        assertThat(result.get(3).requestId()).isEqualTo(failedRequest);
        assertThat(result.get(3).versionId()).isEqualTo(failed.id());
    }

    private AttachDocument command(UUID request) {
        return new AttachDocument(request, owner, Map.of(), checksum, new ByteArrayInputStream(bytes));
    }
}
