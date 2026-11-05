package io.github.pitrecki.dokubridge.adapter.filesystem;

import io.github.pitrecki.dokubridge.core.domain.exception.DocumentContentAlreadyExistsException;
import io.github.pitrecki.dokubridge.core.domain.exception.DocumentContentNotFoundException;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class FilesystemDocumentContentStoreTest {
    @TempDir
    Path directory;

    @Test
    void roundTripsBinaryContentWithoutClosingTheSource() throws IOException {
        var store = new FilesystemDocumentContentStore(directory);
        var versionId = UUID.randomUUID();
        byte[] content = {0, 1, -1, 13, 10, 127};
        var source = new ByteArrayInputStream(content) {
            @Override
            public void close() {
                fail("Store must not close the caller's source");
            }
        };

        store.store(versionId, source);

        try (var input = store.open(versionId)) {
            assertThat(input.readAllBytes()).containsExactly(content);
        }
        assertThat(directory).isDirectoryContaining(path -> path.getFileName().toString().equals(versionId.toString()));
        assertNoStagingFiles();
    }

    @Test
    void supportsEmptyContent() throws IOException {
        var store = new FilesystemDocumentContentStore(directory);
        var id = UUID.randomUUID();
        store.store(id, new ByteArrayInputStream(new byte[0]));
        try (var input = store.open(id)) {
            assertThat(input.read()).isEqualTo(-1);
        }
    }

    @Test
    void createsMissingStorageDirectoryAndCanReopenContentInANewInstance() throws IOException {
        Path root = directory.resolve("nested/content");
        var id = UUID.randomUUID();
        new FilesystemDocumentContentStore(root).store(id, source("persisted"));
        try (var input = new FilesystemDocumentContentStore(root).open(id)) {
            assertThat(new String(input.readAllBytes(), UTF_8)).isEqualTo("persisted");
        }
    }

    @Test
    void rejectsDuplicateWithoutChangingExistingContent() throws IOException {
        var store = new FilesystemDocumentContentStore(directory);
        var id = UUID.randomUUID();
        store.store(id, source("original"));
        assertThatThrownBy(() -> store.store(id, source("replacement")))
                .isInstanceOf(DocumentContentAlreadyExistsException.class)
                .hasCauseInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        try (var input = store.open(id)) {
            assertThat(new String(input.readAllBytes(), UTF_8)).isEqualTo("original");
        }
        assertNoStagingFiles();
    }

    @Test
    void reportsMissingContent() throws IOException {
        var store = new FilesystemDocumentContentStore(directory);
        assertThatThrownBy(() -> store.open(UUID.randomUUID()))
                .isInstanceOf(DocumentContentNotFoundException.class)
                .hasCauseInstanceOf(java.nio.file.NoSuchFileException.class);
    }

    @Test
    void failedSourceLeavesNoPublishedOrStagingContentAndAllowsAnotherAttempt() throws IOException {
        var store = new FilesystemDocumentContentStore(directory);
        var id = UUID.randomUUID();
        var failure = new IOException("Source interrupted");
        var source = new InputStream() {
            private boolean first = true;

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (first) {
                    first = false;
                    target[offset] = 42;
                    return 1;
                }
                throw failure;
            }

            @Override
            public int read() throws IOException {
                throw failure;
            }
        };
        assertThatThrownBy(() -> store.store(id, source)).isSameAs(failure);
        assertThatThrownBy(() -> store.open(id)).isInstanceOf(DocumentContentNotFoundException.class);
        assertThat(directory).isEmptyDirectory();
        store.store(id, source("retry"));
        try (var input = store.open(id)) {
            assertThat(new String(input.readAllBytes(), UTF_8)).isEqualTo("retry");
        }
    }

    @Test
    void doesNotPublishPartialContentDuringUpload() throws Exception {
        var store = new FilesystemDocumentContentStore(directory);
        var id = UUID.randomUUID();
        var reading = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var source = new FilterInputStream(source("complete")) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                reading.countDown();
                await(proceed);
                return super.read(bytes, offset, length);
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var upload = executor.submit(() -> {
                store.store(id, source);
                return null;
            });
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> store.open(id)).isInstanceOf(DocumentContentNotFoundException.class);
            } finally {
                proceed.countDown();
            }
            upload.get(5, TimeUnit.SECONDS);
        }
        try (var input = store.open(id)) {
            assertThat(new String(input.readAllBytes(), UTF_8)).isEqualTo("complete");
        }
    }

    @Test
    void concurrentWritersHaveExactlyOneWinnerEvenAcrossStoreInstances() throws Exception {
        var first = new FilesystemDocumentContentStore(directory);
        var second = new FilesystemDocumentContentStore(directory);
        var id = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var uploads = List.of(
                    executor.submit(upload(first, id, "first", ready, start)),
                    executor.submit(upload(second, id, "second", ready, start)));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            var results = List.of(uploads.get(0).get(5, TimeUnit.SECONDS), uploads.get(1).get(5, TimeUnit.SECONDS));
            assertThat(results).filteredOn(result -> !result.equals("duplicate")).hasSize(1);
            String winner = results.stream().filter(result -> !result.equals("duplicate")).findFirst().orElseThrow();
            try (var input = first.open(id)) {
                assertThat(new String(input.readAllBytes(), UTF_8)).isEqualTo(winner);
            }
        }
        assertNoStagingFiles();
    }

    @Test
    void refusesToReadOrOverwriteASymbolicLink() throws IOException {
        Path root = Files.createDirectory(directory.resolve("content"));
        Path outside = Files.writeString(directory.resolve("private.txt"), "private");
        var id = UUID.randomUUID();
        Files.createSymbolicLink(root.resolve(id.toString()), outside);
        var store = new FilesystemDocumentContentStore(root);

        assertThatThrownBy(() -> store.open(id)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> store.store(id, source("replacement")))
                .isInstanceOf(DocumentContentAlreadyExistsException.class);
        assertThat(Files.readString(outside)).isEqualTo("private");
    }

    @Test
    void rejectsASymbolicLinkAsStorageRoot() throws IOException {
        var link = directory.resolve("link");
        Files.createSymbolicLink(link, Files.createDirectory(directory.resolve("real")));
        assertThatThrownBy(() -> new FilesystemDocumentContentStore(link)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsDirectoriesAsContent() throws IOException {
        var store = new FilesystemDocumentContentStore(directory);
        var id = UUID.randomUUID();
        Files.createDirectory(directory.resolve(id.toString()));
        assertThatThrownBy(() -> store.open(id)).isInstanceOf(IOException.class);
    }

    @Test
    void validatesArgumentsBeforeCreatingFiles() throws IOException {
        var store = new FilesystemDocumentContentStore(directory);
        assertThatNullPointerException().isThrownBy(() -> store.store(null, source("bytes")));
        assertThatNullPointerException().isThrownBy(() -> store.store(UUID.randomUUID(), null));
        assertThatNullPointerException().isThrownBy(() -> store.open(null));
        assertThatNullPointerException().isThrownBy(() -> new FilesystemDocumentContentStore(null));
        assertThat(directory).isEmptyDirectory();
    }

    private Callable<String> upload(FilesystemDocumentContentStore store, UUID id, String content,
                                    CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            await(start);
            try {
                store.store(id, source(content));
                return content;
            } catch (DocumentContentAlreadyExistsException duplicate) {
                return "duplicate";
            }
        };
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for test upload");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload interrupted", interrupted);
        }
    }

    private static ByteArrayInputStream source(String text) {
        return new ByteArrayInputStream(text.getBytes(UTF_8));
    }

    private void assertNoStagingFiles() throws IOException {
        try (var paths = Files.list(directory)) {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".upload-"));
        }
    }
}
