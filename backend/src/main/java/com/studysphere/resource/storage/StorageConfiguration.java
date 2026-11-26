package com.studysphere.resource.storage;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
class StorageConfiguration {
    @Bean
    @ConditionalOnProperty(name = "studysphere.storage.provider", havingValue = "filesystem", matchIfMissing = true)
    ResourceStorage filesystemStorage(@Value("${studysphere.storage.directory:./var/resources}") String directory) {
        return new FileResourceStorage(Path.of(directory));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "studysphere.storage.provider", havingValue = "s3")
    S3Client resourceS3Client(
            @Value("${studysphere.storage.region:us-east-1}") String region,
            @Value("${studysphere.storage.endpoint:}") String endpoint,
            @Value("${studysphere.storage.path-style:false}") boolean pathStyle) {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .forcePathStyle(pathStyle)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(config -> config
                        .apiCallAttemptTimeout(Duration.ofSeconds(30))
                        .apiCallTimeout(Duration.ofSeconds(45)));
        if (!endpoint.isBlank()) {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("S3 endpoint must be an HTTPS service URL.");
            }
            builder.endpointOverride(uri);
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "studysphere.storage.provider", havingValue = "s3")
    ResourceStorage s3Storage(S3Client resourceS3Client, @Value("${studysphere.storage.bucket:}") String bucket) {
        if (bucket.isBlank()) {
            throw new IllegalArgumentException("Configure the private S3 bucket for resource storage.");
        }
        return new S3ResourceStorage(resourceS3Client, bucket);
    }
}
