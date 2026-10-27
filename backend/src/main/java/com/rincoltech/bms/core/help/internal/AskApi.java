package com.rincoltech.bms.core.help.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Bodies of {@code /api/v1/help/ask} (chapter 7 section 7.11.28, ADR-096). Field names are snake_case on the wire. */
final class AskApi {

    private AskApi() {}

    @Schema(name = "AskStatus")
    record AskStatus(
            @Schema(description = "Ask BMS is on for this business; while false the box is not shown")
            boolean enabled,

            @Schema(description = "The help corpus revision the server answers from (ADR-095)")
            String revision) {}

    @Schema(name = "AskRequest")
    record AskRequest(
            @NotBlank @Size(min = 3, max = 300) String question,

            @Schema(
                    description =
                            "The asker's language, for example en; the articles' language is used when there is none")
            @Size(max = 10)
            String locale) {}

    @Schema(name = "AskArticle")
    record AskArticle(String id, String title) {}

    @Schema(name = "AskAnswer")
    record AskAnswer(
            @Schema(
                    description = "model: phrased from the cited article; cache: the same answer as before; articles:"
                            + " no answer was phrased, the matching articles are listed; other_role: the best"
                            + " match is a job for another role; no_match: no article matches")
            String source,

            @Schema(
                    description = "Why no answer was written, for source articles only: not_in_articles (the model"
                            + " found none in the articles), all_failed (every provider failed), no_provider (none is"
                            + " set) or daily_cap (the business's questions for the day are used up); null otherwise",
                    nullable = true)
            String reason,

            @Schema(description = "The answer to show; null for articles and no_match")
            String answer,

            @Schema(description = "The article the answer comes from, to link; null unless source is model or cache")
            AskArticle cited,

            @Schema(description = "The matching articles the asker may read, best first")
            List<AskArticle> articles,

            String revision) {}
}
