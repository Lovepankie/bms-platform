package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.tenancy.Branches.Branch;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.stock.BranchRole;
import com.rincoltech.bms.retail.stock.BranchRoles;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import com.rincoltech.bms.retail.stock.internal.LocationApi.BranchValue;
import com.rincoltech.bms.retail.stock.internal.LocationApi.InFlightReport;
import com.rincoltech.bms.retail.stock.internal.LocationApi.InFlightTransfer;
import com.rincoltech.bms.retail.stock.internal.LocationApi.LocationBalance;
import com.rincoltech.bms.retail.stock.internal.LocationApi.LocationBranch;
import com.rincoltech.bms.retail.stock.internal.LocationApi.LocationReport;
import com.rincoltech.bms.retail.stock.internal.LocationApi.LocationRow;
import com.rincoltech.bms.retail.stock.internal.LocationApi.LocationTotals;
import com.rincoltech.bms.retail.stock.internal.LocationApi.RoleQuantity;
import com.rincoltech.bms.retail.stock.internal.LocationApi.RoleValue;
import com.rincoltech.bms.retail.stock.internal.LocationRepository.Holding;
import com.rincoltech.bms.retail.stock.internal.LocationRepository.OnTheWay;
import com.rincoltech.bms.retail.stock.internal.StockRepository.ProductTotal;
import com.rincoltech.bms.retail.stock.internal.TransferApi.Transfer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The location reports of docs/specs/trade-capabilities.md section 8: stock by store and shop with a
 * combined total, and the transfers on their way. Branch scope applies per column (ADR-017); cost
 * and value appear only with {@code retail.profit.read} in every branch of the report, and per
 * branch where a figure belongs to one branch. Stock on its way is its own column: it is in neither
 * branch's balance, so the combined total neither counts it twice nor loses it.
 */
@Service
class LocationService {

    static final String READ = "retail.stock.read";
    static final String PROFIT_READ = "retail.profit.read";
    /** Inter-branch clearing is shared with lending; only retail transfer entries are read (spec 9.3). */
    static final String CLEARING = "interbranch_clearing";

    private final StockRepository stockRepo;
    private final LocationRepository repo;
    private final TransferRepository transfers;
    private final RetailBranchContext context;
    private final BranchRoles roles;
    private final RetailCapabilities capabilities;
    private final LedgerAccounts accounts;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    LocationService(
            StockRepository stockRepo,
            LocationRepository repo,
            TransferRepository transfers,
            RetailBranchContext context,
            BranchRoles roles,
            RetailCapabilities capabilities,
            LedgerAccounts accounts,
            CurrentTenant tenant,
            BusinessClock clock) {
        this.stockRepo = stockRepo;
        this.repo = repo;
        this.transfers = transfers;
        this.context = context;
        this.roles = roles;
        this.capabilities = capabilities;
        this.accounts = accounts;
        this.tenant = tenant;
        this.clock = clock;
    }

    /** The branches, stores first, then shops, then both; head office first within a role. */
    private List<Branch> ordered(List<Branch> visible, Map<UUID, BranchRole> role) {
        return visible.stream()
                .sorted(Comparator.comparingInt((Branch b) -> role.get(b.id()).ordinal()))
                .toList();
    }

