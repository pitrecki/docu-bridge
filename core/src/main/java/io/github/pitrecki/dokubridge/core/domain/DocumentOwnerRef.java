package io.github.pitrecki.dokubridge.core.domain;

public record DocumentOwnerRef(String type, String id) {
    public DocumentOwnerRef {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Owner type must not be blank");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Owner id must not be blank");
        }
    }
}
