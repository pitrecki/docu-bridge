package io.github.pitrecki.dokubridge.adapter.jpa;

import io.github.pitrecki.dokubridge.adapter.filesystem.FilesystemDocumentContentStore;
import io.github.pitrecki.dokubridge.adapter.jpa.persistence.DocumentVersionEntity;
import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.DocumentAuditAction;
import io.github.pitrecki.dokubridge.core.domain.DocumentAuditEntry;
import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentContentUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.exception.UploadChecksumUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.exception.UploadRequestConflictException;
import io.github.pitrecki.dokubridge.core.domain.exception.UploadRequestNotFoundException;
import io.github.pitrecki.dokubridge.core.port.in.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.in.Delete;
import io.github.pitrecki.dokubridge.core.port.in.ReconcileUpload;
import io.github.pitrecki.dokubridge.core.port.out.DocumentContentStore;
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
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
class JpaUploadReconciliationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager entities;
    static JpaTransactionManager transactions;
    @TempDir Path root;
    JpaDocumentUploadRepository uploads;
    JpaDocumentAuditRepository audits;
    DefaultDocumentBridge bridge;
    FilesystemDocumentContentStore content;
    final UUID request = UUID.randomUUID();
    final String fingerprint = "a".repeat(64);
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "recovery-42");
    final byte[] bytes = {42};
    ContentSha256 checksum;

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
        checksum = new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        uploads = new JpaDocumentUploadRepository(entities, transactions);
        audits = new JpaDocumentAuditRepository(entities, transactions);
        content = new FilesystemDocumentContentStore(root);
        bridge = bridge(content);
    }

    @Test
    void recoversPublishedContentAfterFailedUploadCommitAndReplaysItWithoutAnotherAudit() throws Exception {
        sql("alter table dokubridge_audit add constraint reject_success check (action <> 'UPLOAD_SUCCEEDED')");
        try {
            assertThatThrownBy(() -> bridge.attach(attach())).isInstanceOf(RuntimeException.class);
        } finally {
            sql("alter table dokubridge_audit drop constraint reject_success");
        }
        var pending = bridge.findByOwner(owner).getFirst();
        UUID versionId = new TransactionTemplate(transactions).execute(status ->
                (UUID) entities.createNativeQuery("select id from dokubridge_versions").getSingleResult());
        var command = new ReconcileUpload(request, versionId);
        assertThat(uploads.inspectForRecovery(request, versionId).expectedChecksum()).contains(checksum);
        var recovered = bridge.reconcileUpload(command);
        assertThat(recovered.state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(bridge.reconcileUpload(command)).isEqualTo(recovered);
        assertThat(bridge.attach(attach())).isEqualTo(recovered);
        assertThat(bridge.get(pending.id()).orElseThrow().revision()).isZero();
        try (var input = bridge.getContent(pending.id(), versionId)) {
            assertThat(input.readAllBytes()).containsExactly(bytes);
        }
        assertActions(recovered, DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_RECOVERED);
    }

    @Test
    void mismatchRetainsBlobButMakesItFailedAndUndownloadable() throws Exception {
        var pending = reserve();
        content.store(pending.id(), new ByteArrayInputStream(new byte[]{7}));
        var failed = bridge.reconcileUpload(command(pending));
        assertThat(failed.state()).isEqualTo(DocumentVersionState.FAILED);
        assertThat(bridge.reconcileUpload(command(pending))).isEqualTo(failed);
        assertThat(root.resolve(pending.id().toString())).exists();
        assertThatThrownBy(() -> bridge.getContent(pending.documentId(), pending.id()))
                .isInstanceOf(DocumentContentUnavailableException.class);
        assertActions(pending, DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_RECOVERY_FAILED);
    }

    @Test
    void missingBlobStaysPendingAndCanBeReconciledAfterPublication() throws Exception {
        var pending = reserve();
        assertThat(bridge.reconcileUpload(command(pending))).isEqualTo(pending);
        assertActions(pending, DocumentAuditAction.DOCUMENT_ATTACHED);
        content.store(pending.id(), new ByteArrayInputStream(bytes));
        assertThat(bridge.reconcileUpload(command(pending)).state()).isEqualTo(DocumentVersionState.AVAILABLE);
    }

    @Test
    void legacyPendingChecksumIsNeverGuessedOrBackfilledFromAReplay() throws Exception {
        var legacy = uploads.reserveAttach(request, fingerprint, owner, Map.of()).version();
        content.store(legacy.id(), new ByteArrayInputStream(bytes));
        assertThatThrownBy(() -> bridge.reconcileUpload(command(legacy))).isInstanceOf(UploadChecksumUnavailableException.class);
        assertThat(uploads.reserveAttach(request, fingerprint, owner, Map.of(), checksum).acquired()).isFalse();
        assertThat(uploads.inspectForRecovery(request, legacy.id()).expectedChecksum()).isEmpty();
        assertThatThrownBy(() -> uploads.completeRecovery(request, legacy.id(), DocumentVersionState.AVAILABLE))
                .isInstanceOf(UploadChecksumUnavailableException.class);
        assertActions(legacy, DocumentAuditAction.DOCUMENT_ATTACHED);
        var complete = uploads.complete(request, legacy.id(), DocumentVersionState.AVAILABLE);
        assertThat(bridge.reconcileUpload(command(legacy))).isEqualTo(complete);
    }

    @Test
    void validatesRequestVersionAssociationBeforeOpeningContent() {
        var pending = reserve();
        var guarded = bridge(new DocumentContentStore() {
            public void store(UUID id, InputStream source) { throw new AssertionError("Unexpected store"); }
            public InputStream open(UUID id) { throw new AssertionError("Foreign request must not access content"); }
        });
        for (var invalid : List.of(new ReconcileUpload(UUID.randomUUID(), pending.id()),
                new ReconcileUpload(request, UUID.randomUUID()))) {
            assertThatThrownBy(() -> guarded.reconcileUpload(invalid)).isInstanceOf(UploadRequestNotFoundException.class);
            assertThatThrownBy(() -> uploads.completeRecovery(invalid.requestId(), invalid.versionId(), DocumentVersionState.AVAILABLE))
                    .isInstanceOf(UploadRequestNotFoundException.class);
        }
        assertActions(pending, DocumentAuditAction.DOCUMENT_ATTACHED);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionState.class, names = {"AVAILABLE", "FAILED"})
    void recoveryAuditFailureRollsBackStateAndPermitsExplicitRetry(DocumentVersionState outcome) throws Exception {
        var pending = reserve();
        content.store(pending.id(), new ByteArrayInputStream(outcome == DocumentVersionState.AVAILABLE ? bytes : new byte[]{7}));
        sql("alter table dokubridge_audit add constraint reject_recovery check (action not in ('UPLOAD_RECOVERED', 'UPLOAD_RECOVERY_FAILED'))");
        try {
            assertThatThrownBy(() -> bridge.reconcileUpload(command(pending))).isInstanceOf(RuntimeException.class);
            assertThat(uploads.inspectForRecovery(request, pending.id()).version()).isEqualTo(pending);
            assertActions(pending, DocumentAuditAction.DOCUMENT_ATTACHED);
        } finally {
            sql("alter table dokubridge_audit drop constraint reject_recovery");
        }
        assertThat(bridge.reconcileUpload(command(pending)).state()).isEqualTo(outcome);
        assertThat(audits.findByDocument(pending.documentId())).hasSize(2);
    }

    @Test
    void facadePersistsChecksumForAttachmentsAndAdditionalVersionsWithoutChangingFingerprint() throws Exception {
        var first = bridge.attach(attach());
        var nextRequest = UUID.randomUUID();
        var next = bridge.addVersion(new AddDocumentVersion(nextRequest, first.documentId(), checksum, new ByteArrayInputStream(bytes)));
        assertThat(uploads.inspectForRecovery(request, first.id()).expectedChecksum()).contains(checksum);
        assertThat(uploads.inspectForRecovery(nextRequest, next.id()).expectedChecksum()).contains(checksum);
        assertThat(next.number()).isEqualTo(2);
        var pendingRequest = UUID.randomUUID();
        var reserved = uploads.reserveVersion(pendingRequest, fingerprint, first.documentId(), checksum).version();
        assertThatThrownBy(() -> uploads.reserveVersion(pendingRequest, fingerprint, first.documentId(), new ContentSha256("0".repeat(64))))
                .isInstanceOf(UploadRequestConflictException.class);
        assertThat(uploads.inspectForRecovery(pendingRequest, reserved.id()).expectedChecksum()).contains(checksum);
    }

    @Test
    void failedReservationRollsBackChecksumAndCanClaimSameKeyAgain() {
        sql("alter table dokubridge_audit add constraint reject_attach check (action <> 'DOCUMENT_ATTACHED')");
        try {
            assertThatThrownBy(this::reserve).isInstanceOf(RuntimeException.class);
            long requests = new TransactionTemplate(transactions).execute(status ->
                    ((Number) entities.createNativeQuery("select count(*) from dokubridge_upload_requests").getSingleResult()).longValue());
            assertThat(requests).isZero();
        } finally {
            sql("alter table dokubridge_audit drop constraint reject_attach");
        }
        assertThat(uploads.inspectForRecovery(request, reserve().id()).expectedChecksum()).contains(checksum);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionState.class, names = {"AVAILABLE", "FAILED"})
    void repeatedFinalizationToSameStateIsANoopAndOppositeStateIsRejected(DocumentVersionState outcome) {
        var pending = reserve();
        var terminal = uploads.completeRecovery(request, pending.id(), outcome);
        assertThat(uploads.complete(request, pending.id(), outcome)).isEqualTo(terminal);
        assertThat(uploads.completeRecovery(request, pending.id(), outcome)).isEqualTo(terminal);
        var other = outcome == DocumentVersionState.AVAILABLE ? DocumentVersionState.FAILED : DocumentVersionState.AVAILABLE;
        assertThatIllegalStateException().isThrownBy(() -> uploads.complete(request, pending.id(), other));
        assertThatIllegalStateException().isThrownBy(() -> uploads.completeRecovery(request, pending.id(), other));
        assertThatIllegalArgumentException().isThrownBy(() -> uploads.completeRecovery(request, pending.id(), DocumentVersionState.PENDING));
        assertThat(audits.findByDocument(pending.documentId())).hasSize(2);
    }

    @Test
    void enclosingHostTransactionIsRejectedBeforeContentIo() {
        var pending = reserve();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThatIllegalStateException().isThrownBy(() -> bridge.reconcileUpload(command(pending)))
                    .withMessageContaining("host transaction");
            assertThatIllegalStateException().isThrownBy(() -> uploads.completeRecovery(request, pending.id(), DocumentVersionState.AVAILABLE));
        });
        assertThat(uploads.inspectForRecovery(request, pending.id()).version()).isEqualTo(pending);
    }

    @Test
    void concurrentReconciliationsProduceOneTransitionAndOneAudit() throws Exception {
        var pending = reserve();
        content.store(pending.id(), new ByteArrayInputStream(bytes));
        var bothReading = new CountDownLatch(2);
        var concurrent = bridge(new DocumentContentStore() {
            public void store(UUID id, InputStream source) { throw new AssertionError("Unexpected store"); }
            public InputStream open(UUID id) throws IOException {
                assertNoTransaction();
                bothReading.countDown();
                await(bothReading);
                return content.open(id);
            }
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrent.reconcileUpload(command(pending)));
            var second = executor.submit(() -> concurrent.reconcileUpload(command(pending)));
            assertThat(first.get(20, TimeUnit.SECONDS).state()).isEqualTo(DocumentVersionState.AVAILABLE);
            assertThat(second.get(20, TimeUnit.SECONDS).state()).isEqualTo(DocumentVersionState.AVAILABLE);
        }
        assertActions(pending, DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_RECOVERED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reconciliationDoesNotBreakAnUploaderBeforeOrAfterPublication(boolean published) throws Exception {
        var atPublication = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var id = new AtomicReference<UUID>();
        var uploading = bridge(new DocumentContentStore() {
            public void store(UUID version, InputStream source) throws IOException {
                assertNoTransaction();
                id.set(version);
                if (published) content.store(version, source);
                atPublication.countDown();
                await(release);
                if (!published) content.store(version, source);
            }
            public InputStream open(UUID version) throws IOException {
                assertNoTransaction();
                return content.open(version);
            }
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> uploading.attach(attach()));
            try {
                assertThat(atPublication.await(20, TimeUnit.SECONDS)).isTrue();
                var recovered = uploading.reconcileUpload(new ReconcileUpload(request, id.get()));
                assertThat(recovered.state()).isEqualTo(published ? DocumentVersionState.AVAILABLE : DocumentVersionState.PENDING);
            } finally {
                release.countDown();
            }
            var complete = result.get(20, TimeUnit.SECONDS);
            assertThat(complete.state()).isEqualTo(DocumentVersionState.AVAILABLE);
            assertActions(complete, DocumentAuditAction.DOCUMENT_ATTACHED,
                    published ? DocumentAuditAction.UPLOAD_RECOVERED : DocumentAuditAction.UPLOAD_SUCCEEDED);
        }
    }

    @Test
    void recoveryAfterLogicalDeleteDoesNotExposeContentOrChangeRevision() throws Exception {
        var pending = reserve();
        content.store(pending.id(), new ByteArrayInputStream(bytes));
        bridge.delete(new Delete(pending.documentId()));
        assertThat(bridge.reconcileUpload(command(pending)).state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(bridge.get(pending.documentId())).isEmpty();
        assertThat(bridge.findByOwner(owner)).isEmpty();
        assertThatThrownBy(() -> bridge.getContent(pending.documentId(), pending.id())).isInstanceOf(DocumentNotFoundException.class);
        assertThat(new JpaBusinessDocumentRepository(entities, transactions).findById(pending.documentId()).orElseThrow().revision()).isEqualTo(1);
        assertActions(pending, DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.DOCUMENT_DELETED, DocumentAuditAction.UPLOAD_RECOVERED);
    }

    @Test
    void recoveryCommitsStateAndAuditWhenHostRetainsTheInspectionPersistenceContext() throws Exception {
        var pending = reserve();
        content.store(pending.id(), new ByteArrayInputStream(bytes));
        var retained = factory.getObject().createEntityManager();
        try {
            TransactionSynchronizationManager.bindResource(factory.getObject(), new EntityManagerHolder(retained));
            assertThat(bridge.reconcileUpload(command(pending)).state()).isEqualTo(DocumentVersionState.AVAILABLE);
        } finally {
            if (TransactionSynchronizationManager.hasResource(factory.getObject())) {
                TransactionSynchronizationManager.unbindResource(factory.getObject());
            }
            retained.close();
        }
        assertThat(uploads.inspectForRecovery(request, pending.id()).version().state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertActions(pending, DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_RECOVERED);
    }

    @Test
    void retainedHostContextCannotRecoverAnObsoletePendingSnapshot() throws Exception {
        var pending = reserve();
        var retained = factory.getObject().createEntityManager();
        try {
            retained.find(DocumentVersionEntity.class, pending.id());
            TransactionSynchronizationManager.bindResource(factory.getObject(), new EntityManagerHolder(retained));
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> uploads.complete(request, pending.id(), DocumentVersionState.AVAILABLE)).get(20, TimeUnit.SECONDS);
            }
            // No blob exists. A stale snapshot would incorrectly report PENDING here.
            assertThat(bridge.reconcileUpload(command(pending)).state()).isEqualTo(DocumentVersionState.AVAILABLE);
        } finally {
            if (TransactionSynchronizationManager.hasResource(factory.getObject())) {
                TransactionSynchronizationManager.unbindResource(factory.getObject());
            }
            retained.close();
        }
        assertActions(pending, DocumentAuditAction.DOCUMENT_ATTACHED, DocumentAuditAction.UPLOAD_SUCCEEDED);
    }

    private DefaultDocumentBridge bridge(DocumentContentStore store) {
        return new DefaultDocumentBridge(new JpaBusinessDocumentRepository(entities, transactions),
                new JpaDocumentVersionRepository(entities, transactions), store, uploads,
                new JpaDocumentLifecycleRepository(entities, transactions));
    }

    private DocumentVersion reserve() {
        return uploads.reserveAttach(request, fingerprint, owner, Map.of(), checksum).version();
    }

    private ReconcileUpload command(DocumentVersion version) {
        return new ReconcileUpload(request, version.id());
    }

    private AttachDocument attach() {
        return new AttachDocument(request, owner, Map.of(), checksum, new ByteArrayInputStream(bytes));
    }

    private void assertActions(DocumentVersion version, DocumentAuditAction... actions) {
        assertThat(audits.findByDocument(version.documentId())).extracting(DocumentAuditEntry::action).containsExactly(actions);
    }

    private void sql(String statement) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> entities.createNativeQuery(statement).executeUpdate());
    }

    private static void assertNoTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for upload phase");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for upload phase", interrupted);
        }
    }
}
