package com.rincoltech.bms.core.help.internal;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmException;
import com.rincoltech.bms.core.help.SetupDrafts;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The setup helper's drafting (issue #626, ADR-102), in the order of cost: the switch for the business; reading what the
 * owner gave (a typed list, or a file read into text or a picture by {@link ListFiles}); the person's hourly limit and the
 * business's daily cap, both taken before the work; then the providers, cheapest first, each answer read by the caller's
 * reader and the next provider tried when it does not read. Every provider call reserves its cost in Ask BMS's spend log
 * before it is made and settles after it. A provider problem is never an error for the owner: the reason comes back and
 * the screen offers to type the list or import a file. Nothing is saved but the log row and the spend.
 */
@Service
class SetupDraftService implements SetupDrafts {

    private static final Logger log = LoggerFactory.getLogger(SetupDraftService.class);

    /** What a picture is reckoned to cost in input tokens, for the reservation; a stock book page is about this. */
    static final int PICTURE_TOKENS = 1600;

    private final SetupProviders providers;
    private final SetupProperties properties;
    private final AskProperties ask;
    private final AskStore spend;
    private final SetupStore store;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final Set<UUID> enabledTenants;

    SetupDraftService(
            SetupProviders providers,
            SetupProperties properties,
            AskProperties ask,
            AskStore spend,
            SetupStore store,
            CurrentTenant tenant,
            BusinessClock clock) {
        this.providers = providers;
        this.properties = properties;
        this.ask = ask;
        this.spend = spend;
        this.store = store;
        this.tenant = tenant;
        this.clock = clock;
        this.enabledTenants = properties.tenants();
    }

    @Override
    public Status status() {
        boolean on = enabled();
        return new Status(
                on && !providers.text().chain().isEmpty(),
                on && !providers.photos().chain().isEmpty());
    }

    private boolean enabled() {
        return enabledTenants.contains(tenant.profile().id());
    }

