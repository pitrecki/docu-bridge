package io.github.pitrecki.dokubridge.adapter.jpa.upload;

import io.github.pitrecki.dokubridge.adapter.filesystem.content.FilesystemDocumentContentStore;
import io.github.pitrecki.dokubridge.adapter.jpa.audit.JpaDocumentAuditRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.document.JpaBusinessDocumentRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.document.JpaDocumentLifecycleRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.version.JpaDocumentVersionRepository;
import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.audit.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.content.exception.DocumentContentUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocumentState;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.document.exception.DocumentRevisionConflictException;
import io.github.pitrecki.dokubridge.core.domain.upload.exception.UploadRequestConflictException;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.upload.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        bridge = usingStore(files);
    }

    @Test
    void eachUnkeyedSaveCreatesANewAttachmentWithItsOwnChecksumAndRequest() throws Exception {
        var draft = bridge.upload(bytes).toOwner("Invoice", "42");
        assertThat(bridge.findByOwner("Invoice", "42")).isEmpty();
        var first = draft.save().result();
        var second = draft.save().result();
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
        var first = original.save().result();
        var second = branch.save().result();
        assertThat(download(first)).containsExactly((byte) 42);
        assertThat(download(second)).containsExactly((byte) 42);
        assertThat(bridge.document(first.documentId()).get().orElseThrow().metadata())
                .containsOnlyKeys("nested").containsEntry("nested", List.of("original"));
        assertThat(bridge.document(second.documentId()).get().orElseThrow().metadata())
                .containsEntry("category", "invoice").containsEntry("nullable", null);
        var replaced = branch.metadata(Map.of("only", "replacement")).save().result();
        assertThat(bridge.document(replaced.documentId()).get().orElseThrow().metadata()).containsOnlyKeys("only");
    }

    @Test
    void explicitKeysReplayAcrossBothApisAndChangedMetadataStillConflicts() throws Exception {
        UUID key = UUID.randomUUID();
        var owner = new DocumentOwnerRef("Invoice", "42");
        var draft = bridge.upload(bytes).toOwner(owner).metadata("kind", "invoice").requestId(key);
        var first = draft.save().result();
        assertThat(draft.save().result()).isEqualTo(first);
        assertThat(bridge.attach(new AttachDocument(key, owner, Map.of("kind", "invoice"), ContentSha256.of(bytes),
                new ByteArrayInputStream(bytes)))).isEqualTo(first);
        assertThatThrownBy(() -> draft.metadata("kind", "changed").save().result()).isInstanceOf(UploadRequestConflictException.class);
        assertThat(bridge.document(first.documentId()).versions()).containsExactly(first);
        assertThat(bridge.document(first.documentId()).audit()).hasSize(2);
    }

    @Test
    void versionDraftsDistinguishExplicitRetriesFromNewUnkeyedSaves() throws Exception {
        var first = bridge.upload(bytes).toOwner("Invoice", "42").save().result();
        var document = bridge.document(first.documentId());
        UUID key = UUID.randomUUID();
        var explicit = document.upload(new byte[]{7}).requestId(key);
        var second = explicit.save().result();
        assertThat(explicit.save().result()).isEqualTo(second);
        var unkeyed = document.upload(new byte[]{8});
        var third = unkeyed.save().result();
        var fourth = unkeyed.save().result();
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
        var first = draft.save().result();
        int consumed = reads.get();
        assertThat(consumed).isPositive();
        assertThat(draft.save().result()).isEqualTo(first);
        assertThat(reads).hasValue(consumed);
        assertThat(closed).isFalse();
        source.close();
        assertThat(closed).isTrue();
    }

    @Test
    void versionStreamFailurePreservesItsCauseAndCallerOwnershipAndFailedReplayDoesNotReadAgain() throws Exception {
        var first = bridge.upload(bytes).toOwner("Invoice", "42").save().result();
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
        var failed = draft.save().result();
        assertThat(failed.state()).isEqualTo(DocumentVersionState.FAILED);
        assertThat(reads).hasValue(attempted);
        assertThat(closed).isFalse();
        source.close();
        assertThat(closed).isTrue();
    }

    @Test
    void emptyByteContentIsAvailableAndDownloadable() throws Exception {
        var version = bridge.upload(new byte[0]).toOwner("Invoice", "42").save().result();
        assertThat(version.state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(download(version)).isEmpty();
    }

    @Test
    void metadataReplacementKeepsItsSnapshotAndRequiresTheObservedRevision() throws Exception {
        var first = bridge.upload(bytes).toOwner("Invoice", "42").save().result();
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
        assertThat(ready.save().result().metadata()).containsEntry("nested", List.of("original"));
        assertThatThrownBy(ready::save).isInstanceOf(DocumentRevisionConflictException.class);
        assertThat(document.audit()).hasSize(3);
        assertThat(replacement.atRevision(1).save().result().revision()).isEqualTo(2);
        assertThat(document.replaceMetadata(Map.of()).atRevision(2).save().result().metadata()).isEmpty();
        assertThat(document.versions()).containsExactly(first);
    }

    @Test
    void documentHandleUsesCurrentVisibilityAndDelegatesLifecycleWithoutCachingState() throws Exception {
        var original = bridge.upload(bytes).toOwner("Invoice", "42").requestId(UUID.randomUUID());
        var first = original.save().result();
        var document = bridge.document(first.documentId());
        var archived = document.archive().result();
        assertThat(document.archive().result()).isEqualTo(archived);
        assertThat(document.get().orElseThrow().state()).isEqualTo(BusinessDocumentState.ARCHIVED);
        var second = document.upload(new byte[]{7}).save().result();
        assertThat(document.versions()).containsExactly(first, second);
        document.delete();
        document.delete();
        assertThat(document.get()).isEmpty();
        assertThatThrownBy(document::versions).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(document::audit).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> document.content(first.id())).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> document.upload(bytes).save().result()).isInstanceOf(DocumentNotFoundException.class);
        assertThat(original.save().result()).isEqualTo(first);
        assertThat(audits.findByDocument(first.documentId())).hasSize(6);
    }

    @Test
    void chainKeepsTheDocumentAndSelectedContentWithoutRepeatingUploads() throws Exception {
        var saved = bridge.upload(bytes).toOwner("Invoice", "42").save();
        var archived = saved.and().archive();
        assertThat(archived.result().state()).isEqualTo(BusinessDocumentState.ARCHIVED);
        assertThat(archived.and().id()).isEqualTo(saved.result().documentId());
        assertThat(archived.and().versions()).containsExactly(saved.result());
        try (var input = archived.and().content()) {
            assertThat(input.readAllBytes()).containsExactly(bytes);
        }
        var updated = archived.and().replaceMetadata(Map.of("category", "changed"))
                .atRevision(1).save();
        assertThat(updated.result().revision()).isEqualTo(2);
        try (var input = updated.and().content()) {
            assertThat(input.readAllBytes()).containsExactly(bytes);
        }
        var next = updated.and().upload(new byte[]{7}).save();
        try (var input = next.and().content()) {
            assertThat(input.readAllBytes()).containsExactly((byte) 7);
        }
        assertThat(saved.and().versions()).hasSize(2);
        assertThat(next.and().audit()).hasSize(6);
        assertThat(saved.result().number()).isEqualTo(1);
        assertThat(next.result().number()).isEqualTo(2);
    }

    @Test
    void callbackReceivesTheSameDocumentAndSupportsCheckedIo() throws Exception {
        var calls = new AtomicInteger();
        var saved = bridge.upload(bytes).toOwner("Invoice", "42").saveAndThen(document -> {
            calls.incrementAndGet();
            try (var input = document.content()) {
                assertThat(input.readAllBytes()).containsExactly(bytes);
            }
            document.replaceMetadata(Map.of("callback", true)).atRevision(0)
                    .saveAndThen(updated -> updated.archive());
        });
        assertThat(calls).hasValue(1);
        assertThat(saved.result().state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(saved.and().get().orElseThrow().revision()).isEqualTo(2);
        assertThat(saved.and().get().orElseThrow().state()).isEqualTo(BusinessDocumentState.ARCHIVED);
        assertThat(saved.and().versions()).containsExactly(saved.result());
        assertThat(saved.and().audit()).hasSize(4);
    }

    @Test
    void callbackFailurePreservesItsCauseAndEarlierCommittedOperations() throws Exception {
        var failure = new IOException("Follow-up failed");
        var draft = bridge.upload(bytes).toOwner("Invoice", "42").requestId(UUID.randomUUID());
        assertThatThrownBy(() -> draft.saveAndThen(document -> {
            document.replaceMetadata(Map.of("committed", true)).atRevision(0).save();
            throw failure;
        })).isSameAs(failure);
        var replay = draft.save();
        assertThat(replay.result().state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(replay.and().get().orElseThrow().metadata()).containsEntry("committed", true);
        assertThat(replay.and().get().orElseThrow().revision()).isEqualTo(1);
        assertThat(replay.and().audit()).hasSize(3);
    }

    @Test
    void uploadFailureSkipsCallbackButARegularFailedReplayStillInvokesIt() throws Exception {
        var failure = new IOException("Read failed");
        var calls = new AtomicInteger();
        var draft = bridge.upload(new InputStream() {
            public int read() throws IOException { throw failure; }
        }, ContentSha256.of(bytes)).toOwner("Invoice", "42").requestId(UUID.randomUUID());
        assertThatThrownBy(() -> draft.saveAndThen(document -> calls.incrementAndGet())).isSameAs(failure);
        assertThat(calls).hasValue(0);
        var replay = draft.saveAndThen(document -> {
            calls.incrementAndGet();
            assertThat(document.versions().getFirst().state()).isEqualTo(DocumentVersionState.FAILED);
        });
        assertThat(replay.result().state()).isEqualTo(DocumentVersionState.FAILED);
        assertThat(calls).hasValue(1);
        assertThat(replay.and().audit()).hasSize(2);
    }

    @Test
    void keyedReplaysRunTheirCallbacksAgainWithoutDuplicatingTheUpload() throws Exception {
        var calls = new AtomicInteger();
        var draft = bridge.upload(bytes).toOwner("Invoice", "42").requestId(UUID.randomUUID());
        var first = draft.saveAndThen(document -> { calls.incrementAndGet(); document.archive(); });
        var replay = draft.saveAndThen(document -> { calls.incrementAndGet(); document.archive(); });
        assertThat(replay.result()).isEqualTo(first.result());
        assertThat(calls).hasValue(2);
        assertThat(replay.and().versions()).hasSize(1);
        assertThat(replay.and().audit()).hasSize(3);
        assertThat(replay.and().get().orElseThrow().revision()).isEqualTo(1);
    }

    @Test
    void metadataCallbackFailureDoesNotUndoReplacementAndStaleRetriesDoNotInvokeIt() throws Exception {
        var saved = bridge.upload(bytes).toOwner("Invoice", "42").save();
        var ready = saved.and().replaceMetadata(Map.of("committed", true)).atRevision(0);
        var calls = new AtomicInteger();
        var failure = new IOException("After metadata");
        assertThatThrownBy(() -> ready.saveAndThen(document -> { calls.incrementAndGet(); throw failure; })).isSameAs(failure);
        assertThat(saved.and().get().orElseThrow().revision()).isEqualTo(1);
        assertThatThrownBy(() -> ready.saveAndThen(document -> calls.incrementAndGet()))
                .isInstanceOf(DocumentRevisionConflictException.class);
        assertThat(calls).hasValue(1);
        assertThat(saved.and().audit()).hasSize(3);
    }

    @Test
    void pendingReplayRunsItsCallbackWithoutPublishingOrDuplicatingTheActiveUpload() throws Exception {
        var storing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writes = new AtomicInteger();
        var callbacks = new AtomicInteger();
        var slowBridge = usingStore(new DocumentContentStore() {
            public void store(UUID id, InputStream source) throws IOException {
                writes.incrementAndGet();
                storing.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("Upload was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Upload interrupted", interrupted);
                }
                files.store(id, source);
            }
            public InputStream open(UUID id) throws IOException { return files.open(id); }
        });
        var draft = slowBridge.upload(bytes).toOwner("Invoice", "42").requestId(UUID.randomUUID());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var uploading = executor.submit(draft::save);
            try {
                assertThat(storing.await(20, TimeUnit.SECONDS)).isTrue();
                var replay = draft.saveAndThen(document -> {
                    callbacks.incrementAndGet();
                    assertThat(document.versions()).singleElement()
                            .extracting(DocumentVersion::state).isEqualTo(DocumentVersionState.PENDING);
                    assertThatThrownBy(document::content).isInstanceOf(DocumentContentUnavailableException.class);
                });
                assertThat(replay.result().state()).isEqualTo(DocumentVersionState.PENDING);
                assertThat(callbacks).hasValue(1);
                assertThat(writes).hasValue(1);
                assertThat(replay.and().audit()).hasSize(1);
                assertThat(replay.and().versions()).containsExactly(replay.result());
            } finally {
                release.countDown();
            }
            var completed = uploading.get(20, TimeUnit.SECONDS);
            assertThat(completed.result().state()).isEqualTo(DocumentVersionState.AVAILABLE);
            assertThat(completed.and().versions()).containsExactly(completed.result());
            assertThat(completed.and().audit()).hasSize(2);
        }
    }

    @Test
    void selectedContentStaysOnItsVersionAndRechecksVisibilityAfterDeletion() throws Exception {
        var first = bridge.upload(bytes).toOwner("Invoice", "42").save();
        var second = first.and().upload(new byte[]{7}).save();
        try (var input = first.and().content()) {
            assertThat(input.readAllBytes()).containsExactly(bytes);
        }
        try (var input = second.and().content()) {
            assertThat(input.readAllBytes()).containsExactly((byte) 7);
        }
        second.and().delete();
        assertThatThrownBy(first.and()::content).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(second.and()::content).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(first.and()::versions).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(second.and()::audit).isInstanceOf(DocumentNotFoundException.class);
        assertThat(audits.findByDocument(first.and().id())).hasSize(5);
    }

    @Test
    void metadataResultKeepsItsSnapshotWhenTheCallbackArchivesTheDocument() throws Exception {
        var saved = bridge.upload(bytes).toOwner("Invoice", "42").save();
        var updated = saved.and().replaceMetadata(Map.of("callback", true)).atRevision(0)
                .saveAndThen(document -> document.archive());
        assertThat(updated.result().state()).isEqualTo(BusinessDocumentState.ACTIVE);
        assertThat(updated.result().revision()).isEqualTo(1);
        assertThat(updated.and().get().orElseThrow().state()).isEqualTo(BusinessDocumentState.ARCHIVED);
        assertThat(updated.and().get().orElseThrow().revision()).isEqualTo(2);
        try (var input = updated.and().content()) {
            assertThat(input.readAllBytes()).containsExactly(bytes);
        }
        assertThat(updated.and().audit()).hasSize(4);
    }

    @Test
    void versionCallbackFailureRetainsTheVersionAndReplayDoesNotAllocateAnother() throws Exception {
        var first = bridge.upload(bytes).toOwner("Invoice", "42").save();
        var draft = first.and().upload(new byte[]{7}).requestId(UUID.randomUUID());
        var failure = new IOException("After version upload");
        assertThatThrownBy(() -> draft.saveAndThen(document -> {
            document.archive();
            throw failure;
        })).isSameAs(failure);
        var replay = draft.save();
        assertThat(replay.result().number()).isEqualTo(2);
        assertThat(replay.result().state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(replay.and().versions()).containsExactly(first.result(), replay.result());
        assertThat(replay.and().get().orElseThrow().state()).isEqualTo(BusinessDocumentState.ARCHIVED);
        try (var input = replay.and().content()) {
            assertThat(input.readAllBytes()).containsExactly((byte) 7);
        }
        assertThat(replay.and().audit()).hasSize(5);
    }

    @Test
    void conflictingUploadSkipsTheCallbackAndLeavesTheOriginalDocumentUnchanged() throws Exception {
        var draft = bridge.upload(bytes).toOwner("Invoice", "42").requestId(UUID.randomUUID());
        var original = draft.save();
        var callbacks = new AtomicInteger();
        assertThatThrownBy(() -> draft.metadata("changed", true).saveAndThen(document -> callbacks.incrementAndGet()))
                .isInstanceOf(UploadRequestConflictException.class);
        assertThat(callbacks).hasValue(0);
        assertThat(original.and().get().orElseThrow().metadata()).isEmpty();
        assertThat(original.and().versions()).containsExactly(original.result());
        assertThat(original.and().audit()).hasSize(2);
    }

    @Test
    void ownerResultsExposeVisibleSnapshotsAndContinueOnTheSelectedDocument() throws Exception {
        var active = bridge.upload(bytes).toOwner("Invoice", "42").metadata("category", "active").save();
        var archived = bridge.upload(bytes).toOwner("Invoice", "42").metadata("category", "original")
                .save().and().archive();
        bridge.upload(bytes).toOwner("Invoice", "42").save().and().delete();
        bridge.upload(bytes).toOwner("Order", "42").save();
        bridge.upload(bytes).toOwner("Invoice", "43").save();

        var found = bridge.documentsForOwner("Invoice", "42", 10);
        assertThat(found).extracting(result -> result.result().id())
                .containsExactlyInAnyOrder(active.and().id(), archived.and().id());
        var selected = found.stream().filter(result -> result.result().state() == BusinessDocumentState.ARCHIVED)
                .findFirst().orElseThrow();
        var updated = selected.and().replaceMetadata(Map.of("category", "changed"))
                .atRevision(selected.result().revision()).save();
        var next = updated.and().upload(new byte[]{7}).save();
        assertThat(next.result().documentId()).isEqualTo(selected.result().id());
        assertThat(next.result().number()).isEqualTo(2);
        try (var input = next.and().content()) {
            assertThat(input.readAllBytes()).containsExactly((byte) 7);
        }
        assertThat(selected.result().metadata()).containsEntry("category", "original");
        assertThat(selected.result().revision()).isEqualTo(1);
        assertThat(selected.and().get().orElseThrow().metadata()).containsEntry("category", "changed");
        assertThat(selected.and().get().orElseThrow().revision()).isEqualTo(2);
        assertThat(selected.and().audit()).hasSize(6);
        var fresh = bridge.documentsForOwner(new DocumentOwnerRef("Invoice", "42"), 10).stream()
                .filter(result -> result.result().state() == BusinessDocumentState.ARCHIVED).findFirst().orElseThrow();
        assertThat(fresh.result().metadata()).containsEntry("category", "changed");
        assertThat(fresh.result().revision()).isEqualTo(2);
        assertThat(active.and().versions()).containsExactly(active.result());
    }

    @Test
    void ownerContinuationRechecksDeletionAndRetainsOnlyTheHistoricalSnapshot() throws Exception {
        var saved = bridge.upload(bytes).toOwner("Invoice", "42").save();
        var found = bridge.documentsForOwner("Invoice", "42", 1).getFirst();
        saved.and().delete();

        assertThat(found.result().state()).isEqualTo(BusinessDocumentState.ACTIVE);
        assertThat(found.and().get()).isEmpty();
        assertThat(bridge.documentsForOwner("Invoice", "42")).isEmpty();
        assertThat(bridge.documentsForOwner("Invoice", "42", 1)).isEmpty();
        assertThatThrownBy(found.and()::versions).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(found.and()::audit).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(found.and()::archive).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> found.and().replaceMetadata(Map.of()).atRevision(found.result().revision()).save())
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> found.and().upload(bytes).save()).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> found.and().content(saved.result().id())).isInstanceOf(DocumentNotFoundException.class);
        assertThat(audits.findByDocument(saved.and().id())).hasSize(3);
    }

    @Test
    void documentLookupRetainsItsSnapshotWhileContinuationsUseCurrentRevisionAndState() throws Exception {
        var saved = bridge.upload(bytes).toOwner("Invoice", "42").metadata("category", "original").save();
        var found = bridge.findDocument(saved.and().id()).orElseThrow();
        var updated = found.and().replaceMetadata(Map.of("category", "changed"))
                .atRevision(found.result().revision()).save();
        assertThatThrownBy(() -> found.and().replaceMetadata(Map.of()).atRevision(found.result().revision()).save())
                .isInstanceOf(DocumentRevisionConflictException.class);
        var archived = updated.and().archive();
        var fresh = bridge.findDocument(found.result().id()).orElseThrow();
        assertThat(fresh.result()).isEqualTo(archived.result());
        assertThat(fresh.result().revision()).isEqualTo(2);
        var next = fresh.and().upload(new byte[]{7}).save();
        assertThat(next.result().documentId()).isEqualTo(found.result().id());
        assertThat(next.result().number()).isEqualTo(2);
        try (var input = next.and().content()) {
            assertThat(input.readAllBytes()).containsExactly((byte) 7);
        }
        assertThat(found.result().state()).isEqualTo(BusinessDocumentState.ACTIVE);
        assertThat(found.result().metadata()).containsEntry("category", "original");
        assertThat(found.result().revision()).isZero();
        assertThat(found.and().get()).contains(archived.result());
        assertThat(found.and().versions()).containsExactly(saved.result(), next.result());
        assertThat(found.and().audit()).hasSize(6);
    }

    @Test
    void documentLookupHidesMissingOrDeletedDocumentsAndExistingContinuationsCannotReadOrWriteThem() throws Exception {
        assertThat(bridge.findDocument(UUID.randomUUID())).isEmpty();
        var saved = bridge.upload(bytes).toOwner("Invoice", "42").save();
        var found = bridge.findDocument(saved.and().id()).orElseThrow();
        saved.and().delete();

        assertThat(bridge.findDocument(found.result().id())).isEmpty();
        assertThat(found.result().state()).isEqualTo(BusinessDocumentState.ACTIVE);
        assertThat(found.and().get()).isEmpty();
        assertThatThrownBy(found.and()::versions).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(found.and()::audit).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> found.and().content(saved.result().id())).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(found.and()::archive).isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> found.and().replaceMetadata(Map.of()).atRevision(found.result().revision()).save())
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> found.and().upload(bytes).save()).isInstanceOf(DocumentNotFoundException.class);
        assertThat(audits.findByDocument(saved.and().id())).hasSize(3);
    }

    @Test
    void managedReadsUseSelectedOrExplicitVersionsWithoutChangingHistory() throws Exception {
        var first = bridge.upload(bytes).toOwner("Invoice", "42").save();
        var second = first.and().upload(new byte[]{7}).save();
        var archived = second.and().archive();
        byte[] original = first.and().readContent(InputStream::readAllBytes);
        byte[] latest = archived.and().readContent(InputStream::readAllBytes);
        var found = bridge.findDocument(first.and().id()).orElseThrow();
        byte[] explicit = found.and().readContent(first.result().id(), InputStream::readAllBytes);
        assertThat(original).containsExactly(bytes);
        assertThat(latest).containsExactly((byte) 7);
        assertThat(explicit).containsExactly(bytes);
        assertThat(found.and().versions()).containsExactly(first.result(), second.result());
        assertThat(found.and().get()).contains(archived.result());
        assertThat(found.and().audit()).hasSize(5);
    }

    @Test
    void readerFailureLeavesTheVersionAvailableAndDeletionSkipsTheReader() throws Exception {
        var saved = bridge.upload(bytes).toOwner("Invoice", "42").save();
        var found = bridge.findDocument(saved.and().id()).orElseThrow();
        var failure = new IOException("Parser failed");
        assertThatThrownBy(() -> found.and().readContent(saved.result().id(), input -> { throw failure; }))
                .isSameAs(failure);
        byte[] reread = found.and().readContent(saved.result().id(), InputStream::readAllBytes);
        assertThat(reread).containsExactly(bytes);
        assertThat(saved.and().versions()).containsExactly(saved.result());
        assertThat(saved.and().get().orElseThrow().revision()).isZero();
        assertThat(saved.and().audit()).hasSize(2);
        saved.and().delete();
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> saved.and().readContent(input -> calls.incrementAndGet()))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThat(calls).hasValue(0);
        assertThat(audits.findByDocument(saved.and().id())).hasSize(3);
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

    private DefaultDocumentBridge usingStore(DocumentContentStore store) {
        return new DefaultDocumentBridge(new JpaBusinessDocumentRepository(entities, transactions),
                new JpaDocumentVersionRepository(entities, transactions), store, uploads,
                new JpaDocumentLifecycleRepository(entities, transactions), audits);
    }

    private byte[] download(DocumentVersion version) throws IOException {
        try (var input = bridge.document(version.documentId()).content(version.id())) {
            return input.readAllBytes();
        }
    }
}
