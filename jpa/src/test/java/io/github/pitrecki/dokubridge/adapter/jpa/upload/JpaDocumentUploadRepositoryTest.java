package io.github.pitrecki.dokubridge.adapter.jpa.upload;

import io.github.pitrecki.dokubridge.adapter.jpa.audit.JpaDocumentAuditRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.document.JpaBusinessDocumentRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.version.JpaDocumentVersionRepository;
import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.content.exception.ContentChecksumMismatchException;
import io.github.pitrecki.dokubridge.core.domain.content.exception.DocumentContentUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.upload.exception.UploadRequestConflictException;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.upload.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.upload.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.upload.UploadReservation;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
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
class JpaDocumentUploadRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager entities;
    static JpaTransactionManager transactions;
    JpaDocumentUploadRepository uploads;
    JpaBusinessDocumentRepository documents;
    JpaDocumentVersionRepository versions;
    JpaDocumentAuditRepository audits;
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "42");
    final String fingerprint = "a".repeat(64);

    @BeforeAll
    static void initializePersistence() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/dokubridge").table("dokubridge_flyway_history").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
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
    void reset() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> entities.createNativeQuery(
                "truncate dokubridge_audit, dokubridge_upload_requests, dokubridge_versions, dokubridge_documents restart identity cascade")
                .executeUpdate());
        uploads = new JpaDocumentUploadRepository(entities, transactions);
        documents = new JpaBusinessDocumentRepository(entities, transactions);
        versions = new JpaDocumentVersionRepository(entities, transactions);
        audits = new JpaDocumentAuditRepository(entities, transactions);
    }

    @Test
    void reservesPendingDocumentVersionAndAuditInOneCommitThenReplaysFinalResult() {
        var request = UUID.randomUUID();
        var metadata = Map.<String, Object>of("label", "invoice", "nested", Map.of("valid", true), "items", List.of(1, 2));
        var reservation = uploads.reserveAttach(request, fingerprint, owner, metadata);
        var version = reservation.version();
        assertThat(reservation.acquired()).isTrue();
        assertThat(version.state()).isEqualTo(DocumentVersionState.PENDING);
        assertThat(version.number()).isEqualTo(1);
        assertThat(documents.findById(version.documentId())).get().satisfies(document -> {
            assertThat(document.owner()).isEqualTo(owner);
            assertThat(document.metadata()).containsEntry("label", "invoice")
                    .containsEntry("nested", Map.of("valid", true))
                    .containsEntry("items", List.of(java.math.BigInteger.ONE, java.math.BigInteger.TWO));
            assertThat(document.state()).isEqualTo(BusinessDocumentState.ACTIVE);
        });
        assertThat(versions.findById(UUID.randomUUID(), version.id())).isEmpty();
        assertThat(documents.findByOwner(owner)).hasSize(1);
        assertThat(documents.findByOwner(new DocumentOwnerRef("Order", "42"))).isEmpty();
        var complete = uploads.complete(request, version.id(), DocumentVersionState.AVAILABLE);
        assertThat(complete.state()).isEqualTo(DocumentVersionState.AVAILABLE);
        var replay = uploads.reserveAttach(request, fingerprint, owner, metadata);
        assertThat(replay.acquired()).isFalse();
        assertThat(replay.version()).isEqualTo(complete);
        assertThat(count("dokubridge_documents")).isEqualTo(1);
        assertThat(count("dokubridge_versions")).isEqualTo(1);
        assertThat(audits.findByDocument(version.documentId())).extracting(DocumentAuditEntry::action)
                .containsExactly(DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_SUCCEEDED);
    }

    @Test
    void readFacadeCombinesRealJpaAndFilesystemAdapters(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var request = UUID.randomUUID();
        var version = uploads.reserveAttach(request, fingerprint, owner, Map.of("label", "invoice")).version();
        var store = new io.github.pitrecki.dokubridge.adapter.filesystem.content.FilesystemDocumentContentStore(root);
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        store.store(version.id(), new java.io.ByteArrayInputStream(new byte[]{42}));
        var bridge = new DefaultDocumentBridge(documents, versions, store);
        assertThatThrownBy(() -> bridge.getContent(version.documentId(), version.id()))
                .isInstanceOf(DocumentContentUnavailableException.class);
        uploads.complete(request, version.id(), DocumentVersionState.AVAILABLE);
        try (var input = bridge.getContent(version.documentId(), version.id())) {
            assertThat(input.read()).isEqualTo(42);
            assertThat(input.read()).isEqualTo(-1);
        }
        assertThat(bridge.findByOwner(owner)).hasSize(1);
    }

    @Test
    void readsImmutableOrderedVersionHistoryOfEveryStateWithoutContentIo() {
        UUID request = UUID.randomUUID();
        var reservation = uploads.reserveAttach(request, fingerprint, owner, Map.of()).version();
        var first = uploads.complete(request, reservation.id(), DocumentVersionState.AVAILABLE);
        UUID nextRequest = UUID.randomUUID();
        var secondPending = uploads.reserveVersion(nextRequest, fingerprint, first.documentId()).version();
        var second = uploads.complete(nextRequest, secondPending.id(), DocumentVersionState.FAILED);
        var third = uploads.reserveVersion(UUID.randomUUID(), fingerprint, first.documentId()).version();
        uploads.reserveAttach(UUID.randomUUID(), fingerprint, owner, Map.of());
        var bridge = new DefaultDocumentBridge(documents, versions, new DocumentContentStore() {
            public void store(UUID id, java.io.InputStream source) { throw new AssertionError("History must not store content"); }
            public java.io.InputStream open(UUID id) { throw new AssertionError("History must not open content"); }
        });
        var history = bridge.getVersions(first.documentId());
        assertThat(history).containsExactly(first, second, third);
        assertThatThrownBy(() -> history.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> versions.findByDocument(first.documentId()).clear()).isInstanceOf(UnsupportedOperationException.class);
        setState(first.documentId(), "ARCHIVED");
        assertThat(bridge.getVersions(first.documentId())).isEqualTo(history);
        assertThat(audits.findByDocument(first.documentId())).hasSize(5);
        assertThat(versions.findByDocument(UUID.randomUUID())).isEmpty();
        assertThatNullPointerException().isThrownBy(() -> versions.findByDocument(null));
    }

    @Test
    void deletedDocumentHistoryRemainsRetainedButIsHiddenByTheFacade() {
        var pending = uploads.reserveAttach(UUID.randomUUID(), fingerprint, owner, Map.of()).version();
        var bridge = new DefaultDocumentBridge(documents, versions, new DocumentContentStore() {
            public void store(UUID id, java.io.InputStream source) { throw new AssertionError("Unexpected upload"); }
            public java.io.InputStream open(UUID id) { throw new AssertionError("Unexpected download"); }
        });
        new io.github.pitrecki.dokubridge.adapter.jpa.document.JpaDocumentLifecycleRepository(entities, transactions)
                .delete(pending.documentId());
        assertThatThrownBy(() -> bridge.getVersions(pending.documentId())).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> bridge.getVersions(UUID.randomUUID())).isInstanceOf(DocumentNotFoundException.class);
        assertThat(versions.findByDocument(pending.documentId())).containsExactly(pending);
        assertThat(audits.findByDocument(pending.documentId())).hasSize(2);
    }

    @Test
    void jsonbRoundTripPreservesLargeIntegersDecimalsAndNullValues() {
        var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("integer", new java.math.BigInteger("123456789012345678901234567890"));
        metadata.put("decimal", new java.math.BigDecimal("12345678901234567890.12345678901234567890"));
        metadata.put("nullable", null);
        var version = uploads.reserveAttach(UUID.randomUUID(), fingerprint, owner, metadata).version();
        assertThat(documents.findById(version.documentId()).orElseThrow().metadata()).isEqualTo(metadata);
    }

    @Test
    void conflictingFingerprintDoesNotCreateAnotherDocumentOrAudit() {
        var request = UUID.randomUUID();
        var first = uploads.reserveAttach(request, fingerprint, owner, Map.of());
        assertThatThrownBy(() -> uploads.reserveAttach(request, "b".repeat(64), owner, Map.of()))
                .isInstanceOf(UploadRequestConflictException.class);
        assertThat(count("dokubridge_documents")).isEqualTo(1);
        assertThat(count("dokubridge_versions")).isEqualTo(1);
        assertThat(audits.findByDocument(first.version().documentId())).hasSize(1);
    }

    @Test
    void failedRequestIsReplayedWithoutAllocatingAnotherVersion() {
        var request = UUID.randomUUID();
        var pending = uploads.reserveAttach(request, fingerprint, owner, Map.of()).version();
        uploads.complete(request, pending.id(), DocumentVersionState.FAILED);
        assertThat(uploads.reserveAttach(request, fingerprint, owner, Map.of()).version().state())
                .isEqualTo(DocumentVersionState.FAILED);
        assertThat(count("dokubridge_versions")).isEqualTo(1);
        assertThat(audits.findByDocument(pending.documentId())).extracting(DocumentAuditEntry::action)
                .containsExactly(DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_FAILED);
    }

    @Test
    void concurrentDuplicateRequestsHaveOneReservationOwner() throws Exception {
        var request = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                return uploads.reserveAttach(request, fingerprint, owner, Map.of());
            })).toList();
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            var first = tasks.get(0).get(20, TimeUnit.SECONDS);
            var second = tasks.get(1).get(20, TimeUnit.SECONDS);
            assertThat(List.of(first, second)).filteredOn(UploadReservation::acquired).hasSize(1);
            assertThat(first.version()).isEqualTo(second.version());
        }
        assertThat(count("dokubridge_upload_requests")).isEqualTo(1);
        assertThat(count("dokubridge_documents")).isEqualTo(1);
    }

    @Test
    void concurrentDistinctRequestsAllocateUniqueSequentialVersionNumbers() throws Exception {
        var first = uploads.reserveAttach(UUID.randomUUID(), fingerprint, owner, Map.of()).version();
        try (var pool = Executors.newFixedThreadPool(6)) {
            var start = new CountDownLatch(1);
            var tasks = IntStream.range(0, 6).mapToObj(index -> pool.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                return uploads.reserveVersion(UUID.randomUUID(), fingerprint, first.documentId()).version().number();
            })).toList();
            start.countDown();
            var numbers = new java.util.ArrayList<Integer>();
            for (var task : tasks) numbers.add(task.get(20, TimeUnit.SECONDS));
            assertThat(numbers).containsExactlyInAnyOrder(2, 3, 4, 5, 6, 7);
        }
        assertThat(count("dokubridge_versions")).isEqualTo(7);
        assertThat(versions.findByDocument(first.documentId())).extracting(DocumentVersion::number)
                .containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(audits.findByDocument(first.documentId())).hasSize(7);
    }

    @Test
    void auditFailureRollsBackFinalizationLeavingVersionPending() {
        var request = UUID.randomUUID();
        var version = uploads.reserveAttach(request, fingerprint, owner, Map.of()).version();
        sql("alter table dokubridge_audit add constraint reject_success check (action <> 'UPLOAD_SUCCEEDED')");
        try {
            assertThatThrownBy(() -> uploads.complete(request, version.id(), DocumentVersionState.AVAILABLE))
                    .isInstanceOf(RuntimeException.class);
            assertThat(versions.findById(version.documentId(), version.id())).get()
                    .extracting(DocumentVersion::state).isEqualTo(DocumentVersionState.PENDING);
            assertThat(audits.findByDocument(version.documentId())).hasSize(1);
        } finally {
            sql("alter table dokubridge_audit drop constraint reject_success");
        }
    }

    @Test
    void auditFailureRollsBackReservationIncludingRequestClaim() {
        var request = UUID.randomUUID();
        sql("alter table dokubridge_audit add constraint reject_attach check (action <> 'DOCUMENT_ATTACHED')");
        try {
            assertThatThrownBy(() -> uploads.reserveAttach(request, fingerprint, owner, Map.of()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("dokubridge_documents")).isZero();
            assertThat(count("dokubridge_versions")).isZero();
            assertThat(count("dokubridge_upload_requests")).isZero();
        } finally {
            sql("alter table dokubridge_audit drop constraint reject_attach");
        }
        assertThat(uploads.reserveAttach(request, fingerprint, owner, Map.of()).acquired()).isTrue();
    }

    @Test
    void archivedDocumentsAcceptNewVersionsButMissingAndDeletedDocumentsRejectThem() {
        assertThatThrownBy(() -> uploads.reserveVersion(UUID.randomUUID(), fingerprint, UUID.randomUUID()))
                .isInstanceOf(DocumentNotFoundException.class);
        var version = uploads.reserveAttach(UUID.randomUUID(), fingerprint, owner, Map.of()).version();
        setState(version.documentId(), "ARCHIVED");
        var archivedVersion = uploads.reserveVersion(UUID.randomUUID(), fingerprint, version.documentId()).version();
        assertThat(archivedVersion.number()).isEqualTo(2);
        setState(version.documentId(), "DELETED");
        assertThatThrownBy(() -> uploads.reserveVersion(UUID.randomUUID(), fingerprint, version.documentId()))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThat(count("dokubridge_upload_requests")).isEqualTo(2);
    }

    @Test
    void versionAuditFailureRollsBackCounterAndRequestClaim() {
        var first = uploads.reserveAttach(UUID.randomUUID(), fingerprint, owner, Map.of()).version();
        var request = UUID.randomUUID();
        sql("alter table dokubridge_audit add constraint reject_version check (action <> 'VERSION_ADDED')");
        try {
            assertThatThrownBy(() -> uploads.reserveVersion(request, fingerprint, first.documentId())).isInstanceOf(RuntimeException.class);
            assertThat(count("dokubridge_versions")).isEqualTo(1);
            assertThat(count("dokubridge_upload_requests")).isEqualTo(1);
        } finally {
            sql("alter table dokubridge_audit drop constraint reject_version");
        }
        assertThat(uploads.reserveVersion(request, fingerprint, first.documentId()).version().number()).isEqualTo(2);
    }

    @Test
    void failedVersionsConsumeTheirNumberAndANewRequestGetsTheNextOne() {
        var first = uploads.reserveAttach(UUID.randomUUID(), fingerprint, owner, Map.of()).version();
        var request = UUID.randomUUID();
        var second = uploads.reserveVersion(request, fingerprint, first.documentId()).version();
        uploads.complete(request, second.id(), DocumentVersionState.FAILED);
        assertThat(uploads.reserveVersion(UUID.randomUUID(), fingerprint, first.documentId()).version().number()).isEqualTo(3);
    }

    @Test
    void finalizationRejectsWrongRequestPendingOutcomeAndTerminalVersions() {
        var request = UUID.randomUUID();
        var version = uploads.reserveAttach(request, fingerprint, owner, Map.of()).version();
        assertThatIllegalArgumentException().isThrownBy(() -> uploads.complete(UUID.randomUUID(), version.id(), DocumentVersionState.AVAILABLE));
        assertThatIllegalArgumentException().isThrownBy(() -> uploads.complete(request, UUID.randomUUID(), DocumentVersionState.AVAILABLE));
        assertThatIllegalArgumentException().isThrownBy(() -> uploads.complete(request, version.id(), DocumentVersionState.PENDING));
        uploads.complete(request, version.id(), DocumentVersionState.AVAILABLE);
        assertThatIllegalStateException().isThrownBy(() -> uploads.complete(request, version.id(), DocumentVersionState.FAILED));
        assertThat(audits.findByDocument(version.documentId())).hasSize(2);
    }

    @Test
    void rejectsHostTransactionToKeepContentIoOutsideDatabaseTransactions() {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                assertThatIllegalStateException().isThrownBy(() ->
                        uploads.reserveAttach(UUID.randomUUID(), fingerprint, owner, Map.of())));
        assertThat(count("dokubridge_documents")).isZero();
    }

    @Test
    void facadeUploadsAddsVersionsAndReplaysEquivalentJsonWithoutReadingContent(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var store = new io.github.pitrecki.dokubridge.adapter.filesystem.content.FilesystemDocumentContentStore(root);
        var bridge = new DefaultDocumentBridge(documents, versions, store, uploads);
        var request = UUID.randomUUID();
        byte[] bytes = "first content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var first = bridge.attach(command(request, Map.of("value", 1), bytes));
        assertThat(first.state()).isEqualTo(DocumentVersionState.AVAILABLE);
        var replay = bridge.attach(new AttachDocument(request, owner, Map.of("value", new java.math.BigDecimal("1.0")),
                checksum(bytes), unreadable()));
        assertThat(replay).isEqualTo(first);
        byte[] next = "second content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var added = bridge.addVersion(new AddDocumentVersion(UUID.randomUUID(), first.documentId(), checksum(next),
                new java.io.ByteArrayInputStream(next)));
        assertThat(added.number()).isEqualTo(2);
        assertThat(added.documentId()).isEqualTo(first.documentId());
        assertThat(documents.findById(first.documentId()).orElseThrow().metadata())
                .containsEntry("value", java.math.BigInteger.ONE);
        try (var content = bridge.getContent(first.documentId(), added.id())) {
            assertThat(content.readAllBytes()).containsExactly(next);
        }
        assertThat(audits.findByDocument(first.documentId())).extracting(DocumentAuditEntry::action).containsExactly(
                DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_SUCCEEDED,
                DocumentAuditAction.VERSION_ADDED, DocumentAuditAction.UPLOAD_SUCCEEDED);
    }

    @Test
    void facadeConflictsOnChangedMetadataOwnerChecksumOrOperationBeforeStorage(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var store = new io.github.pitrecki.dokubridge.adapter.filesystem.content.FilesystemDocumentContentStore(root);
        var bridge = new DefaultDocumentBridge(documents, versions, store, uploads);
        var request = UUID.randomUUID();
        byte[] bytes = {42};
        var version = bridge.attach(command(request, Map.of("label", "one"), bytes));
        assertThatThrownBy(() -> bridge.attach(new AttachDocument(request, owner, Map.of("label", "two"), checksum(bytes), unreadable())))
                .isInstanceOf(UploadRequestConflictException.class);
        assertThatThrownBy(() -> bridge.attach(new AttachDocument(request, new DocumentOwnerRef("Order", "42"),
                Map.of("label", "one"), checksum(bytes), unreadable())))
                .isInstanceOf(UploadRequestConflictException.class);
        assertThatThrownBy(() -> bridge.attach(new AttachDocument(request, owner, Map.of("label", "one"),
                new ContentSha256("0".repeat(64)), unreadable())))
                .isInstanceOf(UploadRequestConflictException.class);
        assertThatThrownBy(() -> bridge.addVersion(new AddDocumentVersion(request, version.documentId(), checksum(bytes), unreadable())))
                .isInstanceOf(UploadRequestConflictException.class);
        assertThat(count("dokubridge_versions")).isEqualTo(1);
        assertThat(audits.findByDocument(version.documentId())).hasSize(2);
    }

    @Test
    void checksumFailurePersistsFailedStateAndBlocksThePublishedBlob(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var store = new io.github.pitrecki.dokubridge.adapter.filesystem.content.FilesystemDocumentContentStore(root);
        var bridge = new DefaultDocumentBridge(documents, versions, store, uploads);
        var request = UUID.randomUUID();
        var declared = new ContentSha256("0".repeat(64));
        assertThatThrownBy(() -> bridge.attach(new AttachDocument(request, owner, Map.of(), declared,
                new java.io.ByteArrayInputStream(new byte[]{42})))).isInstanceOf(ContentChecksumMismatchException.class);
        var replay = bridge.attach(new AttachDocument(request, owner, Map.of(), declared, unreadable()));
        assertThat(replay.state()).isEqualTo(DocumentVersionState.FAILED);
        try (var raw = store.open(replay.id())) { assertThat(raw.read()).isEqualTo(42); }
        assertThatThrownBy(() -> bridge.getContent(replay.documentId(), replay.id()))
                .isInstanceOf(DocumentContentUnavailableException.class);
        assertThat(audits.findByDocument(replay.documentId())).extracting(DocumentAuditEntry::action)
                .containsExactly(DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_FAILED);
    }

    @Test
    void successFinalizationFailureLeavesPendingAndReplayDoesNotRewriteBlob(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var store = new io.github.pitrecki.dokubridge.adapter.filesystem.content.FilesystemDocumentContentStore(root);
        var bridge = new DefaultDocumentBridge(documents, versions, store, uploads);
        var request = UUID.randomUUID();
        byte[] bytes = {42};
        sql("alter table dokubridge_audit add constraint reject_success check (action <> 'UPLOAD_SUCCEEDED')");
        try {
            assertThatThrownBy(() -> bridge.attach(command(request, Map.of(), bytes))).isInstanceOf(RuntimeException.class);
            var replay = bridge.attach(new AttachDocument(request, owner, Map.of(), checksum(bytes), unreadable()));
            assertThat(replay.state()).isEqualTo(DocumentVersionState.PENDING);
            assertThatThrownBy(() -> bridge.getContent(replay.documentId(), replay.id()))
                    .isInstanceOf(DocumentContentUnavailableException.class);
            try (var raw = store.open(replay.id())) { assertThat(raw.read()).isEqualTo(42); }
            assertThat(audits.findByDocument(replay.documentId())).hasSize(1);
        } finally {
            sql("alter table dokubridge_audit drop constraint reject_success");
        }
    }

    @Test
    void concurrentFacadeReplayReturnsInProgressWithoutAnotherWrite(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var files = new io.github.pitrecki.dokubridge.adapter.filesystem.content.FilesystemDocumentContentStore(root);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        var gated = new DocumentContentStore() {
            public void store(UUID id, java.io.InputStream source) throws java.io.IOException {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                writes.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Upload timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(interrupted);
                }
                files.store(id, source);
            }
            public java.io.InputStream open(UUID id) throws java.io.IOException { return files.open(id); }
        };
        var bridge = new DefaultDocumentBridge(documents, versions, gated, uploads);
        var request = UUID.randomUUID();
        byte[] bytes = {42};
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> bridge.attach(command(request, Map.of(), bytes)));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var pending = bridge.attach(new AttachDocument(request, owner, Map.of(), checksum(bytes), unreadable()));
                assertThat(pending.state()).isEqualTo(DocumentVersionState.PENDING);
                assertThat(writes.get()).isEqualTo(1);
            } finally {
                release.countDown();
            }
            var available = first.get(20, TimeUnit.SECONDS);
            assertThat(available.state()).isEqualTo(DocumentVersionState.AVAILABLE);
            assertThat(bridge.attach(new AttachDocument(request, owner, Map.of(), checksum(bytes), unreadable())))
                    .isEqualTo(available);
        }
        assertThat(writes.get()).isEqualTo(1);
        assertThat(count("dokubridge_versions")).isEqualTo(1);
    }

    @Test
    void archivedDocumentCanReceiveNewVersionsThroughTheFacade(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var store = new io.github.pitrecki.dokubridge.adapter.filesystem.content.FilesystemDocumentContentStore(root);
        var bridge = new DefaultDocumentBridge(documents, versions, store, uploads);
        byte[] bytes = {42};
        var first = bridge.attach(command(UUID.randomUUID(), Map.of(), bytes));
        setState(first.documentId(), "ARCHIVED");
        var next = bridge.addVersion(new AddDocumentVersion(UUID.randomUUID(), first.documentId(), checksum(bytes),
                new java.io.ByteArrayInputStream(bytes)));
        assertThat(next.number()).isEqualTo(2);
        assertThat(next.state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(bridge.get(first.documentId()).orElseThrow().state()).isEqualTo(BusinessDocumentState.ARCHIVED);
    }

    private AttachDocument command(UUID request, Map<String, Object> metadata, byte[] bytes) throws Exception {
        return new AttachDocument(request, owner, metadata, checksum(bytes), new java.io.ByteArrayInputStream(bytes));
    }

    private ContentSha256 checksum(byte[] bytes) throws Exception {
        return new ContentSha256(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private java.io.InputStream unreadable() {
        return new java.io.InputStream() {
            public int read() { throw new AssertionError("Replay or conflict must not consume content"); }
        };
    }

    private long count(String table) {
        return new TransactionTemplate(transactions).execute(status ->
                ((Number) entities.createNativeQuery("select count(*) from " + table).getSingleResult()).longValue());
    }

    private void sql(String statement) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> entities.createNativeQuery(statement).executeUpdate());
    }

    private void setState(UUID id, String state) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> entities.createNativeQuery(
                "update dokubridge_documents set state = :state where id = :id")
                .setParameter("state", state).setParameter("id", id).executeUpdate());
    }
}
