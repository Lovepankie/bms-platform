/**
 * Lending: the AI advisor (extras slice C, issues #223 and later; ADR-037; {@code docs/specs/client-extras.md}
 * section 5). Advisory only: the model never changes a record, sends nothing and has no tools. This slice (C2)
 * holds everything that stands between the portfolio figures and a model, and nothing that reaches one: the
 * alias mapper that turns branches and products into {@code Branch 1} and {@code Product A} for a prompt and back
 * for the person who asked, the prompt builder over the typed facts of
 * {@link com.rincoltech.bms.lending.insights.PortfolioFacts}, the {@code NumericValidator} that refuses any answer
 * holding a number that is not among the facts it cites or a direction word that contradicts the sign of a
 * change, and the offline evaluation harness ({@link com.rincoltech.bms.lending.advisor.AdvisorEvaluation}) that
 * scores recorded answers, adversarial ones included, without a network call. The daily job, the endpoints and
 * the screens come in later slices and reach a model only through {@code core.ai}'s gateway.
 */
@ApplicationModule(
        id = "lending.advisor",
        displayName = "Lending: AI advisor",
        allowedDependencies = {"kernel", "core.ai", "core.jobs", "core.tenancy", "lending.insights"})
package com.rincoltech.bms.lending.advisor;

import org.springframework.modulith.ApplicationModule;
