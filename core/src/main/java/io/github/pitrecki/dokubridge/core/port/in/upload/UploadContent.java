package io.github.pitrecki.dokubridge.core.port.in.upload;

import io.github.pitrecki.dokubridge.core.domain.content.ContentSha256;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Keeps owned byte snapshots separate from caller-owned streams. */
final class UploadContent {
    private final byte[] bytes;
    private final InputStream source;
    private final ContentSha256 checksum;

    private UploadContent(byte[] bytes, InputStream source, ContentSha256 checksum) {
        this.bytes = bytes;
        this.source = source;
        this.checksum = checksum;
    }

    static UploadContent bytes(byte[] content) {
        byte[] snapshot = Objects.requireNonNull(content, "Content must not be null").clone();
        return new UploadContent(snapshot, null, ContentSha256.of(snapshot));
    }

    static UploadContent stream(InputStream content, ContentSha256 checksum) {
        return new UploadContent(null, Objects.requireNonNull(content, "Content must not be null"),
                Objects.requireNonNull(checksum, "Checksum must not be null"));
    }

    ContentSha256 checksum() {
        return checksum;
    }

    Input open() {
        return bytes == null ? new Input(source, false) : new Input(new ByteArrayInputStream(bytes), true);
    }

    record Input(InputStream stream, boolean owned) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (owned) stream.close();
        }
    }
}
