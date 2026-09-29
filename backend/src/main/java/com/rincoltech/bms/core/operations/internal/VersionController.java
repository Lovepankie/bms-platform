package com.rincoltech.bms.core.operations.internal;

import com.rincoltech.bms.kernel.PublicEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /version} (chapter 7 section 7.11.1): what code is running. */
@RestController
class VersionController {

    private final String version;
    private final String gitSha;

    VersionController(
            ObjectProvider<BuildProperties> build,
            ObjectProvider<GitProperties> git,
            @Value("${bms.git-sha:}") String imageGitSha) {
        BuildProperties b = build.getIfAvailable();
        GitProperties g = git.getIfAvailable();
        this.version = b == null ? "unknown" : b.getVersion();
        // The image carries BMS_GIT_SHA (set at build time); local builds carry git.properties.
        this.gitSha = !imageGitSha.isBlank() ? imageGitSha : g != null ? g.getCommitId() : "unknown";
    }

    @GetMapping(path = "/version", produces = "application/json")
    @PublicEndpoint
    @Operation(summary = "Running version and git SHA", tags = "operations")
    Map<String, String> version() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("git_sha", gitSha);
        return body;
    }
}
