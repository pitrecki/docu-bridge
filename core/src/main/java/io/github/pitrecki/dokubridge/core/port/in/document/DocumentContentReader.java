package io.github.pitrecki.dokubridge.core.port.in.document;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a value from borrowed document content. The stream is valid only during
 * this call; the library closes it afterward, including when reading fails.
 * Return the parsed value rather than retaining or returning the stream.
 *
 * @param <T> the value produced by reading the content
 */
@FunctionalInterface
public interface DocumentContentReader<T> {
    T read(InputStream content) throws IOException;
}
