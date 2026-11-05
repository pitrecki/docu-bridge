package io.github.pitrecki.dokubridge.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class DocumentAuditContextTest {
    @ParameterizedTest
    @EnumSource(value = DocumentAuditAction.class, names = {"DOCUMENT_ATTACHED", "VERSION_ADDED", "UPLOAD_SUCCEEDED", "UPLOAD_FAILED", "UPLOAD_RECOVERED", "UPLOAD_RECOVERY_FAILED"})
    void uploadEventsRetainMandatoryVersionAndRequestIdentity(DocumentAuditAction action) {
        UUID version = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        assertThat(entry(action, version, request).versionId()).isEqualTo(version);
        assertThatNullPointerException().isThrownBy(() -> entry(action, null, request));
        assertThatNullPointerException().isThrownBy(() -> entry(action, version, null));
    }

    @ParameterizedTest
    @EnumSource(value = DocumentAuditAction.class, names = {"METADATA_UPDATED", "DOCUMENT_ARCHIVED", "DOCUMENT_DELETED"})
    void documentEventsHaveNoInventedUploadContext(DocumentAuditAction action) {
        var entry = entry(action, null, null);
        assertThat(entry.versionId()).isNull();
        assertThat(entry.requestId()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> entry(action, UUID.randomUUID(), null));
        assertThatIllegalArgumentException().isThrownBy(() -> entry(action, null, UUID.randomUUID()));
        assertThatIllegalArgumentException().isThrownBy(() -> entry(action, UUID.randomUUID(), UUID.randomUUID()));
    }

    private DocumentAuditEntry entry(DocumentAuditAction action, UUID version, UUID request) {
        return new DocumentAuditEntry(UUID.randomUUID(), UUID.randomUUID(), version, request, action, Instant.now());
    }
}
