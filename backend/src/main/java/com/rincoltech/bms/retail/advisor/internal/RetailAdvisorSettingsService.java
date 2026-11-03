package com.rincoltech.bms.retail.advisor.internal;

import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.core.ai.AiSettings.Status;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.advisor.internal.RetailAdvisorApi.Acknowledgement;
import com.rincoltech.bms.retail.advisor.internal.RetailAdvisorApi.SettingsView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The AI assistant's settings for a shop or wholesale business (issue #422) over {@link AiSettings}: the same tenant settings
 * group {@code ai}, so one switch, one acknowledgement record and one pair of daily caps serve the lending and the retail brief
 * alike. Reading and the caps need {@code retail.advisor.configure}; switching on or off needs {@code core.settings.manage} as
 * well, and switching on needs the acknowledgement of the text shown, stored with the user, the time and the text version.
 */
@Service
class RetailAdvisorSettingsService {

    static final String ACK_TEXT_VERSION = "retail-advisor-1";

    static final String ACK_TEXT =
            "Switching on the AI assistant sends a summary of your shop figures to a language model run by an outside"
                    + " provider, in a country outside Uganda. What is sent is totals and counts, with items shown as Item A"
                    + " and Item B: never the name or phone number of a buyer or a staff member, and never a sale number."
                    + " The assistant only advises. It cannot change or send anything, and every text it adds is labelled and"
                    + " written by our system from a reviewed list of actions, so check it before you act. The platform keeps"
                    + " a log of each call that holds counts and cost, never the text. Daily limits apply, and when the"
                    + " provider is down or a limit is reached the automatic summary is shown instead.";

    static final String ACK_RECORD = ACK_TEXT_VERSION + "+" + digest(ACK_TEXT);

    static final String MANAGE = "core.settings.manage";

    record Change(Boolean enabled, String acknowledgedTextVersion, Long dailyTokenCap, Long dailyCostCapMicro) {}

    private final AiSettings settings;

    RetailAdvisorSettingsService(AiSettings settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    SettingsView view() {
        return view(settings.status());
    }

    @Transactional
    SettingsView update(String ifMatch, Change change) {
        int expected = Versions.fromIfMatch(ifMatch);
        Status before = settings.status();
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        if (change.enabled() != null && !CurrentPrincipal.require().hasPermission(MANAGE)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "You do not have permission to perform this action.");
        }
        if (change.acknowledgedTextVersion() != null && !Boolean.TRUE.equals(change.enabled())) {
            throw ApiException.rule(
                    "acknowledgement_only_when_switching_on", "The acknowledgement is taken only when switching on.");
        }
        int version = expected;
        if (Boolean.TRUE.equals(change.enabled()) && !before.enabled()) {
            if (!ACK_TEXT_VERSION.equals(change.acknowledgedTextVersion())) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "acknowledgement_required",
                        "Acknowledgement required",
                        "Switching the assistant on needs the acknowledgement of the text that was shown.");
            }
            version = settings.enable(ACK_RECORD, version).version();
        } else if (Boolean.FALSE.equals(change.enabled()) && before.enabled()) {
            version = settings.disable(version).version();
        }
        if (change.dailyTokenCap() != null || change.dailyCostCapMicro() != null) {
            version = settings.setCaps(change.dailyTokenCap(), change.dailyCostCapMicro(), version)
                    .version();
        }
        return view(settings.status());
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }

    private static SettingsView view(Status s) {
        return new SettingsView(
                s.enabled(),
                s.allowed(),
                s.providerConfigured(),
                s.dailyTokenCap(),
                s.dailyCostCapMicro(),
                s.costCurrency(),
                s.tokensUsedToday(),
                s.costUsedTodayMicro(),
                s.acknowledgedBy(),
                s.acknowledgedAt(),
                s.acknowledgedTextVersion(),
                new Acknowledgement(ACK_TEXT_VERSION, ACK_TEXT),
                s.version());
    }
}
