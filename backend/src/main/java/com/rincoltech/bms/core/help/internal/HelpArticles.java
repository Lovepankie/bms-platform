package com.rincoltech.bms.core.help.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The How do I...? articles (ADR-095) as the server reads them: the very files of {@code frontend/src/help/articles},
 * copied onto the classpath under {@code help/articles} by the build (the Maven resource in {@code pom.xml}, and the
 * {@code help} build context of the image), so there is no second list to keep in step. Parsed the way
 * {@code frontend/src/help/corpus.ts} parses them; the revision is computed exactly as the Vite plugin computes it (the
 * first 16 hex digits of the SHA-256 of each file's name, a NUL, its bytes and a NUL, in name order), so the app and the
 * server name the same corpus by the same value. Read once at start: the corpus changes only with a release.
 */
@Component
class HelpArticles {

    private static final Logger log = LoggerFactory.getLogger(HelpArticles.class);
    static final String LOCATION = "classpath*:help/articles/*.md";
    static final String DEFAULT_LOCALE = "en";
    private static final Pattern FRONT = Pattern.compile("^---\\n([\\s\\S]*?)\\n---\\n([\\s\\S]*)$");
    private static final Pattern PICTURE = Pattern.compile("^\\s*!\\[[^\\]]+\\]\\([^)]+\\)\\s*$");

    /**
     * One article as the retrieval and the prompt need it.
     *
     * @param module {@code retail} or {@code lending}, or null for every business
     * @param text the introduction, the numbered steps and the notes as plain text, without pictures or marks
     */
    record Article(
            String id,
            String title,
            String job,
            String module,
            List<String> needs,
            List<String> needsAny,
            List<String> words,
            String locale,
            String text) {

        /** The same rule as a tour step (ADR-025, ADR-095 decision 2): the module, every {@code needs}, one {@code needs_any}. */
        boolean visibleTo(Set<String> permissions, Set<String> modules) {
            if (module != null && !modules.contains(module)) {
                return false;
            }
            if (!permissions.containsAll(needs)) {
                return false;
            }
            return needsAny.isEmpty() || needsAny.stream().anyMatch(permissions::contains);
        }
    }

    private final List<Article> articles;
    private final String revision;

    HelpArticles() {
        this(read());
    }

    /** From file name to raw bytes, as the build ships them. */
    HelpArticles(Map<String, byte[]> files) {
        List<String> names = files.keySet().stream().sorted().toList();
        List<Article> parsed = new ArrayList<>();
        for (String name : names) {
            parsed.add(parse(
                    name.substring(0, name.length() - ".md".length()),
                    new String(files.get(name), StandardCharsets.UTF_8)));
        }
        this.articles = List.copyOf(parsed);
        this.revision = revisionOf(files);
        if (articles.isEmpty()) {
            // Ask BMS then finds nothing and says so; the build is at fault (HelpArticlesTest fails on it in CI).
            log.error("no help article on the classpath under help/articles: Ask BMS has nothing to answer from");
        }
    }

    List<Article> all() {
        return articles;
    }

    String revision() {
        return revision;
    }

    private static Map<String, byte[]> read() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                String name = resource.getFilename();
                if (name == null || !name.endsWith(".md")) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    files.put(name, in.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("the help articles could not be read", e);
        }
        return files;
    }

    /** ADR-095 decision 4, byte for byte the Vite plugin's {@code bms-help-corpus}. */
    static String revisionOf(Map<String, byte[]> files) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String name :
                    files.keySet().stream().sorted(Comparator.naturalOrder()).toList()) {
                hash.update((name + "\0").getBytes(StandardCharsets.UTF_8));
                hash.update(files.get(name));
                hash.update((byte) 0);
            }
            return HexFormat.of().formatHex(hash.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }

    /** As {@code parseArticle} of {@code corpus.ts}: front matter of {@code key: value} lines, then the body. */
    static Article parse(String id, String source) {
        Matcher m = FRONT.matcher(source.replace("\r\n", "\n"));
        if (!m.matches()) {
            throw new IllegalStateException(id + ": no front matter");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : m.group(1).split("\n")) {
            int at = line.indexOf(':');
            if (at > 0) {
                meta.put(line.substring(0, at).strip(), line.substring(at + 1).strip());
            }
        }
        String title = meta.getOrDefault("title", "");
        String job = meta.getOrDefault("job", "");
        if (title.isEmpty() || job.isEmpty()) {
            throw new IllegalStateException(id + ": a title and a job are needed");
        }
        String module = meta.get("module");
        List<String> body = new ArrayList<>();
        for (String line : m.group(2).strip().split("\n")) {
            if (PICTURE.matcher(line).matches() || line.isBlank()) {
                continue;
            }
            body.add(line.replaceFirst("^>\\s?", "Note: ").replace("**", "").strip());
        }
        return new Article(
                id,
                title,
                job,
                "retail".equals(module) || "lending".equals(module) ? module : null,
                list(meta.get("needs")),
                list(meta.get("needs_any")),
                list(meta.get("words")),
                meta.getOrDefault("locale", DEFAULT_LOCALE),
                String.join("\n", body));
    }

    private static List<String> list(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(v -> !v.isEmpty())
                .toList();
    }
}
