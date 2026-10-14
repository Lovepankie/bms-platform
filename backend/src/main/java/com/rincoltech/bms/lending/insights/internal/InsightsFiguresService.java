package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.internal.Positions.Position;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InsightsFigures} over the insights code (ADR-038 decision 3): the scope is resolved by
 * {@link InsightsService#scope} exactly as the insights endpoints do, and the PAR figures are totalled from the same
 * positions the portfolio page reads ({@link Positions}), with the same bucket rule ({@link Positions#bucketOf}) and the
 * same basis point rounding ({@link Metrics#bp}). Positions are read in blocks and added into one accumulator per group,
 * so memory is the number of groups and not the number of loans; this class adds the grouping and nothing else.
 */
@Service
class InsightsFiguresService implements InsightsFigures {

    private static final List<String> BUCKETS = List.of("current", "1_30", "31_60", "61_90", "over_90");
    private static final int EDGES = 4;
    private static final int[] PAR_EDGES = {0, 30, 60, 90};
    /** The loans between two calls of the caller's checkpoint. */
    static final int CHECKPOINT_EVERY = 1000;

    private final InsightsService service;
    private final InsightsQueries queries;
    private final Positions positions;
    private final CurrentTenant tenant;

    InsightsFiguresService(
            InsightsService service, InsightsQueries queries, Positions positions, CurrentTenant tenant) {
        this.service = service;
        this.queries = queries;
        this.positions = positions;
        this.tenant = tenant;
    }

    @Override
    public PortfolioScope scope(LocalDate from, LocalDate to, List<UUID> branchIds, UUID officerId, UUID productId) {
        Principal principal = CurrentPrincipal.require();
        requireRead(principal);
        Scope s = service.scope(from, to, branchIds, officerId, productId);
        return new PortfolioScope(s.from(), s.to(), s.today(), s.branchIds(), s.officerId(), s.productId());
    }

    private static void requireRead(Principal principal) {
        if (!principal.hasPermission(InsightsService.READ)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "You need access to the insights to run this report.");
        }
    }

    @Override
    public boolean covers(PortfolioScope scope) {
        Principal principal = CurrentPrincipal.require();
        if (!principal.hasPermission(InsightsService.READ)) {
            return false;
        }
        List<UUID> current = principal.branchFilter(InsightsService.READ, null);
        boolean branches = scope.branchIds() == null
                ? current == null
                : current == null || new HashSet<>(current).containsAll(scope.branchIds());
        boolean officers = principal.hasPermission(InsightsService.ALL_OFFICERS)
                || principal.userId().equals(scope.officerId());
        return branches && officers;
    }

    /** The totals of one group, built a position at a time. */
    private static final class Totals {
        final String key;
        final String label;
        long loans;
        long po;
        long io;
        long arrears;
        final long[] atRisk = new long[EDGES];
        final long[] bucketLoans = new long[BUCKETS.size()];
        final long[] bucketPo = new long[BUCKETS.size()];
        final long[] bucketArrears = new long[BUCKETS.size()];

        Totals(String key, String label) {
            this.key = key;
            this.label = label;
        }

        void add(Position l) {
            loans++;
            po += l.principalOutstandingMinor();
            io += l.interestOutstandingMinor();
            arrears += l.arrearsMinor();
            for (int e = 0; e < EDGES; e++) {
                if (l.daysPastDue() > PAR_EDGES[e]) {
                    atRisk[e] += l.principalOutstandingMinor();
                }
            }
            int b = BUCKETS.indexOf(l.bucket());
            bucketLoans[b]++;
            bucketPo[b] += l.principalOutstandingMinor();
            bucketArrears[b] += l.arrearsMinor();
        }

        ParGroup done(String newLabel) {
            List<ParBucket> buckets = new ArrayList<>();
            for (int b = 0; b < BUCKETS.size(); b++) {
                buckets.add(new ParBucket(
                        BUCKETS.get(b), bucketLoans[b], bucketPo[b], Metrics.bp(bucketPo[b], po), bucketArrears[b]));
            }
            return new ParGroup(
                    key,
                    newLabel,
                    loans,
                    po,
                    arrears,
                    Metrics.bp(atRisk[0], po),
                    Metrics.bp(atRisk[1], po),
                    Metrics.bp(atRisk[2], po),
                    Metrics.bp(atRisk[3], po),
                    buckets);
        }
    }

    /** The positions of the scope, a block at a time into {@code sink}; returns whether they came from the live tables. */
    private boolean read(PortfolioScope p, Runnable checkpoint, java.util.function.Consumer<Position> sink) {
        // The business date of the request, not of the moment the run executes (see PortfolioScope).
        LocalDate today = p.today();
        if (p.to().isAfter(today)) {
            throw ApiException.rule("range_in_future", "The date is today or earlier.");
        }
        Scope s = scopeOf(p);
        boolean live = s.to().equals(today);
        if (live && service.today().isAfter(today)) {
            // The date the figures were asked for has passed while the run waited: the loan tables now hold the new
            // day's
            // disbursements and repayments, so they are no longer the book of the date on the page. Read that date's
            // snapshot
            // when the nightly job has written it, and refuse (never a guess) when it has not.
            if (!positions.snapshotTaken(s.to())) {
                throw ApiException.rule(
                        "report_stale_as_of",
                        "The date of this report (" + s.to()
                                + ") passed before it was made and its snapshot is not written"
                                + " yet. Ask for it again once the nightly snapshot has run, or for today's date.");
            }
            live = false;
        }
        if (!live && !positions.snapshotTaken(s.to())) {
            throw ApiException.rule(
                    "snapshot_unavailable",
                    "No snapshot of the loan book exists for " + s.to() + ". Pick a later date.");
        }
        long[] seen = {0};
        java.util.function.Consumer<Position> counted = l -> {
            if ((++seen[0] % CHECKPOINT_EVERY) == 0) {
                checkpoint.run();
            }
            sink.accept(l);
        };
        checkpoint.run();
        if (live) {
            positions.forEachLive(s, counted);
        } else {
            positions.forEachSnapshot(s, s.to(), counted);
        }
        checkpoint.run();
        return live;
    }

    private Scope scopeOf(PortfolioScope p) {
        return new Scope(
                p.from(),
                p.to(),
                p.today(),
                p.branchIds(),
                p.officerId(),
                p.productId(),
                tenant.profile().currency());
    }

    private static Function<Position, UUID> groupKey(ParGroupBy groupBy) {
        return switch (groupBy) {
            case BRANCH -> Position::branchId;
            case PRODUCT -> Position::productId;
            default -> Position::officerUserId;
        };
    }

    @Override
    @Transactional(readOnly = true)
    public ParFigures par(PortfolioScope p, ParGroupBy groupBy, Runnable checkpoint) {
        Totals total = new Totals("all", "All loans");
        Map<UUID, Totals> groups = new LinkedHashMap<>();
        Function<Position, UUID> by = groupKey(groupBy);
        boolean live = read(p, checkpoint, l -> {
            total.add(l);
            if (groupBy != ParGroupBy.NONE) {
                groups.computeIfAbsent(by.apply(l), k -> new Totals(String.valueOf(k), null))
                        .add(l);
            }
        });
        List<ParGroup> out = new ArrayList<>();
        if (groupBy != ParGroupBy.NONE) {
            Map<UUID, String> names = names(groupBy, groups.keySet());
            for (Map.Entry<UUID, Totals> e : groups.entrySet()) {
                out.add(e.getValue().done(names.getOrDefault(e.getKey(), InsightsService.staffLabel(e.getKey()))));
            }
            out.sort(Comparator.comparing(ParGroup::label).thenComparing(ParGroup::key));
        }
        String currency = tenant.profile().currency();
        return new ParFigures(p.to(), live ? "live" : "snapshot", currency, total.done("All loans"), out);
    }

    // JUSTIFICATION-A3: the portfolio report (issue #217) needs borrowers and interest per group; it reads the same
    // positions as par().
    @Override
    @Transactional(readOnly = true)
    public PortfolioFigures portfolio(PortfolioScope p, ParGroupBy groupBy, Runnable checkpoint) {
        Totals total = new Totals("all", "All loans");
        Map<UUID, Totals> groups = new LinkedHashMap<>();
        Function<Position, UUID> by = groupKey(groupBy);
        boolean live = read(p, checkpoint, l -> {
            total.add(l);
            if (groupBy != ParGroupBy.NONE) {
                groups.computeIfAbsent(by.apply(l), k -> new Totals(String.valueOf(k), null))
                        .add(l);
            }
        });
        String column = switch (groupBy) {
            case NONE -> null;
            case BRANCH -> "branch_id";
            case PRODUCT -> "product_id";
            case OFFICER -> "officer_user_id";
        };
        Positions.Borrowers borrowers = positions.borrowers(scopeOf(p), p.to(), live, column);
        checkpoint.run();
        List<PortfolioGroup> out = new ArrayList<>();
        if (groupBy != ParGroupBy.NONE) {
            Map<UUID, String> names = names(groupBy, groups.keySet());
            for (Map.Entry<UUID, Totals> e : groups.entrySet()) {
                out.add(portfolioGroup(
                        e.getValue(),
                        names.getOrDefault(e.getKey(), InsightsService.staffLabel(e.getKey())),
                        borrowers.byGroup().getOrDefault(e.getKey(), 0L)));
            }
            out.sort(Comparator.comparing(PortfolioGroup::label).thenComparing(PortfolioGroup::key));
        }
        return new PortfolioFigures(
                p.to(),
                live ? "live" : "snapshot",
                tenant.profile().currency(),
                portfolioGroup(total, "All loans", borrowers.total()),
                out);
    }

    private static PortfolioGroup portfolioGroup(Totals t, String label, long borrowers) {
        return new PortfolioGroup(
                t.key,
                label,
                t.loans,
                borrowers,
                t.po,
                t.io,
                t.arrears,
                t.loans == 0 ? null : InsightsService.divideHalfUp(t.po, t.loans));
    }

    private Map<UUID, String> names(ParGroupBy by, Set<UUID> ids) {
        return switch (by) {
            case BRANCH -> queries.branchNames();
            case PRODUCT -> queries.productNames();
            default -> {
                Set<UUID> known = new HashSet<>(ids);
                known.remove(null);
                yield queries.userNames(known);
            }
        };
    }
}
