package com.rincoltech.bms.core.help.internal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmClientFactory;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Claude is never used by Ask BMS (the owner's rule on #20, ADR-096 decision 4). Fails if an Anthropic endpoint, SDK or
 * model id appears in anything Ask BMS reaches, if Ask BMS depends on anything of {@code core.ai} but its neutral port, if
 * a configuration naming Anthropic or Claude could start, or if the provider allow-list could be changed by a property or
 * an environment value.
 * The adapter factory here builds no client: nothing in this test can reach a network.
 */
class NeverClaudeTest {

    /** An Anthropic endpoint, SDK package or model id. */
    static final Pattern ANTHROPIC = Pattern.compile(
            "api\\.anthropic\\.com|anthropic\\.com|com\\.anthropic|@anthropic-ai|anthropic-sdk|anthropic-java"
                    + "|claude-(?:opus|sonnet|haiku|fable|instant|[0-9])",
            Pattern.CASE_INSENSITIVE);

    /** Records what it was asked to build and builds a stub, so no client can call out. */
    static final class Factory implements LlmClientFactory {
        final List<String> built = new ArrayList<>();

        @Override
        public Optional<LlmClient> openAiCompatible(
                String baseUrl, String apiKey, String model, java.time.Duration b, String reasoningEffort) {
            built.add(baseUrl + " " + model + " " + reasoningEffort);
            return Optional.of(new RecordingLlmClient(model));
        }
    }

    static AskProperties.Provider provider(String baseUrl, String model) {
        return new AskProperties.Provider(baseUrl, "test-key-not-real", model, null, null, null);
    }

    /** DeepSeek is paid, so it runs only with both prices set. */
    static AskProperties.Provider deepseek(Long price) {
        return new AskProperties.Provider(
                "https://api.deepseek.com", "test-key-not-real", "deepseek-chat", price, price, null);
    }

    static AskProperties properties(AskProperties.Provider gemini, AskProperties.Provider deepseek) {
        return new AskProperties(
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                gemini,
                provider("https://api.groq.com/openai/v1", "llama-3.1-8b-instant"),
                deepseek);
    }

    static AskProperties.Provider gemini(String baseUrl, String model) {
        return new AskProperties.Provider(baseUrl, "test-key-not-real", model, null, null, "minimal");
    }

    static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai";

    @Test
    void theChainIsGeminiThenGroqThenDeepSeekAndNothingElse() {
        Factory factory = new Factory();
        AskProviders chain =
                AskProviders.from(properties(gemini(GEMINI_URL, "gemini-2.5-flash-lite"), deepseek(270_000L)), factory);
        assertThat(chain.chain()).extracting(AskProviders.Provider::name).containsExactly("gemini", "groq", "deepseek");
        assertThat(factory.built).noneMatch(b -> ANTHROPIC.matcher(b).find());
        assertThat(AskProviders.ALLOWED)
                .containsOnlyKeys("gemini", "groq", "deepseek")
                .containsValues("generativelanguage.googleapis.com", "api.groq.com", "api.deepseek.com");
    }

    static AskProperties withReasoning(String gemini, String groq) {
        return new AskProperties(
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new AskProperties.Provider(
                        GEMINI_URL, "test-key-not-real", "gemini-3.5-flash-lite", null, null, gemini),
                new AskProperties.Provider(
                        "https://api.groq.com/openai/v1",
                        "test-key-not-real",
                        "llama-3.1-8b-instant",
                        null,
                        null,
                        groq),
                deepseek(270_000L));
    }

    /** Only Gemini is sent a reasoning effort (#601); Groq and DeepSeek requests are unchanged. */
    @Test
    void onlyGeminiIsGivenAReasoningEffort() {
        Factory factory = new Factory();
        AskProviders.from(withReasoning("low", "medium"), factory);
        assertThat(factory.built)
                .containsExactly(
                        GEMINI_URL + " gemini-3.5-flash-lite low",
                        "https://api.groq.com/openai/v1 llama-3.1-8b-instant null",
                        "https://api.deepseek.com deepseek-chat null");
    }

    /** Unset or empty is {@code minimal}, never nothing: Gemini 3 would think at its own default and miss the budget. */
    @Test
    void anUnsetOrEmptyReasoningEffortIsMinimal() {
        assertThat(withReasoning(null, null).gemini().reasoning()).isEqualTo("minimal");
        assertThat(withReasoning(" ", null).gemini().reasoning()).isEqualTo("minimal");
        assertThat(withReasoning(" Low ", null).gemini().reasoning()).isEqualTo("low");
        assertThat(new AskProperties("", null, null, null, null, null, null, null, null, null, null)
                        .gemini()
                        .reasoning())
                .isEqualTo("minimal");
    }

    /**
     * The shipped {@code application.yml} binds {@code minimal} whether {@code BMS_HELP_ASK_GEMINI_REASONING} is absent or
     * set but empty (a placeholder default covers only the absent case).
     */
    @Test
    void theShippedConfigurationSendsMinimalWhenTheVariableIsAbsentOrEmpty() throws IOException {
        for (Map<String, Object> env :
                List.<Map<String, Object>>of(Map.of(), Map.of("BMS_HELP_ASK_GEMINI_REASONING", ""))) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("env", env));
            new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml")).stream()
                            .findFirst()
                            .ifPresent(environment.getPropertySources()::addLast);
            AskProperties bound = Binder.get(environment)
                    .bind("bms.help.ask", AskProperties.class)
                    .get();
            assertThat(bound.gemini().reasoning()).isEqualTo("minimal");
            assertThat(bound.gemini().model()).isEqualTo("gemini-3.5-flash-lite");
            assertThat(bound.groq().reasoning()).isNull();
        }
    }

    /** Gemini 3 cannot turn thinking off, so {@code none} (refused by Google with 400) or a typo stops startup. */
    @Test
    void anUnknownReasoningEffortStopsStartup() {
        for (String bad : List.of("none", "off", "0")) {
            assertThatThrownBy(() -> withReasoning(bad, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("BMS_HELP_ASK_GEMINI_REASONING");
        }
    }

    @Test
    void aProviderWithoutAKeyIsSkippedAndNoKeyAtAllIsAnEmptyChain() {
        AskProperties none = new AskProperties("", null, null, null, null, null, null, null, null, null, null);
        assertThat(AskProviders.from(none, new Factory()).chain()).isEmpty();
        AskProperties noGemini = properties(gemini(GEMINI_URL, null), deepseek(270_000L));
        assertThat(AskProviders.from(noGemini, new Factory()).chain())
                .extracting(AskProviders.Provider::name)
                .containsExactly("groq", "deepseek");
    }

    @Test
    void deepSeekWithoutBothPricesIsSkippedSinceNoSpendCapCouldHold() {
        assertThat(AskProviders.from(
                                properties(gemini(GEMINI_URL, "gemini-2.5-flash-lite"), deepseek(null)), new Factory())
                        .chain())
                .extracting(AskProviders.Provider::name)
                .containsExactly("gemini", "groq");
        AskProperties.Provider half = new AskProperties.Provider(
                "https://api.deepseek.com", "test-key-not-real", "deepseek-chat", 270_000L, 0L, null);
        assertThat(AskProviders.from(properties(null, half), new Factory()).chain())
                .extracting(AskProviders.Provider::name)
                .containsExactly("groq");
    }

    static SetupProperties setup(String geminiPhoto, String groqPhoto) {
        return new SetupProperties("", null, null, null, null, null, geminiPhoto, groqPhoto);
    }

    /**
     * The setup helper (ADR-102) uses the same chain and checks: text goes to Gemini, Groq and DeepSeek on Ask BMS's models;
     * a photo to Gemini, and to Groq only with a vision model set; DeepSeek never reads a photo.
     */
    @Test
    void theSetupHelperChainsAreAskBmssAndDeepSeekNeverReadsAPhoto() {
        Factory factory = new Factory();
        AskProperties ask = properties(gemini(GEMINI_URL, "gemini-3.5-flash-lite"), deepseek(270_000L));
        SetupProviders chains = new AskConfiguration().setupProviders(ask, setup("", ""), factory);
        assertThat(chains.text().chain())
                .extracting(AskProviders.Provider::name)
                .containsExactly("gemini", "groq", "deepseek");
        assertThat(chains.photos().chain())
                .extracting(AskProviders.Provider::name)
                .containsExactly("gemini");
        assertThat(chains.photos().chain().getFirst().client().model()).isEqualTo("gemini-3.5-flash-lite");
        SetupProviders withGroq = new AskConfiguration()
                .setupProviders(ask, setup("gemini-3.5-flash", "test-groq-vision"), new Factory());
        assertThat(withGroq.photos().chain())
                .extracting(p -> p.name() + "/" + p.client().model())
                .containsExactly("gemini/gemini-3.5-flash", "groq/test-groq-vision");
        assertThat(factory.built).noneMatch(b -> ANTHROPIC.matcher(b).find());
    }

    @Test
    void aSetupPhotoModelNamingClaudeStopsStartup() {
        AskProperties ask = properties(gemini(GEMINI_URL, "gemini-3.5-flash-lite"), deepseek(270_000L));
        assertThatThrownBy(
                        () -> new AskConfiguration().setupProviders(ask, setup("claude-haiku-4-5", ""), new Factory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never uses");
        assertThatThrownBy(
                        () -> new AskConfiguration().setupProviders(ask, setup("", "anthropic-vision"), new Factory()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anythingNamingAnthropicOrClaudeStopsStartup() {
        assertThatThrownBy(() -> AskProviders.from(
                        properties(gemini("https://api.anthropic.com/v1", "gemini-2.5-flash-lite"), null),
                        new Factory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never uses");
        assertThatThrownBy(() -> AskProviders.from(
                        properties(gemini("https://generativelanguage.googleapis.com", "claude-haiku-4-5"), null),
                        new Factory()))
                .isInstanceOf(IllegalStateException.class);
        // A base URL off the provider's allowed host, whatever its model is called.
        assertThatThrownBy(() -> AskProviders.from(
                        properties(gemini("https://proxy.example.test/v1", "x"), null), new Factory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generativelanguage.googleapis.com");
        // A chain built by hand with a Claude model is refused too.
        assertThatThrownBy(() -> new AskProviders(List.of(new AskProviders.Provider(
                        "gemini", new RecordingLlmClient("claude-sonnet-4-5"), provider(null, null)))))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The allow-list is a constant, so no property or environment value can point a provider elsewhere: an allow-list
     * given the way BMS_HELP_ASK_ALLOWED_PROVIDERS would bind is ignored, and the proxy's host still stops startup.
     */
    @Test
    void anEnvironmentValueCannotWidenTheAllowList() {
        Map<String, String> env = Map.of(
                "bms.help.ask.allowed-providers", "gemini=llm-proxy.example.test",
                "bms.help.ask.allowedproviders", "gemini=llm-proxy.example.test",
                "bms.help.ask.gemini.base-url", "https://llm-proxy.example.test/v1",
                "bms.help.ask.gemini.api-key", "test-key-not-real",
                "bms.help.ask.gemini.model", "x",
                "bms.help.ask.gemini.reasoning", "minimal");
        AskProperties bound = new Binder(new MapConfigurationPropertySource(env))
                .bind("bms.help.ask", AskProperties.class)
                .get();
        assertThat(bound.gemini().baseUrl()).isEqualTo("https://llm-proxy.example.test/v1");
        assertThat(bound.gemini().reasoning()).isEqualTo("minimal");
        assertThat(AskProperties.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("allowedProviders");
        assertThatThrownBy(() -> AskProviders.from(bound, new Factory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be on generativelanguage.googleapis.com");
    }

    /**
     * Everything Ask BMS can reach: its module, its block of {@code application.yml}, its screens, client and articles, and
     * its lines of the deploy environment. {@code core.ai} is not scanned: it holds the advisor's own provider switch, and
     * {@link #coreHelpUsesOnlyTheNeutralPortOfCoreAi} keeps Ask BMS away from anything there but the neutral port.
     */
    @Test
    void noAnthropicEndpointSdkOrModelIsInAnythingAskBmsReaches() throws IOException {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        Map<String, String> texts = new java.util.LinkedHashMap<>();
        for (String folder : List.of(
                "backend/src/main/java/com/rincoltech/bms/core/help",
                "frontend/src/areas/staff/help",
                "frontend/src/help")) {
            try (java.util.stream.Stream<Path> files = Files.walk(repo.resolve(folder))) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    texts.put(repo.relativize(f).toString(), Files.readString(f));
                }
            }
        }
        texts.put("frontend/src/api/help.ts", Files.readString(repo.resolve("frontend/src/api/help.ts")));
        // The code that makes Ask BMS's calls: the factory bean and the one client it builds (AskBmsIT asserts the bean
        // returns that client, calling the base URL it was given).
        for (String code : List.of("OpenAiCompatibleFactory.java", "OpenAiCompatibleClient.java")) {
            Path file = repo.resolve("backend/src/main/java/com/rincoltech/bms/core/ai/internal")
                    .resolve(code);
            texts.put(repo.relativize(file).toString(), Files.readString(file));
        }
        texts.put(
                "backend/src/main/resources/db/migration/V108__help_ask_bms.sql",
                Files.readString(repo.resolve("backend/src/main/resources/db/migration/V108__help_ask_bms.sql")));
        String yml = Files.readString(repo.resolve("backend/src/main/resources/application.yml"));
        int from = yml.indexOf("\n  help:\n");
        assertThat(from).as("the help block of application.yml").isPositive();
        int to = yml.indexOf("\n  # ", from + 1);
        texts.put("application.yml (help)", yml.substring(from, to < 0 ? yml.length() : to));
        for (String env : List.of("deploy/compose.yml", "deploy/compose.pi-staging.yml", ".env.example")) {
            texts.put(
                    env + " (BMS_HELP_ASK_)",
                    String.join(
                            "\n",
                            Files.readAllLines(repo.resolve(env)).stream()
                                    .filter(l -> l.contains("BMS_HELP_ASK_"))
                                    .toList()));
        }
        assertThat(texts.get("application.yml (help)")).contains("BMS_HELP_ASK_GEMINI_API_KEY");
        for (Map.Entry<String, String> e : texts.entrySet()) {
            assertThat(ANTHROPIC.matcher(e.getValue()).find())
                    .as("%s names an Anthropic endpoint, SDK or model", e.getKey())
                    .isFalse();
        }
    }

    /**
     * Ask BMS reaches a provider only through the neutral port of {@code core.ai} (the chat request, the error and the
     * OpenAI-compatible factory), never through an Anthropic or Claude client, in {@code core.ai} or anywhere else.
     */
    @Test
    void coreHelpUsesOnlyTheNeutralPortOfCoreAi() {
        JavaClasses help = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.rincoltech.bms.core.help");
        classes()
                .that()
                .resideInAPackage("com.rincoltech.bms.core.help..")
                .should()
                .onlyDependOnClassesThat(
                        DescribedPredicate.describe(
                                "outside core.ai, or LlmClient, LlmClientFactory or LlmException of core.ai",
                                (JavaClass c) -> !c.getPackageName().startsWith("com.rincoltech.bms.core.ai")
                                        || c.getName()
                                                .matches(
                                                        "com\\.rincoltech\\.bms\\.core\\.ai\\.(LlmClient|LlmClientFactory|LlmException)(\\$.*)?")))
                .check(help);
        noClasses()
                .that()
                .resideInAPackage("com.rincoltech.bms.core.help..")
                .should()
                .dependOnClassesThat(DescribedPredicate.describe(
                        "name Anthropic or Claude", (JavaClass c) -> c.getName().matches("(?i).*(anthropic|claude).*")))
                .check(help);
        // And no Anthropic SDK on the classpath for it to reach.
        assertThat(getClass().getClassLoader().getResource("com/anthropic")).isNull();
    }
}
