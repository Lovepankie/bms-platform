package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Per-address token buckets for the two public password reset routes (FR-IAM-15, ADR-042), in memory and bounded: at most
 * {@link #MAX_KEYS} keys, the least recently used dropped first, so a flood of addresses cannot grow it. An IPv6 client is
 * keyed by its /64, an IPv4 client by its address. This is the cheap first line only and depends on the caller alone, never on
 * whether an account exists, so a refusal tells a prober nothing. The limit per account is counted in the database by
 * {@link PasswordResets} and answers exactly like a request that found no account. Per instance and lost on restart.
 */
@Component
class ResetThrottle {

    /** {@code capacity} requests, refilled evenly over {@code per}. */
    record Limit(String name, int capacity, Duration per) {}

    /** Requests for a reset link: 20 an hour from one address (a branch office shares one). */
    static final Limit REQUEST_PER_IP = new Limit("reset-request-ip", 20, Duration.ofHours(1));
    /** Attempts to use a link: 30 in 10 minutes from one address. The token is 256 random bits; this only bounds noise. */
    static final Limit COMPLETE_PER_IP = new Limit("reset-complete-ip", 30, Duration.ofMinutes(10));

    static final int MAX_KEYS = 10_000;

    private final BusinessClock clock;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_KEYS;
        }
    };

    ResetThrottle(BusinessClock clock) {
        this.clock = clock;
    }

    /** Takes one token or answers 429 {@code rate_limited}. */
    void acquire(Limit limit, String address) {
        if (!tryAcquire(limit, address)) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limited",
                    "Too many requests",
                    "Too many attempts. Wait a few minutes and try again.");
        }
    }

    synchronized boolean tryAcquire(Limit limit, String address) {
        Instant now = clock.now();
        String id = limit.name() + ":" + key(address);
        Bucket bucket = buckets.computeIfAbsent(id, k -> new Bucket(limit.capacity(), now));
        double perToken = (double) limit.per().toMillis() / limit.capacity();
        double refill = Math.max(0, Duration.between(bucket.at, now).toMillis()) / perToken;
        bucket.tokens = Math.min(limit.capacity(), bucket.tokens + refill);
        bucket.at = now;
        if (bucket.tokens < 1) {
            return false;
        }
        bucket.tokens -= 1;
        return true;
    }

    private static String key(String address) {
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

    private static final class Bucket {
        double tokens;
        Instant at;

        Bucket(double tokens, Instant at) {
            this.tokens = tokens;
            this.at = at;
        }
    }
}