    @Transactional(readOnly = true)
    LocationReport stockByLocation(String query, UUID categoryId, boolean negativeOnly, Integer limit, String cursor) {
        Principal principal = CurrentPrincipal.require();
        List<Branch> visible = context.visible(READ, stockRepo.branchesHoldingBalance(context.inactiveInScope(READ)));
        Map<UUID, BranchRole> role = new LinkedHashMap<>();
        visible.forEach(b -> role.put(b.id(), roles.roleOf(b.id())));
        List<Branch> columns = ordered(visible, role);
        List<UUID> ids = columns.stream().map(Branch::id).toList();
        String currency = tenant.profile().currency();
        List<LocationBranch> head = columns.stream()
                .map(b -> new LocationBranch(
                        b.id(),
                        b.code(),
                        b.name(),
                        b.headOffice(),
                        role.get(b.id()).wire(),
                        !b.active()))
                .toList();
        if (ids.isEmpty()) {
            return new LocationReport(
                    currency,
                    head,
                    List.of(),
                    new LocationTotals(List.of(), List.of(), null, null, null, null, null),
                    null);
        }
        boolean cost = ids.stream().allMatch(b -> principal.may(PROFIT_READ, b));
        int size = limit == null ? StockService.DEFAULT_LIMIT : Math.clamp(limit, 1, StockService.MAX_LIMIT);
        Search.Query search = Search.parse(query);
        Search.After position = Search.unwrap(search, cursor);
        Cursor.Key after = Cursor.decodeKey(position.cursor()).orElse(null);
        List<Search.Ranked<ProductTotal>> rows = stockRepo.allBranches(
                ids,
                search,
                categoryId,
                negativeOnly,
                null,
                position.rank(),
                after == null ? null : after.sortKey(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<Search.Ranked<ProductTotal>> ranked = more ? rows.subList(0, size) : rows;
        List<ProductTotal> page = ranked.stream().map(Search.Ranked::row).toList();
        List<UUID> productIds = page.stream().map(ProductTotal::id).toList();
        Map<UUID, Map<UUID, BigDecimal>> held = stockRepo.balances(productIds, ids);
        Map<UUID, OnTheWay> way = repo.onTheWay(productIds, ids);
        List<BranchRole> present =
                columns.stream().map(b -> role.get(b.id())).distinct().sorted().toList();

        List<LocationRow> items = new ArrayList<>();
        for (ProductTotal p : page) {
            Map<UUID, BigDecimal> mine = held.getOrDefault(p.id(), Map.of());
            Map<BranchRole, BigDecimal> bySubtotal = new LinkedHashMap<>();
            present.forEach(r -> bySubtotal.put(r, BigDecimal.ZERO));
            List<LocationBalance> balances = new ArrayList<>();
            long value = 0;
            for (Branch b : columns) {
                BigDecimal q = mine.getOrDefault(b.id(), BigDecimal.ZERO);
                bySubtotal.merge(role.get(b.id()), q, BigDecimal::add);
                boolean mayCost = principal.may(PROFIT_READ, b.id());
                Long branchValue = mayCost ? Quantities.value(q, p.costMinor()) : null;
                if (branchValue != null) {
                    value = Math.addExact(value, branchValue);
                }
                balances.add(new LocationBalance(b.id(), Quantities.format(q), q.signum() < 0, branchValue));
            }
            OnTheWay transit = way.get(p.id());
            BigDecimal transitQty = transit == null ? BigDecimal.ZERO : transit.qty();
            long transitValue = transit == null ? 0 : transit.valueMinor();
            items.add(new LocationRow(
                    p.id(),
                    p.code(),
                    p.description(),
                    p.categoryId(),
                    p.category(),
                    p.unit(),
                    balances,
                    bySubtotal.entrySet().stream()
                            .map(e -> new RoleQuantity(e.getKey().wire(), Quantities.format(e.getValue())))
                            .toList(),
                    Quantities.format(transitQty),
                    Quantities.format(p.total().add(transitQty)),
                    p.negative(),
                    p.sellMinor(),
                    cost ? p.costMinor() : null,
                    cost ? value : null,
                    cost ? transitValue : null,
                    cost ? Math.addExact(value, transitValue) : null));
        }
        String next = more
                ? Search.wrap(
                        search,
                        ranked.getLast().rank(),
                        Cursor.encode(
                                page.getLast().code() + "|" + page.getLast().id()))
                : null;
        return new LocationReport(currency, head, items, totals(principal, columns, role, ids, cost), next);
    }

    /**
     * Over every non-zero balance of the report's branches, not only the page. The ledger side is
     * the inventory account of those branches: stock on its way left it at dispatch, so held value
     * at cost less the account is the same revaluation difference the valuation shows.
     */
    private LocationTotals totals(
            Principal principal, List<Branch> columns, Map<UUID, BranchRole> role, List<UUID> ids, boolean cost) {
        Map<UUID, Long> byBranch = new LinkedHashMap<>();
        for (Holding h : repo.holdings(ids)) {
            if (principal.may(PROFIT_READ, h.branchId())) {
                byBranch.merge(h.branchId(), Quantities.value(h.qty(), h.costMinor()), Math::addExact);
            }
        }
        List<BranchValue> branchValues = columns.stream()
                .filter(b -> principal.may(PROFIT_READ, b.id()))
                .map(b -> new BranchValue(b.id(), byBranch.getOrDefault(b.id(), 0L)))
                .toList();
        List<RoleValue> roleValues = new ArrayList<>();
        for (BranchRole r :
                columns.stream().map(b -> role.get(b.id())).distinct().sorted().toList()) {
            List<Branch> ofRole =
                    columns.stream().filter(b -> role.get(b.id()) == r).toList();
            if (ofRole.stream().allMatch(b -> principal.may(PROFIT_READ, b.id()))) {
                roleValues.add(new RoleValue(
                        r.wire(),
                        ofRole.stream()
                                .mapToLong(b -> byBranch.getOrDefault(b.id(), 0L))
                                .sum()));
            }
        }
        if (!cost) {
            return new LocationTotals(branchValues, roleValues, null, null, null, null, null);
        }
        long held = byBranch.values().stream().mapToLong(Long::longValue).sum();
        long transit = repo.onTheWayValue(ids);
        Map<UUID, Long> inventory = accounts.balanceByBranch(
                "inventory", clock.today(tenant.profile().timezone()));
        long ledger = ids.stream().mapToLong(b -> inventory.getOrDefault(b, 0L)).sum();
        return new LocationTotals(
                branchValues,
                roleValues,
                held,
                transit,
                Math.addExact(held, transit),
                ledger,
                Math.subtractExact(held, ledger));
    }

    /**
     * The open dispatches the caller can see (their source or destination is a branch they read),
     * the longest wait first. The value is the sum of those transfers' dispatch cost, built from the
     * transfers and never from the clearing account, which lending shares. A caller who reads every
     * branch with cost also gets the net of the retail transfer entries in clearing and whether it
     * equals every open dispatch.
     */
    @Transactional(readOnly = true)
    InFlightReport inFlight() {
        capabilities.require(RetailCapabilities.TRANSFER_RECEIPT);
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter(READ, null);
        LocalDate today = clock.today(tenant.profile().timezone());
        List<Transfer> open = transfers.inTransit(filter).stream()
                .map(TransferService::visible)
                .toList();
        List<InFlightTransfer> items = open.stream()
                .map(t -> new InFlightTransfer(
                        t.id(),
                        t.fromBranchId(),
                        t.toBranchId(),
                        t.transferDate(),
                        t.createdAt(),
                        (int) Math.max(0, ChronoUnit.DAYS.between(t.transferDate(), today)),
                        t.note(),
                        t.lines(),
                        t.costTotalMinor()))
                .toList();
        boolean costShown = open.stream().allMatch(t -> t.costTotalMinor() != null)
                && (!open.isEmpty() || principal.hasPermission(PROFIT_READ));
        Long value =
                costShown ? open.stream().mapToLong(Transfer::costTotalMinor).sum() : null;
        Long clearing = null;
        Boolean reconciled = null;
        boolean everywhere = principal.scopeOf(READ).map(s -> s.all()).orElse(false)
                && principal.scopeOf(PROFIT_READ).map(s -> s.all()).orElse(false);
        if (everywhere) {
            long allOpen = transfers.inTransit(null).stream()
                    .mapToLong(Transfer::costTotalMinor)
                    .sum();
            clearing = accounts.balanceBySource(CLEARING, "retail", TransferService.TRANSFER);
            reconciled = clearing == allOpen;
        }
        return new InFlightReport(tenant.profile().currency(), items, value, clearing, reconciled);
    }
}
