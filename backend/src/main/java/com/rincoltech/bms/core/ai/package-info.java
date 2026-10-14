/**
 * The language model gateway (ADR-037, FR-AIA, docs/specs/client-extras.md section 5). The
 * {@link com.rincoltech.bms.core.ai.LlmClient} port with one OpenAI-compatible HTTP adapter (the
 * first provider is DeepSeek; base URL, model and key are environment values), a recording stub
 * that is refused outside the dev and test profiles, and the
 * {@link com.rincoltech.bms.core.ai.AiGateway} every caller goes through: tenant switch and
 * platform allow-list, per call and daily token and cost caps checked before any network call, a
 * circuit breaker, a 30 second budget, and the {@code ai_calls} log, which holds counts and a
 * request hash and never a prompt, a response or a provider error body. The tenant's own switch
 * and caps are the settings group {@code ai}, reached through
 * {@link com.rincoltech.bms.core.ai.AiSettings}. Off unless configured; advisory only; this module
 * depends on no vertical and knows nothing about portfolios.
 */
@ApplicationModule(
        id = "core.ai",
        displayName = "Core: AI gateway",
        allowedDependencies = {"kernel", "core.tenancy"})
package com.rincoltech.bms.core.ai;

import org.springframework.modulith.ApplicationModule;
