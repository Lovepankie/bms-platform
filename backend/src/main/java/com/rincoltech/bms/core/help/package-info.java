/**
 * Core: Ask BMS (issue #20, owner decision D52, ADR-096). A member of staff asks how to do something and gets an answer
 * phrased by a small model from the How do I...? articles alone (ADR-095), citing and linking the article. The articles
 * are read from the same Markdown files the app bundles; PostgreSQL full-text search picks the best ones among those the
 * asker's permissions, modules and locale allow. The question is scrubbed of phone numbers, ID numbers, emails, long
 * figures and names before it leaves the server, and no business record is ever read or sent. Providers are tried
 * cheapest first (Gemini, then Groq, then DeepSeek), each only on its own host from a constant allow-list.
 * Answers are cached per normalised question, roles, permission set, modules, locale, corpus revision and permission
 * matrix version. Off unless the platform names the business in {@code BMS_HELP_ASK_TENANTS}; a daily question cap per
 * business and a per-person hourly limit, both taken before the work, and the spend of every provider call reserved before
 * it ({@code help_ask_spend}, migration V108). With no provider configured, all failing or the cap reached, the best
 * matching articles are shown instead. No public API: the module only serves {@code /help/ask}.
 */
@ApplicationModule(
        id = "core.help",
        displayName = "Core: Ask BMS",
        allowedDependencies = {"kernel", "core.tenancy", "core.ai"})
package com.rincoltech.bms.core.help;

import org.springframework.modulith.ApplicationModule;
