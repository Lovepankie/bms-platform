package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.PhoneNumbers;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Token buckets for the public sign-in family (FR-IAM-05, FR-IAM-15, ADR-042, ADR-059), in memory, per instance and lost on
 * restart. None of them is what keeps a guesser off an account: that is the database lock of {@link AuthFlow}, five
 * failures in a row for 15 minutes, which counts an address that matches no account exactly as it counts an account
 * ({@link SignInAddressFailures}, migration V78). These buckets only bound the noise one client can make:
 *
 * <ul>
 *   <li><b>Failed sign-ins per client IP</b> ({@link #countingFailures}), an IPv6 client by its /64: a wrong password, a
 *       wrong code, a bad token. A success costs nothing, so the many subscribers a mobile carrier puts behind one public
 *       IPv4 (carrier-grade NAT, the normal case on MTN and Airtel in Uganda) do not use up one another's budget by signing
 *       in, and a caller with the right password is never turned away for the sign-ins of others; only a run of failures
 *       from that IP is.
 *   <li>Requests for a reset link per client IP and per sign-in address, and uses of a link per client IP, as before.
 *   <li>Second-factor attempts per account.
 * </ul>
 *
 * <p>Bounded without forgetting: each limit keeps at most {@link #MAX_KEYS} keys, and only a bucket that has refilled
 * completely (it remembers nothing) is ever dropped to make room. A bucket that is drained or partly drained is never
 * evicted, so a flood of new keys cannot reset the bucket of the IP it wants to free. When every kept bucket still holds
 * state, a new key is let through uncounted (logged, at most once a minute) rather than refusing every newcomer; the
 * database lock still applies to it.
 */
@Component
class AuthThrottle {

    private static final Logger log = LoggerFactory.getLogger(AuthThrottle.class);

    /** {@code capacity} requests, refilled evenly over {@code per}. */
    record Limit(String name, int capacity, Duration per) {}

    /** Requests for a reset link: 20 an hour from one address (a branch office shares one). */
    static final Limit REQUEST_PER_IP = new Limit("reset-request-ip", 20, Duration.ofHours(1));
    /** Attempts to use a link: 30 in 10 minutes from one address. The token is 256 random bits; this only bounds noise. */
    static final Limit COMPLETE_PER_IP = new Limit("reset-complete-ip", 30, Duration.ofMinutes(10));
    /** Requests for a reset link for one sign-in address: 10 an hour, whoever asks (the database allows 3 links of them). */
    static final Limit REQUEST_PER_ADDRESS = new Limit("reset-request-address", 10, Duration.ofHours(1));
    /** Second-factor attempts for one account: 30 in 15 minutes; the fifth wrong code in a row still locks it. */
    static final Limit MFA_PER_ACCOUNT = new Limit("mfa-account", 30, Duration.ofMinutes(15));

    /** The default of {@code bms.auth.sign-in-failures-per-ip}: failed sign-ins from one client IP in 10 minutes. */
    static final int DEFAULT_SIGN_IN_FAILURES_PER_IP = 300;

    static final int MAX_KEYS = 50_000;
    /** A full store is swept for refilled buckets at most this often, so a flood cannot make every request scan it. */
    static final Duration SWEEP_EVERY = Duration.ofSeconds(1);

    /**
     * Failed sign-in, two-step, invitation and setup requests from one client IP: {@code bms.auth.sign-in-failures-per-ip}
     * in 10 minutes, {@value #DEFAULT_SIGN_IN_FAILURES_PER_IP} by default. The test profile raises it, because the whole
     * suite signs in from one address. Package-private and not final so a test can lower it for a moment.
     */
    Limit signInPerIp;

    private final BusinessClock clock;
    private final Map<String, Store> stores = new HashMap<>();
    private Instant lastWarned = Instant.EPOCH;

    AuthThrottle(
            BusinessClock clock,
            @Value("${bms.auth.sign-in-failures-per-ip:" + DEFAULT_SIGN_IN_FAILURES_PER_IP + "}") int failuresPerIp) {
        this.clock = clock;
        this.signInPerIp = new Limit("sign-in-ip", Math.max(1, failuresPerIp), Duration.ofMinutes(10));
    }

    /** Takes one token for the client IP or answers 429 {@code rate_limited} with Retry-After. */
    void acquire(Limit limit, String ip) {
        take(limit, ip(ip));
    }

    /** Takes one token for a subject (a sign-in address, an account) or answers 429 {@code rate_limited} with Retry-After. */
    void acquireFor(Limit limit, String subject) {
        take(limit, "subject:" + subject);
    }

    /**
     * Runs {@code work} for a caller from {@code ip}, counting only its failures. A token is taken BEFORE the work, so a
     * burst sent at once cannot all pass an almost empty bucket together (review of #433): while the bucket is empty the
     * caller is refused with 429 {@code rate_limited} and Retry-After and no work is done. The token is kept when the work
     * fails ({@link #isFailure}) and given back otherwise, so a success costs nothing once it is over.
     */
    <T> T countingFailures(Limit limit, String ip, Supplier<T> work) {
        String key = ip(ip);
        take(limit, key);
        boolean keep = false;
        try {
            return work.get();
        } catch (ApiException e) {
            keep = isFailure(e);
            throw e;
        } finally {
            if (!keep) {
                giveBack(limit, key);
            }
        }
    }

    /**
     * Whether an answer counts against the caller's IP: a client error (a wrong password, code or token, a weak password)
     * does; a lock (423), a refusal by a limit (429) and the server being busy (503) cost the caller no work and do not.
     */
    static boolean isFailure(ApiException e) {
        int status = e.status().value();
        return status >= 400 && status < 500 && status != 423 && status != 429;
    }

    /** Whether one more request from {@code ip} would be let through now; takes nothing. */
    boolean allows(Limit limit, String ip) {
        return waitFor(limit, ip(ip)) == null;
    }

    /** Counts one failure for {@code ip}, as {@link #countingFailures} does when its work fails. */
    void recordFailure(Limit limit, String ip) {
        failed(limit, ip(ip));
    }

    /**
     * The key of a sign-in address within one tenant and one kind of account: an email in lower case, a phone in E.164
     * when it reads as one, anything else as typed in lower case.
     */
    static String address(Accounts accounts, String login) {
        String typed = login == null ? "" : login.strip().toLowerCase(Locale.ROOT);
        String normal = typed.contains("@")
                ? typed
                : PhoneNumbers.normaliseUganda(typed).orElse(typed);
        return accounts.kind() + ":" + accounts.tenantId() + ":" + normal;
    }

    /** How many keys a limit keeps now; for tests. */
    synchronized int keys(Limit limit) {
        Store store = stores.get(limit.name());
        return store == null ? 0 : store.buckets.size();
    }

    private void take(Limit limit, String key) {
        Duration wait = tryTake(limit, key);
        if (wait != null) {
            throw limited(wait);
        }
    }

    static ApiException limited(Duration wait) {
        return new ApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "rate_limited",
                        "Too many requests",
                        "Too many attempts. Wait a few minutes and try again.")
                .withRetryAfter(wait);
    }

    /** Takes a token; null when taken, else how long until one is there. */
    private synchronized Duration tryTake(Limit limit, String key) {
        Instant now = clock.now();
        Store store = store(limit);
        Bucket bucket = store.buckets.get(key);
        if (bucket == null) {
            if (!store.makeRoom(limit, now)) {
                full(limit, now);
                return null;
            }
            bucket = new Bucket(limit.capacity(), now);
            store.buckets.put(key, bucket);
        }
        bucket.refill(limit, now);
        if (bucket.tokens < 1) {
            return bucket.untilOne(limit);
        }
        bucket.tokens -= 1;
        return null;
    }

    /** Null when a request may go ahead, else how long until it may. Takes nothing and adds no key. */
    private synchronized Duration waitFor(Limit limit, String key) {
        Store store = stores.get(limit.name());
        Bucket bucket = store == null ? null : store.buckets.get(key);
        if (bucket == null) {
            return null;
        }
        bucket.refill(limit, clock.now());
        return bucket.tokens < 1 ? bucket.untilOne(limit) : null;
    }

    /** Returns the token {@link #countingFailures} took for work that did not fail; never above the capacity. */
    private synchronized void giveBack(Limit limit, String key) {
        Store store = stores.get(limit.name());
        Bucket bucket = store == null ? null : store.buckets.get(key);
        if (bucket == null) {
            // Not counted (the store was full of live buckets): nothing was taken.
            return;
        }
        bucket.refill(limit, clock.now());
        bucket.tokens = Math.min(limit.capacity(), bucket.tokens + 1);
    }

    private synchronized void failed(Limit limit, String key) {
        Instant now = clock.now();
        Store store = store(limit);
        Bucket bucket = store.buckets.get(key);
        if (bucket == null) {
            if (!store.makeRoom(limit, now)) {
                full(limit, now);
                return;
            }
            bucket = new Bucket(limit.capacity(), now);
            store.buckets.put(key, bucket);
        }
        bucket.refill(limit, now);
        bucket.tokens -= 1;
    }

    private Store store(Limit limit) {
        return stores.computeIfAbsent(limit.name(), k -> new Store());
    }

    private void full(Limit limit, Instant now) {
        if (Duration.between(lastWarned, now).compareTo(Duration.ofMinutes(1)) >= 0) {
            lastWarned = now;
            log.warn(
                    "Sign-in throttle {} holds {} keys that still remember requests; new keys are not counted",
                    limit.name(),
                    MAX_KEYS);
        }
    }

    private static String ip(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        try {
            InetAddress parsed = InetAddress.ofLiteral(address.trim());
            if (parsed instanceof Inet6Address) {
                return "v6:" + HexFormat.of().formatHex(parsed.getAddress(), 0, 8) + "::/64";
            }
            return "v4:" + parsed.getHostAddress();
        } catch (IllegalArgumentException e) {
            return "raw:" + address;
        }
    }

    /** The buckets of one limit, least recently used first. */
    private static final class Store {
        final LinkedHashMap<String, Bucket> buckets = new LinkedHashMap<>(256, 0.75f, true);
        Instant lastSweep = Instant.EPOCH;

        /**
         * Whether a new key fits: at once below {@link #MAX_KEYS}, else after dropping every bucket that has refilled
         * completely (it remembers nothing, so dropping it changes no answer). A bucket that still holds state is never
         * dropped.
         */
        boolean makeRoom(Limit limit, Instant now) {
            if (buckets.size() < MAX_KEYS) {
                return true;
            }
            if (Duration.between(lastSweep, now).compareTo(SWEEP_EVERY) < 0) {
                return false;
            }
            lastSweep = now;
            for (Iterator<Bucket> it = buckets.values().iterator(); it.hasNext(); ) {
                if (it.next().fullAt(limit, now)) {
                    it.remove();
                }
            }
            return buckets.size() < MAX_KEYS;
        }
    }

    private static final class Bucket {
        double tokens;
        Instant at;

        Bucket(double tokens, Instant at) {
            this.tokens = tokens;
            this.at = at;
        }

        static double perTokenMillis(Limit limit) {
            return (double) limit.per().toMillis() / limit.capacity();
        }

        void refill(Limit limit, Instant now) {
            double refill = Math.max(0, Duration.between(at, now).toMillis()) / perTokenMillis(limit);
            tokens = Math.min(limit.capacity(), tokens + refill);
            at = now;
        }

        boolean fullAt(Limit limit, Instant now) {
            double refill = Math.max(0, Duration.between(at, now).toMillis()) / perTokenMillis(limit);
            return tokens + refill >= limit.capacity();
        }

        /** Time until the bucket holds one token again, after {@link #refill}. */
        Duration untilOne(Limit limit) {
            return Duration.ofMillis((long) Math.ceil((1 - tokens) * perTokenMillis(limit)));
        }
    }
}
