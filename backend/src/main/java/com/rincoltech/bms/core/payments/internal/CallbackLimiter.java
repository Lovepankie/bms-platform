package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The application's own limit on the public notification endpoints (ADR-035 decision 4, spec 3.6 step 7): a fixed window
 * of one minute, per client address and for all addresses together. The edge rule and the proxy come first (chapter 9 section
 * 9.8); this is what still holds when they are not there. Over the limit a request is answered 429 and does nothing
 * else, so a flood costs a counter and no database write. The clock is the kernel's, so a test moves the window by hand.
 * An address table that grows past a bound is treated as full: a flood from many addresses is limited as a whole.
 */
@Component
class CallbackLimiter {

    static final int MAX_ADDRESSES = 20_000;

    private final BusinessClock clock;
    private final int perAddress;
    private final int overall;
    private final Map<String, Integer> counts = new HashMap<>();
    private long window = Long.MIN_VALUE;
    private int total;

    CallbackLimiter(PaymentsProperties properties, BusinessClock clock) {
        this.clock = clock;
        this.perAddress = properties.callbackIpPerMinute();
        this.overall = properties.callbackGlobalPerMinute();
    }

    /** True when the request may go on. */
    synchronized boolean tryAcquire(String address) {
        long minute = clock.now().getEpochSecond() / 60;
        if (minute != window) {
            window = minute;
            counts.clear();
            total = 0;
        }
        if (total >= overall) {
            return false;
        }
        String key = address == null ? "unknown" : address;
        if (!counts.containsKey(key) && counts.size() >= MAX_ADDRESSES) {
            return false;
        }
        int n = counts.merge(key, 1, Integer::sum);
        if (n > perAddress) {
            return false;
        }
        total++;
        return true;
    }
}
