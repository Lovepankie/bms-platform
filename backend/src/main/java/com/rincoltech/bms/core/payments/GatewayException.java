package com.rincoltech.bms.core.payments;

import java.util.regex.Pattern;

/**
 * A provider call that did not give a usable answer. It carries a closed {@link Kind}, the http status and a short
 * code from a safe alphabet, and nothing the provider wrote: its message, its body, the URL and every credential stay
 * out, so the exception can be logged, stored as a reason or shown without a leak (spec 3.13).
 */
public final class GatewayException extends Exception {

    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

    /**
     * What went wrong, in terms of what the caller may do next.
     *
     * <ul>
     *   <li>{@code UNAVAILABLE}: no call was made (not configured, or the breaker is open); nothing happened at the provider.
     *   <li>{@code TRANSIENT}: a network error, a timeout or a 5xx; the request may or may not have been applied.
     *   <li>{@code THROTTLED}: the provider asked us to slow down (http 409 on a registration); retry later.
     *   <li>{@code AUTH}: the credentials or the token were refused.
     *   <li>{@code REFUSED}: the provider understood and said no (a business error); it was not applied.
     *   <li>{@code BAD_RESPONSE}: an answer we could not read or that broke the contract; treated as unknown.
     * </ul>
     */
    public enum Kind {
        UNAVAILABLE,
        TRANSIENT,
        THROTTLED,
        AUTH,
        REFUSED,
        BAD_RESPONSE
    }

    private final Kind kind;
    private final int httpStatus;
    private final String code;

    public GatewayException(Kind kind, int httpStatus, String code) {
        super("gateway call failed: kind=" + kind + " http=" + httpStatus + " code=" + safe(code), null, false, false);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.code = safe(code);
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** The provider's error code when it matches a safe alphabet, else {@code unspecified}. */
    public String code() {
        return code;
    }

    /**
     * True when the request may have been applied although no answer came: a lost answer. A creator that sees this
     * repeats the same reference and body rather than assuming the order does not exist (spec 3.5).
     */
    public boolean outcomeUnknown() {
        return kind == Kind.TRANSIENT || kind == Kind.BAD_RESPONSE;
    }

    public static String safe(String code) {
        return code != null && SAFE_CODE.matcher(code).matches() ? code : "unspecified";
    }
}
