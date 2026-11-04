package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.stock.EntryLocations;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where each sale and usage or damage entry was made, and each shop's own location (issue #826, ADR-122; tables {@code
 * retail_entry_locations} and {@code retail_shop_locations}). Holders of {@link #READ} get the distance from the shop in
 * plain words, never the coordinates; the owner sets a shop's location with {@link #MANAGE}. Nothing here logs or audits a
 * coordinate.
 */
@Service
class EntryLocationService implements EntryLocations {

    static final String READ = "retail.entry_location.read";
    static final String MANAGE = "core.settings.manage";

    /** The mean radius of the Earth in metres (IUGG), for the haversine distance. */
    static final double EARTH_RADIUS_M = 6_371_008.8;

    private final JdbcClient jdbc;
    private final RetailBranchContext context;
    private final Branches branches;
    private final AuditLog audit;

    EntryLocationService(JdbcClient jdbc, RetailBranchContext context, Branches branches, AuditLog audit) {
        this.jdbc = jdbc;
        this.context = context;
        this.branches = branches;
        this.audit = audit;
    }

    @Override
    public void record(String source, UUID sourceId, UUID branchId, Fix fix) {
        // An incomplete location is no location: the entry is never refused for it (ADR-122 decision 2).
        if (fix == null || !fix.complete()) {
            return;
        }
        requireValid(fix, "location");
        jdbc.sql("""
                        INSERT INTO retail_entry_locations (tenant_id, source, source_id, branch_id, latitude, longitude,
                            accuracy_m)
                        VALUES (current_setting('app.tenant_id')::uuid, :source, :id, :branch, :lat, :lng, :acc)
                        ON CONFLICT DO NOTHING
                        """)
                .param("source", source)
                .param("id", sourceId)
                .param("branch", branchId)
                .param("lat", degrees(fix.latitude()))
                .param("lng", degrees(fix.longitude()))
                .param("acc", metres(fix.accuracyM()))
                .update();
    }

    /** One entry's distance from its shop, in plain words; {@code state} is located, no_location or shop_not_set. */
    record Distance(UUID id, String state, Long distanceM, Long accuracyM, String words) {}

    /**
     * The distances of these entries of one kind, in the order asked. An entry with no location, or no such entry, reads
     * no_location, and so does an entry with a location at a branch outside the caller's scope of {@link #READ}: the three
     * are the same answer, so nothing is learnt about an id.
     */
    @Transactional(readOnly = true)
    List<Distance> distances(String source, List<UUID> ids) {
        Principal principal = CurrentPrincipal.require();
        Map<UUID, Distance> found = new HashMap<>();
        if (!ids.isEmpty()) {
            jdbc.sql("""
                            SELECT e.source_id, e.branch_id, e.latitude, e.longitude, e.accuracy_m,
                                   s.latitude AS shop_latitude, s.longitude AS shop_longitude
                              FROM retail_entry_locations e
                              LEFT JOIN retail_shop_locations s ON s.tenant_id = e.tenant_id AND s.branch_id = e.branch_id
                             WHERE e.source = :source AND e.source_id IN (:ids)
                            """)
                    .param("source", source)
                    .param("ids", ids)
                    .query((rs, n) -> {
                        UUID id = rs.getObject("source_id", UUID.class);
                        UUID branch = rs.getObject("branch_id", UUID.class);
                        if (!principal.may(READ, branch)) {
                            return 0;
                        }
                        long accuracy =
                                Math.round(rs.getBigDecimal("accuracy_m").doubleValue());
                        BigDecimal shopLat = rs.getBigDecimal("shop_latitude");
                        if (shopLat == null) {
                            found.put(id, new Distance(id, "shop_not_set", null, accuracy, "shop location not set"));
                            return 0;
                        }
                        double metres = haversine(
                                rs.getBigDecimal("latitude").doubleValue(),
                                rs.getBigDecimal("longitude").doubleValue(),
                                shopLat.doubleValue(),
                                rs.getBigDecimal("shop_longitude").doubleValue());
                        long rounded = rounded(metres);
                        found.put(id, new Distance(id, "located", rounded, accuracy, words(rounded)));
                        return 0;
                    })
                    .list();
        }
        List<Distance> out = new ArrayList<>();
        for (UUID id : ids) {
            out.add(found.getOrDefault(id, new Distance(id, "no_location", null, null, "no location")));
        }
        return out;
    }

    /** A shop and whether its location is set; never the coordinates. */
    record Shop(UUID branchId, String code, String name, boolean hasLocation, Long accuracyM, Instant setAt) {}

    /** The active branches in the caller's scope of {@link #MANAGE}, head office first, with whether each has a location. */
    @Transactional(readOnly = true)
    List<Shop> shops() {
        Map<UUID, Shop> set = new HashMap<>();
        jdbc.sql("SELECT branch_id, accuracy_m, set_at FROM retail_shop_locations")
                .query((rs, n) -> {
                    UUID branch = rs.getObject("branch_id", UUID.class);
                    set.put(
                            branch,
                            new Shop(
                                    branch,
                                    null,
                                    null,
                                    true,
                                    Math.round(rs.getBigDecimal("accuracy_m").doubleValue()),
                                    rs.getObject("set_at", OffsetDateTime.class).toInstant()));
                    return 0;
                })
                .list();
        return context.visible(MANAGE).stream()
                .map(b -> {
                    Shop s = set.get(b.id());
                    return s == null
                            ? new Shop(b.id(), b.code(), b.name(), false, null, null)
                            : new Shop(b.id(), b.code(), b.name(), true, s.accuracyM(), s.setAt());
                })
                .toList();
    }

    /** Sets a shop's location to where the owner's phone is now; audited without the coordinates. */
    @Transactional
    Shop set(UUID branchId, Fix fix) {
        Branches.Branch branch = managed(branchId);
        requireValid(fix, "");
        boolean had = has(branchId);
        jdbc.sql("""
                        INSERT INTO retail_shop_locations (tenant_id, branch_id, latitude, longitude, accuracy_m, set_by,
                            set_at)
                        VALUES (current_setting('app.tenant_id')::uuid, :branch, :lat, :lng, :acc, :by, now())
                        ON CONFLICT (tenant_id, branch_id) DO UPDATE SET latitude = EXCLUDED.latitude,
                            longitude = EXCLUDED.longitude, accuracy_m = EXCLUDED.accuracy_m, set_by = EXCLUDED.set_by,
                            set_at = now()
                        """)
                .param("branch", branchId)
                .param("lat", degrees(fix.latitude()))
                .param("lng", degrees(fix.longitude()))
                .param("acc", metres(fix.accuracyM()))
                .param("by", CurrentPrincipal.require().userId())
                .update();
        audit.record(new AuditLog.Entry(
                "retail.shop_location.set",
                "retail.shop_location",
                branchId,
                branchId,
                Map.of("has_location", had),
                Map.of("has_location", true)));
        return shops().stream()
                .filter(s -> s.branchId().equals(branch.id()))
                .findFirst()
                .orElseThrow(ApiException::notFound);
    }

    /** Clears a shop's location; its entries then read "shop location not set". */
    @Transactional
    void clear(UUID branchId) {
        managed(branchId);
        int removed = jdbc.sql("DELETE FROM retail_shop_locations WHERE branch_id = :branch")
                .param("branch", branchId)
                .update();
        if (removed > 0) {
            audit.record(new AuditLog.Entry(
                    "retail.shop_location.cleared",
                    "retail.shop_location",
                    branchId,
                    branchId,
                    Map.of("has_location", true),
                    Map.of("has_location", false)));
        }
    }

    private Branches.Branch managed(UUID branchId) {
        if (!CurrentPrincipal.require().may(MANAGE, branchId)) {
            throw ApiException.notFound();
        }
        return branches.all().stream()
                .filter(b -> b.id().equals(branchId) && b.active())
                .findFirst()
                .orElseThrow(ApiException::notFound);
    }

    private boolean has(UUID branchId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM retail_shop_locations WHERE branch_id = :branch)")
                .param("branch", branchId)
                .query(Boolean.class)
                .single();
    }

    private static void requireValid(Fix fix, String prefix) {
        if (fix == null || !fix.valid()) {
            String field = prefix.isEmpty() ? "latitude" : prefix + ".latitude";
            throw ApiException.validation(List.of(new FieldProblem(
                    field,
                    "invalid_location",
                    "A location needs a latitude from -90 to 90, a longitude from -180 to 180 and an accuracy of 0 or"
                            + " more metres.")));
        }
    }

    private static BigDecimal degrees(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal metres(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    /** The great-circle distance in metres between two points given in degrees (the haversine formula). */
    static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lng2 - lng1);
        double a =
                Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /**
     * A distance rounded as people say it: under 10 m is 0 (at the shop); under a kilometre to the nearest 10 m; from 1 km to
     * under 100 km to the nearest 100 m; further, to the nearest kilometre.
     */
    static long rounded(double metres) {
        if (metres < 10) {
            return 0;
        }
        if (metres < 995) {
            return Math.round(metres / 10) * 10;
        }
        if (metres < 99_950) {
            return Math.round(metres / 100) * 100;
        }
        return Math.round(metres / 1000) * 1000;
    }

    /** "at the shop", "40 m from the shop", "3.2 km from the shop", "1 km from the shop", "250 km from the shop". */
    static String words(long rounded) {
        if (rounded == 0) {
            return "at the shop";
        }
        if (rounded < 1000) {
            return rounded + " m from the shop";
        }
        long tenths = rounded / 100;
        String km = tenths % 10 == 0 || rounded >= 100_000
                ? String.valueOf(Math.round(rounded / 1000.0))
                : (tenths / 10) + "." + (tenths % 10);
        return km + " km from the shop";
    }
}
