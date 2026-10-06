package com.rincoltech.bms.core.onboarding.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Per-address token buckets for the public sign-up endpoints (spec sections 10 and 12, chapter 7
 * section 7.10), in memory and bounded: at most {@link #MAX_KEYS} keys, the least recently used
 * dropped first, so a flood of addresses cannot grow it. An IPv6 client is keyed by its /64, which
 * one line or server holds whole, and an IPv4 client by its address (review B2). These buckets are
 * the first, cheap line only: a flood can evict them, so the bounds that must hold (per mailbox and
 * global) are counted in the database by {@link ApplicationService}, behind the Cloudflare edge rule
 * of docs/runbooks/onboard-tenant.md. Per instance and lost on restart.
 */
@Component
class SignUpRateLimiter {

    /** {@code capacity} requests, refilled evenly over {@code per}. */
    record Limit(String name, int capacity, Duration per) {}

    /** New applications: 5 an hour from one address. */
    static final Limit CREATE_PER_IP = new Limit("create-ip", 5, Duration.ofHours(1));
    /** Link reads (verify, status, reply): 30 in 10 minutes from one address. */
    static final Limit LINK_PER_IP = new Limit("link-ip", 30, Duration.ofMinutes(10));

    static final int MAX_KEYS = 10_000;

    private final BusinessClock clock;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_KEYS;
        }
    };

    SignUpRateLimiter(BusinessClock clock) {
        this.clock = clock;
    }

    /** Takes one token or answers 429 {@code rate_limited} with a Retry-After hint in the detail. */
    void acquire(Limit limit, String key) {
        if (!tryAcquire(limit, key)) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limited",
                    "Too many requests",
                    "Too many attempts. Wait a few minutes and try again.");
        }
    }

    /**
     * The bucket key of a client address: the /64 network of an IPv6 address, the address itself
     * for IPv4. A literal is parsed without any name lookup; anything else keys as itself.
     */
    static String addressKey(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        try {
            java.net.InetAddress parsed = java.net.InetAddress.ofLiteral(address.trim());
            if (parsed instanceof java.net.Inet6Address) {
                byte[] bytes = parsed.getAddress();
                return "v6:" + java.util.HexFormat.of().formatHex(bytes, 0, 8) + "::/64";
            }
            return "v4:" + parsed.getHostAddress();
        } catch (IllegalArgumentException e) {
            return "raw:" + address;
        }
    }

    synchronized boolean tryAcquire(Limit limit, String key) {
        Instant now = clock.now();
        String id = limit.name() + ":" + addressKey(key);
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

    synchronized int size() {
        return buckets.size();
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
