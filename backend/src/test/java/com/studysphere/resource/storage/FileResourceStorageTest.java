package com.studysphere.resource.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileResourceStorageTest {
    @TempDir
    Path directory;

    @Test
    void roundTripsBytesAndDeletesIdempotently() throws IOException {
        ResourceStorage store = new FileResourceStorage(directory);
        String key = UUID.randomUUID().toString();
        byte[] contents = {1, 2, 3};

        store.put(key, new ByteArrayInputStream(contents), contents.length, "text/plain");
        try (var object = store.open(key)) {
            assertThat(object.readAllBytes()).isEqualTo(contents);
        }
        store.delete(key);
        store.delete(key);
        assertThatThrownBy(() -> store.open(key)).isInstanceOf(StorageException.class);
    }

    @Test
    void failedUploadRemovesPartialBytes() throws IOException {
        ResourceStorage store = new FileResourceStorage(directory);
        String key = UUID.randomUUID().toString();
        InputStream broken = new InputStream() {
            private int reads;

            @Override
            public int read() throws IOException {
                if (reads++ == 0) {
                    return 1;
                }
                throw new IOException("Broken source");
            }
        };

        assertThatThrownBy(() -> store.put(key, broken, 2, "text/plain"))
                .isInstanceOf(StorageException.class);
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void rejectsTraversalAndIncorrectSizes() throws IOException {
        ResourceStorage store = new FileResourceStorage(directory);
        assertThatThrownBy(() -> store.open("../outside")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put(UUID.randomUUID().toString(), new ByteArrayInputStream(new byte[1]),
                2, "text/plain")).isInstanceOf(StorageException.class);
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }
}
