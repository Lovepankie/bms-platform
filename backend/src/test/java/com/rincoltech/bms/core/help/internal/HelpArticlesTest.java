package com.rincoltech.bms.core.help.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.help.internal.HelpArticles.Article;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The server reads the very articles the app bundles (ADR-095, ADR-096): every file of {@code frontend/src/help/articles}
 * is on the classpath, parsed, and named by the same revision as the Vite plugin computes, and an article is shown by the
 * same rule as a tour step.
 */
class HelpArticlesTest {

    static final Path FOLDER = Path.of("..", "frontend", "src", "help", "articles");

    @Test
    void everyArticleOfTheAppIsOnTheClasspathWithTheSameRevision() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(FOLDER)) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".md")).toList()) {
                files.put(p.getFileName().toString(), Files.readAllBytes(p));
            }
        }
        HelpArticles corpus = new HelpArticles();
        assertThat(files).hasSizeGreaterThanOrEqualTo(20);
        assertThat(corpus.all()).hasSize(files.size());
        assertThat(corpus.revision()).isEqualTo(HelpArticles.revisionOf(files)).matches("^[0-9a-f]{16}$");
        assertThat(corpus.all())
                .extracting(Article::id)
                .contains("invite-someone", "record-a-sale", "move-stock-store-to-shop");
    }

    /** The same two files give the same value in corpus.test.ts, so the app and the server cannot drift apart. */
    @Test
    void theRevisionIsTheVitePluginsHash() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("b.md", "y".getBytes(StandardCharsets.UTF_8));
        files.put("a.md", "x".getBytes(StandardCharsets.UTF_8));
        assertThat(HelpArticles.revisionOf(files)).isEqualTo("a2bb2ea5e9574e21");
    }

    @Test
    void anArticleIsParsedWithoutPicturesOrMarksAndShownByTheTourRule() {
        Article a = HelpArticles.parse("invite-test", """
                ---
                title: How do I invite someone?
                job: users
                order: 10
                needs: core.users.manage
                words: invite, cashier
                ---
                You invite a person with their phone number.

                1. Open **Staff**, then tap **Invite someone**.
                   ![Tap Invite someone](invite-someone-1.webp)

                > The link works once.
                """);
        assertThat(a.title()).isEqualTo("How do I invite someone?");
        assertThat(a.locale()).isEqualTo("en");
        assertThat(a.words()).containsExactly("invite", "cashier");
        assertThat(a.text())
                .isEqualTo("You invite a person with their phone number.\n1. Open Staff, then tap Invite someone.\n"
                        + "Note: The link works once.");
        assertThat(a.visibleTo(Set.of("core.users.manage"), Set.of())).isTrue();
        assertThat(a.visibleTo(Set.of("retail.sale.create"), Set.of("retail"))).isFalse();

        Article sale = new Article(
                "s", "How do I sell?", "sell", "retail", List.of("retail.sale.create"), List.of(), List.of(), "en", "");
        assertThat(sale.visibleTo(Set.of("retail.sale.create"), Set.of("retail")))
                .isTrue();
        // The module off for the business, or not held: hidden, as on the menu.
        assertThat(sale.visibleTo(Set.of("retail.sale.create"), Set.of("lending")))
                .isFalse();
        Article either =
                new Article("e", "t", "money", null, List.of(), List.of("a.one", "a.two"), List.of(), "en", "");
        assertThat(either.visibleTo(Set.of("a.two"), Set.of())).isTrue();
        assertThat(either.visibleTo(Set.of("a.three"), Set.of())).isFalse();
    }
}
