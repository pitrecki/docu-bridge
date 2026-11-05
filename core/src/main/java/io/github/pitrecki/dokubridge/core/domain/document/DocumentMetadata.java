package io.github.pitrecki.dokubridge.core.domain.document;

import java.util.Map;
import java.util.Objects;

public record DocumentMetadata(Map<String, Object> values) {
    public DocumentMetadata {
        values = JsonMetadata.snapshot(Objects.requireNonNull(values, "Metadata must not be null"));
    }
}
