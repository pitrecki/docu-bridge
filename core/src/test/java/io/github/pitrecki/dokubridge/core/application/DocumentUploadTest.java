package io.github.pitrecki.dokubridge.core.application;

import io.github.pitrecki.dokubridge.core.domain.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.domain.exception.ContentChecksumMismatchException;
import io.github.pitrecki.dokubridge.core.port.in.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.out.BusinessDocumentRepository;
import io.github.pitrecki.dokubridge.core.port.out.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.DocumentUploadRepository;
import io.github.pitrecki.dokubridge.core.port.out.DocumentVersionRepository;
import io.github.pitrecki.dokubridge.core.port.out.UploadReservation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class DocumentUploadTest {
    final UUID requestId = UUID.randomUUID();
    final UUID documentId = UUID.randomUUID();
    final UUID versionId = UUID.randomUUID();
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "42");
    final byte[] bytes = {42};
    final ContentSha256 checksum = new ContentSha256(HexFormat.of().formatHex(UploadFingerprint.sha256().digest(bytes)));
    final List<String> events = new ArrayList<>();
    final Uploads uploads = new Uploads();
    final BusinessDocumentRepository documents = new BusinessDocumentRepository() {
        public Optional<BusinessDocument> findById(UUID id) { return Optional.empty(); }
        public List<BusinessDocument> findByOwner(DocumentOwnerRef ref) { return List.of(); }
    };
    final DocumentVersionRepository versions = (document, version) -> Optional.empty();
    IOException storageFailure;
    RuntimeException runtimeStorageFailure;
    final DocumentContentStore content = new DocumentContentStore() {
        public void store(UUID id, InputStream source) throws IOException {
            assertThat(id).isEqualTo(versionId);
            assertThat(uploads.version.state()).isEqualTo(DocumentVersionState.PENDING);
            events.add("store");
            if (storageFailure != null) throw storageFailure;
            if (runtimeStorageFailure != null) throw runtimeStorageFailure;
            source.transferTo(OutputStream.nullOutputStream());
        }
        public InputStream open(UUID id) { throw new AssertionError("Unexpected download"); }
    };
    final DefaultDocumentBridge bridge = new DefaultDocumentBridge(documents, versions, content, uploads);

    @Test
    void commitsPendingBeforeWritingAndFinalizesOnlyAfterContentWasVerified() throws IOException {
        var closed = new AtomicBoolean();
        var source = new ByteArrayInputStream(bytes) {
            public void close() { closed.set(true); }
        };
        var result = bridge.attach(new AttachDocument(requestId, owner, Map.of(), checksum, source));
        assertThat(result.state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(events).containsExactly("reserveAttach", "store", "AVAILABLE");
        assertThat(closed).isFalse();
    }

    @Test
    void addsVersionThroughItsSemanticReservationOperation() throws IOException {
        var result = bridge.addVersion(new AddDocumentVersion(requestId, documentId, checksum, new ByteArrayInputStream(bytes)));
        assertThat(result.state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(events).containsExactly("reserveVersion", "store", "AVAILABLE");
    }

    @Test
    void checksumMismatchFailsTheVersionAndPropagatesTheError() {
        var command = new AttachDocument(requestId, owner, Map.of(), new ContentSha256("0".repeat(64)), new ByteArrayInputStream(bytes));
        assertThatThrownBy(() -> bridge.attach(command)).isInstanceOf(ContentChecksumMismatchException.class);
        assertThat(uploads.version.state()).isEqualTo(DocumentVersionState.FAILED);
        assertThat(events).containsExactly("reserveAttach", "store", "FAILED");
    }

    @ParameterizedTest
    @EnumSource(DocumentVersionState.class)
    void replaysReturnCurrentVersionWithoutReadingOrRewritingContent(DocumentVersionState state) throws IOException {
        uploads.acquired = false;
        uploads.version = new DocumentVersion(versionId, documentId, 1, state);
        var untouched = new InputStream() {
            public int read() { throw new AssertionError("Replay must not read content"); }
        };
        assertThat(bridge.attach(new AttachDocument(requestId, owner, Map.of(), checksum, untouched)))
                .isEqualTo(uploads.version);
        assertThat(events).containsExactly("reserveAttach");
    }

    @Test
    void storageFailureIsPreservedWhenFailureFinalizationAlsoFails() {
        storageFailure = new IOException("Storage failed");
        uploads.finalizationFailure = new IllegalStateException("Database failed");
        assertThatThrownBy(() -> bridge.attach(command())).isSameAs(storageFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(uploads.finalizationFailure));
        assertThat(uploads.version.state()).isEqualTo(DocumentVersionState.PENDING);
        assertThat(events).containsExactly("reserveAttach", "store", "FAILED");
    }

    @Test
    void preservesPrimaryFailureEvenIfBothBoundariesThrowTheSameExceptionInstance() {
        runtimeStorageFailure = new IllegalStateException("Shared boundary failure");
        uploads.finalizationFailure = runtimeStorageFailure;
        assertThatThrownBy(() -> bridge.attach(command())).isSameAs(runtimeStorageFailure);
    }

    @Test
    void ordinaryStorageFailureIsRecordedAsFailed() {
        storageFailure = new IOException("Storage failed");
        assertThatThrownBy(() -> bridge.attach(command())).isSameAs(storageFailure);
        assertThat(uploads.version.state()).isEqualTo(DocumentVersionState.FAILED);
    }

    @Test
    void runtimeStorageFailureIsRecordedAndPreserved() {
        runtimeStorageFailure = new IllegalStateException("Storage failed");
        assertThatThrownBy(() -> bridge.attach(command())).isSameAs(runtimeStorageFailure);
        assertThat(uploads.version.state()).isEqualTo(DocumentVersionState.FAILED);
    }

    @Test
    void failedSuccessFinalizationDoesNotGuessFailedStateOrRepeatContentWrite() {
        uploads.finalizationFailure = new IllegalStateException("Database failed");
        assertThatThrownBy(() -> bridge.attach(command())).isSameAs(uploads.finalizationFailure);
        assertThat(events).containsExactly("reserveAttach", "store", "AVAILABLE");
        assertThat(uploads.version.state()).isEqualTo(DocumentVersionState.PENDING);
    }

    @Test
    void reservationFailureStopsBeforeStorage() {
        uploads.reservationFailure = new IllegalStateException("Database failed");
        assertThatThrownBy(() -> bridge.attach(command())).isSameAs(uploads.reservationFailure);
        assertThat(events).containsExactly("reserveAttach");
    }

    @Test
    void existingReadOnlyConstructorStillWorksAndRejectsUnconfiguredUploads() {
        var readOnly = new DefaultDocumentBridge(documents, versions, content);
        assertThat(readOnly.get(documentId)).isEmpty();
        assertThatThrownBy(() -> readOnly.attach(command())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> readOnly.addVersion(new AddDocumentVersion(requestId, documentId, checksum, new ByteArrayInputStream(bytes))))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatNullPointerException().isThrownBy(() -> new DefaultDocumentBridge(documents, versions, content, null));
        assertThatNullPointerException().isThrownBy(() -> bridge.attach(null));
        assertThatNullPointerException().isThrownBy(() -> bridge.addVersion(null));
    }

    private AttachDocument command() {
        return new AttachDocument(requestId, owner, Map.of(), checksum, new ByteArrayInputStream(bytes));
    }

    private class Uploads implements DocumentUploadRepository {
        DocumentVersion version = DocumentVersion.pending(versionId, documentId, 1);
        boolean acquired = true;
        RuntimeException finalizationFailure;
        RuntimeException reservationFailure;

        public UploadReservation reserveAttach(UUID request, String fingerprint, DocumentOwnerRef ref, Map<String, Object> metadata) {
            events.add("reserveAttach");
            assertThat(request).isEqualTo(requestId);
            assertThat(ref).isEqualTo(owner);
            assertThat(fingerprint).matches("[0-9a-f]{64}");
            if (reservationFailure != null) throw reservationFailure;
            return new UploadReservation(version, acquired);
        }
        public UploadReservation reserveVersion(UUID request, String fingerprint, UUID document) {
            events.add("reserveVersion");
            assertThat(request).isEqualTo(requestId);
            assertThat(document).isEqualTo(documentId);
            return new UploadReservation(version, acquired);
        }
        public DocumentVersion complete(UUID request, UUID id, DocumentVersionState outcome) {
            assertThat(request).isEqualTo(requestId);
            assertThat(id).isEqualTo(versionId);
            events.add(outcome.name());
            if (finalizationFailure != null) throw finalizationFailure;
            version = new DocumentVersion(id, documentId, 1, outcome);
            return version;
        }
    }
}
