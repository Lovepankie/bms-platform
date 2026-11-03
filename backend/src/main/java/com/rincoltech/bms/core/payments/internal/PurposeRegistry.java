package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.PurposeHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The purposes the modules registered (chapter 5 section 5.4.3, spec 3.4): every {@link PurposeHandler} bean of the
 * application, by purpose. Two handlers for one purpose stop the application at start, since a payment must have exactly
 * one place to be booked.
 */
@Component
class PurposeRegistry {

    private final Map<String, PurposeHandler> handlers = new HashMap<>();

    PurposeRegistry(ObjectProvider<PurposeHandler> all) {
        // No module has registered a purpose yet in this slice: an empty registry is valid, a failed injection is not.
        for (PurposeHandler handler : all.orderedStream().toList()) {
            if (handlers.put(handler.purpose(), handler) != null) {
                throw new IllegalStateException("two payment purpose handlers for " + handler.purpose());
            }
        }
    }

    /** Every registered handler, once each. */
    java.util.Collection<PurposeHandler> all() {
        return java.util.List.copyOf(handlers.values());
    }

    Optional<PurposeHandler> find(String purpose) {
        return Optional.ofNullable(purpose == null ? null : handlers.get(purpose));
    }
}
