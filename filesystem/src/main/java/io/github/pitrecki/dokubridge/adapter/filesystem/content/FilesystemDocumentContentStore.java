package io.github.pitrecki.dokubridge.adapter.filesystem.content;

import io.github.pitrecki.dokubridge.core.domain.content.exception.DocumentContentAlreadyExistsException;
import io.github.pitrecki.dokubridge.core.domain.content.exception.DocumentContentNotFoundException;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Requires a private, application-controlled root on a filesystem supporting hard links.
 * Other processes must not replace the root or modify its entries.
 */
public final class FilesystemDocumentContentStore implements DocumentContentStore {
    private final Path root;

    public FilesystemDocumentContentStore(Path root) throws IOException {
        Objects.requireNonNull(root, "Content root must not be null");
        Files.createDirectories(root);
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) {
            throw new IOException("Content root must be a directory, not a symbolic link");
        }
        this.root = root.toRealPath();
    }

    @Override
    public void store(UUID versionId, InputStream source) throws IOException {
        Path target = contentPath(versionId);
        Objects.requireNonNull(source, "Content source must not be null");
        Path staging = Files.createTempFile(root, ".upload-", ".tmp");
        try {
            try (var output = Files.newOutputStream(staging, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS)) {
                source.transferTo(output);
            }
            try {
                // Unlike ATOMIC_MOVE, a hard link cannot replace an existing target.
                Files.createLink(target, staging);
            } catch (FileAlreadyExistsException duplicate) {
                throw new DocumentContentAlreadyExistsException(versionId, duplicate);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("Content filesystem must support hard links", unsupported);
            }
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        Files.delete(staging);
    }

    @Override
    public InputStream open(UUID versionId) throws IOException {
        Path target = contentPath(versionId);
        try {
            var attributes = Files.readAttributes(target, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("Content must be a regular file for version " + versionId);
            }
            return Files.newInputStream(target, NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            throw new DocumentContentNotFoundException(versionId, missing);
        }
    }

    private Path contentPath(UUID versionId) {
        Objects.requireNonNull(versionId, "Version id must not be null");
        return root.resolve(versionId.toString());
    }
}
