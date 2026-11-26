package com.studysphere.resource.storage;

import java.io.InputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

final class S3ResourceStorage implements ResourceStorage {
    private final S3Client client;
    private final String bucket;

    S3ResourceStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, InputStream source, long size, String mediaType) {
        try {
            client.putObject(request -> request.bucket(bucket).key(objectKey(key)).contentType(mediaType),
                    RequestBody.fromInputStream(source, size));
        } catch (SdkException e) {
            throw new StorageException("Unable to store resource.", e);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return client.getObject(request -> request.bucket(bucket).key(objectKey(key)));
        } catch (SdkException e) {
            throw new StorageException("Unable to open resource.", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(request -> request.bucket(bucket).key(objectKey(key)));
        } catch (SdkException e) {
            throw new StorageException("Unable to remove resource.", e);
        }
    }

    private static String objectKey(String key) {
        return "resources/" + ResourceStorage.validatedKey(key);
    }
}
