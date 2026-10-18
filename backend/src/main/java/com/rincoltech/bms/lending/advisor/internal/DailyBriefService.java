package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.core.ai.AiGateway;
import com.rincoltech.bms.core.ai.AiGateway.AiResult;
import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.advisor.internal.BriefBuilder.Brief;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.insights.PortfolioFacts;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The automatic daily brief for the bound tenant (ADR-037, spec 5.2, 5.7 and 5.13): one per tenant and business date,
 * over every branch, made once. It runs outside a transaction (the gateway opens its own) and is given the date it was
 * queued for: a job that was queued before midnight and runs after it reads the facts of the date it was queued for
 * (the scope carries that date, so after midnight the facts come from that day's snapshot, never from the next day's live
 * tables), and stores the brief under that date.
 *
 * <p>Nothing is sent unless the tenant has switched the assistant on: the check comes first, before any facts are read
 * or a request is built. Every other failure (the provider, the breaker, a cap, a refused answer) stores the
 * deterministic facts-only brief with its reason, so the day is not retried and the card shows the automatic summary.
 */
@Service
class DailyBriefService {

    /** What one run did, for the job's log and the tests. */
    enum Outcome {
        /** The assistant is not active for the tenant (its switch is off, or it is not on the platform list): no facts, no call. */
        OFF,
        /** A brief for the date exists already. */
        EXISTS,
        /** The facts of the date could not be read (for example no snapshot yet): nothing stored, a later run may retry. */
        NO_FACTS,
        /** A brief rendered from an accepted model answer was stored. */
        STORED_MODEL,
        /** The facts-only brief was stored: a paid call that gave no usable answer, so the day is final. */
        STORED_FALLBACK,
        /** The state said no (switch, list, provider, open breaker) and nothing was paid for: nothing stored, a later run retries. */
        NOT_STORED
    }

    /** Reasons that describe the state of the platform, not of the day: nothing was sent, so the day is not locked. */
    private static final Set<AiGateway.Reason> STATE_REFUSALS = Set.of(
            AiGateway.Reason.NOT_ENABLED,
            AiGateway.Reason.NOT_ALLOWED,
            AiGateway.Reason.PROVIDER_OFF,
            AiGateway.Reason.BREAKER_OPEN,
            AiGateway.Reason.INTERNAL_ERROR);

    /** Reasons where a network call was made: the row's model and call id are kept for the cost trail. */
    private static final Set<AiGateway.Reason> CALLED = Set.of(
            AiGateway.Reason.TIMEOUT,
            AiGateway.Reason.NETWORK,
            AiGateway.Reason.RATE_LIMITED,
            AiGateway.Reason.PROVIDER_ERROR,
            AiGateway.Reason.AUTH_ERROR,
            AiGateway.Reason.BAD_RESPONSE,
            AiGateway.Reason.BAD_REQUEST);

    private static final Logger log = LoggerFactory.getLogger(DailyBriefService.class);

    private final AiSettings settings;
    private final AiGateway gateway;
    private final PortfolioFacts portfolioFacts;
    private final BriefStore store;
    private final BusinessClock clock;
    private final LlmClient llm;

    DailyBriefService(
            AiSettings settings,
            AiGateway gateway,
            PortfolioFacts portfolioFacts,
            BriefStore store,
            BusinessClock clock,
            LlmClient llm) {
        this.settings = settings;
        this.gateway = gateway;
        this.portfolioFacts = portfolioFacts;
        this.store = store;
        this.clock = clock;
        this.llm = llm;
    }

    /** Makes and stores the brief of {@code date} for the bound tenant, once. Call outside a transaction. */
    Outcome generate(LocalDate date) {
        // The cheap read: this runs for every tenant on every tick. Off includes not being on the platform list.
        if (!settings.active()) {
            return Outcome.OFF;
        }
        if (store.exists(date)) {
            return Outcome.EXISTS;
        }
        FactSet set;
        try {
            // Every branch, every officer, the month so far ending on the queued date; no principal is read.
            set = portfolioFacts.build(new PortfolioScope(date.withDayOfMonth(1), date, date, null, null, null));
        } catch (ApiException e) {
            log.warn("daily brief facts unavailable date={} code={}", date, e.code());
            return Outcome.NO_FACTS;
        }
        AliasedFacts facts = AliasedFacts.of(set);
        AiResult result = gateway.complete("brief", null, PromptBuilder.brief(facts));
        if (result.callId() == null || (result.reason() != null && STATE_REFUSALS.contains(result.reason()))) {
            // Nothing was paid for and the answer is not the day's: do not lock the day with a stand-in, try again
            // later.
            log.info("daily brief not stored date={} reason={}", date, result.reason());
            return Outcome.NOT_STORED;
        }
        Brief brief = BriefBuilder.build(facts, result);
        boolean called = result.answered() || CALLED.contains(result.reason());
        Instant now = clock.now();
        try {
            store.insertOnce(date, brief, now, called ? llm.model() : null, called ? result.callId() : null);
        } catch (RuntimeException e) {
            // The call is paid for: do not let the next tick pay again. Try a minimal facts-only row; if even that
            // cannot be
            // written the failure goes to the job, which logs it (the type only, never a message).
            log.error(
                    "daily brief store failed date={} error={}",
                    date,
                    e.getClass().getSimpleName());
            brief = BriefBuilder.fallback(facts, "internal_error");
            store.insertOnce(date, brief, now, null, null);
        }
        log.info("daily brief stored date={} source={} reason={}", date, brief.source(), brief.reason());
        return brief.model() ? Outcome.STORED_MODEL : Outcome.STORED_FALLBACK;
    }
}
