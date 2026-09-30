package com.rincoltech.bms.core.documents.internal;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Cloudflare R2 through the S3 API (chapter 12 section 12.5). R2 takes region {@code auto}; the
 * bucket is private and its token is scoped to it. Covered by the manually triggered contract test
 * of chapter 12 section 12.8, not by the pull request suite.
 */
class R2ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    private R2ObjectStorage(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    static R2ObjectStorage create(StorageConfiguration.StorageProperties.R2 r2) {
        if (r2 == null
                || blank(r2.endpoint())
                || blank(r2.accessKeyId())
                || blank(r2.secretAccessKey())
                || blank(r2.bucket())) {
            throw new IllegalStateException(
                    "OBJECT_STORAGE=r2 needs R2_ENDPOINT, R2_DOCUMENTS_ACCESS_KEY_ID, R2_DOCUMENTS_SECRET_ACCESS_KEY and R2_DOCUMENTS_BUCKET");
        }
        var credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(r2.accessKeyId(), r2.secretAccessKey()));
        S3Configuration pathStyle =
                S3Configuration.builder().pathStyleAccessEnabled(true).build();
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(r2.endpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .build();
        S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(URI.create(r2.endpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        return new R2ObjectStorage(s3, presigner, r2.bucket());
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
    }

    @Override
    public String signedGetUrl(String key, Duration ttl, String downloadName, String contentType) {
        return presigner
                .presignGetObject(p -> p.signatureDuration(ttl)
                        .getObjectRequest(g -> g.bucket(bucket)
                                .key(key)
                                .responseContentDisposition(StorageConfiguration.attachment(downloadName))
                                .responseContentType(contentType)
                                .responseCacheControl(StorageConfiguration.NO_STORE)))
                .url()
                .toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
