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
    private final FlowFigures flows;

    InsightsFiguresService(
            InsightsService service,
            InsightsQueries queries,
            Positions positions,
            CurrentTenant tenant,
            FlowFigures flows) {
        this.service = service;
        this.queries = queries;
        this.positions = positions;
        this.tenant = tenant;
        this.flows = flows;
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

    // JUSTIFICATION-A3: slice D3 (issue #222): the collections, disbursements, arrears and officer figures of the port,
    // over FlowFigures (grouped twins of the insights queries) and the positions par() reads.

    /** The group's name, or the staff label of an id the names do not hold, worked out only when it is needed. */
    private static String label(Map<UUID, String> names, UUID id) {
        String name = names.get(id);
        return name != null ? name : InsightsService.staffLabel(id);
    }

    /** A range figure cannot end after the business date the scope was resolved on. */
    private static void requireNotFuture(PortfolioScope p) {
        if (p.to().isAfter(p.today())) {
            throw ApiException.rule("range_in_future", "The range ends today or earlier.");
        }
    }

    private <K> List<K> sortedGroups(
            ParGroupBy by,
            Map<UUID, long[]> parts,
            java.util.function.BiFunction<UUID, String, K> make,
            Function<K, String> label) {
        List<K> out = new ArrayList<>();
        if (by == ParGroupBy.NONE) {
            return out;
        }
        Map<UUID, String> names = names(by, parts.keySet());
        for (Map.Entry<UUID, long[]> e : parts.entrySet()) {
            out.add(make.apply(e.getKey(), label(names, e.getKey())));
        }
        out.sort(Comparator.comparing(label).thenComparing(Object::toString));
        return out;
    }

    private static CollectionGroup collectionGroup(UUID key, String label, long[] a) {
        long parts = a[FlowFigures.ON_DUE_IN_RANGE]
                + a[FlowFigures.ARREARS_RECOVERED]
                + a[FlowFigures.PREPAID]
                + a[FlowFigures.PENALTIES]
                + a[FlowFigures.OVERPAID]
                + a[FlowFigures.RECOVERIES];
        return new CollectionGroup(
                String.valueOf(key),
                label,
                a[FlowFigures.EXPECTED],
                a[FlowFigures.ON_DUE],
                Metrics.bp(a[FlowFigures.ON_DUE], a[FlowFigures.EXPECTED]),
                a[FlowFigures.ON_DUE_IN_RANGE],
                a[FlowFigures.ARREARS_RECOVERED],
                a[FlowFigures.PREPAID],
                a[FlowFigures.PENALTIES],
                a[FlowFigures.OVERPAID],
                a[FlowFigures.RECOVERIES],
                a[FlowFigures.TOTAL],
                a[FlowFigures.TOTAL] - parts);
    }

    @Override
    @Transactional(readOnly = true)
    public CollectionFigures collections(PortfolioScope p, ParGroupBy groupBy, Runnable checkpoint) {
        requireNotFuture(p);
        checkpoint.run();
        Map<UUID, long[]> parts = flows.collections(scopeOf(p), groupBy);
        checkpoint.run();
        long[] total = new long[FlowFigures.PARTS];
        for (long[] a : parts.values()) {
            for (int i = 0; i < total.length; i++) {
                total[i] += a[i];
            }
        }
        List<CollectionGroup> groups = sortedGroups(
                groupBy, parts, (k, label) -> collectionGroup(k, label, parts.get(k)), CollectionGroup::label);
        return new CollectionFigures(
                p.from(), p.to(), tenant.profile().currency(), collectionGroup(null, "All loans", total), groups);
    }

    private static DisbursementGroup disbursementGroup(UUID key, String label, long[] a) {
        long loans = a[FlowFigures.LOANS];
        return new DisbursementGroup(
                String.valueOf(key),
                label,
                loans,
                a[FlowFigures.PRINCIPAL],
                a[FlowFigures.FEES],
                loans == 0 ? null : InsightsService.divideHalfUp(a[FlowFigures.PRINCIPAL], loans),
                a[FlowFigures.MEDIAN] < 0 ? null : a[FlowFigures.MEDIAN]);
    }

    @Override
    @Transactional(readOnly = true)
    public DisbursementFigures disbursements(
            PortfolioScope p,
            ParGroupBy groupBy,
            Runnable checkpoint,
            java.util.function.Consumer<DisbursementLine> lines) {
        requireNotFuture(p);
        checkpoint.run();
        Scope s = scopeOf(p);
        Map<UUID, long[]> parts = flows.disbursements(s, groupBy);
        long[] total = parts.remove(FlowFigures.TOTAL_KEY);
        List<DisbursementGroup> groups = sortedGroups(
                groupBy, parts, (k, label) -> disbursementGroup(k, label, parts.get(k)), DisbursementGroup::label);
        long[] seen = {0};
        flows.disbursementLines(s, l -> {
            if ((++seen[0] % CHECKPOINT_EVERY) == 0) {
                checkpoint.run();
            }
            lines.accept(l);
        });
        checkpoint.run();
        return new DisbursementFigures(
                p.from(), p.to(), tenant.profile().currency(), disbursementGroup(null, "All loans", total), groups);
    }

    /** The loans an arrears list reads its extras for at a time. */
    static final int ARREARS_BLOCK = 500;

    @Override
    @Transactional(readOnly = true)
    public void arrears(
            PortfolioScope p,
            String bucket,
            List<UUID> nameBranchIds,
            List<UUID> contactBranchIds,
            Runnable checkpoint,
            java.util.function.Consumer<ArrearsLine> lines) {
        requireNotFuture(p);
        // Only the loans in arrears are kept, to sort them; the book's other loans pass through.
        List<Position> late = new ArrayList<>();
        boolean live = read(p, checkpoint, l -> {
            if (l.daysPastDue() > 0 && (bucket == null || bucket.equals(l.bucket()))) {
                late.add(l);
            }
        });
        late.sort(Comparator.comparing((Position l) -> BUCKETS.indexOf(l.bucket()))
                .thenComparing(Comparator.comparingInt(Position::daysPastDue).reversed())
                .thenComparing(Position::loanNo));
        // A loan always has a responsible officer: officer_user_id is NOT NULL on lending_loans (V8) and on
        // lending_loan_daily_snapshots (V33); one who is no user is labelled by the end of their id.
        Set<UUID> officerIds = new HashSet<>();
        late.forEach(l -> officerIds.add(l.officerUserId()));
        Map<UUID, String> officers = queries.userNames(officerIds);
        java.time.ZoneId zone = tenant.profile().timezone();
        for (int from = 0; from < late.size(); from += ARREARS_BLOCK) {
            checkpoint.run();
            List<Position> block = late.subList(from, Math.min(late.size(), from + ARREARS_BLOCK));
            Map<UUID, FlowFigures.ArrearsExtras> extras =
                    flows.arrearsExtras(block.stream().map(Position::loanId).toList(), p.to(), zone);
            for (Position l : block) {
                // A loan whose row cannot be read keeps its position line, without the member and the extras.
                FlowFigures.ArrearsExtras x = extras.getOrDefault(l.loanId(), FlowFigures.ArrearsExtras.NONE);
                // The position's branch: the snapshot's on a past date, the loan's today.
                boolean name = nameBranchIds == null || nameBranchIds.contains(l.branchId());
                boolean contact = contactBranchIds == null || contactBranchIds.contains(l.branchId());
                lines.accept(new ArrearsLine(
                        l.bucket(),
                        l.loanNo(),
                        x.memberNo(),
                        name ? x.memberName() : null,
                        contact ? x.phone() : masked(x.phone()),
                        label(officers, l.officerUserId()),
                        l.daysPastDue(),
                        l.arrearsMinor(),
                        live ? x.penaltiesOutstandingMinor() : null,
                        l.principalOutstandingMinor(),
                        x.lastRepaymentOn(),
                        x.lastAction(),
                        x.lastActionOn(),
                        x.promisedDate(),
                        x.promisedAmountMinor()));
            }
        }
    }

    /** A phone outside the caller's contact branches: the first four and last four characters, as the collection lists. */
    static String masked(String e164) {
        if (e164 == null) {
            return null;
        }
        return e164.length() <= 8
                ? "*".repeat(e164.length())
                : e164.substring(0, 4) + "*".repeat(e164.length() - 8) + e164.substring(e164.length() - 4);
    }

    @Override
    @Transactional(readOnly = true)
    public OfficerFigures officers(PortfolioScope p, Runnable checkpoint) {
        requireNotFuture(p);
        Totals all = new Totals("all", "All officers");
        Map<UUID, Totals> stock = new LinkedHashMap<>();
        boolean live = read(p, checkpoint, l -> {
            all.add(l);
            stock.computeIfAbsent(l.officerUserId(), k -> new Totals(String.valueOf(k), null))
                    .add(l);
        });
        Scope s = scopeOf(p);
        Map<UUID, long[]> money = flows.collections(s, ParGroupBy.OFFICER);
        checkpoint.run();
        Map<UUID, long[]> paidOut = flows.disbursements(s, ParGroupBy.OFFICER);
        long[] paidOutTotal = paidOut.remove(FlowFigures.TOTAL_KEY);
        Map<UUID, long[]> activity = flows.officerActivity(s, tenant.profile().timezone());
        checkpoint.run();
        Set<UUID> ids = new java.util.LinkedHashSet<>(stock.keySet());
        ids.addAll(money.keySet());
        ids.addAll(paidOut.keySet());
        ids.addAll(activity.keySet());
        Map<UUID, String> labels = names(ParGroupBy.OFFICER, ids);
        long[] moneyTotal = new long[FlowFigures.PARTS];
        long[] activityTotal = new long[3];
        List<OfficerGroup> groups = new ArrayList<>();
        for (UUID id : ids) {
            Totals t = stock.getOrDefault(id, new Totals(String.valueOf(id), null));
            long[] m = money.getOrDefault(id, new long[FlowFigures.PARTS]);
            long[] d = paidOut.getOrDefault(id, new long[] {0, 0, 0, -1});
            long[] a = activity.getOrDefault(id, new long[3]);
            for (int i = 0; i < moneyTotal.length; i++) {
                moneyTotal[i] += m[i];
            }
            for (int i = 0; i < activityTotal.length; i++) {
                activityTotal[i] += a[i];
            }
            groups.add(officerGroup(String.valueOf(id), label(labels, id), t, d, m, a));
        }
        groups.sort(Comparator.comparing(OfficerGroup::label).thenComparing(OfficerGroup::key));
        return new OfficerFigures(
                p.from(),
                p.to(),
                live ? "live" : "snapshot",
                tenant.profile().currency(),
                officerGroup("all", "All officers", all, paidOutTotal, moneyTotal, activityTotal),
                groups);
    }

    private static OfficerGroup officerGroup(String key, String label, Totals t, long[] d, long[] m, long[] a) {
        return new OfficerGroup(
                key,
                label,
                t.loans,
                t.po,
                Metrics.bp(t.atRisk[1], t.po),
                d[FlowFigures.LOANS],
                d[FlowFigures.PRINCIPAL],
                m[FlowFigures.EXPECTED],
                m[FlowFigures.ON_DUE],
                Metrics.bp(m[FlowFigures.ON_DUE], m[FlowFigures.EXPECTED]),
                a[0],
                a[1],
                a[2]);
    }
}
