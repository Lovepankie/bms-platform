package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.payments.GatewayException;
import com.rincoltech.bms.core.payments.PaymentCollections;
import com.rincoltech.bms.core.payments.PaymentGateway;
import com.rincoltech.bms.core.payments.PaymentGateway.Scope;
import com.rincoltech.bms.core.payments.PurposeHandler;
import com.rincoltech.bms.core.payments.PurposeHandler.PurposeRefusal;
import com.rincoltech.bms.core.payments.PurposeHandler.Subject;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Masking;
import com.rincoltech.bms.kernel.PhoneNumbers;
import com.rincoltech.bms.kernel.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starting, reading and cancelling a collection (FR-PAY-01, spec 3.2, 3.5, 3.7). Creation is two steps on purpose, as the
 * spec says (3.2): the first transaction validates, writes the intent {@code created} and commits, with nothing
 * external done; then the order is asked for with no transaction open, within a 15 second budget, by
 * {@link OrderCreator}. If that does not finish (the provider is slow, the breaker is open) the intent stays
 * {@code created} and the poller finishes it, so a request that dies at any point leaves a row, never a half state.
 *
 * <p>An intent is accepted only while collections are on for the tenant, the host has the credentials, and the tenant is
 * the one the merchant account is bound to (checked again here, so a settings value written around the API changes
 * nothing). The amount is whole currency units and positive, the payer's phone is a strict Ugandan mobile number, the
 * purpose's handler says whether the subject can take the money and which branch it belongs to, the caller must hold
 * {@code core.payments.collect} in that branch, and the tenant's daily ceiling, counted over the tenant's own day under an
 * advisory lock so two requests together cannot both fit into the last of it, is not exceeded.
 */
@Service
class IntentService implements PaymentCollections {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    /** The budget for making the order inside the request. */
    static final Duration INLINE_BUDGET = Duration.ofSeconds(15);

    private final IntentStore store;
    private final IntentAudit audit;
    private final PurposeRegistry registry;
    private final PaymentGateway gateway;
    private final PesapalSettings pesapal;
    private final PaymentsSettingsService settings;
    private final OrderCreator creator;
    private final Idempotency idempotency;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate read;

    IntentService(
            IntentStore store,
            IntentAudit audit,
            PurposeRegistry registry,
            PaymentGateway gateway,
            PesapalSettings pesapal,
            PaymentsSettingsService settings,
            OrderCreator creator,
            Idempotency idempotency,
            CurrentTenant tenant,
            BusinessClock clock,
            JdbcClient jdbc,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.audit = audit;
        this.registry = registry;
        this.gateway = gateway;
        this.pesapal = pesapal;
        this.settings = settings;
        this.creator = creator;
        this.idempotency = idempotency;
        this.tenant = tenant;
        this.clock = clock;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
    }

    @Override
    public IntentView create(CreateIntent request, String idempotencyKey) {
        Principal principal = CurrentPrincipal.require();
        IntentView made = tx.execute(status -> createLocked(request, idempotencyKey, principal));
        if ("created".equals(made.status())) {
            creator.create(made.id(), INLINE_BUDGET);
        }
        // Always the row as it stands now: a replayed key returns the first answer's identity, not its first state.
        return read.execute(status -> view(store.find(made.id()).orElseThrow()));
    }

    private IntentView createLocked(CreateIntent request, String key, Principal principal) {
        CurrentTenant.Profile profile = tenant.profile();
        PaymentsSettingsService.Effective effective = settings.effective();
        if (!effective.enabled()
                || !gateway.available(Scope.TENANT)
                || !profile.id().equals(pesapal.boundTenant())) {
            throw ApiException.rule("collections_disabled", "Collecting by mobile money or card is not switched on.");
        }
        return idempotency
                .once(
                        key,
                        "POST",
                        "/api/v1/payments/intents",
                        request,
                        IntentView.class,
                        () -> insert(request, key, principal, profile, effective))
                .body();
    }

