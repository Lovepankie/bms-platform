package com.rincoltech.bms.core.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.rincoltech.bms.BmsApplication;
import com.rincoltech.bms.TestDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Issue #889: the rehearsal's API container ({@code deploy/restore-drill.sh --rehearse-import}, step 6) starts. A live
 * API's environment is made from the api service of {@code deploy/compose.pi-staging.yml} or {@code deploy/compose.yml}
 * with fabricated host values, passed through the script's own {@code rh_api_env} (read from the script, not copied), and
 * the application is started as {@code rehearse-import} starts it ({@link RehearseImportCommand#application}) with exactly
 * those lines as its whole environment, against the throwaway test PostgreSQL. Only the database address and the
 * password the script makes are swapped for the test database's. It must reach readiness, so a placeholder of the wrong
 * shape (the storage endpoint without a scheme of #889) fails here. Every key, address and value is fabricated.
 */
class RehearsalSettingsIT {

    static final Path SCRIPT = Path.of("..", "deploy", "restore-drill.sh");

    static final String REHEARSAL_DB = "bms-rehearsal-db-4242";

    static final String SCRIPT_APP_PASSWORD = "f00dfeed".repeat(4);

    /** Marks every live value the rehearsal must not pass on: secrets and outside addresses. */
    static final String LIVE_ONLY = "live-only";

    /** The values every host sets (compose's {@code :?}), fabricated. */
    static Map<String, String> required() {
        Map<String, String> host = new LinkedHashMap<>();
        host.put("BMS_APP_PASSWORD", LIVE_ONLY + "-app-password");
        host.put("BMS_TENANT_HOST_PATTERN", "{slug}-bms.example.test");
        host.put("BMS_PLATFORM_HOST", "bms.example.test");
        host.put("BMS_TOKEN_SIGNING_JWK", jwk());
        host.put("BMS_DATA_KEY", dataKey());
        host.put("R2_ENDPOINT", "https://" + LIVE_ONLY + ".r2.example.test");
        host.put("R2_DOCUMENTS_ACCESS_KEY_ID", LIVE_ONLY + "-access-key-id");
        host.put("R2_DOCUMENTS_SECRET_ACCESS_KEY", LIVE_ONLY + "-secret-access-key");
        host.put("R2_DOCUMENTS_BUCKET", "bms-documents-fabricated");
        host.put("BMS_IMPORT_KEY", dataKey());
        return host;
    }

    /** Every outside service on, as a host could set it: mail, chat, SMS, push, AI, Ask BMS and payments. */
    static Map<String, String> allOn(boolean production) {
        Map<String, String> host = required();
        host.put("BMS_SMTP_HOST", "smtp." + LIVE_ONLY + ".example.test");
        host.put("BMS_SMTP_USER", "fabricated-smtp-user");
        host.put("BMS_SMTP_PASSWORD", LIVE_ONLY + "-smtp-password");
        host.put("BMS_MAIL_FROM", "bms@example.test");
        host.put("BMS_RESEND_API_KEY", LIVE_ONLY + "-resend-key");
        host.put("BMS_RESEND_WEBHOOK_SECRET", "whsec_" + LIVE_ONLY);
        host.put("BMS_TELEGRAM_BOT_TOKEN", "123456:" + LIVE_ONLY + "-telegram-token");
        host.put("BMS_TELEGRAM_OPERATOR_CHAT_ID", "-1000000000001");
        host.put("BMS_SMS_PROVIDER", "africastalking");
        host.put("BMS_SMS_MODE", production ? "live" : "sink");
        host.put(
                "BMS_SMS_AT_BASE_URL",
                production ? "https://api.africastalking.com" : "https://api.sandbox.africastalking.com");
        host.put("BMS_SMS_AT_USERNAME", production ? "fabricated-account" : "sandbox");
        host.put("BMS_SMS_AT_API_KEY", LIVE_ONLY + "-sms-key");
        host.put("BMS_SMS_CALLBACK_TOKEN", LIVE_ONLY + "-sms-callback-token-0123456789");
        host.put("BMS_PUSH_VAPID_PUBLIC_KEY", LIVE_ONLY + "-vapid-public");
        host.put("BMS_PUSH_VAPID_PRIVATE_KEY", LIVE_ONLY + "-vapid-private");
        host.put("BMS_PUSH_SUBJECT", "mailto:ops@example.test");
        host.put("BMS_AI_PROVIDER", "openai_compatible");
        host.put("BMS_AI_BASE_URL", "https://ai." + LIVE_ONLY + ".example.test/v1");
        host.put("BMS_AI_API_KEY", LIVE_ONLY + "-ai-key");
        host.put("BMS_AI_MODEL", "fabricated-model");
        host.put("BMS_HELP_ASK_GEMINI_API_KEY", LIVE_ONLY + "-gemini-key");
        host.put("BMS_HELP_ASK_GROQ_API_KEY", LIVE_ONLY + "-groq-key");
        host.put("BMS_HELP_ASK_DEEPSEEK_API_KEY", LIVE_ONLY + "-deepseek-key");
        host.put(
                "BMS_PESAPAL_BASE_URL",
                production ? "https://pay.pesapal.com/v3" : "https://cybqa.pesapal.com/pesapalv3");
        host.put("BMS_PESAPAL_CALLBACK_BASE_URL", "https://pay-callback.example.test");
        host.put("BMS_PESAPAL_REFUSED_KEY_DIGESTS", "ab".repeat(32));
        host.put("BMS_PESAPAL_PLATFORM_CONSUMER_KEY", LIVE_ONLY + "-consumer-key");
        host.put("BMS_PESAPAL_PLATFORM_CONSUMER_SECRET", LIVE_ONLY + "-consumer-secret");
        host.put("BMS_PESAPAL_PLATFORM_IPN_ID", "0b5e7a2c-0000-4000-8000-00000000e001");
        host.put("BMS_PESAPAL_PLATFORM_IPN_TOKEN", LIVE_ONLY + "-ipn-token-0123456789abcdef");
        if (production) {
            host.put("BMS_ENVIRONMENT", "production");
        }
        return host;
    }

    static Stream<Arguments> hosts() {
        return Stream.of(
                Arguments.of("staging as at #889", "compose.pi-staging.yml", required()),
                Arguments.of("staging, every outside service on", "compose.pi-staging.yml", allOn(false)),
                Arguments.of("production, every outside service on", "compose.yml", allOn(true)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    void theRehearsalApiStartsWithTheLinesTheScriptWrites(String name, String compose, Map<String, String> host)
            throws Exception {
        Map<String, String> live = liveApiEnvironment(Path.of("..", "deploy", compose), host);
        List<String> lines = rehearsalLines(live);

        Map<String, String> settings = new LinkedHashMap<>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            assertThat(eq).as("a KEY=value line: %s", line).isPositive();
            assertThat(settings.put(line.substring(0, eq), line.substring(eq + 1)))
                    .as("one line for %s", line.substring(0, eq))
                    .isNull();
        }
        assertThat(lines).noneMatch(line -> line.contains(LIVE_ONLY));
        assertThat(settings)
                .containsEntry("BMS_DB_URL", "jdbc:postgresql://" + REHEARSAL_DB + ":5432/bms")
                .containsEntry("BMS_APP_USER", "bms_app")
                .containsEntry("BMS_APP_PASSWORD", SCRIPT_APP_PASSWORD)
                .containsEntry("BMS_SCHEDULER_ENABLED", "false")
                .containsEntry("BMS_IMPORT_KEY", "")
                .containsEntry("BMS_DATA_KEY", host.get("BMS_DATA_KEY"))
                .containsEntry("BMS_TOKEN_SIGNING_JWK", host.get("BMS_TOKEN_SIGNING_JWK"))
                .containsEntry("R2_ENDPOINT", "http://rehearsal-storage.invalid")
                .containsEntry("BMS_PUSH_VAPID_PUBLIC_KEY", "")
                .containsEntry("BMS_PUSH_VAPID_PRIVATE_KEY", "")
                // Set even when the live value is empty or absent: empty means the provider's real host (#890 review).
                .containsEntry("BMS_RESEND_API_URL", "https://rehearsal-mail.invalid")
                .containsEntry("BMS_AI_BASE_URL", "https://rehearsal-ai.invalid");
        if (host.containsKey("BMS_SMTP_HOST")) {
            assertThat(settings).containsEntry("BMS_SMTP_HOST", "rehearsal-mail.invalid");
        } else {
            // A service off on the live API stays off: an empty value is not turned into a placeholder.
            assertThat(settings).containsEntry("BMS_SMTP_PASSWORD", "").containsEntry("BMS_AI_API_KEY", "");
        }

        withStarted(settings, context -> {
            assertThat(context.getBean(ApplicationAvailability.class).getReadinessState())
                    .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
            assertThat(context.getEnvironment().getProperty("bms.storage.r2.endpoint"))
                    .isEqualTo("http://rehearsal-storage.invalid");
            assertThat(context.getEnvironment().getProperty("bms.notifications.mail.resend-url"))
                    .isEqualTo("https://rehearsal-mail.invalid");
            // Only the image's own configuration: the server profile, nothing from the test classpath.
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("server");
            assertThat(context.getEnvironment().getPropertySources().stream())
                    .map(source -> source.getName())
                    .noneMatch(source -> source.contains("application-test") || source.contains("test-classes"));
        });
    }

    /** The placeholders the script wrote before #889, one at a time, on the lines it writes now. */
    static Stream<Arguments> oldPlaceholders() {
        return Stream.of(
                Arguments.of(
                        Map.of("R2_ENDPOINT", "rehearsal-placeholder"),
                        "The URI scheme of endpointOverride must not be null"),
                Arguments.of(
                        Map.of(
                                "BMS_PUSH_VAPID_PUBLIC_KEY", "rehearsal-placeholder",
                                "BMS_PUSH_VAPID_PRIVATE_KEY", "rehearsal-placeholder"),
                        "the VAPID keys are not valid"),
                Arguments.of(
                        Map.of("BMS_PESAPAL_PLATFORM_IPN_TOKEN", "rehearsal-placeholder"),
                        "an IPN path token must be 24 to 128"));
    }

    /** This test fails for a placeholder of the wrong shape: each one of before #889 stops the start (review of #890). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("oldPlaceholders")
    void aPlaceholderOfTheWrongShapeStopsTheStart(Map<String, String> old, String refusal) throws Exception {
        Map<String, String> settings = settingsOf(
                rehearsalLines(liveApiEnvironment(Path.of("..", "deploy", "compose.pi-staging.yml"), allOn(false))));
        settings.putAll(old);
        assertThatThrownBy(() -> withStarted(settings, context -> {})).hasStackTraceContaining(refusal);
    }

    static Map<String, String> settingsOf(List<String> lines) {
        Map<String, String> settings = new LinkedHashMap<>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            settings.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return settings;
    }

    interface Check {
        void accept(ConfigurableApplicationContext context) throws Exception;
    }

    /**
     * Starts the application as {@code rehearse-import} does, with {@code settings} as its whole environment (no system
     * properties either), on the test database instead of the script's container.
     */
    static void withStarted(Map<String, String> settings, Check check) throws Exception {
        Map<String, Object> env = new LinkedHashMap<>(settings);
        env.put("BMS_DB_URL", TestDatabase.url());
        env.put("BMS_APP_PASSWORD", TestDatabase.APP_PASSWORD);
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                        new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
        SpringApplication app = RehearseImportCommand.application(BmsApplication.class);
        app.setEnvironment(environment);
        app.setRegisterShutdownHook(false);
        // The test JVM's logging is left as it is: the command's WARN level and the server profile's JSON lines would
        // otherwise stay for every later test in this JVM, and some of them read INFO lines. The start also writes the
        // logging system properties (the structured console format among them), so every system property is restored.
        // Restored key by key on the same Properties object, so nothing holding it sees a stale copy.
        Map<Object, Object> before = new HashMap<>(System.getProperties());
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
        try (ConfigurableApplicationContext context = app.run()) {
            check.accept(context);
        } finally {
            Properties now = System.getProperties();
            now.keySet().removeIf(key -> !before.containsKey(key));
            now.putAll(before);
        }
    }

    private static final Pattern ENTRY = Pattern.compile("^ {6}([A-Z][A-Z0-9_]*): ?(.*)$");

    private static final Pattern VARIABLE = Pattern.compile("^\\$\\{([A-Z][A-Z0-9_]*):([?-])(.*)}$");

    /**
     * The api service's environment as Docker Compose would give it from {@code host} (a host's .env): {@code :?} must be
     * set, {@code :-} falls back when unset or empty; plus a few lines the image itself sets.
     */
    static Map<String, String> liveApiEnvironment(Path compose, Map<String, String> host) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        boolean inApi = false;
        boolean inEnvironment = false;
        String folded = null;
        for (String line : Files.readAllLines(compose, StandardCharsets.UTF_8)) {
            if (folded != null && line.startsWith("        ")) {
                // A folded value (>-): its lines joined by spaces.
                env.merge(folded, line.trim(), (a, b) -> a.isEmpty() ? b : a + " " + b);
                continue;
            }
            folded = null;
            if (line.matches("^ {2}[a-z][a-z0-9-]*:\\s*$")) {
                inApi = line.trim().equals("api:");
                inEnvironment = false;
                continue;
            }
            if (inApi && line.equals("    environment:")) {
                inEnvironment = true;
                continue;
            }
            if (!inEnvironment || line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            if (line.length() - line.stripLeading().length() < 6) {
                // The next key of the api service: the environment ends here.
                inEnvironment = false;
                continue;
            }
            // Any other line inside the environment stops the test, so no key below it is dropped unread (review of
            // #890): the list form, an anchor or a merge key needs this reader taught first.
            Matcher entry = ENTRY.matcher(line);
            assertThat(entry.matches())
                    .as("%s: a line of the api environment this test cannot read: %s", compose, line)
                    .isTrue();
            String raw = entry.group(2).trim();
            if (raw.equals(">-")) {
                folded = entry.group(1);
                env.put(folded, "");
                continue;
            }
            if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw = raw.substring(1, raw.length() - 1);
            }
            Matcher variable = VARIABLE.matcher(raw);
            String value = raw;
            if (variable.matches()) {
                String set = host.getOrDefault(variable.group(1), "");
                if (variable.group(2).equals("?")) {
                    assertThat(set)
                            .as("%s needs %s on the host", compose, variable.group(1))
                            .isNotEmpty();
                    value = set;
                } else {
                    value = set.isEmpty() ? variable.group(3) : set;
                }
            } else {
                assertThat(raw)
                        .as("%s: a value this test cannot resolve: %s", compose, line)
                        .doesNotContain("$");
            }
            env.put(entry.group(1), value);
        }
        assertThat(env).as("the api service's environment in %s", compose).containsKeys("BMS_DB_URL", "R2_ENDPOINT");
        // docker inspect also lists the image's own ENV; a key compose sets wins, as Docker gives it.
        imageEnvironment().forEach(env::putIfAbsent);
        env.putIfAbsent("PATH", "/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.putIfAbsent("JAVA_HOME", "/opt/java/openjdk");
        env.putIfAbsent("LANG", "en_US.UTF-8");
        return env;
    }

    private static final Pattern ENV_BLOCK = Pattern.compile("(?m)^ENV ((?:.*\\\\\\n)*.*)$");

    private static final Pattern ENV_PAIR = Pattern.compile("([A-Z][A-Z0-9_]*)=(\"[^\"]*\"|\\S*)");

    /**
     * The ENV lines of the runtime stage of {@code backend/Dockerfile}, its build arguments fabricated (review of #890). A
     * new ENV fails here until this test is taught it, so an image setting cannot change the start unseen.
     * JUSTIFICATION-A3: the review asked for the image's own ENV in the live environment; this reads it from the Dockerfile.
     */
    static Map<String, String> imageEnvironment() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"), StandardCharsets.UTF_8);
        String runtime = dockerfile.substring(dockerfile.lastIndexOf("\nFROM "));
        Map<String, String> env = new LinkedHashMap<>();
        Matcher block = ENV_BLOCK.matcher(runtime);
        while (block.find()) {
            Matcher pair = ENV_PAIR.matcher(block.group(1));
            while (pair.find()) {
                String value = pair.group(2);
                if (value.startsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                env.put(
                        pair.group(1),
                        value.replace("${GIT_SHA}", "0".repeat(40)).replace("${GIT_TIME}", "20261109000000"));
            }
        }
        assertThat(env.keySet())
                .as("the ENV of backend/Dockerfile's runtime stage: give a new one its value here")
                .containsExactly("BMS_GIT_SHA", "BMS_GIT_TIME", "SPRING_PROFILES_ACTIVE", "JAVA_TOOL_OPTIONS");
        assertThat(env.values()).noneMatch(value -> value.contains("$"));
        return env;
    }

    /** {@code docker inspect ... | rh_api_env "$app_pw"}, with the function and RH_JAVA_OPTS read from the script itself. */
    static List<String> rehearsalLines(Map<String, String> live) throws Exception {
        Path in = Files.createTempFile("live-api", ".env");
        try {
            StringBuilder inspect = new StringBuilder();
            live.forEach((k, v) -> inspect.append(k).append('=').append(v).append('\n'));
            Files.writeString(in, inspect, StandardCharsets.UTF_8);
            ProcessBuilder bash = new ProcessBuilder(
                    "bash",
                    "-c",
                    "set -uo pipefail; source <(sed -n -e '/^RH_JAVA_OPTS=/p' -e '/^rh_api_env() {/,/^}/p' \"$1\")"
                            + " && rh_api_env \"$2\"",
                    "rh_api_env",
                    SCRIPT.toString(),
                    SCRIPT_APP_PASSWORD);
            bash.environment().put("RH_DB", REHEARSAL_DB);
            bash.environment().put("RH_TOKEN", "f00dfeed".repeat(6));
            bash.redirectInput(in.toFile());
            bash.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = bash.start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as("rh_api_env's exit status").isZero();
            List<String> lines = new ArrayList<>(out.lines().toList());
            assertThat(lines).as("rh_api_env wrote the settings").anyMatch(l -> l.startsWith("JAVA_TOOL_OPTIONS=-Xmx"));
            return lines;
        } finally {
            Files.deleteIfExists(in);
        }
    }

    static String jwk() {
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyID("k-fabricated")
                    .generate()
                    .toJSONString();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static String dataKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
