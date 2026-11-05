package io.github.pitrecki.dokubridge.adapter.jpa.document;

import io.github.pitrecki.dokubridge.adapter.filesystem.content.FilesystemDocumentContentStore;
import io.github.pitrecki.dokubridge.adapter.jpa.audit.JpaDocumentAuditRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.upload.JpaDocumentUploadRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.version.JpaDocumentVersionRepository;
import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.content.exception.DocumentContentUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentRevisionConflictException;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.document.Archive;
import io.github.pitrecki.dokubridge.core.port.in.document.Delete;
import io.github.pitrecki.dokubridge.core.port.in.document.UpdateMetadata;
import io.github.pitrecki.dokubridge.core.port.in.upload.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.upload.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
class JpaDocumentLifecycleRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager entities;
    static JpaTransactionManager transactions;
    @TempDir Path directory;
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "lifecycle-42");
    final UUID initialRequest = UUID.randomUUID();
    ContentSha256 checksum;
    JpaBusinessDocumentRepository documents;
    JpaDocumentVersionRepository versions;
    JpaDocumentAuditRepository audits;
    JpaDocumentUploadRepository uploads;
    JpaDocumentLifecycleRepository lifecycle;
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
        sql("truncate dokubridge_audit, dokubridge_upload_requests, dokubridge_versions, dokubridge_documents restart identity cascade");
        checksum = new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[]{42})));
        documents = new JpaBusinessDocumentRepository(entities, transactions);
        versions = new JpaDocumentVersionRepository(entities, transactions);
        audits = new JpaDocumentAuditRepository(entities, transactions);
        uploads = new JpaDocumentUploadRepository(entities, transactions);
        lifecycle = new JpaDocumentLifecycleRepository(entities, transactions);
        files = new FilesystemDocumentContentStore(directory);
        bridge = new DefaultDocumentBridge(documents, versions, files, uploads, lifecycle);
    }

    @Test
    void replacesAndClearsMetadataWithRevisionChecksWithoutChangingContentHistory() throws Exception {
        var version = newDocument();
        UUID id = version.documentId();
        var changed = bridge.updateMetadata(new UpdateMetadata(id, 0, Map.of("new", true)));
        assertThat(changed.revision()).isEqualTo(1);
        assertThat(changed.metadata()).containsOnlyKeys("new");
        assertThatThrownBy(() -> bridge.updateMetadata(new UpdateMetadata(id, 0, Map.of("lost", true))))
                .isInstanceOf(DocumentRevisionConflictException.class);
        assertThat(audits.findByDocument(id)).hasSize(3);
        var cleared = bridge.updateMetadata(new UpdateMetadata(id, 1, Map.of()));
        assertThat(cleared.metadata()).isEmpty();
        assertThat(cleared.revision()).isEqualTo(2);
        var next = bridge.addVersion(new AddDocumentVersion(UUID.randomUUID(), id, checksum, bytes()));
        assertThat(next.number()).isEqualTo(2);
        assertThat(bridge.get(id).orElseThrow().revision()).isEqualTo(2);
        try (var content = bridge.getContent(id, version.id())) { assertThat(content.read()).isEqualTo(42); }
        assertThat(audits.findByDocument(id)).extracting(DocumentAuditEntry::action).containsExactly(
                DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_SUCCEEDED,
                DocumentAuditAction.METADATA_UPDATED, DocumentAuditAction.METADATA_UPDATED,
                DocumentAuditAction.VERSION_ADDED, DocumentAuditAction.UPLOAD_SUCCEEDED);
    }

    @Test
    void identicalMetadataReplacementIsStillAnAcceptedRevisionAndItsStaleReplayConflicts() throws Exception {
        UUID id = newDocument().documentId();
        var metadata = bridge.get(id).orElseThrow().metadata();
        assertThat(bridge.updateMetadata(new UpdateMetadata(id, 0, metadata)).revision()).isEqualTo(1);
        assertThatThrownBy(() -> bridge.updateMetadata(new UpdateMetadata(id, 0, metadata)))
                .isInstanceOf(DocumentRevisionConflictException.class);
        assertThat(bridge.get(id).orElseThrow().revision()).isEqualTo(1);
        assertThat(audits.findByDocument(id)).hasSize(3);
    }

    @Test
    void archiveIsIdempotentAndStillAllowsMetadataAndContentVersions() throws Exception {
        var version = newDocument();
        UUID id = version.documentId();
        var archived = bridge.archive(new Archive(id));
        assertThat(archived.state()).isEqualTo(BusinessDocumentState.ARCHIVED);
        assertThat(archived.revision()).isEqualTo(1);
        assertThat(bridge.archive(new Archive(id))).isEqualTo(archived);
        assertThat(audits.findByDocument(id)).hasSize(3);
        var changed = bridge.updateMetadata(new UpdateMetadata(id, 1, Map.of("archived", true)));
        bridge.addVersion(new AddDocumentVersion(UUID.randomUUID(), id, checksum, bytes()));
        assertThat(bridge.archive(new Archive(id))).isEqualTo(changed);
        assertThat(audits.findByDocument(id)).hasSize(6);
        assertThat(bridge.get(id).orElseThrow().state()).isEqualTo(BusinessDocumentState.ARCHIVED);
    }

    @ParameterizedTest
    @EnumSource(value = BusinessDocumentState.class, names = {"ACTIVE", "ARCHIVED"})
    void logicalDeleteHidesDocumentButPreservesFilesVersionsRequestsAndAudit(BusinessDocumentState state) throws Exception {
        var version = newDocument();
        UUID id = version.documentId();
        if (state == BusinessDocumentState.ARCHIVED) bridge.archive(new Archive(id));
        var before = bridge.get(id).orElseThrow();
        int auditCount = audits.findByDocument(id).size();
        bridge.delete(new Delete(id));
        bridge.delete(new Delete(id));
        assertThat(bridge.get(id)).isEmpty();
        assertThat(bridge.findByOwner(owner)).isEmpty();
        assertThatThrownBy(() -> bridge.getContent(id, version.id())).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> bridge.archive(new Archive(id))).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> bridge.updateMetadata(new UpdateMetadata(id, before.revision(), Map.of())))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> bridge.addVersion(new AddDocumentVersion(UUID.randomUUID(), id, checksum, bytes())))
                .isInstanceOf(DocumentNotFoundException.class);
        var retained = documents.findById(id).orElseThrow();
        assertThat(retained.state()).isEqualTo(BusinessDocumentState.DELETED);
        assertThat(retained.revision()).isEqualTo(before.revision() + 1);
        assertThat(retained.metadata()).isEqualTo(before.metadata());
        assertThat(versions.findById(id, version.id())).contains(version);
        try (var raw = files.open(version.id())) { assertThat(raw.read()).isEqualTo(42); }
        var history = audits.findByDocument(id);
        assertThat(history).hasSize(auditCount + 1);
        var deletion = history.getLast();
        assertThat(deletion.action()).isEqualTo(DocumentAuditAction.DOCUMENT_DELETED);
        assertThat(deletion.versionId()).isNull();
        assertThat(deletion.requestId()).isNull();
        assertThat(bridge.attach(new AttachDocument(initialRequest, owner, Map.of("old", true), checksum, unreadable())))
                .isEqualTo(version);
        assertThat(count("dokubridge_versions")).isEqualTo(1);
        assertThat(count("dokubridge_upload_requests")).isEqualTo(1);
    }

    @Test
    void missingDocumentsDoNotCreateAuditOrState() {
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> bridge.updateMetadata(new UpdateMetadata(missing, 0, Map.of()))).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> bridge.archive(new Archive(missing))).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> bridge.delete(new Delete(missing))).isInstanceOf(DocumentNotFoundException.class);
        assertThat(count("dokubridge_documents")).isZero();
        assertThat(count("dokubridge_audit")).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = DocumentAuditAction.class, names = {"METADATA_UPDATED", "DOCUMENT_ARCHIVED", "DOCUMENT_DELETED"})
    void failedAuditRollsBackDocumentChangeAndRevision(DocumentAuditAction action) throws Exception {
        var version = newDocument();
        UUID id = version.documentId();
        var before = bridge.get(id).orElseThrow();
        sql("alter table dokubridge_audit add constraint reject_lifecycle check (action <> '" + action.name() + "')");
        try {
            assertThatThrownBy(() -> mutate(action, id)).isInstanceOf(RuntimeException.class);
            assertThat(bridge.get(id)).contains(before);
            assertThat(audits.findByDocument(id)).hasSize(2);
        } finally {
            sql("alter table dokubridge_audit drop constraint reject_lifecycle");
        }
        mutate(action, id);
        assertThat(documents.findById(id).orElseThrow().revision()).isEqualTo(1);
        assertThat(audits.findByDocument(id)).hasSize(3);
    }

    @Test
    void concurrentMetadataUpdatesHaveOneWinnerAndOneRevisionConflict() throws Exception {
        UUID id = newDocument().documentId();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.of("first", "second").stream().map(writer -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                try {
                    return (Object) bridge.updateMetadata(new UpdateMetadata(id, 0, Map.of("writer", writer)));
                } catch (DocumentRevisionConflictException conflict) {
                    return conflict;
                }
            })).toList();
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            var results = List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS));
            assertThat(results).filteredOn(BusinessDocument.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(DocumentRevisionConflictException.class::isInstance).hasSize(1);
            var winner = (BusinessDocument) results.stream().filter(BusinessDocument.class::isInstance).findFirst().orElseThrow();
            assertThat(bridge.get(id)).contains(winner);
        }
        assertThat(bridge.get(id).orElseThrow().revision()).isEqualTo(1);
        assertThat(audits.findByDocument(id)).hasSize(3);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentAuditAction.class, names = {"DOCUMENT_ARCHIVED", "DOCUMENT_DELETED"})
    void concurrentDuplicateStateCommandsApplyOneTransitionAndOneAudit(DocumentAuditAction action) throws Exception {
        UUID id = newDocument().documentId();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                mutate(action, id);
                return null;
            })).toList();
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
        }
        assertThat(documents.findById(id).orElseThrow().revision()).isEqualTo(1);
        assertThat(audits.findByDocument(id)).hasSize(3).filteredOn(entry -> entry.action() == action).hasSize(1);
    }

    @Test
    void metadataAndVersionAllocationPreserveEachOthersChanges() throws Exception {
        UUID id = newDocument().documentId();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var metadata = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                return bridge.updateMetadata(new UpdateMetadata(id, 0, Map.of("new", true)));
            });
            var version = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                return bridge.addVersion(new AddDocumentVersion(UUID.randomUUID(), id, checksum, bytes()));
            });
            start.countDown();
            assertThat(metadata.get(20, TimeUnit.SECONDS).revision()).isEqualTo(1);
            assertThat(version.get(20, TimeUnit.SECONDS).number()).isEqualTo(2);
        }
        var current = bridge.get(id).orElseThrow();
        assertThat(current.revision()).isEqualTo(1);
        assertThat(current.metadata()).containsEntry("new", true);
        assertThat(count("dokubridge_versions")).isEqualTo(2);
        assertThat(audits.findByDocument(id)).hasSize(5);
    }

    @Test
    void staleHostPersistenceContextCannotBypassRevisionConflict() throws Exception {
        UUID id = newDocument().documentId();
        var retained = factory.getObject().createEntityManager();
        try {
            retained.find(io.github.pitrecki.dokubridge.adapter.jpa.document.persistence.BusinessDocumentEntity.class, id);
            TransactionSynchronizationManager.bindResource(factory.getObject(), new org.springframework.orm.jpa.EntityManagerHolder(retained));
            try (var executor = Executors.newSingleThreadExecutor()) {
                var updated = executor.submit(() -> bridge.updateMetadata(new UpdateMetadata(id, 0, Map.of("winner", true))));
                assertThat(updated.get(20, TimeUnit.SECONDS).revision()).isEqualTo(1);
            }
            assertThatThrownBy(() -> bridge.updateMetadata(new UpdateMetadata(id, 0, Map.of("stale", true))))
                    .isInstanceOf(DocumentRevisionConflictException.class);
        } finally {
            if (TransactionSynchronizationManager.hasResource(factory.getObject())) {
                TransactionSynchronizationManager.unbindResource(factory.getObject());
            }
            retained.close();
        }
        assertThat(bridge.get(id).orElseThrow().metadata()).containsOnlyKeys("winner");
        assertThat(audits.findByDocument(id)).hasSize(3);
    }

    @Test
    void staleHostPersistenceContextCannotResurrectDeletedDocumentOnVersionAllocation() throws Exception {
        UUID id = newDocument().documentId();
        var retained = factory.getObject().createEntityManager();
        try {
            retained.find(io.github.pitrecki.dokubridge.adapter.jpa.document.persistence.BusinessDocumentEntity.class, id);
            TransactionSynchronizationManager.bindResource(factory.getObject(), new org.springframework.orm.jpa.EntityManagerHolder(retained));
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> bridge.delete(new Delete(id))).get(20, TimeUnit.SECONDS);
            }
            assertThatThrownBy(() -> bridge.addVersion(new AddDocumentVersion(UUID.randomUUID(), id, checksum, bytes())))
                    .isInstanceOf(DocumentNotFoundException.class);
        } finally {
            if (TransactionSynchronizationManager.hasResource(factory.getObject())) {
                TransactionSynchronizationManager.unbindResource(factory.getObject());
            }
            retained.close();
        }
        assertThat(bridge.get(id)).isEmpty();
        assertThat(count("dokubridge_versions")).isEqualTo(1);
        assertThat(count("dokubridge_upload_requests")).isEqualTo(1);
        assertThat(audits.findByDocument(id)).hasSize(3);
    }

    @Test
    void staleHostPersistenceContextCannotFinalizeATerminalVersionAgain() throws Exception {
        UUID id = newDocument().documentId();
        UUID request = UUID.randomUUID();
        var pending = uploads.reserveVersion(request, "a".repeat(64), id).version();
        var retained = factory.getObject().createEntityManager();
        try {
            retained.find(io.github.pitrecki.dokubridge.adapter.jpa.version.persistence.DocumentVersionEntity.class, pending.id());
            TransactionSynchronizationManager.bindResource(factory.getObject(), new org.springframework.orm.jpa.EntityManagerHolder(retained));
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> uploads.complete(request, pending.id(), DocumentVersionState.FAILED)).get(20, TimeUnit.SECONDS);
            }
            assertThatIllegalStateException().isThrownBy(() -> uploads.complete(request, pending.id(), DocumentVersionState.AVAILABLE));
        } finally {
            if (TransactionSynchronizationManager.hasResource(factory.getObject())) {
                TransactionSynchronizationManager.unbindResource(factory.getObject());
            }
            retained.close();
        }
        assertThat(versions.findById(id, pending.id()).orElseThrow().state()).isEqualTo(DocumentVersionState.FAILED);
        assertThat(audits.findByDocument(id)).hasSize(4);
    }

    @Test
    void retainedHostContextMustNotReturnAnObsoleteUploadReplay() throws Exception {
        UUID request = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        var pending = uploads.reserveAttach(request, fingerprint, owner, Map.of()).version();
        var retained = factory.getObject().createEntityManager();
        try {
            retained.find(io.github.pitrecki.dokubridge.adapter.jpa.version.persistence.DocumentVersionEntity.class, pending.id());
            TransactionSynchronizationManager.bindResource(factory.getObject(), new org.springframework.orm.jpa.EntityManagerHolder(retained));
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> uploads.complete(request, pending.id(), DocumentVersionState.AVAILABLE)).get(20, TimeUnit.SECONDS);
            }
            var replay = uploads.reserveAttach(request, fingerprint, owner, Map.of());
            assertThat(replay.acquired()).isFalse();
            assertThat(replay.version().state()).isEqualTo(DocumentVersionState.AVAILABLE);
        } finally {
            if (TransactionSynchronizationManager.hasResource(factory.getObject())) {
                TransactionSynchronizationManager.unbindResource(factory.getObject());
            }
            retained.close();
        }
        assertThat(audits.findByDocument(pending.documentId())).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "findByOwner", "getContent", "getVersions"})
    void retainedHostContextCannotExposeACommittedDeletion(String operation) throws Exception {
        var version = newDocument();
        var retained = factory.getObject().createEntityManager();
        try {
            retained.find(io.github.pitrecki.dokubridge.adapter.jpa.document.persistence.BusinessDocumentEntity.class, version.documentId());
            TransactionSynchronizationManager.bindResource(factory.getObject(), new org.springframework.orm.jpa.EntityManagerHolder(retained));
            assertThat(bridge.get(version.documentId())).isPresent();
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> bridge.delete(new Delete(version.documentId()))).get(20, TimeUnit.SECONDS);
            }
            var guarded = new DefaultDocumentBridge(documents, versions, new DocumentContentStore() {
                public void store(UUID id, InputStream source) { throw new AssertionError("Unexpected upload"); }
                public InputStream open(UUID id) { throw new AssertionError("Deleted content must not be opened"); }
            });
            switch (operation) {
                case "get" -> assertThat(guarded.get(version.documentId())).isEmpty();
                case "findByOwner" -> assertThat(guarded.findByOwner(owner)).isEmpty();
                case "getVersions" -> assertThatThrownBy(() -> guarded.getVersions(version.documentId()))
                        .isInstanceOf(DocumentNotFoundException.class);
                case "getContent" -> assertThatThrownBy(() -> guarded.getContent(version.documentId(), version.id()))
                        .isInstanceOf(DocumentNotFoundException.class);
                default -> throw new AssertionError("Unknown read operation");
            }
        } finally {
            if (TransactionSynchronizationManager.hasResource(factory.getObject())) {
                TransactionSynchronizationManager.unbindResource(factory.getObject());
            }
            retained.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "findByOwner"})
    void retainedHostContextReturnsCommittedMetadataRevisionAndState(String operation) throws Exception {
        var version = newDocument();
        var retained = factory.getObject().createEntityManager();
        try {
            retained.find(io.github.pitrecki.dokubridge.adapter.jpa.document.persistence.BusinessDocumentEntity.class, version.documentId());
            TransactionSynchronizationManager.bindResource(factory.getObject(), new org.springframework.orm.jpa.EntityManagerHolder(retained));
            assertThat(bridge.findByOwner(owner).getFirst().revision()).isZero();
            BusinessDocument changed;
            try (var executor = Executors.newSingleThreadExecutor()) {
                changed = executor.submit(() -> {
                    bridge.updateMetadata(new UpdateMetadata(version.documentId(), 0, Map.of("new", true)));
                    return bridge.archive(new Archive(version.documentId()));
                }).get(20, TimeUnit.SECONDS);
            }
            var result = operation.equals("get") ? bridge.get(version.documentId()).orElseThrow()
                    : bridge.findByOwner(owner).getFirst();
            assertThat(result).isEqualTo(changed);
            assertThat(result.revision()).isEqualTo(2);
            assertThat(result.metadata()).containsOnlyKeys("new");
            assertThat(result.state()).isEqualTo(BusinessDocumentState.ARCHIVED);
        } finally {
            if (TransactionSynchronizationManager.hasResource(factory.getObject())) {
                TransactionSynchronizationManager.unbindResource(factory.getObject());
            }
            retained.close();
        }
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionState.class, names = {"AVAILABLE", "FAILED"})
    void retainedHostContextReturnsCommittedVersionOutcome(DocumentVersionState outcome) throws Exception {
        var first = newDocument();
        UUID request = UUID.randomUUID();
        var pending = uploads.reserveVersion(request, "a".repeat(64), first.documentId()).version();
        files.store(pending.id(), bytes());
        var retained = factory.getObject().createEntityManager();
        try {
            retained.find(io.github.pitrecki.dokubridge.adapter.jpa.version.persistence.DocumentVersionEntity.class, pending.id());
            TransactionSynchronizationManager.bindResource(factory.getObject(), new org.springframework.orm.jpa.EntityManagerHolder(retained));
            assertThat(versions.findById(first.documentId(), pending.id())).contains(pending);
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> uploads.complete(request, pending.id(), outcome)).get(20, TimeUnit.SECONDS);
            }
            assertThat(versions.findById(first.documentId(), pending.id()).orElseThrow().state()).isEqualTo(outcome);
            if (outcome == DocumentVersionState.AVAILABLE) {
                try (var input = bridge.getContent(first.documentId(), pending.id())) {
                    assertThat(input.readAllBytes()).containsExactly((byte) 42);
                }
            } else {
                assertThatThrownBy(() -> bridge.getContent(first.documentId(), pending.id()))
                        .isInstanceOf(DocumentContentUnavailableException.class).hasMessageContaining("FAILED");
            }
        } finally {
            if (TransactionSynchronizationManager.hasResource(factory.getObject())) {
                TransactionSynchronizationManager.unbindResource(factory.getObject());
            }
            retained.close();
        }
    }

    @Test
    void versionHistoryUsesFreshRowsDespiteRetainedEntitiesAndConcurrentNewReservations() throws Exception {
        var first = newDocument();
        UUID request = UUID.randomUUID();
        var pending = uploads.reserveVersion(request, "a".repeat(64), first.documentId()).version();
        var retained = factory.getObject().createEntityManager();
        try {
            retained.find(io.github.pitrecki.dokubridge.adapter.jpa.version.persistence.DocumentVersionEntity.class, pending.id());
            TransactionSynchronizationManager.bindResource(factory.getObject(), new org.springframework.orm.jpa.EntityManagerHolder(retained));
            var before = bridge.getVersions(first.documentId());
            assertThat(before).containsExactly(first, pending);
            DocumentVersion third;
            try (var executor = Executors.newSingleThreadExecutor()) {
                third = executor.submit(() -> {
                    uploads.complete(request, pending.id(), DocumentVersionState.FAILED);
                    return uploads.reserveVersion(UUID.randomUUID(), "a".repeat(64), first.documentId()).version();
                }).get(20, TimeUnit.SECONDS);
            }
            assertThat(bridge.getVersions(first.documentId())).containsExactly(first, pending.markFailed(), third);
            assertThat(before).containsExactly(first, pending);
            assertThat(bridge.get(first.documentId()).orElseThrow().revision()).isZero();
            assertThat(audits.findByDocument(first.documentId())).hasSize(5);
        } finally {
            if (TransactionSynchronizationManager.hasResource(factory.getObject())) {
                TransactionSynchronizationManager.unbindResource(factory.getObject());
            }
            retained.close();
        }
    }

    @Test
    void reservedUploadCanFinishAfterDeletionWithoutBecomingDownloadable() throws Exception {
        UUID id = newDocument().documentId();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var gatedStore = new DocumentContentStore() {
            public void store(UUID version, InputStream source) throws IOException {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Upload timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                files.store(version, source);
            }
            public InputStream open(UUID version) throws IOException { return files.open(version); }
        };
        var gated = new DefaultDocumentBridge(documents, versions, gatedStore, uploads, lifecycle);
        UUID request = UUID.randomUUID();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> gated.addVersion(new AddDocumentVersion(request, id, checksum, bytes())));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                bridge.delete(new Delete(id));
            } finally { release.countDown(); }
            var completed = result.get(20, TimeUnit.SECONDS);
            assertThat(completed.state()).isEqualTo(DocumentVersionState.AVAILABLE);
            assertThat(bridge.get(id)).isEmpty();
            assertThatThrownBy(() -> bridge.getContent(id, completed.id())).isInstanceOf(DocumentNotFoundException.class);
            try (var raw = files.open(completed.id())) { assertThat(raw.read()).isEqualTo(42); }
            assertThat(bridge.addVersion(new AddDocumentVersion(request, id, checksum, unreadable()))).isEqualTo(completed);
        }
        assertThat(count("dokubridge_versions")).isEqualTo(2);
        assertThat(count("dokubridge_upload_requests")).isEqualTo(2);
        assertThat(audits.findByDocument(id)).hasSize(5);
    }

    @Test
    void rejectsHostTransactionsBeforeMutatingAnything() throws Exception {
        UUID id = newDocument().documentId();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThatIllegalStateException().isThrownBy(() -> bridge.updateMetadata(new UpdateMetadata(id, 0, Map.of())));
            assertThatIllegalStateException().isThrownBy(() -> bridge.archive(new Archive(id)));
            assertThatIllegalStateException().isThrownBy(() -> bridge.delete(new Delete(id)));
        });
        assertThat(bridge.get(id).orElseThrow().revision()).isZero();
        assertThat(audits.findByDocument(id)).hasSize(2);
    }

    @Test
    void databaseRejectsInvalidAuditContextsEvenOutsideJpa() throws Exception {
        var version = newDocument();
        for (String action : List.of("DOCUMENT_ARCHIVED", "UPLOAD_SUCCEEDED", "UPLOAD_RECOVERED", "UPLOAD_RECOVERY_FAILED", "UNKNOWN")) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var statement = entities.createNativeQuery("""
                        insert into dokubridge_audit(id, document_id, version_id, request_id, action, occurred_at)
                        values (:id, :document, :version, :request, :action, current_timestamp)
                        """).setParameter("id", UUID.randomUUID()).setParameter("document", version.documentId())
                        .setParameter("action", action);
                if (action.equals("DOCUMENT_ARCHIVED")) {
                    statement.setParameter("version", version.id()).setParameter("request", initialRequest);
                } else {
                    statement.setParameter("version", null).setParameter("request", null);
                }
                statement.executeUpdate();
            })).isInstanceOf(RuntimeException.class).satisfies(failure -> {
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                assertThat(cause).isInstanceOf(java.sql.SQLException.class);
                assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("23514");
            });
        }
        assertThat(audits.findByDocument(version.documentId())).hasSize(2);
    }

    private DocumentVersion newDocument() throws IOException {
        return bridge.attach(new AttachDocument(initialRequest, owner, Map.of("old", true), checksum, bytes()));
    }

    private void mutate(DocumentAuditAction action, UUID id) {
        switch (action) {
            case METADATA_UPDATED -> bridge.updateMetadata(new UpdateMetadata(id, 0, Map.of("new", true)));
            case DOCUMENT_ARCHIVED -> bridge.archive(new Archive(id));
            case DOCUMENT_DELETED -> bridge.delete(new Delete(id));
            default -> throw new AssertionError("Unexpected action");
        }
    }

    private ByteArrayInputStream bytes() { return new ByteArrayInputStream(new byte[]{42}); }

    private InputStream unreadable() {
        return new InputStream() { public int read() { throw new AssertionError("Replay must not read content"); } };
    }

    private void sql(String statement) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> entities.createNativeQuery(statement).executeUpdate());
    }

    private long count(String table) {
        return new TransactionTemplate(transactions).execute(status ->
                ((Number) entities.createNativeQuery("select count(*) from " + table).getSingleResult()).longValue());
    }
}