    private IntentView insert(
            CreateIntent request,
            String key,
            Principal principal,
            CurrentTenant.Profile profile,
            PaymentsSettingsService.Effective effective) {
        List<FieldProblem> problems = new ArrayList<>();
        PurposeHandler handler = request.purpose() == null
                ? null
                : registry.find(request.purpose()).orElse(null);
        if (handler == null) {
            throw ApiException.rule("unknown_purpose", "No such payment purpose.");
        }
        if (request.subjectId() == null) {
            problems.add(new FieldProblem("subject_id", "required", "Say what is being paid."));
        }
        if (request.amountMinor() < 1) {
            problems.add(new FieldProblem("amount_minor", "invalid", "The amount must be at least one currency unit."));
        }
        String phone = PhoneNumbers.forSms(request.payerPhone()).orElse(null);
        if (phone == null) {
            problems.add(new FieldProblem("payer_phone", "invalid_phone", "Use a mobile number such as 0700000000."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        String currency = profile.currency();
        if (!MinorUnits.supported(currency)) {
            throw ApiException.rule("currency_not_supported", "Collections are offered in Uganda shillings only.");
        }
        Subject subject;
        try {
            subject = handler.describe(request.subjectId(), request.amountMinor(), currency);
        } catch (PurposeRefusal refusal) {
            throw ApiException.rule(refusal.code(), "This payment cannot be taken for that account.");
        }
        if (!principal.may("core.payments.collect", subject.branchId())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "You do not have permission to collect payments for this branch.");
        }
        Instant now = clock.now();
        if (effective.dailyCeilingMinor() != null) {
            // One request at a time per tenant through the sum below, so two together cannot both fit into the last of
            // it.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                    .param("payments:ceiling:" + profile.id())
                    .query((rs, n) -> 0)
                    .list();
            ZoneId zone = profile.timezone();
            LocalDate today = clock.today(zone);
            long asked = store.askedBetween(
                    today.atStartOfDay(zone).toInstant(),
                    today.plusDays(1).atStartOfDay(zone).toInstant());
            if (asked + request.amountMinor() > effective.dailyCeilingMinor()) {
                throw ApiException.rule("daily_ceiling_exceeded", "The daily limit for collections would be exceeded.");
            }
        }
        UUID id = UUID.randomUUID();
        Intent intent = Intent.created(
                id,
                profile.id(),
                now,
                subject.branchId(),
                request.purpose(),
                subject.subjectType(),
                request.subjectId(),
                phone,
                request.amountMinor(),
                currency,
                Scope.TENANT.key(),
                MerchantReference.of(id, pesapal.environment()),
                principal.userId(),
                now.plus(CheckSchedule.OFFER));
        store.insert(intent, key);
        audit.created(intent, request.subjectId());
        log.info("payment intent {} created", id);
        return view(intent);
    }

    @Override
    public IntentView get(UUID intentId) {
        Principal principal = CurrentPrincipal.require();
        return read.execute(status -> {
            Intent intent =
                    store.find(intentId).filter(i -> visible(principal, i)).orElseThrow(ApiException::notFound);
            return view(intent);
        });
    }

    private static boolean visible(Principal principal, Intent intent) {
        return principal.userId().equals(intent.initiatedBy())
                || principal.may("core.payments.read", intent.branchId());
    }

    @Override
    public IntentView cancel(UUID intentId) {
        Principal principal = CurrentPrincipal.require();
        Intent intent = read.execute(status -> store.find(intentId)
                .filter(i -> principal.userId().equals(i.initiatedBy())
                        || principal.may("core.payments.collect", i.branchId()))
                .orElseThrow(ApiException::notFound));
        if (!Set.of("created", "pending", "expired").contains(intent.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "not_cancellable",
                    "Cannot cancel",
                    "This payment can no longer be cancelled.");
        }
        if (intent.providerReference() != null) {
            try {
                gateway.cancelOrder(Scope.ofKey(intent.scope()), intent.providerReference());
            } catch (GatewayException e) {
                // The provider refuses once the payer has paid or the order was already cancelled: do not guess.
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "cancel_failed",
                        "Could not cancel",
                        "The payment could not be cancelled; it may have been paid. Check its status.");
            }
        }
        return tx.execute(status -> {
            Instant now = clock.now();
            Intent locked = store.lock(intentId).orElseThrow(ApiException::notFound);
            Map<String, Object> cols = new HashMap<>();
            cols.put("failure_reason", "cancelled_by_staff");
            cols.put("next_check_at", null);
            cols.put("creation_lease_until", null);
            if (!store.update(intentId, locked.status(), "cancelled", now, cols)
                    || !Set.of("created", "pending", "expired").contains(locked.status())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "not_cancellable",
                        "Cannot cancel",
                        "This payment can no longer be cancelled.");
            }
            audit.user(locked, "cancelled", "cancelled", Map.of("reason", "cancelled_by_staff"));
            return view(store.find(intentId).orElseThrow());
        });
    }

    static IntentView view(Intent i) {
        List<String> flags = new ArrayList<>();
        if (i.paidAmountMinor() != null && i.paidAmountMinor() < i.amountMinor()) {
            flags.add("paid_less");
        }
        if (i.paidAmountMinor() != null && i.paidAmountMinor() > i.amountMinor()) {
            flags.add("paid_more");
        }
        if (i.paidAt() != null && i.paidAt().isAfter(i.expiresAt())) {
            flags.add("late");
        }
        return new IntentView(
                i.id(),
                i.purpose(),
                i.subjectType(),
                i.subjectId(),
                i.amountMinor(),
                i.currency(),
                i.status(),
                i.merchantReference(),
                i.redirectUrl(),
                Masking.lastFour(i.payerPhone()),
                i.createdAt(),
                i.expiresAt(),
                i.paidAmountMinor(),
                i.paidAt(),
                flags,
                i.failureReason(),
                i.refusalCode(),
                i.bookedTxnId());
    }
}
