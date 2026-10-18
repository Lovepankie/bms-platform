package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.advisor.internal.AdvisorApi.BriefView;
import com.rincoltech.bms.lending.advisor.internal.AdvisorApi.ChipView;
import com.rincoltech.bms.lending.advisor.internal.AdvisorApi.PointView;
import com.rincoltech.bms.lending.advisor.internal.BriefBuilder.Brief;
import com.rincoltech.bms.lending.advisor.internal.BriefStore.Stored;
import com.rincoltech.bms.lending.insights.PortfolioFacts;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@code GET /lending/advisor/brief} answers (ADR-037, spec 5.2 and 5.7). The stored daily brief covers every branch,
 * so it is returned only to a caller whose branch scope covers every branch of the tenant in each of the three permissions
 * that matter here and who holds {@code lending.insights.all_officers}; anyone else, and every case where no model brief
 * exists, gets the deterministic facts-only brief computed for their own scope, labelled "Automatic summary" with the
 * reason. Nothing here calls a model, and nothing here fails the page: whatever goes wrong with the figures is an
 * automatic summary that says they are not available.
 */
@Service
class BriefReadService {

    static final String ALL_OFFICERS = "lending.insights.all_officers";
    static final String INSIGHTS_READ = "lending.insights.read";
    static final String ADVISOR_USE = "lending.advisor.use";

    static final String LABEL_MODEL = "AI assistant (advisory)";
    static final String LABEL_AUTOMATIC = "Automatic summary";
    static final String NOTE_MODEL =
            "Generated from your portfolio figures. Check before acting. It does not change any record.";
    static final String NOTE_AUTOMATIC = "Written from your portfolio figures by fixed rules. No AI was used.";

    private static final Logger log = LoggerFactory.getLogger(BriefReadService.class);

    private final AiSettings settings;
    private final BriefStore store;
    private final PortfolioFacts portfolioFacts;
    private final BusinessClock clock;
    private final Branches branches;

    BriefReadService(
            AiSettings settings,
            BriefStore store,
            PortfolioFacts portfolioFacts,
            BusinessClock clock,
            Branches branches) {
        this.settings = settings;
        this.store = store;
        this.portfolioFacts = portfolioFacts;
        this.clock = clock;
        this.branches = branches;
    }

    /**
     * Today's brief, or empty when the assistant is not active for the tenant (its switch is off or it is not on the platform
     * list): then nothing is read and the card is not drawn. The transaction is opened here, at the entry the controller
     * calls, because the tenant is bound when a transaction begins.
     */
    @Transactional(readOnly = true)
    Optional<BriefView> today() {
        // The business date is read once for the whole request.
        return forDate(clock.today(BusinessClock.DEFAULT_ZONE));
    }

    /** The brief for one business date (the date read once by {@link #today}); a test chooses it. */
    @Transactional(readOnly = true)
    Optional<BriefView> forDate(LocalDate date) {
        if (!settings.active()) {
            return Optional.empty();
        }
        Principal principal = CurrentPrincipal.require();
        Optional<Stored> stored = store.find(date);
        if (stored.isPresent() && coversEveryBranch(principal)) {
            return Optional.of(view(
                    stored.get().brief(),
                    stored.get().generatedAt(),
                    reasonOf(stored.get().brief())));
        }
        return Optional.of(deterministic(stored.isPresent() ? "scope" : "not_ready"));
    }

    /** The facts-only brief for the caller's own scope; computed per request, never stored or shared. */
    private BriefView deterministic(String reason) {
        try {
            FactSet set = portfolioFacts.forCaller();
            Brief brief = BriefBuilder.fallback(AliasedFacts.of(set), reason);
            return view(brief, clock.now(), reason);
        } catch (ApiException e) {
            if (e.status() == HttpStatus.FORBIDDEN) {
                throw e;
            }
            log.warn("advisor brief figures unavailable code={}", e.code());
        } catch (RuntimeException e) {
            log.error("advisor brief figures failed error={}", e.getClass().getSimpleName());
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

    private BriefView view(Brief brief, Instant at, String reason) {
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

    /** The API's closed reason for a stored fallback (spec chapter 7 section 7.11.24); the stored word is the gateway's. */
    static String reasonOf(Brief brief) {
        if (brief.model()) {
            return null;
        }
        return switch (brief.reason() == null ? "" : brief.reason()) {
            case "validation" -> "validation";
            case "not_enabled", "not_allowed", "provider_off" -> "off";
            case "tenant_token_cap", "tenant_cost_cap", "platform_token_cap", "platform_cost_cap" -> "cap";
            case "breaker_open", "timeout", "network", "rate_limited", "provider_error", "auth_error", "bad_response" ->
                "provider_down";
            default -> "unavailable";
        };
    }

    /**
     * True when the caller's scope covers every branch of the tenant, in the permissions that give the brief its figures
     * and its audience, and the caller holds {@code lending.insights.all_officers}. A branch manager holds all officers
     * but only for their own branches, so they do not pass.
     */
    boolean coversEveryBranch(Principal principal) {
        if (!principal.hasPermission(ALL_OFFICERS)) {
            return false;
        }
        // Every ACTIVE branch: an inactive branch has no loans being worked and no user is scoped to it, so a branch
        // list that
        // names all the active branches covers the tenant. (Counting inactive ones would keep such a list from ever
        // passing.)
        Set<UUID> every = branches.all().stream()
                .filter(Branches.Branch::active)
                .map(Branches.Branch::id)
                .collect(java.util.stream.Collectors.toSet());
        for (String permission : List.of(ADVISOR_USE, INSIGHTS_READ, ALL_OFFICERS)) {
            Optional<Principal.BranchScope> scope = principal.scopeOf(permission);
            if (scope.isEmpty()
                    || !(scope.get().all() || scope.get().branchIds().containsAll(every))) {
                return false;
            }
        }
        return true;
    }
}
