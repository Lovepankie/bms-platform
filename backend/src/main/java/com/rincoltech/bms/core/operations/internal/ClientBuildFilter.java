package com.rincoltech.bms.core.operations.internal;

import com.rincoltech.bms.kernel.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The app's build both ways (#576, ADR-088). Every response carries {@code Bms-Build}, the sha {@code /version} reports, so an app
 * on an older build knows to fetch the new one. When {@code BMS_MIN_CLIENT_BUILD} is set, an API request from an app built before
 * it ({@code Bms-Client-Build: sha-abc1234; built=YYYYMMDDHHMMSS}) is answered 426 {@code app_too_old} before anything else runs,
 * so an old screen cannot send what the new API no longer understands. A request without the build time (curl, a callback, an app
 * from before #576) is never refused. A minimum later than the API's own build ({@code BMS_GIT_TIME}) is ignored with a warning:
 * after a rollback to an older release with the setting left in place, refusing every app the web container now serves would stop
 * every phone.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class ClientBuildFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientBuildFilter.class);

    static final String BUILD = "Bms-Build";
    static final String CLIENT_BUILD = "Bms-Client-Build";
    private static final Pattern BUILT = Pattern.compile("(?:^|;)\\s*built=(\\d{14})\\s*(?:;|$)");
    private static final Pattern TIME = Pattern.compile("\\d{14}");

    private final String build;
    private final String minBuilt;
    private final ObjectMapper mapper;

    ClientBuildFilter(
            VersionController version,
            @Value("${bms.min-client-build:}") String minClientBuild,
            @Value("${bms.git-time:}") String ownBuilt,
            ObjectMapper mapper) {
        this.build = version.gitSha();
        this.mapper = mapper;
        String min = minClientBuild.strip();
        if (!min.isEmpty() && !TIME.matcher(min).matches()) {
            throw new IllegalStateException(
                    "BMS_MIN_CLIENT_BUILD is the commit time of the oldest app build the API serves, UTC YYYYMMDDHHMMSS"
                            + " (docs/runbooks/deploy.md)");
        }
        String own = ownBuilt.strip();
        if (!min.isEmpty() && TIME.matcher(own).matches() && min.compareTo(own) > 0) {
            log.warn(
                    "BMS_MIN_CLIENT_BUILD {} is later than this release ({}), as after a rollback: every app is served."
                            + " Clear it in .env (docs/runbooks/rollback.md).",
                    min,
                    own);
            min = "";
        } else if (!min.isEmpty() && !TIME.matcher(own).matches()) {
            log.warn(
                    "BMS_MIN_CLIENT_BUILD {} is enforced, but this image does not know its own build time (BMS_GIT_TIME),"
                            + " so a rollback with it set would refuse every app. Build images with GIT_TIME.",
                    min);
        }
        this.minBuilt = min;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(BUILD, build);
        if (tooOld(request)) {
            Problems.write(
                    response,
                    mapper,
                    Problems.of(
                            HttpStatus.UPGRADE_REQUIRED,
                            "app_too_old",
                            "App too old",
                            "A new version of the app is ready. Load the page again to get it."));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean tooOld(HttpServletRequest request) {
        if (minBuilt.isEmpty() || !request.getRequestURI().startsWith("/api/")) return false;
        String client = request.getHeader(CLIENT_BUILD);
        if (client == null) return false;
        Matcher built = BUILT.matcher(client);
        return built.find() && built.group(1).compareTo(minBuilt) < 0;
    }
}
