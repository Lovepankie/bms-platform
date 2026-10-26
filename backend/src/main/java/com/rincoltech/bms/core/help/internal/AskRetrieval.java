package com.rincoltech.bms.core.help.internal;

import com.rincoltech.bms.core.help.internal.HelpArticles.Article;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Picks the articles that answer a question with PostgreSQL full-text search (ADR-096 decision 2; no embeddings at this
 * size). The articles are passed in as arrays and searched in the query, so no copy of the corpus is kept in a table that
 * could drift from the files: the title weighs most (A), then the article's search words (B), then its text (C). The
 * question's words become an OR query, so a question with a word no article uses still finds the article with the others;
 * the hits are ranked by how many of the question's words an article has, then by the rank. The language of the search is
 * the locale's ({@code english} for {@code en}, {@code simple} otherwise).
 */
@Component
class AskRetrieval {

    /** How many hits are returned at most. */
    static final int LIMIT = 5;

    /**
     * @param matched how many of the question's distinct words the article has
     * @param asked how many distinct words the question has after the stop words
     */
    record Hit(Article article, int matched, int asked, double rank) {

        /** Half or more of the question's words are in the article. */
        boolean confident() {
            return asked > 0 && matched * 2 >= asked;
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate reads;

    AskRetrieval(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
    }

    /** The best articles among {@code candidates}, best first; empty when no word of the question is in any. */
    List<Hit> search(String question, String locale, List<Article> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Article> byId = candidates.stream().collect(Collectors.toMap(Article::id, Function.identity()));
        return reads.execute(tx -> jdbc.sql("""
                        WITH q AS (
                            SELECT array_agg(DISTINCT l) AS lexemes
                              FROM unnest(tsvector_to_array(to_tsvector(CAST(:cfg AS regconfig), :question))) AS l
                        ), docs AS (
                            SELECT d.id,
                                   setweight(to_tsvector(CAST(:cfg AS regconfig), d.title), 'A')
                                   || setweight(to_tsvector(CAST(:cfg AS regconfig), d.words), 'B')
                                   || setweight(to_tsvector(CAST(:cfg AS regconfig), d.body), 'C') AS v
                              FROM unnest(CAST(:ids AS text[]), CAST(:titles AS text[]), CAST(:words AS text[]),
                                          CAST(:bodies AS text[])) AS d(id, title, words, body)
                        ), query AS (
                            SELECT lexemes,
                                   CAST(array_to_string(ARRAY(SELECT quote_literal(l) FROM unnest(lexemes) AS l), ' | ')
                                        AS tsquery) AS any_word
                              FROM q
                             WHERE lexemes IS NOT NULL
                        )
                        SELECT docs.id,
                               (SELECT count(*) FROM unnest(query.lexemes) AS l
                                 WHERE docs.v @@ CAST(quote_literal(l) AS tsquery)) AS matched,
                               cardinality(query.lexemes) AS asked,
                               ts_rank(docs.v, query.any_word) AS rank
                          FROM docs, query
                         WHERE docs.v @@ query.any_word
                         ORDER BY matched DESC, rank DESC, docs.id
                         LIMIT :limit
                        """)
                .param("cfg", "en".equals(locale) ? "english" : "simple")
                .param("question", question)
                .param("ids", candidates.stream().map(Article::id).toArray(String[]::new))
                .param("titles", candidates.stream().map(Article::title).toArray(String[]::new))
                .param(
                        "words",
                        candidates.stream()
                                .map(a -> String.join(", ", a.words()))
                                .toArray(String[]::new))
                .param("bodies", candidates.stream().map(Article::text).toArray(String[]::new))
                .param("limit", LIMIT)
                .query((rs, n) -> new Hit(
                        byId.get(rs.getString("id")), rs.getInt("matched"), rs.getInt("asked"), rs.getDouble("rank")))
                .list());
    }
}
