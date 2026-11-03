package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.notifications.SmsEvents;
import com.rincoltech.bms.core.notifications.SmsOptOuts;
import com.rincoltech.bms.core.payments.PurposeHandler;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the payment link of an open intent to the payer's number by SMS (issue #231, spec 4.13), from the collection
 * screen. The text goes through the SMS port the money events use ({@link SmsEvents}), category transactional, so the
 * channel applies the operator's and the tenant's switches, the opt-out, the caps and the provider mode on every attempt.
 *
 * <p>The rules, in the order they are checked:
 *
 * <ul>
 *   <li>The caller holds {@code core.payments.collect} in the intent's branch; anyone else is told it does not exist.
 *   <li>Collecting by mobile money or card is on for the business ({@code collections_disabled} otherwise) and SMS is
 *       on, both switches ({@code sms_off} otherwise): a plain refusal, nothing written.
 *   <li>Once per intent: a second send for the same intent writes nothing and answers {@code already_sent} with where
 *       the earlier text stands ({@code text_status}: queued, sent, failed or expired, issue #772). The outbox key is per
 *       intent and member, so even a race writes one row. A text still waiting when its payment closes is withdrawn
 *       ({@link IntentStore#update}).
 *   <li>Only while the intent is open: {@code pending} with a link and before its expiry; the text expires with the
 *       intent, so a link that has lapsed is never sent ({@code intent_not_open}).
 *   <li>A member who asked for no SMS at all is not texted ({@code member_opted_out}).
 *   <li>At most {@value #PER_MEMBER_PER_HOUR} link texts per member in an hour ({@code too_many_link_texts}) and
 *       {@value #PER_BUSINESS_PER_DAY} per business in 24 hours ({@code link_text_daily_limit}), so a user who types
 *       one number on many payments cannot spend the business's SMS credit on it. Both are counted from the audit rows
 *       through the index {@code audit_log_by_time (tenant_id, created_at)}, under one lock per business so two
 *       requests together cannot both pass.
 *   <li>A text the channel would not queue (a switch turned off in between, a cap reached) is refused
 *       ({@code link_text_not_queued}), never reported as sent.
 *   <li>Each text sent is audited ({@code core.payments.intent.link_texted}) with the member and never the number.
 * </ul>
 */
@Service
class PaymentLinkTexts {

    static final String TEMPLATE = "payment.link";

    static final String ACTION = "core.payments.intent.link_texted";

    static final int PER_MEMBER_PER_HOUR = 3;

    static final int PER_BUSINESS_PER_DAY = 200;

    /**
     * The longest business name the text carries (issue #772): with the provider's address of about 113 characters, a
     * large amount and a savings account reference, the text stays within two GSM-7 segments.
     */
    static final int NAME_MAX = 40;

    /** How long a transactional text waits in the outbox at most, as the SMS channel sets it (spec 4.6). */
    static final Duration TEXT_LIFETIME = Duration.ofHours(1);

    /**
     * What the screen is told. {@code alreadySent}: this intent's link had been texted before and nothing new went.
     * {@code textStatus}: where that text stands, read from the outbox (issue #772), never assumed.
     * JUSTIFICATION-A3: the answer gains one field and its two constants; no new mechanism.
     */
    @Schema(name = "PaymentLinkText")
    record LinkText(
            UUID intentId,
            boolean alreadySent,

            @Schema(allowableValues = {"queued", "sent", "failed", "unconfirmed", "expired"})
            String textStatus,

            Instant expiresAt) {}

    private final IntentStore store;
    private final IntentAudit audit;
    private final PurposeRegistry registry;
    private final PaymentsSettingsService settings;
    private final PesapalSettings pesapal;
    private final SmsEvents sms;
    private final SmsOptOuts optOuts;
    private final BusinessClock clock;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    PaymentLinkTexts(
            IntentStore store,
            IntentAudit audit,
            PurposeRegistry registry,
            PaymentsSettingsService settings,
            PesapalSettings pesapal,
            SmsEvents sms,
            SmsOptOuts optOuts,
            BusinessClock clock,
            JdbcClient jdbc,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.audit = audit;
        this.registry = registry;
        this.settings = settings;
        this.pesapal = pesapal;
        this.sms = sms;
        this.optOuts = optOuts;
        this.clock = clock;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
    }

    LinkText send(UUID intentId) {
        Principal principal = CurrentPrincipal.require();
        return tx.execute(status -> sendLocked(intentId, principal));
    }

    private LinkText sendLocked(UUID intentId, Principal principal) {
        Intent intent = store.find(intentId)
                .filter(i -> principal.may("core.payments.collect", i.branchId()))
                .orElseThrow(ApiException::notFound);
        if (!settings.effective().enabled() || !pesapal.tenantAccountsEnabled()) {
            throw ApiException.rule(
                    "collections_disabled",
                    "Collecting by mobile money or card is not switched on for this business, so there is no link to send.");
        }
        if (!sms.active()) {
            throw ApiException.rule(
                    "sms_off",
                    "Text messages are not switched on for this business. Copy the link or show the QR code instead.");
        }
        PurposeHandler handler = registry.find(intent.purpose())
                .orElseThrow(() -> ApiException.rule("unknown_purpose", "No such payment purpose."));
        UUID member = handler.memberOf(intent.subjectId())
                .orElseThrow(() -> ApiException.rule(
                        "link_text_not_offered", "A link cannot be sent by text message for this kind of payment."));
        // One link text at a time per business through the checks and the counts below.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param("payments:link-text:" + intent.tenantId())
                .query((rs, n) -> 0)
                .list();
        Optional<Texted> texted = texted(intentId);
        if (texted.isPresent()) {
            return new LinkText(intentId, true, textStatus(intent, texted.get()), intent.expiresAt());
        }
        // Read again under the intent's row lock: a payment confirmed since the first read is not texted.
        intent = store.lock(intentId).orElseThrow(ApiException::notFound);
        Instant now = clock.now();
        if (!"pending".equals(intent.status())
                || intent.redirectUrl() == null
                || !intent.expiresAt().isAfter(now)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "intent_not_open",
                    "Payment link closed",
                    "This payment link has expired or the payment has ended. Make a new payment link to send one.");
        }
        if (optOuts.status(member)
                .filter(o -> o.scope() == SmsOptOuts.Scope.ALL)
                .isPresent()) {
            throw ApiException.rule(
                    "member_opted_out",
                    "This member asked not to get text messages. Copy the link or show the QR code instead.");
        }
        if (texted("24 hours", null) >= PER_BUSINESS_PER_DAY) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "link_text_daily_limit",
                    "Too many texts today",
                    "This business sent " + PER_BUSINESS_PER_DAY
                            + " payment links by text message in the last day. Copy the link or show the QR code instead.");
        }
        if (texted("1 hour", member) >= PER_MEMBER_PER_HOUR) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "too_many_link_texts",
                    "Too many texts",
                    "This member was sent " + PER_MEMBER_PER_HOUR
                            + " payment links in the last hour. Wait a while, or copy the link instead.");
        }
        Map<String, String> params = new LinkedHashMap<>();
        // The name with no dash and bounded, so the words carry no dash and the text stays within two segments.
        params.put("tenant_name", sms.plainTenantName(NAME_MAX));
        params.put("currency", intent.currency());
        params.put("amount", sms.amount(intent.amountMinor(), intent.currency()));
        // No dashes in the text: a loan number such as LN-HQ-000123 is written LN HQ 000123.
        params.put(
                "reference",
                handler.label(intent.subjectId()).orElse("your payment").replace('-', ' '));
        params.put("link", intent.redirectUrl());
        SmsEvents.Queued queued = sms.queueTracked(new SmsEvents.MemberSms(
                TEMPLATE,
                member,
                intent.payerPhone(),
                params,
                intentId.toString(),
                Outbox.TRANSACTIONAL,
                null,
                intent.expiresAt()));
        switch (queued.result()) {
            case QUEUED -> {}
            // Not "already sent": the outbox row and the audit row are written in one transaction and the audit row was
            // looked for above, under the member's lock, so no row exists for this intent. The channel refused it (a
            // switch turned off this instant, a cap reached): say so, so staff never think a text went that did not.
            case NOT_QUEUED ->
                throw ApiException.rule(
                        "link_text_not_queued",
                        "The text message was not sent: text messages are off for this business or its limit for today"
                                + " is reached. Copy the link or show the QR code instead.");
            case NO_PHONE ->
                throw ApiException.rule(
                        "payer_phone_not_textable", "The payer's number on this payment cannot take a text message.");
            default ->
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "link_text_failed",
                        "Could not send",
                        "The text message could not be queued just now. Nothing was sent. Try again in a moment.");
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("member_id", member.toString());
        extra.put("template", TEMPLATE);
        extra.put("expires_at", intent.expiresAt().toString());
        // The outbox row's id, so a later press reads where this text stands (issue #772): an internal id, not the
        // number.
        if (queued.rowId() != null) {
            extra.put("outbox_id", queued.rowId().toString());
        }
        audit.user(intent, "link_texted", intent.status(), extra);
        return new LinkText(intentId, false, "queued", intent.expiresAt());
    }

    /** That an intent's link was texted: whether the text's hour has run out, and its outbox row (null before #772). */
    record Texted(boolean hourOver, UUID outboxId) {}

    /**
     * Whether this intent's link was texted, from its audit row: empty when it never was. The hour is read on the
     * database's clock, the one the audit row is stamped with.
     */
    private Optional<Texted> texted(UUID intentId) {
        return jdbc.sql("""
                        SELECT created_at <= now() - CAST(? AS interval) AS hour_over,
                               data -> 'after' ->> 'outbox_id' AS outbox_id
                        FROM audit_log
                        WHERE entity_type = 'payment_intent' AND entity_id = ? AND action = ?
                        ORDER BY created_at LIMIT 1
                        """)
                .params(TEXT_LIFETIME.toMinutes() + " minutes", intentId, ACTION)
                .query((rs, n) -> new Texted(
                        rs.getBoolean("hour_over"),
                        rs.getString("outbox_id") == null ? null : UUID.fromString(rs.getString("outbox_id"))))
                .optional();
    }

    /**
     * Where the earlier text stands (issue #772). One that has not reached the provider is queued until its time runs out
     * (the intent's expiry or the transactional hour) or its payment closes, and expired after.
     */
    private String textStatus(Intent intent, Texted texted) {
        boolean hourOver = texted.hourOver();
        return switch (sms.status(texted.outboxId())) {
            case PENDING -> "queued";
            case SENT -> "sent";
            case FAILED -> "failed";
            case UNCONFIRMED -> "unconfirmed";
            // A known row no longer waiting that never reached the provider will never go: expired. Only a text queued
            // before this release (no outbox id in its audit row, so its row cannot be read) is "queued" while its time
            // lasts, so staff are not led to cancel a payment whose text may yet go.
            case NOT_SENT ->
                texted.outboxId() != null
                                || hourOver
                                || !clock.now().isBefore(intent.expiresAt())
                                || !"pending".equals(intent.status())
                        ? "expired"
                        : "queued";
        };
    }

    /**
     * Link texts of the bound business in the last {@code window} ({@code 1 hour} or {@code 24 hours}, constants of this
     * class), for one member or all. The tenant and the time come first, so the index {@code audit_log_by_time} bounds
     * the rows read to that window of one business; on the database's clock, the one the audit rows are stamped with.
     */
    private long texted(String window, UUID member) {
        return jdbc.sql("""
                        SELECT count(*) FROM audit_log
                        WHERE tenant_id = current_setting('app.tenant_id')::uuid
                          AND created_at > now() - CAST(? AS interval)
                          AND entity_type = 'payment_intent' AND action = ?
                          AND (CAST(? AS text) IS NULL OR data -> 'after' ->> 'member_id' = ?)
                        """)
                .params(
                        window,
                        ACTION,
                        member == null ? null : member.toString(),
                        member == null ? null : member.toString())
                .query(Long.class)
                .single();
    }
}
