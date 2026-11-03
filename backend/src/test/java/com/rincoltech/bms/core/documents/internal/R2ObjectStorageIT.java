package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Issue #39: the R2 adapter's {@code list} reads past the first page of 1000 objects and keeps to its prefix, and its
 * {@code deleteAll} removes a batch in one {@code DeleteObjects} request, against an S3-compatible store (LocalStack) in
 * a container. Keys and the bucket are invented.
 */
class R2ObjectStorageIT {

    private static final String BUCKET = "test-documents";

    private static GenericContainer<?> s3;
    private static R2ObjectStorage storage;

    @BeforeAll
    static void start() {
        s3 = new GenericContainer<>("localstack/localstack:4.0")
                .withExposedPorts(4566)
                .withEnv("SERVICES", "s3")
                .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566));
        s3.start();
        String endpoint = "http://" + s3.getHost() + ":" + s3.getMappedPort(4566);
        try (S3Client admin = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            admin.createBucket(b -> b.bucket(BUCKET));
        }
        storage =
                R2ObjectStorage.create(new StorageConfiguration.StorageProperties.R2(endpoint, "test", "test", BUCKET));
    }

    @AfterAll
    static void stop() {
        if (s3 != null) {
            s3.stop();
        }
    }

    @Test
    void listReadsEveryPageOfItsPrefixAndDeleteAllRemovesABatch() {
        String prefix = "tenants/00000000-0000-4000-8000-000000000039/upload/";
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 1050; i++) {
            String key = prefix + "2026/01/%04d.png".formatted(i);
            storage.put(key, new byte[] {1}, "image/png");
            keys.add(key);
        }
        storage.put(
                "tenants/00000000-0000-4000-8000-000000000040/upload/2026/01/other.png", new byte[] {1}, "image/png");

        List<ObjectStorage.Listed> listed;
        try (Stream<ObjectStorage.Listed> s = storage.list(prefix)) {
            listed = s.toList();
        }
        assertThat(listed).hasSize(1050);
        assertThat(listed).allMatch(l -> l.key().startsWith(prefix));
        assertThat(listed).allMatch(l -> l.lastModified() != null);

        assertThat(storage.deleteAll(keys.subList(0, 1000))).isEmpty();
        // A key already gone counts as removed.
        assertThat(storage.deleteAll(List.of(keys.getFirst()))).isEmpty();
        try (Stream<ObjectStorage.Listed> s = storage.list(prefix)) {
            assertThat(s.map(ObjectStorage.Listed::key).toList())
                    .containsExactlyInAnyOrderElementsOf(keys.subList(1000, 1050));
        }
    }
}
