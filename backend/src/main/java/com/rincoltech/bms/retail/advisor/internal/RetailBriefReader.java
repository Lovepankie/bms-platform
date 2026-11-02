package com.rincoltech.bms.retail.advisor.internal;

import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.advisor.internal.BriefWriter.Brief;
import com.rincoltech.bms.retail.advisor.internal.RetailAdvisorApi.BriefView;
import com.rincoltech.bms.retail.advisor.internal.RetailAdvisorApi.ChipView;
import com.rincoltech.bms.retail.advisor.internal.RetailAdvisorApi.PointView;
import com.rincoltech.bms.retail.advisor.internal.RetailBriefStore.Stored;
import com.rincoltech.bms.retail.reports.RetailBriefFacts;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@code GET /retail/advisor/brief} answers (issue #422). The stored brief covers every branch, every seller, cost and
 * profit, so it is returned only to a caller who holds the brief, every seller's sales and profit over every branch: the owner
 * and the administrators. Anyone else, and every day with no model brief, gets the facts only brief computed for what they may
 * see (ADR-055): a Supervisor their shops, a seller their own sales, a Storekeeper stock counts and never money. Nothing here calls
 * a model, and nothing here fails the page.
 */
@Service
class RetailBriefReader {

    static final String USE = "retail.advisor.use";
    static final String ALL_SELLERS = "retail.sale.all_sellers";
    static final String PROFIT = "retail.profit.read";

    static final String LABEL_MODEL = "AI assistant (advisory)";
    static final String LABEL_AUTOMATIC = "Automatic summary";
    static final String NOTE_MODEL = "Made from your shop figures. Check before acting. It does not change any record.";
    static final String NOTE_AUTOMATIC = "Written from your shop figures by fixed rules. No AI was used.";

    private static final Logger log = LoggerFactory.getLogger(RetailBriefReader.class);

    private final AiSettings settings;
    private final RetailBriefStore store;
    private final RetailBriefFacts briefFacts;
    private final BusinessClock clock;

    RetailBriefReader(AiSettings settings, RetailBriefStore store, RetailBriefFacts briefFacts, BusinessClock clock) {
        this.settings = settings;
        this.store = store;
        this.briefFacts = briefFacts;
        this.clock = clock;
    }

    /** Today's brief, or empty when the assistant is not active for the tenant: then nothing is read and no card is drawn. */
    @Transactional(readOnly = true)
    Optional<BriefView> today() {
        return forDate(clock.today(BusinessClock.DEFAULT_ZONE));
    }

    @Transactional(readOnly = true)
    Optional<BriefView> forDate(LocalDate date) {
        if (!settings.active()) {
            return Optional.empty();
        }
        Principal principal = CurrentPrincipal.require();
        Optional<Stored> stored = store.find(date);
        if (stored.isPresent() && seesEverything(principal)) {
            Brief brief = stored.get().brief();
            return Optional.of(view(brief, stored.get().generatedAt(), reasonOf(brief)));
        }
        return Optional.of(deterministic(date, stored.isPresent() ? "scope" : "not_ready"));
    }

    /** The facts only brief for what the caller may see; computed per request, never stored or shared. */
    private BriefView deterministic(LocalDate date, String reason) {
        try {
            Brief brief = BriefWriter.fallback(PromptFacts.of(briefFacts.forCaller(date)), reason);
            return view(brief, clock.now(), reason);
        } catch (ApiException e) {
            if (e.status() == HttpStatus.FORBIDDEN) {
                throw e;
            }
            log.warn("retail brief figures unavailable code={}", e.code());
        } catch (RuntimeException e) {
            log.error("retail brief figures failed error={}", e.getClass().getSimpleName());
        }
        return new BriefView(
                true,
                "fallback",
                reason,
                LABEL_AUTOMATIC,
                clock.now(),
                null,
                "The figures are not available right now.",
                List.of(),
                List.of(),
                NOTE_AUTOMATIC);
    }

    private static BriefView view(Brief brief, Instant at, String reason) {
        boolean model = brief.model();
        return new BriefView(
                true,
                brief.source(),
                model ? null : reason,
                model ? LABEL_MODEL : LABEL_AUTOMATIC,
                at,
                brief.factsAsOf(),
                brief.summary(),
                brief.points().stream()
                        .map(p -> new PointView(p.text(), p.facts()))
                        .toList(),
                brief.chips().stream()
                        .map(c -> new ChipView(c.key(), c.label(), c.display(), c.metric()))
                        .toList(),
                model ? NOTE_MODEL : NOTE_AUTOMATIC);
    }

    /** The API's closed reason for a stored fallback; the stored word is the gateway's. */
    static String reasonOf(Brief brief) {
        if (brief.model()) {
            return null;
        }
        return switch (brief.reason() == null ? "" : brief.reason()) {
            case "validation" -> "validation";
            case BriefWriter.EMPTY -> "empty";
            case "not_enabled", "not_allowed", "provider_off" -> "off";
            case "tenant_token_cap", "tenant_cost_cap", "platform_token_cap", "platform_cost_cap" -> "cap";
            case "breaker_open", "timeout", "network", "rate_limited", "provider_error", "auth_error", "bad_response" ->
                "provider_down";
            default -> "unavailable";
        };
    }

    /**
     * Every permission a figure of the stored brief needs, each over every branch: the brief itself, every seller's sales, cost
     * and profit, the stock counts, the Controls counts and the open orders (ADR-037 amendment of #422, decision 4).
     */
    static final List<String> STORED_BRIEF_NEEDS =
            List.of(USE, ALL_SELLERS, PROFIT, "retail.stock.read", "retail.controls.read", "retail.order.manage");

    /** The owner's view: every permission of {@link #STORED_BRIEF_NEEDS}, each over every branch. */
    static boolean seesEverything(Principal principal) {
        for (String permission : STORED_BRIEF_NEEDS) {
            Optional<Principal.BranchScope> scope = principal.scopeOf(permission);
            if (scope.isEmpty() || !scope.get().all()) {
                return false;
            }
        }
        return true;
    }
}
