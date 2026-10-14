package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.kernel.PhoneNumbers;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The SMS channel's resolved settings and the startup guard that makes a real send from the wrong
 * place impossible (ADR-036 decision 4, FR-NTF-12, FR-NTF-18, spec section 4.4). {@link #resolve}
 * throws {@link IllegalStateException} at start, whatever the mode, for each of these:
 *
 * <ul>
 *   <li>outside production (staging, a laptop, an unset {@code BMS_ENVIRONMENT}): any
 *       {@code BMS_SMS_AT_BASE_URL} other than the provider's sandbox host, and any
 *       {@code BMS_SMS_AT_USERNAME} other than the sandbox account, even with the mode {@code off}
 *       or {@code sink}, because an allow-list on the live URL is still a real send;
 *   <li>mode {@code live} anywhere but production;
 *   <li>production: mode {@code sink}, the recording fake provider, and (when it can send) the
 *       sandbox URL or account;
 *   <li>a provider name or a mode that is not one of the known ones, and an allow-list entry that is
 *       not a number we send to.
 * </ul>
 *
 * Messages name the variable and never print a value, a number or a key.
 */
record SmsSettings(
        String environment,
        Mode mode,
        Kind provider,
        Set<String> allowlist,
        int platformDailyCap,
        URI baseUrl,
        String username,
        String apiKey) {

    static final String SANDBOX_HOST = "api.sandbox.africastalking.com";
    static final String SANDBOX_USERNAME = "sandbox";
    static final String LIVE_HOST = "api.africastalking.com";
    static final int DEFAULT_PLATFORM_DAILY_CAP = 2000;

    enum Mode {
        OFF,
        SINK,
        ALLOWLIST,
        LIVE;

        /** Allowlist and live can reach a real phone; off and sink cannot. */
        boolean sending() {
            return this == ALLOWLIST || this == LIVE;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum Kind {
        NONE,
        AFRICASTALKING,
        FAKE
    }

    /** True when the provider can be reached: a username, a key and a base URL are all set. */
    boolean providerConfigured() {
        return provider == Kind.AFRICASTALKING
                ? present(username) && present(apiKey) && baseUrl != null
                : provider == Kind.FAKE;
    }

    static SmsSettings resolve(String environment, SmsProperties p) {
        String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        boolean production = env.equals("production");
        boolean staging = env.equals("staging");

        Mode mode = mode(p.mode(), staging);
        Kind kind = kind(p.provider());
        String username = trimmed(p.africasTalking().username());
        String rawBase = trimmed(p.africasTalking().baseUrl());
        String apiKey = trimmed(p.africasTalking().apiKey());

        boolean sandboxHost = rawBase != null && hostIsSandbox(rawBase);
        boolean sandboxUser = SANDBOX_USERNAME.equals(username);

        if (!production) {
            if (rawBase != null && !sandboxHost) {
                throw refuse(
                        "BMS_SMS_AT_BASE_URL must be the Africa's Talking sandbox host outside production, in every"
                                + " mode (BMS_ENVIRONMENT is not production)");
            }
            if (username != null && !sandboxUser) {
                throw refuse("BMS_SMS_AT_USERNAME must be the sandbox account outside production, in every mode"
                        + " (BMS_ENVIRONMENT is not production)");
            }
            if (mode == Mode.LIVE) {
                throw refuse("BMS_SMS_MODE=live is allowed only when BMS_ENVIRONMENT=production");
            }
        } else {
            if (mode == Mode.SINK) {
                throw refuse("BMS_SMS_MODE=sink is not allowed in production");
            }
            if (kind == Kind.FAKE) {
                throw refuse("BMS_SMS_PROVIDER=fake is not allowed in production");
            }
            if (mode.sending() && (sandboxHost || sandboxUser)) {
                throw refuse("production must not send through the Africa's Talking sandbox host or account");
            }
        }

        URI base = null;
        if (kind == Kind.AFRICASTALKING) {
            String chosen = rawBase != null ? rawBase : "https://" + (production ? LIVE_HOST : SANDBOX_HOST);
            try {
                base = URI.create(chosen.endsWith("/") ? chosen.substring(0, chosen.length() - 1) : chosen);
            } catch (IllegalArgumentException e) {
                throw refuse("BMS_SMS_AT_BASE_URL is not a URL");
            }
            if (!"https".equalsIgnoreCase(base.getScheme())
                    || base.getHost() == null
                    || base.getRawUserInfo() != null) {
                throw refuse("BMS_SMS_AT_BASE_URL must be an https URL without user info");
            }
        }
        int cap = p.platformDailyCap() == null ? DEFAULT_PLATFORM_DAILY_CAP : p.platformDailyCap();
        if (cap < 0) {
            throw refuse("BMS_SMS_PLATFORM_DAILY_CAP must be 0 or more");
        }
        return new SmsSettings(env, mode, kind, allowlist(p.allowlist()), cap, base, username, apiKey);
    }

    private static Mode mode(String raw, boolean staging) {
        String m = trimmed(raw);
        if (m == null) {
            return staging ? Mode.SINK : Mode.OFF;
        }
        return switch (m.toLowerCase(Locale.ROOT)) {
            case "off" -> Mode.OFF;
            case "sink" -> Mode.SINK;
            case "allowlist" -> Mode.ALLOWLIST;
            case "live" -> Mode.LIVE;
            default -> throw refuse("BMS_SMS_MODE is not one of off, sink, allowlist, live");
        };
    }

    private static Kind kind(String raw) {
        String k = trimmed(raw);
        if (k == null) {
            return Kind.NONE;
        }
        return switch (k.toLowerCase(Locale.ROOT)) {
            case "africastalking" -> Kind.AFRICASTALKING;
            case "fake" -> Kind.FAKE;
            default -> throw refuse("BMS_SMS_PROVIDER is not africastalking or fake");
        };
    }

    private static Set<String> allowlist(String raw) {
        Set<String> numbers = new LinkedHashSet<>();
        if (trimmed(raw) == null) {
            return numbers;
        }
        int entry = 0;
        for (String part : raw.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            entry++;
            int position = entry;
            numbers.add(PhoneNumbers.forSms(part)
                    .orElseThrow(() -> refuse("BMS_SMS_ALLOWLIST entry " + position + " is not a number we send to")));
        }
        return Set.copyOf(numbers);
    }

    /** Exactly the sandbox host over https, with no user info, and the default port: no look-alike passes. */
    static boolean hostIsSandbox(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && SANDBOX_HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String trimmed(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static IllegalStateException refuse(String message) {
        return new IllegalStateException("SMS configuration refused: " + message);
    }

    @Override
    public String toString() {
        return "SmsSettings[environment=" + environment + ", mode=" + mode + ", provider=" + provider + "]";
    }
}
