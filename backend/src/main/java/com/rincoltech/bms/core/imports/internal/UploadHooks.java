package com.rincoltech.bms.core.imports.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * The two points of the upload a test needs to reach (review of #790): the free disk the upload sees, and the moment
 * between fetching a batch's file and reading it, where a concurrent cancel or move can land. In the application they
 * read the file store and do nothing; a test replaces this bean.
 */
@Component
class UploadHooks {

    /** Usable bytes on the file store of the work directory. */
    long freeDisk(Path workDir) throws IOException {
        return Files.getFileStore(workDir).getUsableSpace();
    }

    /** Runs after a batch's file is fetched and before it is read. */
    void afterFetch() {}
}
