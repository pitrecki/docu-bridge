package io.github.pitrecki.dokubridge.core.application.upload;

import com.google.common.io.Closer;
import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.content.exception.ContentChecksumMismatchException;
import io.github.pitrecki.dokubridge.core.domain.content.exception.DocumentContentNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.upload.exception.UploadChecksumUnavailableException;
import io.github.pitrecki.dokubridge.core.domain.upload.exception.UploadRequestNotFoundException;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.in.upload.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.upload.AttachDocument;
import io.github.pitrecki.dokubridge.core.port.in.upload.ReconcileUpload;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.upload.DocumentUploadRepository;
import io.github.pitrecki.dokubridge.core.port.out.upload.UploadReservation;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Coordinates request reservation, binary I/O and audited finalization across independent stores. */
public final class DocumentUploads {
    private final DocumentContentStore content;
    private final DocumentUploadRepository uploads;

    public DocumentUploads(DocumentContentStore content, DocumentUploadRepository uploads) {
        this.content = Objects.requireNonNull(content);
        this.uploads = Objects.requireNonNull(uploads);
    }

    public DocumentVersion reconcileUpload(ReconcileUpload command) throws IOException {
        Objects.requireNonNull(command, "Reconciliation command must not be null");
        var candidate = uploads.inspectForRecovery(command.requestId(), command.versionId());
        var version = candidate.version();
        if (!version.id().equals(command.versionId())) {
            throw new UploadRequestNotFoundException(command.requestId(), command.versionId());
        }
        if (version.state() != DocumentVersionState.PENDING) return version;
        var expected = candidate.expectedChecksum()
                .orElseThrow(() -> new UploadChecksumUnavailableException(command.requestId()));
        InputStream stored;
        try {
            stored = content.open(version.id());
        } catch (DocumentContentNotFoundException missing) {
            return uploads.inspectForRecovery(command.requestId(), command.versionId()).version();
        }
        var digest = UploadFingerprint.sha256();
        try (var closer = Closer.create()) {
            var input = closer.register(stored);
            try {
                new DigestInputStream(input, digest).transferTo(OutputStream.nullOutputStream());
            } catch (Throwable failure) {
                throw closer.rethrow(failure);
            }
        }
        var outcome = expected.value().equals(HexFormat.of().formatHex(digest.digest()))
                ? DocumentVersionState.AVAILABLE : DocumentVersionState.FAILED;
        return uploads.completeRecovery(command.requestId(), version.id(), outcome);
    }

    public DocumentVersion attach(AttachDocument command) throws IOException {
        Objects.requireNonNull(command, "Attach command must not be null");
        var reservation = uploads.reserveAttach(command.requestId(), UploadFingerprint.attach(command),
                command.owner(), command.metadata(), command.checksum());
        return upload(command.requestId(), reservation, command.checksum(), command.content());
    }

    public DocumentVersion addVersion(AddDocumentVersion command) throws IOException {
        Objects.requireNonNull(command, "Version command must not be null");
        var reservation = uploads.reserveVersion(command.requestId(), UploadFingerprint.version(command),
                command.documentId(), command.checksum());
        return upload(command.requestId(), reservation, command.checksum(), command.content());
    }

    private DocumentVersion upload(UUID requestId, UploadReservation reservation,
                                   ContentSha256 checksum, InputStream source) throws IOException {
        if (!reservation.acquired()) {
            return reservation.version();
        }
        var version = reservation.version();
        var digest = UploadFingerprint.sha256();
        try {
            content.store(version.id(), new DigestInputStream(source, digest));
            if (!HexFormat.of().formatHex(digest.digest()).equals(checksum.value())) {
                throw new ContentChecksumMismatchException(version.id());
            }
        } catch (IOException | RuntimeException failure) {
            try {
                uploads.complete(requestId, version.id(), DocumentVersionState.FAILED);
            } catch (RuntimeException finalizationFailure) {
                if (failure != finalizationFailure) failure.addSuppressed(finalizationFailure);
            }
            throw failure;
        }
        // A failed finalization must not trigger another content write or a guessed FAILED transition.
        return uploads.complete(requestId, version.id(), DocumentVersionState.AVAILABLE);
    }

}
