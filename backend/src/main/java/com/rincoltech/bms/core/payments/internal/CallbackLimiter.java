package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The application's own limit on the public notification endpoints (ADR-035 decision 4, spec 3.6 step 7): a fixed window
 * of one minute, per client address and for all addresses together, with a bucket of its own for the provider's IPN and
 * another for the payer's return page. The return page is open to any browser, so it must never be able to use up the
 * budget the provider's real notifications need. The edge rule and the proxy come first (chapter 9 section 9.8); this is
 * what still holds when they are not there. Over the limit a request is answered 429 and does nothing else, so a flood
 * costs a counter and no database write. The clock is the kernel's, so a test moves the window by hand. An address
 * table that grows past a bound is treated as full: a flood from many addresses is limited as a whole.
 */
@Component
class CallbackLimiter {

    static final int MAX_ADDRESSES = 20_000;

    enum Bucket {
        IPN,
        RETURN,
        /** Requests with a wrong path token: they can fill their own bucket and nobody else's. */
        BAD_TOKEN
    }

    private final BusinessClock clock;
    private final int perAddress;
    private final int overall;
    private final Map<Bucket, Map<String, Integer>> counts = new EnumMap<>(Bucket.class);
    private final Map<Bucket, Integer> totals = new EnumMap<>(Bucket.class);
    private long window = Long.MIN_VALUE;

    CallbackLimiter(PaymentsProperties properties, BusinessClock clock) {
        this.clock = clock;
        this.perAddress = properties.callbackIpPerMinute();
        this.overall = properties.callbackGlobalPerMinute();
    }

    /** True when the address has used up its bucket this minute (a read only: nothing is counted). */
    synchronized boolean exhausted(Bucket bucket, String address) {
        long minute = clock.now().getEpochSecond() / 60;
        if (minute != window) {
            return false;
        }
        Map<String, Integer> perIp = counts.get(bucket);
        String key = address == null ? "unknown" : address;
        // This address's own count only: a flood from many addresses must not shut the door on every lender's genuine
        // notification (the bucket's overall total applies to counted requests, not to this pre-check).
        return perIp != null && perIp.getOrDefault(key, 0) >= perAddress;
    }

    /** True when the request may go on. */
    synchronized boolean tryAcquire(Bucket bucket, String address) {
        long minute = clock.now().getEpochSecond() / 60;
        if (minute != window) {
            window = minute;
            counts.clear();
            totals.clear();
        }
        int total = totals.getOrDefault(bucket, 0);
        if (total >= overall) {
            return false;
        }
        Map<String, Integer> perIp = counts.computeIfAbsent(bucket, b -> new HashMap<>());
        String key = address == null ? "unknown" : address;
        if (!perIp.containsKey(key) && perIp.size() >= MAX_ADDRESSES) {
            return false;
        }
        int n = perIp.merge(key, 1, Integer::sum);
        if (n > perAddress) {
            return false;
        }
        totals.put(bucket, total + 1);
        return true;
    }
}
