package com.rincoltech.bms.core.documents.internal;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
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
    public void putFile(String key, java.nio.file.Path file, String contentType) {
        // Streamed from the file with a known length: the object is never held in memory (the checksum calculation is
        // only when required, as for put).
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromFile(file));
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(
                    s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<java.io.InputStream> open(String key) {
        try {
            return Optional.of(s3.getObject(b -> b.bucket(bucket).key(key)));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }

    /** A HEAD request: the object's size and type, never its bytes. */
    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public java.util.stream.Stream<Listed> list(String prefix) {
        // The paginator fetches the next page of 1000 only when the stream reaches it.
        return s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents().stream()
                .map(o -> new Listed(o.key(), o.lastModified()));
    }

    // JUSTIFICATION-A3: the #39 review asks for batched DeleteObjects; this is the port method's R2 adapter.
    @Override
    public java.util.List<String> deleteAll(java.util.List<String> keys) {
        if (keys.isEmpty()) {
            return java.util.List.of();
        }
        if (keys.size() > 1000) {
            throw new IllegalArgumentException("DeleteObjects takes at most 1000 keys.");
        }
        var ids = keys.stream()
                .map(k -> software.amazon.awssdk.services.s3.model.ObjectIdentifier.builder()
                        .key(k)
                        .build())
                .toList();
        // Quiet mode: the answer lists only the keys that failed.
        var reply = s3.deleteObjects(
                b -> b.bucket(bucket).delete(d -> d.objects(ids).quiet(true)));
        return reply.errors().stream()
                .map(software.amazon.awssdk.services.s3.model.S3Error::key)
                .toList();
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
