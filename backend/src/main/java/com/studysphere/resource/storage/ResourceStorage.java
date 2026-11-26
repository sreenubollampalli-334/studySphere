package com.studysphere.resource.storage;

import java.io.InputStream;
import java.util.UUID;

public interface ResourceStorage {
    // The caller owns the source stream. The recipient of open owns its returned stream.
    void put(String key, InputStream source, long size, String mediaType);

    InputStream open(String key);

    void delete(String key);

    static String validatedKey(String key) {
        if (key == null || key.length() != 36) {
            throw new IllegalArgumentException("Invalid storage key.");
        }
        if (!UUID.fromString(key).toString().equals(key)) {
            throw new IllegalArgumentException("Invalid storage key.");
        }
        return key;
    }
}
