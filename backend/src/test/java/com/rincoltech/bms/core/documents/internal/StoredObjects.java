package com.rincoltech.bms.core.documents.internal;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.context.ApplicationContext;

/**
 * For tests outside this module: the keys object storage holds under a prefix (the recording fake in the test profile), so a
 * test can show that a run wrote no file (issue #132, review of #869: a dry run leaves no file behind).
 */
public final class StoredObjects {

    private StoredObjects() {}

    /** Every key under the prefix, sorted. */
    public static List<String> keys(ApplicationContext context, String prefix) {
        try (Stream<ObjectStorage.Listed> listed =
                context.getBean(ObjectStorage.class).list(prefix)) {
            return listed.map(ObjectStorage.Listed::key).sorted().toList();
        }
    }
}
