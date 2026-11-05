package io.github.pitrecki.dokubridge.core.port.out.upload;

import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.version.DocumentVersionState;
import java.util.Objects;

public record UploadReservation(DocumentVersion version, boolean acquired) {
    public UploadReservation {
        Objects.requireNonNull(version, "Version must not be null");
        if (acquired && version.state() != DocumentVersionState.PENDING) {
            throw new IllegalArgumentException("A new upload must be pending");
        }
    }
}
