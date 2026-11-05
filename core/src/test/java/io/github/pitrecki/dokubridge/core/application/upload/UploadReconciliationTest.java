package io.github.pitrecki.dokubridge.core.application.upload;

import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.content.exception.DocumentContentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.document.BusinessDocument;
import io.github.pitrecki.dokubridge.core.domain.document.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.upload.exception.UploadChecksumUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.upload.exception.UploadRequestNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.upload.ReconcileUpload;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.document.BusinessDocumentRepository;
import io.github.pitrecki.dokubridge.core.port.out.upload.DocumentUploadRepository;
import io.github.pitrecki.dokubridge.core.port.out.upload.UploadRecoveryCandidate;
import io.github.pitrecki.dokubridge.core.port.out.upload.UploadReservation;
import io.github.pitrecki.dokubridge.core.port.out.version.DocumentVersionRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class UploadReconciliationTest {
    final UUID requestId = UUID.randomUUID();
    final UUID versionId = UUID.randomUUID();
    final UUID documentId = UUID.randomUUID();
    final ReconcileUpload command = new ReconcileUpload(requestId, versionId);
    final byte[] bytes = {42};
    final ContentSha256 checksum = new ContentSha256(HexFormat.of().formatHex(UploadFingerprint.sha256().digest(bytes)));
    final List<String> events = new ArrayList<>();
    final Uploads uploads = new Uploads();
    final BusinessDocumentRepository documents = new BusinessDocumentRepository() {
        public Optional<BusinessDocument> findById(UUID id) { return Optional.empty(); }
        public List<BusinessDocument> findByOwner(DocumentOwnerRef owner) { return List.of(); }
    };
    final DocumentVersionRepository versions = (document, version) -> Optional.empty();
    IOException openFailure;
    IOException readFailure;
    IOException closeFailure;
    boolean closed;
    final DocumentContentStore content = new DocumentContentStore() {
        public void store(UUID id, InputStream source) { throw new AssertionError("Recovery must not write content"); }
        public InputStream open(UUID id) throws IOException {
            assertThat(id).isEqualTo(versionId);
            events.add("open");
            if (openFailure != null) throw openFailure;
            return new InputStream() {
                final InputStream delegate = new ByteArrayInputStream(bytes);
                public int read() throws IOException {
                    if (readFailure != null) throw readFailure;
                    return delegate.read();
                }
                public void close() throws IOException {
                    closed = true;
                    events.add("close");
                    if (closeFailure != null) throw closeFailure;
                }
            };
        }
    };
    final DefaultDocumentBridge bridge = new DefaultDocumentBridge(documents, versions, content, uploads);

    @Test
    void verifiesExistingContentAndClosesItBeforeRequestingAtomicRecovery() throws IOException {
        assertThat(bridge.reconcileUpload(command).state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(closed).isTrue();
        assertThat(events).containsExactly("inspect", "open", "close", "AVAILABLE");
    }

    @Test
    void mismatchFinalizesFailedWithoutRewritingContent() throws IOException {
        uploads.expected = Optional.of(new ContentSha256("0".repeat(64)));
        assertThat(bridge.reconcileUpload(command).state()).isEqualTo(DocumentVersionState.FAILED);
        assertThat(events).containsExactly("inspect", "open", "close", "FAILED");
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionState.class, names = {"AVAILABLE", "FAILED"})
    void terminalLegacyVersionsNeedNeitherChecksumNorContentRead(DocumentVersionState state) throws IOException {
        uploads.version = new DocumentVersion(versionId, documentId, 1, state);
        uploads.expected = Optional.empty();
        assertThat(bridge.reconcileUpload(command)).isEqualTo(uploads.version);
        assertThat(events).containsExactly("inspect");
    }

    @Test
    void missingContentLeavesVersionPending() throws IOException {
        openFailure = new DocumentContentNotFoundException(versionId, null);
        assertThat(bridge.reconcileUpload(command).state()).isEqualTo(DocumentVersionState.PENDING);
        assertThat(events).containsExactly("inspect", "open", "inspect");
    }

    @Test
    void missingContentReturnsFreshStateIfUploaderFinishedMeanwhile() throws IOException {
        openFailure = new DocumentContentNotFoundException(versionId, null);
        uploads.completeOnSecondInspection = true;
        assertThat(bridge.reconcileUpload(command).state()).isEqualTo(DocumentVersionState.AVAILABLE);
        assertThat(events).containsExactly("inspect", "open", "inspect");
    }

    @Test
    void refusesToGuessChecksumForLegacyPendingRequest() {
        uploads.expected = Optional.empty();
        assertThatThrownBy(() -> bridge.reconcileUpload(command)).isInstanceOf(UploadChecksumUnavailableException.class);
        assertThat(events).containsExactly("inspect");
        assertThat(uploads.version.state()).isEqualTo(DocumentVersionState.PENDING);
    }

    @Test
    void openFailureDoesNotChangeState() {
        openFailure = new IOException("Storage inaccessible");
        assertThatThrownBy(() -> bridge.reconcileUpload(command)).isSameAs(openFailure);
        assertThat(events).containsExactly("inspect", "open");
    }

    @Test
    void readFailureClosesStreamWithoutFinalizing() {
        readFailure = new IOException("Read interrupted");
        assertThatThrownBy(() -> bridge.reconcileUpload(command)).isSameAs(readFailure);
        assertThat(events).containsExactly("inspect", "open", "close");
        assertThat(closed).isTrue();
    }

    @Test
    void missingContentReportedDuringReadIsAnErrorRatherThanASuccessfulMissingProbe() {
        readFailure = new DocumentContentNotFoundException(versionId, null);
        assertThatThrownBy(() -> bridge.reconcileUpload(command)).isSameAs(readFailure);
        assertThat(events).containsExactly("inspect", "open", "close");
    }

    @Test
    void closeFailurePreventsFinalization() {
        closeFailure = new IOException("Close failed");
        assertThatThrownBy(() -> bridge.reconcileUpload(command)).isSameAs(closeFailure);
        assertThat(events).containsExactly("inspect", "open", "close");
    }

    @Test
    void failedRecoveryCommitDoesNotGuessAnotherOutcome() {
        uploads.finalizationFailure = new IllegalStateException("Commit failed");
        assertThatThrownBy(() -> bridge.reconcileUpload(command)).isSameAs(uploads.finalizationFailure);
        assertThat(uploads.version.state()).isEqualTo(DocumentVersionState.PENDING);
        assertThat(events).containsExactly("inspect", "open", "close", "AVAILABLE");
    }

    @Test
    void foreignVersionSnapshotIsRejectedBeforeOpeningContent() {
        uploads.version = DocumentVersion.pending(UUID.randomUUID(), documentId, 1);
        assertThatThrownBy(() -> bridge.reconcileUpload(command)).isInstanceOf(UploadRequestNotFoundException.class);
        assertThat(events).containsExactly("inspect");
    }

    @Test
    void rejectsInvalidCommandsAndUnconfiguredRecovery() {
        assertThatNullPointerException().isThrownBy(() -> new ReconcileUpload(null, versionId));
        assertThatNullPointerException().isThrownBy(() -> new ReconcileUpload(requestId, null));
        assertThatNullPointerException().isThrownBy(() -> bridge.reconcileUpload(null));
        var readOnly = new DefaultDocumentBridge(documents, versions, content);
        assertThatThrownBy(() -> readOnly.reconcileUpload(command)).isInstanceOf(UnsupportedOperationException.class);
    }

    private class Uploads implements DocumentUploadRepository {
        DocumentVersion version = DocumentVersion.pending(versionId, documentId, 1);
        Optional<ContentSha256> expected = Optional.of(checksum);
        RuntimeException finalizationFailure;
        boolean completeOnSecondInspection;
        int inspections;

        public UploadReservation reserveAttach(UUID request, String fingerprint, DocumentOwnerRef owner, Map<String, Object> metadata) {
            throw new AssertionError("Recovery must not allocate a version");
        }
        public UploadReservation reserveVersion(UUID request, String fingerprint, UUID document) {
            throw new AssertionError("Recovery must not allocate a version");
        }
        public DocumentVersion complete(UUID request, UUID version, DocumentVersionState outcome) {
            throw new AssertionError("Recovery requires its own audit operation");
        }
        public UploadRecoveryCandidate inspectForRecovery(UUID request, UUID id) {
            assertThat(request).isEqualTo(requestId);
            assertThat(id).isEqualTo(versionId);
            events.add("inspect");
            if (++inspections == 2 && completeOnSecondInspection) version = version.markAvailable();
            return new UploadRecoveryCandidate(version, expected);
        }
        public DocumentVersion completeRecovery(UUID request, UUID id, DocumentVersionState outcome) {
            assertThat(request).isEqualTo(requestId);
            assertThat(id).isEqualTo(versionId);
            events.add(outcome.name());
            if (finalizationFailure != null) throw finalizationFailure;
            version = new DocumentVersion(versionId, documentId, 1, outcome);
            return version;
        }
    }
}
