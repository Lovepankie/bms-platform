package com.rincoltech.bms.retail.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The kinds of business the owner chooses from in Retail settings (issue #424, ADR-092), stored as the profile of the group
 * {@code retail.capabilities}. A kind is a preset of the six switches in {@link #GOVERNED}, named by what the owner has; it is
 * never a mode, and the module reads the switches only (ADR-033). Branch roles and customer returns belong to no kind and are
 * left as they are by a choice. Each branch is a store or a shop on the Branches screen (ADR-048), whatever the kind.
 */
public final class BusinessTypes {

    private BusinessTypes() {}

    /** One or more shops that sell over the counter; the default. */
    public static final String SHOPS = "shops";

    /** Stores that receive stock and send it to shops; any number of each. */
    public static final String STORES_AND_SHOPS = "stores_and_shops";

    /** Selling in bulk to other shops. */
    public static final String WHOLESALE = "wholesale";

    /** Selling in bulk to other shops, and over the counter in its own shops. */
    public static final String WHOLESALE_AND_SHOPS = "wholesale_and_shops";

    /** The switches a choice of kind sets on or off; the wholesale tools, and the two-step stock move. */
    public static final List<String> GOVERNED =
            List.of("units", "price_lists", "customer_accounts", "documents", "purchasing", "transfer_receipt");

    /** The wholesale tools: packs, price lists and customer prices, credit to shops, invoices and quotations, purchasing. */
    public static final Set<String> WHOLESALE_TOOLS =
            Set.of("units", "price_lists", "customer_accounts", "documents", "purchasing");

    /** Each kind, in the order the screen offers them, with the governed switches it turns on. */
    public static final Map<String, Set<String>> PRESETS = presets();

    private static Map<String, Set<String>> presets() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        out.put(SHOPS, Set.of());
        out.put(STORES_AND_SHOPS, Set.of("transfer_receipt"));
        out.put(WHOLESALE, WHOLESALE_TOOLS);
        out.put(
                WHOLESALE_AND_SHOPS,
                Set.of("units", "price_lists", "customer_accounts", "documents", "purchasing", "transfer_receipt"));
        return java.util.Collections.unmodifiableMap(out);
    }

    public static boolean isType(String value) {
        return value != null && PRESETS.containsKey(value);
    }

    /**
     * The kind a stored profile from before issue #424 (or one an older server wrote back during a deploy) reads as, judged from the
     * switches without changing any: Wholesale and shops when a wholesale tool is on; otherwise {@code trade} is Stores and shops,
     * and {@code shop} is Stores and shops when the two-step stock move is on, else Shops only. The same rule as migration
     * {@code V103}.
     */
    public static String fromLegacy(String profile, Map<String, Object> flags) {
        if (WHOLESALE_TOOLS.stream().anyMatch(k -> Boolean.TRUE.equals(flags.get(k)))) {
            return WHOLESALE_AND_SHOPS;
        }
        return "trade".equals(profile) || Boolean.TRUE.equals(flags.get("transfer_receipt")) ? STORES_AND_SHOPS : SHOPS;
    }

    /** True when choosing {@code type} switches {@code capability} off; false for a switch no kind governs. */
    public static boolean switchesOff(String type, String capability) {
        return isType(type)
                && GOVERNED.contains(capability)
                && !PRESETS.get(type).contains(capability);
    }
}
