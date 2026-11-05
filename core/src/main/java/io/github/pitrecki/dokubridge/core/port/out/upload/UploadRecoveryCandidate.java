package io.github.pitrecki.dokubridge.core.port.out.upload;

import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import java.util.Objects;
import java.util.Optional;

/** An empty checksum identifies a legacy reservation whose content cannot be verified safely. */
public record UploadRecoveryCandidate(DocumentVersion version, Optional<ContentSha256> expectedChecksum) {
    public UploadRecoveryCandidate {
        Objects.requireNonNull(version, "Version must not be null");
        Objects.requireNonNull(expectedChecksum, "Checksum availability must not be null");
    }
}
