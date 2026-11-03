package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.core.documents.QuarantineStorage;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** {@link QuarantineStorage} over the object storage port, under the bound business's quarantine prefix. */
@Component
class QuarantineStore implements QuarantineStorage {

    /** The content type of every quarantined object: encrypted bytes, never served. */
    static final String CONTENT_TYPE = "application/octet-stream";

    private final ObjectStorage storage;

    QuarantineStore(ObjectStorage storage) {
        this.storage = storage;
    }

    /** The bound business's quarantine prefix. */
    static String prefix() {
        return "tenants/" + TenantContext.require() + "/quarantine/imports/";
    }

    @Override
    public String put(UUID runId, UUID batchId, Path encryptedFile) {
        String key = prefix() + runId + "/" + batchId + ".bin";
        storage.putFile(key, encryptedFile, CONTENT_TYPE);
        return key;
    }

    @Override
    public Optional<InputStream> open(String key) {
        return storage.open(own(key));
    }

    @Override
    public void delete(String key) {
        storage.delete(own(key));
    }

    @Override
    public Stream<Held> list() {
        return storage.list(prefix()).map(o -> new Held(o.key(), o.lastModified()));
    }

    @Override
    public List<String> deleteAll(List<String> keys) {
        keys.forEach(QuarantineStore::own);
        return storage.deleteAll(keys);
    }

    private static String own(String key) {
        if (key == null || !key.startsWith(prefix()) || key.contains("..")) {
            throw new IllegalArgumentException("Not a quarantined object of the bound business.");
        }
        return key;
    }
}
