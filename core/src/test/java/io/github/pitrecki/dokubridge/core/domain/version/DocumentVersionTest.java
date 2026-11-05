package io.github.pitrecki.dokubridge.core.domain.version;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class DocumentVersionTest {
    private final UUID versionId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @Test
    void startsPendingAndFinalizesWithoutMutatingTheOriginalSnapshot() {
        var pending = DocumentVersion.pending(versionId, documentId, 1);
        var available = pending.markAvailable();

        assertThat(pending.state()).isEqualTo(DocumentVersionState.PENDING);
        assertThat(available).isEqualTo(new DocumentVersion(versionId, documentId, 1,
                DocumentVersionState.AVAILABLE));
    }

    @Test
    void recordsFailedUploadWithTheSameVersionIdentity() {
        var failed = DocumentVersion.pending(versionId, documentId, 2).markFailed();
        assertThat(failed).isEqualTo(new DocumentVersion(versionId, documentId, 2,
                DocumentVersionState.FAILED));
    }

    @ParameterizedTest
    @EnumSource(value = DocumentVersionState.class, names = {"AVAILABLE", "FAILED"})
    void rejectsRepeatedOrConflictingFinalization(DocumentVersionState state) {
        var version = new DocumentVersion(versionId, documentId, 1, state);
        assertThatIllegalStateException().isThrownBy(version::markAvailable);
        assertThatIllegalStateException().isThrownBy(version::markFailed);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveVersionNumbers(int number) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DocumentVersion.pending(versionId, documentId, number));
    }

    @Test
    void requiresVersionIdentityDocumentIdentityAndState() {
        assertThatNullPointerException()
                .isThrownBy(() -> DocumentVersion.pending(null, documentId, 1));
        assertThatNullPointerException()
                .isThrownBy(() -> DocumentVersion.pending(versionId, null, 1));
        assertThatNullPointerException()
                .isThrownBy(() -> new DocumentVersion(versionId, documentId, 1, null));
    }
}