    @Override
    public void requireOn() {
        if (!enabled()) {
            // Nothing of the business is read or sent for a business the platform has not named (owner decision D62).
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "setup_ai_off",
                    "The setup helper is off",
                    "Reading a list for you is not switched on for this business. Type it or import a file instead.");
        }
    }

    @Override
    public <T> Result<T> draft(Request request, Reader<T> reader) {
        requireOn();
        Principal principal = CurrentPrincipal.require();
        boolean typed = request.text() != null && !request.text().isBlank();
        if (typed == (request.upload() != null)) {
            throw ApiException.rule("setup_list_missing", "Type your list, or choose one photo or file.");
        }
        if (typed && request.text().strip().length() > properties.maxTextChars()) {
            throw tooLong();
        }
        Instant start = clock.now();
        Instant dayStart = LocalDate.ofInstant(start, tenant.profile().timezone())
                .atStartOfDay(tenant.profile().timezone())
                .toInstant();
        // The limits are taken before the file is decoded, so a person past their hour costs the server nothing.
        SetupStore.Start started = store.start(
                request.kind(),
                typed ? "text" : ListFiles.source(request.upload()),
                principal.userId(),
                start,
                start.minus(Duration.ofHours(1)),
                dayStart,
                properties.userHourlyDrafts(),
                properties.dailyDrafts(),
                ask.costCurrency());
        if (started.limit() == SetupStore.Limit.PERSON) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "setup_ai_rate_limited",
                    "Too many lists",
                    "You have sent " + properties.userHourlyDrafts()
                            + " lists in the last hour. Try again later, or type it or import a file instead.");
        }
        if (started.limit() == SetupStore.Limit.BUSINESS) {
            return Result.none("daily_cap");
        }
        Tally tally = new Tally(started.id(), start);
        try {
            return drafted(request, reader, tally);
        } finally {
            // Whatever went wrong after the row was written (a refused file, a failure of ours), it never stays
            // started,
            // so it cannot hold a place in the day's cap.
            tally.finishIfOpen();
        }
    }

    private <T> Result<T> drafted(Request request, Reader<T> reader, Tally tally) {
        ListFiles.Read given = given(request);
        if (given.pictureDataUrl() == null
                && (given.text() == null || given.text().isBlank())) {
            tally.finish("unreadable", null, 0);
            return Result.none("unreadable");
        }
        AskProviders chain = given.pictureDataUrl() == null ? providers.text() : providers.photos();
        if (chain.chain().isEmpty()) {
            tally.finish("no_provider", null, 0);
            return Result.none("no_provider");
        }
        LlmClient.Request prompt = prompt(request, given);
        long estimatedInput =
                Math.max(1, (prompt.characters() + 2L) / 3) + (given.pictureDataUrl() == null ? 0 : PICTURE_TOKENS);
        for (AskProviders.Provider provider : chain.chain()) {
            long reserved = provider.config().costMicro(estimatedInput, prompt.maxTokens());
            Optional<UUID> held = spend.reserve(
                    provider.name(),
                    provider.client().model(),
                    clock.now(),
                    reserved,
                    ask.costCurrency(),
                    "deepseek".equals(provider.name()) ? ask.deepseekMonthlyCostMicro() : null,
                    monthStart());
            if (held.isEmpty()) {
                tally.failures.add(provider.name() + ":spend_cap");
                continue;
            }
            LlmClient.Result result;
            try {
                result = provider.client().complete(prompt);
            } catch (LlmException e) {
                AskStore.Settled settled = spend.settleFailed(held.get(), clock.now(), provider.config(), e, reserved);
                tally.add(settled.inputTokens(), settled.outputTokens(), settled.costMicro());
                tally.failures.add(provider.name() + ":" + e.kind().reason());
                log.info(
                        "setup helper provider failed provider={} reason={}",
                        provider.name(),
                        e.kind().reason());
                continue;
            } catch (RuntimeException e) {
                // A bug of ours in the adapter; the type only, since a message can quote data. It may have been billed.
                tally.add(0, 0, reserved);
                tally.failures.add(provider.name() + ":internal_error");
                log.error(
                        "setup helper provider failed provider={} error={}",
                        provider.name(),
                        e.getClass().getSimpleName());
                continue;
            }
            if (result.inputTokens() < 0 || result.outputTokens() < 0) {
                spend.settle(held.get(), clock.now(), 0, 0, reserved);
                tally.add(0, 0, reserved);
            } else {
                long cost = provider.config().costMicro(result.inputTokens(), result.outputTokens());
                spend.settle(held.get(), clock.now(), result.inputTokens(), result.outputTokens(), cost);
                tally.add(result.inputTokens(), result.outputTokens(), cost);
            }
            Optional<T> draft;
            try {
                draft = result.text() == null ? Optional.empty() : reader.read(result.text());
            } catch (RuntimeException e) {
                draft = Optional.empty();
            }
            if (draft.isEmpty()) {
                tally.failures.add(provider.name() + ":bad_answer");
                continue;
            }
            tally.finish("drafted", provider, reader.rows(draft.get()));
            return new Result<>(draft.get(), provider.name(), null);
        }
        tally.finish("all_failed", null, 0);
        return Result.none("all_failed");
    }

    /** What the owner gave, exactly one of typed text and a file (checked already), read into text or a picture. */
    private ListFiles.Read given(Request request) {
        boolean typed = request.text() != null && !request.text().isBlank();
        ListFiles.Read read =
                typed ? new ListFiles.Read(request.text().strip(), null, "text") : ListFiles.read(request.upload());
        if (read.text() != null && read.text().length() > properties.maxTextChars()) {
            throw tooLong();
        }
        return read;
    }

    private static ApiException tooLong() {
        return ApiException.rule(
                "setup_list_too_long",
                "This list is longer than the app reads at once. Split it in two, or import it as a file.");
    }

    private LlmClient.Request prompt(Request request, ListFiles.Read given) {
        String context = request.context() == null || request.context().isBlank()
                ? ""
                : "What the business has already:\n" + request.context().strip() + "\n\n";
        LlmClient.Message user = given.pictureDataUrl() == null
                ? new LlmClient.Message("user", context + "The owner's list:\n" + given.text())
                : new LlmClient.Message(
                        "user", context + "The owner's list is in this picture.", given.pictureDataUrl());
        return new LlmClient.Request(
                List.of(new LlmClient.Message("system", request.instructions()), user), properties.maxTokens(), true);
    }

    private Instant monthStart() {
        return YearMonth.from(clock.now().atZone(ZoneOffset.UTC))
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }

    /** One draft's log row, filled in as it goes along. */
    private final class Tally {
        private final UUID id;
        private final Instant start;
        private final List<String> failures = new ArrayList<>();
        private int inputTokens;
        private int outputTokens;
        private long costMicro;
        private boolean finished;

        Tally(UUID id, Instant start) {
            this.id = id;
            this.start = start;
        }

        void add(int in, int out, long cost) {
            inputTokens += Math.max(0, in);
            outputTokens += Math.max(0, out);
            costMicro += cost;
        }

        /** A draft stopped by a refused file or a failure of ours is finished as one that could not be read or failed. */
        void finishIfOpen() {
            if (!finished) {
                finish(costMicro > 0 || !failures.isEmpty() ? "all_failed" : "unreadable", null, 0);
            }
        }

        void finish(String outcome, AskProviders.Provider provider, int rows) {
            finished = true;
            store.finish(
                    id,
                    new SetupStore.Finish(
                            outcome,
                            provider == null ? null : provider.name(),
                            provider == null ? null : provider.client().model(),
                            List.copyOf(failures),
                            rows,
                            inputTokens,
                            outputTokens,
                            costMicro,
                            Math.max(0, clock.now().toEpochMilli() - start.toEpochMilli())));
        }
    }
}
