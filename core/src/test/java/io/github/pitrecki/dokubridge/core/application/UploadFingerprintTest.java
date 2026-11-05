package io.github.pitrecki.dokubridge.core.application;

import io.github.pitrecki.dokubridge.core.domain.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.DocumentOwnerRef;
import io.github.pitrecki.dokubridge.core.port.in.AddDocumentVersion;
import io.github.pitrecki.dokubridge.core.port.in.AttachDocument;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class UploadFingerprintTest {
    final UUID request = UUID.randomUUID();
    final DocumentOwnerRef owner = new DocumentOwnerRef("Invoice", "42");
    final ContentSha256 checksum = new ContentSha256("a".repeat(64));

    @Test
    void v1EncodingIsStableForPersistedRequestKeys() {
        assertThat(fingerprint(Map.of())).isEqualTo("366785d87965cb135546bb8bf71e57556c2ed9cfb7a4dcdb40ef0ffca6d554d8");
    }

    @Test
    void ignoresObjectKeyOrderAndEquivalentNumericRepresentationsRecursively() {
        var first = new LinkedHashMap<String, Object>();
        first.put("a", List.of(1, Map.of("n", new BigDecimal("2.00"))));
        first.put("b", true);
        var second = new LinkedHashMap<String, Object>();
        second.put("b", true);
        second.put("a", List.of(new BigInteger("1"), Map.of("n", 2.0)));
        assertThat(fingerprint(first)).isEqualTo(fingerprint(second));
        assertThat(fingerprint(Map.of("n", -0.0))).isEqualTo(fingerprint(Map.of("n", 0)));
    }

    @Test
    void distinguishesArrayOrderScalarTypesAndChangedMetadata() {
        assertThat(fingerprint(Map.of("a", List.of(1, 2)))).isNotEqualTo(fingerprint(Map.of("a", List.of(2, 1))));
        assertThat(fingerprint(Map.of("a", "1"))).isNotEqualTo(fingerprint(Map.of("a", 1)));
        assertThat(fingerprint(Map.of("a", false))).isNotEqualTo(fingerprint(Map.of()));
        var nullable = new LinkedHashMap<String, Object>();
        nullable.put("a", null);
        assertThat(fingerprint(nullable)).isNotEqualTo(fingerprint(Map.of("a", "null")));
    }

    @Test
    void includesOwnerChecksumOperationAndVersionTargetButNotRequestId() {
        var original = new AttachDocument(request, owner, Map.of(), checksum, bytes());
        String hash = UploadFingerprint.attach(original);
        assertThat(hash).isEqualTo(UploadFingerprint.attach(new AttachDocument(UUID.randomUUID(), owner, Map.of(), checksum, bytes())));
        assertThat(hash).isNotEqualTo(UploadFingerprint.attach(new AttachDocument(request, new DocumentOwnerRef("Order", "42"), Map.of(), checksum, bytes())));
        assertThat(hash).isNotEqualTo(UploadFingerprint.attach(new AttachDocument(request, owner, Map.of(), new ContentSha256("b".repeat(64)), bytes())));
        var version = new AddDocumentVersion(request, UUID.randomUUID(), checksum, bytes());
        assertThat(hash).isNotEqualTo(UploadFingerprint.version(version));
        assertThat(UploadFingerprint.version(version)).isNotEqualTo(UploadFingerprint.version(
                new AddDocumentVersion(request, UUID.randomUUID(), checksum, bytes())));
    }

    @Test
    void lengthFramingPreventsAmbiguousFieldConcatenationAndStringReplacement() {
        var first = new AttachDocument(request, new DocumentOwnerRef("ab", "c"), Map.of(), checksum, bytes());
        var second = new AttachDocument(request, new DocumentOwnerRef("a", "bc"), Map.of(), checksum, bytes());
        assertThat(UploadFingerprint.attach(first)).isNotEqualTo(UploadFingerprint.attach(second));
        assertThat(fingerprint(Map.of("value", "\ud800"))).isNotEqualTo(fingerprint(Map.of("value", "?")));
    }

    private String fingerprint(Map<String, Object> metadata) {
        return UploadFingerprint.attach(new AttachDocument(request, owner, metadata, checksum, bytes()));
    }

    private ByteArrayInputStream bytes() {
        return new ByteArrayInputStream(new byte[0]);
    }
}
