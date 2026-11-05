package io.github.pitrecki.dokubridge.core.port.out;

import io.github.pitrecki.dokubridge.core.domain.DocumentVersion;
import io.github.pitrecki.dokubridge.core.domain.DocumentVersionState;
import java.util.Objects;

public record UploadReservation(DocumentVersion version, boolean acquired) {
    public UploadReservation {
        Objects.requireNonNull(version, "Version must not be null");
        if (acquired && version.state() != DocumentVersionState.PENDING) {
            throw new IllegalArgumentException("A new upload must be pending");
        }
    }
}
