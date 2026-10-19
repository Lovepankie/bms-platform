package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.PaymentGateway.Scope;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The path token of a notification url selects the account the notification belongs to (ADR-035 decision 10 and the
 * amendment of 2026-10-18). The platform's token is compared in constant time against the environment; a lender's is
 * looked up by its SHA-256 in the database through a definer function (the tenant is not known until it is found), so no
 * token is ever stored in the clear and a database reader cannot rebuild a url. Tokens are 32 random bytes, generated
 * here, so a lender's token can never equal the platform's.
 */
@Component
class AccountTokens {

    private static final Pattern SHAPE = Pattern.compile("^[A-Za-z0-9_-]{24,128}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Which account a token selects: the platform's, or one tenant's. */
    record Selected(Scope scope, UUID tenantId) {}

    private final PesapalSettings settings;
    private final JdbcClient jdbc;

    AccountTokens(PesapalSettings settings, JdbcClient jdbc) {
        this.settings = settings;
        this.jdbc = jdbc;
    }

    /** A new random path token (43 characters of the URL-safe alphabet). */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The digest stored for a token. */
    static String digest(String token) {
        return HexFormat.of().formatHex(PesapalSettings.sha256(token));
    }

    /** Empty when the token selects no account: nothing is read from the request but the token. */
    Optional<Selected> resolve(String presented) {
        if (settings.isPlatformToken(presented)) {
            return Optional.of(new Selected(Scope.PLATFORM, null));
        }
        if (presented == null || !SHAPE.matcher(presented).matches()) {
            return Optional.empty();
        }
        return jdbc.sql(
                        "SELECT tenant_id FROM app_resolve_payment_account(?, ?) WHERE tenant_status IN ('active', 'suspended')")
                .params(AccountStore.PROVIDER, digest(presented))
                .query((rs, n) -> new Selected(Scope.TENANT, rs.getObject("tenant_id", UUID.class)))
                .optional();
    }
}
