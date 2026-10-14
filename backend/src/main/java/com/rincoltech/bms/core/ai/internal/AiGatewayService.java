package com.rincoltech.bms.core.ai.internal;

import com.rincoltech.bms.core.ai.AiGateway;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.LlmException;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The gateway (ADR-037): the order of refusals is the order of cost. The tenant switch, the platform
 * allow-list and the provider being wired are checked first and leave no row (a feature that is off
 * is not an event). Then the input screen refuses text that looks like personal data. Then one
 * reservation transaction applies the per call cap and the daily token and cost caps of the tenant and
 * of the platform and writes the call row; a refusal there is a logged fallback and the provider is never
 * reached. Then the circuit breaker; then the network call with no database connection held; then the row
 * is finished with what the provider reported. Whatever the provider says is reduced to a closed reason and
 * a status number: no text from it, and none from the caller, is stored or logged.
 *
 * <p>Two kinds of failure are kept apart. The provider's (a timeout, a 5xx, a rejected key) are fallbacks
 * that feed the breaker. Ours (the database, a bug) are a fallback {@code internal_error} that never feeds
 * it, and a failure to write the log after the provider answered does not throw away the answer. A caller
 * error, a blank purpose or a call made inside a transaction, is an exception: it is a bug, not a state.
 */
@Service
class AiGatewayService implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayService.class);
    private static final Pattern PURPOSE = Pattern.compile("^[a-z][a-z_]{1,39}$");

    private final LlmClient client;
    private final AiProperties properties;
    private final AiSettingsService settings;
    private final AiAllowList allowList;
    private final AiCallLog callLog;
    private final CircuitBreaker breaker;
    private final BusinessClock clock;
    private final CurrentTenant tenant;

    AiGatewayService(
            LlmClient client,
            AiProperties properties,
            AiSettingsService settings,
            AiAllowList allowList,
            AiCallLog callLog,
            CircuitBreaker breaker,
            BusinessClock clock,
            CurrentTenant tenant) {
        this.client = client;
        this.properties = properties;
        this.settings = settings;
        this.allowList = allowList;
        this.callLog = callLog;
        this.breaker = breaker;
        this.clock = clock;
        this.tenant = tenant;
    }

    @Override
    public AiResult complete(String purpose, UUID askedBy, LlmClient.Request request) {
        if (purpose == null || !PURPOSE.matcher(purpose).matches()) {
            throw new IllegalArgumentException("purpose must be a lower case word");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The call logs in transactions of its own and waits up to 30 seconds on the network: inside the
            // caller's transaction it would hold that connection and its locks all that time and take a second
            // connection for the log. Run it from a request handler without @Transactional, or from a job
            // through TenantJobs.callAsTenant, never from forEachActiveTenant.
            throw new IllegalStateException("AiGateway.complete must be called outside a transaction");
        }
        try {
            return run(purpose, askedBy, request);
        } catch (RuntimeException e) {
            // Ours, not the provider's, so it never feeds the breaker; the type only, since a message can quote data.
            log.error(
                    "ai call failed internally purpose={} error={}",
                    purpose,
                    e.getClass().getSimpleName());
            return AiResult.fallback(Reason.INTERNAL_ERROR, null);
        }
    }

    private AiResult run(String purpose, UUID askedBy, LlmClient.Request request) {
        CurrentTenant.Profile profile = tenant.profile();
        AiSettingsService.Effective config = settings.effective();
        if (!config.enabled()) {
            return AiResult.fallback(Reason.NOT_ENABLED, null);
        }
        if (!allowList.allows(profile)) {
            return AiResult.fallback(Reason.NOT_ALLOWED, null);
        }
        if (!client.available()) {
            return AiResult.fallback(Reason.PROVIDER_OFF, null);
        }

        Instant start = clock.now();
        // A max_tokens on every request, never above the platform's per call cap (spec 5.7).
        int maxTokens = Math.min(request.maxTokens(), properties.maxTokensPerCall());
        LlmClient.Request sent = new LlmClient.Request(request.messages(), maxTokens, request.json());
        UUID id = UUID.randomUUID();
        AiCallLog.Request logged = new AiCallLog.Request(
                id,
                purpose,
                askedBy,
                client.model(),
                hash(sent),
                estimateInputTokens(sent),
                maxTokens,
                properties.priceCurrency(),
                start,
                windows(start, profile.timezone()),
                new AiCallLog.Limits(
                        Math.min(config.dailyTokenCap(), properties.platformDailyTokens()),
                        Math.min(config.dailyCostCapMicro(), properties.platformDailyCostMicro()),
                        properties.platformDailyTokens(),
                        properties.platformDailyCostMicro(),
                        properties.maxInputTokensPerCall()),
                properties::costMicro);

        Optional<PromptScreen.Finding> found = PromptScreen.check(sent);
        if (found.isPresent()) {
            callLog.logRefusal(logged, Reason.INPUT_REFUSED);
            // The finding class is a closed word; the text is never logged.
            log.warn(
                    "ai call refused tenant={} purpose={} reason=input_refused finding={}",
                    profile.id(),
                    purpose,
                    found.get());
            return AiResult.fallback(Reason.INPUT_REFUSED, id);
        }

        AiCallLog.Reservation reservation = callLog.reserve(logged);
        if (reservation.refused() != null) {
            log.info(
                    "ai call refused tenant={} purpose={} reason={}",
                    profile.id(),
                    purpose,
                    reservation.refused().code());
            return AiResult.fallback(reservation.refused(), id);
        }

        Optional<CircuitBreaker.Permit> permit = breaker.tryAcquire();
        if (permit.isEmpty()) {
            quietly("refuse", () -> callLog.refuse(id, clock.now(), Reason.BREAKER_OPEN));
            log.info("ai call refused tenant={} purpose={} reason=breaker_open", profile.id(), purpose);
            return AiResult.fallback(Reason.BREAKER_OPEN, id);
        }
        return network(purpose, id, sent, permit.get(), start, profile.id());
    }

    /** The network call and its bookkeeping. Every path settles the permit; nothing here throws a provider problem. */
    private AiResult network(
            String purpose,
            UUID id,
            LlmClient.Request sent,
            CircuitBreaker.Permit permit,
            Instant start,
            UUID tenantId) {
        boolean settled = false;
        try {
            LlmClient.Result result;
            try {
                result = client.complete(sent);
            } catch (LlmException e) {
                if (e.kind().countsAgainstBreaker()) {
                    breaker.recordFailure(permit);
                } else {
                    breaker.release(permit);
                }
                settled = true;
                Instant end = clock.now();
                Reason reason = reasonOf(e);
                quietly(
                        "fail",
                        () -> callLog.fail(
                                id,
                                end,
                                reason,
                                e.httpStatus(),
                                e.attempts(),
                                millis(start, end),
                                e.reportedInputTokens(),
                                e.reportedOutputTokens(),
                                properties::costMicro));
                log.warn(
                        "ai call failed tenant={} purpose={} reason={} status={}",
                        tenantId,
                        purpose,
                        reason.code(),
                        e.httpStatus());
                return AiResult.fallback(reason, id);
            } catch (RuntimeException e) {
                // A bug of ours in the adapter says nothing about the provider's health, so like every failure of ours
                // it is an internal_error fallback that does not feed the breaker (the permit is given back). The
                // request
                // may have been sent, so the reservation stays. Only the type is logged: a message can quote data.
                breaker.release(permit);
                settled = true;
                Instant end = clock.now();
                quietly(
                        "fail",
                        () -> callLog.fail(
                                id,
                                end,
                                Reason.INTERNAL_ERROR,
                                0,
                                1,
                                millis(start, end),
                                -1,
                                -1,
                                properties::costMicro));
                log.error(
                        "ai call failed tenant={} purpose={} unexpected={}",
                        tenantId,
                        purpose,
                        e.getClass().getSimpleName());
                return AiResult.fallback(Reason.INTERNAL_ERROR, id);
            }

            // The provider answered: that is its verdict, whatever our own bookkeeping does next.
            breaker.recordSuccess(permit);
            settled = true;
            Instant end = clock.now();
            int in = Math.max(0, result.inputTokens());
            int out = Math.max(0, result.outputTokens());
            quietly(
                    "succeed",
                    () -> callLog.succeed(
                            id, end, result.attempts(), in, out, properties.costMicro(in, out), millis(start, end)));
            log.info(
                    "ai call ok tenant={} purpose={} tokens={} latencyMs={}",
                    tenantId,
                    purpose,
                    in + out,
                    millis(start, end));
            return new AiResult(result.text(), in, out, result.finishReason(), null, id);
        } catch (Error e) {
            // An Error (out of memory, a linkage error) is not ours to absorb: close the row if we can, and rethrow.
            quietly(
                    "fail",
                    () -> callLog.fail(id, clock.now(), Reason.INTERNAL_ERROR, 0, 1, 0, -1, -1, properties::costMicro));
            throw e;
        } finally {
            if (!settled) {
                // Whatever happened, a half open breaker's single probe slot is never left taken.
                breaker.release(permit);
            }
        }
    }

    /** Runs a log write whose failure must not change the answer: the row stays pending and housekeeping closes it. */
    private void quietly(String what, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.error("ai call log {} failed error={}", what, e.getClass().getSimpleName());
        }
    }

    private static long millis(Instant start, Instant end) {
        return Math.max(0, end.toEpochMilli() - start.toEpochMilli());
    }

    private static Reason reasonOf(LlmException e) {
        return switch (e.kind()) {
            case TIMEOUT -> Reason.TIMEOUT;
            case NETWORK -> Reason.NETWORK;
            case RATE_LIMITED -> Reason.RATE_LIMITED;
            case PROVIDER_ERROR -> Reason.PROVIDER_ERROR;
            case AUTH -> Reason.AUTH_ERROR;
            case BAD_RESPONSE -> Reason.BAD_RESPONSE;
            case BAD_REQUEST -> Reason.BAD_REQUEST;
            case UNAVAILABLE -> Reason.PROVIDER_OFF;
        };
    }

    /** Rough and on the high side (three characters a token), so a reservation is never short. */
    static long estimateInputTokens(LlmClient.Request request) {
        return Math.max(1, (request.characters() + 2L) / 3);
    }

    static AiCallLog.Windows windows(Instant now, ZoneId tenantZone) {
        Instant tenantFrom = dayStart(now, tenantZone);
        Instant platformFrom = dayStart(now, BusinessClock.DEFAULT_ZONE);
        return new AiCallLog.Windows(
                tenantFrom,
                dayEnd(tenantFrom, tenantZone),
                platformFrom,
                dayEnd(platformFrom, BusinessClock.DEFAULT_ZONE));
    }

    private static Instant dayStart(Instant now, ZoneId zone) {
        return LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant();
    }

    private static Instant dayEnd(Instant start, ZoneId zone) {
        return LocalDate.ofInstant(start, zone).plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** SHA-256 over the messages, length-prefixed so two different requests never hash alike. */
    static String hash(LlmClient.Request request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (LlmClient.Message m : request.messages()) {
                byte[] content = m.content().getBytes(StandardCharsets.UTF_8);
                digest.update((m.role() + ":" + content.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(content);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }
}
