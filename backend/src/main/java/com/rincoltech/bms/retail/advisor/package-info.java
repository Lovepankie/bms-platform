/**
 * Retail: the AI brief (issue #422; ADR-037 as amended for retail). The shop and wholesale counterpart of
 * {@code lending.advisor}: each morning a brief over the shop figures of {@link com.rincoltech.bms.retail.reports.RetailBriefFacts},
 * made through {@code core.ai}'s one gateway, under the same tenant switch, platform list, daily caps and call log as the
 * lending brief. The model writes no text: it chooses fact keys, a topic, an action of the reviewed catalogue
 * ({@code retail-advisor/catalogue.json}) and a priority, and our code writes every sentence. Advisory only: it changes no
 * record and sends nothing. Items are sent as {@code Item A}, never by name.
 */
@ApplicationModule(
        id = "retail.advisor",
        displayName = "Retail: AI brief",
        allowedDependencies = {"kernel", "core.ai", "core.jobs", "core.tenancy", "retail.reports"})
package com.rincoltech.bms.retail.advisor;

import org.springframework.modulith.ApplicationModule;
