package com.rincoltech.bms.core.payments.internal;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The gateway's resolved settings and the startup guard that makes a payment from the wrong place impossible
 * (ADR-035 decision 10 and 13, FR-PAY-08, spec 3.11 and 8; the approach of {@code SmsSettings}). {@link #resolve} throws
 * {@link IllegalStateException} at start, whatever the provider mode, for each of these:
 *
 * <ul>
 *   <li>outside production (staging, a laptop, an unset {@code BMS_ENVIRONMENT}): any {@code BMS_PESAPAL_BASE_URL}
 *       other than the provider's sandbox, even with the gateway otherwise off, because a live URL with a key is a real
 *       charge. Staging can therefore never move real money, whatever credentials sit in its environment;
 *   <li>production: the sandbox URL (a sandbox order would be a sale that never paid) and the in-process fake;
 *   <li>a base URL that is not exactly one of the two known hosts over https with the provider's own path: no look-alike
 *       host, no user info, no other port passes;
 *   <li>a callback base that is not a public https origin, an IPN path token that is short or not path safe, and an
 *       unknown provider name. Only the platform's own pair is read here; a lender's merchant account is stored encrypted
 *       per tenant and is never in the environment (issue #233).
 * </ul>
 *
 * Messages name the variable and never print a value, a key, a secret or a token.
 */
record PesapalSettings(
        String environment,
        Kind provider,
        URI baseUrl,
        Pair platformPair,
        URI callbackBase,
        java.util.Set<String> refusedKeyDigests) {

    /**
     * The form of a consumer key that is compared and digested to decide whether two keys are the same account (issue #309): lower
     * cased. Nothing recorded in this repository (the sandbox fixtures, the ADR, the Pesapal reference we hold) says whether
     * Pesapal treats a key's letter case as significant, so we assume it may not: "AbC" and "abc" must not be two lenders' keys,
     * and a platform key must not get past the refused list by changing a letter's case. Only the refused-list check and the
     * cross-tenant uniqueness check use this; the key sent to Pesapal is always exactly what the lender pasted.
     */
    static String fold(String consumerKey) {
        return consumerKey.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * True when the consumer key is one of the platform operator's own, so no lender may enter it. Compared without regard to
     * letter case (see {@link #fold}): the configured platform key by its folded bytes, the refused digests by the SHA-256 of
     * the key as entered and of its folded form (an operator lists the digest of the key exactly as issued; add the folded
     * form's digest too, runbook payments.md).
     */
    boolean isRefusedKey(String consumerKey) {
        if (consumerKey == null) {
            return false;
        }
        String folded = fold(consumerKey);
        return refusedKeyDigests.contains(java.util.HexFormat.of().formatHex(sha256(consumerKey)))
                || refusedKeyDigests.contains(java.util.HexFormat.of().formatHex(sha256(folded)))
                || (platformPair.key() != null
                        && MessageDigest.isEqual(
                                fold(platformPair.key()).getBytes(StandardCharsets.UTF_8),
                                folded.getBytes(StandardCharsets.UTF_8)));
    }

    static final String SANDBOX_HOST = "cybqa.pesapal.com";
    static final String SANDBOX_PATH = "/pesapalv3";
    static final String LIVE_HOST = "pay.pesapal.com";
    static final String LIVE_PATH = "/v3";

    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{24,128}$");
    private static final Pattern PRIVATE_HOST = Pattern.compile(
            "^(localhost|0\\.0\\.0\\.0|\\[::1?]|(127|10)(\\.\\d{1,3}){3}|192\\.168(\\.\\d{1,3}){2}|169\\.254(\\.\\d{1,3}){2}"
                    + "|172\\.(1[6-9]|2\\d|3[01])(\\.\\d{1,3}){2}|.*\\.(localhost|local|internal|lan))$");

    enum Kind {
        OFF,
        PESAPAL,
        FAKE
    }

    /** One credential pair with its IPN registration and path token. Prints nothing. */
    record Pair(String key, String secret, String ipnId, String ipnToken) {

        boolean complete() {
            return usable() && present(ipnId) && present(ipnToken);
        }

        /** A key and a secret: enough to ask the provider for a token. */
        boolean usable() {
            return present(key) && present(secret);
        }

        @Override
        public String toString() {
            return "Pair[complete=" + complete() + "]";
        }
    }

    /**
     * True when the path token is the platform pair's, compared in constant time (a digest of each side, so neither the
     * length nor a shared prefix shows in the time taken). A lender's own token is looked up by its digest in the
     * database ({@code AccountTokens}); the two can never be equal, because a lender's token is generated by us.
     */
    boolean isPlatformToken(String presented) {
        if (presented == null || presented.isEmpty() || presented.length() > 256) {
            return false;
        }
        return matches(platformPair.ipnToken(), presented);
    }

    /**
     * True when a lender's own account can be tested and used on this host: the real adapter needs the callback origin
     * (the notification url is built from it), the fake needs nothing, and "off" offers nothing.
     */
    boolean tenantAccountsEnabled() {
        return switch (provider) {
            case OFF -> false;
            case FAKE -> true;
            case PESAPAL -> callbackBase != null;
        };
    }

    /** True when the platform pair can be used: the real adapter needs a complete pair and an origin. */
    boolean platformConfigured() {
        return switch (provider) {
            case OFF -> false;
            case FAKE -> true;
            case PESAPAL -> platformPair.complete() && callbackBase != null;
        };
    }

    private static boolean matches(String configured, String presented) {
        if (!present(configured)) {
            return false;
        }
        return MessageDigest.isEqual(sha256(configured), sha256(presented));
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static PesapalSettings resolve(String environment, PesapalProperties p) {
        String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        boolean production = env.equals("production");

        Pair platform = new Pair(
                trimmed(p.platform().consumerKey()),
                trimmed(p.platform().consumerSecret()),
                trimmed(p.platform().ipnId()),
                trimmed(p.platform().ipnToken()));

        String rawBase = trimmed(p.baseUrl());
        URI base = null;
        if (rawBase != null) {
            base = parse(rawBase, "BMS_PESAPAL_BASE_URL");
            if (production) {
                if (isHost(base, SANDBOX_HOST)) {
                    throw refuse("production must not use the Pesapal sandbox URL (BMS_PESAPAL_BASE_URL)");
                }
                if (!isHost(base, LIVE_HOST)) {
                    throw refuse("BMS_PESAPAL_BASE_URL must be the Pesapal live API in production");
                }
            } else if (!isHost(base, SANDBOX_HOST)) {
                throw refuse("BMS_PESAPAL_BASE_URL must be the Pesapal sandbox outside production, in every mode"
                        + " (BMS_ENVIRONMENT is not production)");
            }
        }
        URI resolvedBase = URI.create("https://" + (production ? LIVE_HOST + LIVE_PATH : SANDBOX_HOST + SANDBOX_PATH));

        Kind kind = kind(p.provider(), platform.complete() || trimmed(p.callbackBaseUrl()) != null);
        if (production && kind == Kind.FAKE) {
            throw refuse("BMS_PESAPAL_PROVIDER=fake is not allowed in production");
        }

        for (Pair pair : new Pair[] {platform}) {
            if (present(pair.ipnToken()) && !TOKEN.matcher(pair.ipnToken()).matches()) {
                throw refuse("an IPN path token must be 24 to 128 letters, digits, dashes or underscores"
                        + " (BMS_PESAPAL_PLATFORM_IPN_TOKEN)");
            }
        }

        URI callback = null;
        if (trimmed(p.callbackBaseUrl()) != null) {
            callback = callbackOrigin(trimmed(p.callbackBaseUrl()));
        }
        java.util.Set<String> refused = new java.util.LinkedHashSet<>();
        if (trimmed(p.refusedKeyDigests()) != null) {
            for (String d : trimmed(p.refusedKeyDigests()).split(",")) {
                String digest = d.trim().toLowerCase(Locale.ROOT);
                if (!DIGEST.matcher(digest).matches()) {
                    throw refuse("BMS_PESAPAL_REFUSED_KEY_DIGESTS must be comma separated SHA-256 digests in hex");
                }
                refused.add(digest);
            }
        }
        if (production && kind != Kind.OFF && refused.isEmpty()) {
            throw refuse(
                    "production needs BMS_PESAPAL_REFUSED_KEY_DIGESTS: the SHA-256 of every consumer key the platform"
                            + " operator owns, so no lender can be connected to the platform's own merchant account");
        }
        return new PesapalSettings(env, kind, resolvedBase, platform, callback, java.util.Set.copyOf(refused));
    }

    private static Kind kind(String raw, boolean credentials) {
        String k = trimmed(raw);
        if (k == null) {
            return credentials ? Kind.PESAPAL : Kind.OFF;
        }
        return switch (k.toLowerCase(Locale.ROOT)) {
            case "pesapal" -> Kind.PESAPAL;
            case "fake" -> Kind.FAKE;
            case "off" -> Kind.OFF;
            default -> throw refuse("BMS_PESAPAL_PROVIDER is not one of pesapal, fake, off");
        };
    }

    private static URI parse(String raw, String variable) {
        try {
            return URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw refuse(variable + " is not a URL");
        }
    }

    /** Exactly the named host over https, with its own path or none, no user info, default port, no query. */
    static boolean isHost(URI uri, String host) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String expected = host.equals(SANDBOX_HOST) ? SANDBOX_PATH : LIVE_PATH;
        return "https".equalsIgnoreCase(uri.getScheme())
                && host.equalsIgnoreCase(uri.getHost())
                && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && (path.isEmpty() || path.equals(expected));
    }

    /** True for an https URL with a host that is not a local or private address, no user info and no fragment. */
    static boolean publicHttps(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getRawFragment() == null
                && !PRIVATE_HOST.matcher(uri.getHost().toLowerCase(Locale.ROOT)).matches();
    }

    private static URI callbackOrigin(String raw) {
        URI uri = parse(raw, "BMS_PESAPAL_CALLBACK_BASE_URL");
        String host = uri.getHost();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!publicHttps(uri) || uri.getRawQuery() != null || !(path.isEmpty() || path.equals("/"))) {
            throw refuse("BMS_PESAPAL_CALLBACK_BASE_URL must be the public https origin of the callback host"
                    + " (no path, no user info, not a local or private address)");
        }
        return URI.create("https://" + host + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort()));
    }

    private static String trimmed(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static IllegalStateException refuse(String message) {
        return new IllegalStateException("Pesapal configuration refused: " + message);
    }

    @Override
    public String toString() {
        return "PesapalSettings[environment=" + environment + ", provider=" + provider + "]";
    }
}
