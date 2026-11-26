package com.studysphere.resource.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class FileResourceStorage implements ResourceStorage {
    private final Path root;

    FileResourceStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, InputStream source, long size, String mediaType) {
        Path target = path(key);
        try {
            Files.createDirectories(target.getParent());
            Path staging = Files.createTempFile(target.getParent(), "upload-", ".part");
            try {
                long count = Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING);
                if (size != count) {
                    throw new IOException("Object size differs from declared size.");
                }
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(staging);
            }
        } catch (IOException e) {
            throw new StorageException("Unable to store resource.", e);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return Files.newInputStream(path(key), LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new StorageException("Unable to open resource.", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException e) {
            throw new StorageException("Unable to remove resource.", e);
        }
    }

    private Path path(String key) {
        return root.resolve(ResourceStorage.validatedKey(key));
    }
}
