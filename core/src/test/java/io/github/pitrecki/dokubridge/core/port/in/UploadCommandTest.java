package io.github.pitrecki.dokubridge.core.port.in;

import io.github.pitrecki.dokubridge.core.domain.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersionState;
import io.github.pitrecki.dokubridge.core.port.out.UploadReservation;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class UploadCommandTest {
    final UUID request = UUID.randomUUID();
    final UUID document = UUID.randomUUID();
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "42");
    final ContentSha256 checksum = new ContentSha256("a".repeat(64));

    @Test
    void normalizesHexadecimalChecksumCase() {
        assertThat(new ContentSha256("ABCDEF01".repeat(8))).isEqualTo(new ContentSha256("abcdef01".repeat(8)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "xyz", "aaaaaaaa", "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"})
    void rejectsInvalidChecksums(String hash) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ContentSha256(hash));
    }

    @Test
    void snapshotsAttachMetadataBeforePersistence() {
        var values = new ArrayList<>(List.of("original"));
        var metadata = new HashMap<String, Object>();
        metadata.put("items", values);
        var command = new AttachDocument(request, owner, metadata, checksum, bytes());
        values.clear();
        metadata.clear();
        assertThat(command.metadata()).containsEntry("items", List.of("original"));
        assertThatThrownBy(() -> command.metadata().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresRequestIdentityTargetChecksumAndContent() {
        assertThatNullPointerException().isThrownBy(() -> new AttachDocument(null, owner, Map.of(), checksum, bytes()));
        assertThatNullPointerException().isThrownBy(() -> new AttachDocument(request, null, Map.of(), checksum, bytes()));
        assertThatNullPointerException().isThrownBy(() -> new AttachDocument(request, owner, null, checksum, bytes()));
        assertThatNullPointerException().isThrownBy(() -> new AttachDocument(request, owner, Map.of(), null, bytes()));
        assertThatNullPointerException().isThrownBy(() -> new AttachDocument(request, owner, Map.of(), checksum, null));
        assertThatNullPointerException().isThrownBy(() -> new AddDocumentVersion(null, document, checksum, bytes()));
        assertThatNullPointerException().isThrownBy(() -> new AddDocumentVersion(request, null, checksum, bytes()));
        assertThatNullPointerException().isThrownBy(() -> new AddDocumentVersion(request, document, null, bytes()));
        assertThatNullPointerException().isThrownBy(() -> new AddDocumentVersion(request, document, checksum, null));
    }

    @Test
    void rejectsANewReservationThatIsAlreadyFinalized() {
        var available = new DocumentVersion(UUID.randomUUID(), document, 1, DocumentVersionState.AVAILABLE);
        assertThatIllegalArgumentException().isThrownBy(() -> new UploadReservation(available, true));
        assertThat(new UploadReservation(available, false).version()).isEqualTo(available);
    }

    private ByteArrayInputStream bytes() {
        return new ByteArrayInputStream(new byte[0]);
    }
}
