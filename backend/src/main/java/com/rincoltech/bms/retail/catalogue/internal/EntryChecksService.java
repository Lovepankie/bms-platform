package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.internal.EntryChecksApi.CategoryHint;
import com.rincoltech.bms.retail.catalogue.internal.EntryChecksApi.Confirmation;
import com.rincoltech.bms.retail.catalogue.internal.EntryChecksApi.EntryNorms;
import com.rincoltech.bms.retail.catalogue.internal.EntryChecksApi.NameHint;
import com.rincoltech.bms.retail.catalogue.internal.EntryChecksApi.NameHints;
import com.rincoltech.bms.retail.catalogue.internal.EntryChecksApi.Usage;
import com.rincoltech.bms.retail.catalogue.internal.EntryChecksRepository.HintRow;
import com.rincoltech.bms.retail.catalogue.internal.EntryChecksRepository.LastSale;
import com.rincoltech.bms.retail.catalogue.internal.EntryChecksRepository.LineQty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The entry warnings (issue #353, ADR-093): the history an entry screen warns against, the "did you mean" and category
 * hints while a new item is named, and the record of a warning the person confirmed. Rule based and deterministic: no
 * AI call (the owner parked it). A warning is never a refusal; the server's own rules (the price floor, stock) are unchanged.
 */
@Service
class EntryChecksService {

    /** How many of an item's newest lines of one kind are read. */
    static final int LINES_READ = 20;
    /** The fewest lines a median or a usual unit is given from. */
    static final int MIN_LINES = 3;
    /** A quantity ten times the median or more, or ten times less, is warned about. */
    static final int QUANTITY_FACTOR = 10;
    /** A selling price twice the last price or more, or half of it or less, is warned about. */
    static final int PRICE_FACTOR = 2;

    static final int HINTS_SHOWN = 5;
    static final int SHORTEST_TYPED = 3;
    static final int SHORTEST_WORD = 3;
    static final int MOST_WORDS = 6;
    static final int CANDIDATES = 200;
    static final int NAME_READ = 200;
    static final String ALL_SELLERS = "retail.sale.all_sellers";

    private final EntryChecksRepository repo;
    private final AuditLog audit;

    EntryChecksService(EntryChecksRepository repo, AuditLog audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    EntryNorms norms(UUID productId) {
        String unit = repo.baseUnit(productId);
        if (unit == null) {
            throw ApiException.notFound();
        }
        Principal principal = CurrentPrincipal.require();
        boolean prices = RetailCatalogue.maySeePrices(principal);
        // ADR-055: a seller who sees only their own figures gets the last price of their own sales, never another
        // seller's.
        UUID ownOnly = principal.hasPermission(ALL_SELLERS) ? null : principal.userId();
        LastSale last = prices ? repo.lastSale(productId, ownOnly) : null;
        return new EntryNorms(
                productId,
                unit,
                usage(repo.purchases(productId, LINES_READ), unit),
                usage(repo.sales(productId, LINES_READ), unit),
                usage(repo.moves(productId, LINES_READ), unit),
                last == null ? null : perBaseUnit(last.lineTotalMinor(), last.baseQty()),
                last == null ? null : last.saleDate(),
                QUANTITY_FACTOR,
                PRICE_FACTOR,
                MIN_LINES);
    }

    /** The median and the usual unit of the lines read; both absent under {@link #MIN_LINES} lines. */
    static Usage usage(List<LineQty> lines, String baseUnit) {
        if (lines.size() < MIN_LINES) {
            return new Usage(lines.size(), null, null, null);
        }
        LineQty usual = usualUnit(lines);
        return new Usage(
                lines.size(),
                plain(median(lines.stream().map(LineQty::baseQty).toList())),
                usual == null ? null : usual.unitId(),
                usual == null ? null : usual.unitId() == null ? baseUnit : usual.unit());
    }

    /** The middle quantity, or the mean of the two middle ones for an even count, to three decimals rounded half up. */
    static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        BigDecimal mid = n % 2 == 1
                ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.TWO, 4, RoundingMode.HALF_UP);
        return mid.setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * The unit of more than half of the lines (a line in the item's own unit counts under a null id), or null when none
     * holds a majority. The line returned is the newest in that unit, for its name.
     */
    static LineQty usualUnit(List<LineQty> lines) {
        Map<UUID, Integer> counts = new HashMap<>();
        Map<UUID, LineQty> newest = new LinkedHashMap<>();
        for (LineQty l : lines) {
            counts.merge(l.unitId(), 1, Integer::sum);
            newest.putIfAbsent(l.unitId(), l);
        }
        for (Map.Entry<UUID, LineQty> e : newest.entrySet()) {
            if (counts.get(e.getKey()) * 2 > lines.size()) {
                return e.getValue();
            }
        }
        return null;
    }

    static long perBaseUnit(long totalMinor, BigDecimal baseQty) {
        if (baseQty.signum() <= 0) {
            return totalMinor;
        }
        return BigDecimal.valueOf(totalMinor)
                .divide(baseQty, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private static String plain(BigDecimal qty) {
        return qty.stripTrailingZeros().toPlainString();
    }

    @Transactional(readOnly = true)
    NameHints nameHints(String description) {
        String typed = description == null ? "" : description.strip();
        // A name is at most 300 characters (the description column); a hint needs no more than its first 200.
        String key = repo.nameKey(typed.length() > NAME_READ ? typed.substring(0, NAME_READ) : typed);
        String compact = key.replace(" ", "");
        if (compact.length() < SHORTEST_TYPED) {
            return new NameHints(List.of(), null);
        }
        Map<UUID, HintRow> shown = new LinkedHashMap<>();
        for (HintRow r : repo.holding(compact, HINTS_SHOWN)) {
            shown.put(r.id(), r);
        }
        if (shown.size() < HINTS_SHOWN) {
            List<HintRow> close = new ArrayList<>();
            Map<UUID, Integer> distance = new HashMap<>();
            for (HintRow r :
                    repo.nearLength(compact.length(), CatalogueService.MAX_DISTANCE, CatalogueService.NEIGHBOURS)) {
                if (shown.containsKey(r.id())) {
                    continue;
                }
                int d = CatalogueService.editDistance(compact, r.compactKey());
                if (d <= CatalogueService.allowedDistance(compact, r.compactKey())) {
                    close.add(r);
                    distance.put(r.id(), d);
                }
            }
            close.sort(
                    Comparator.comparingInt((HintRow r) -> distance.get(r.id())).thenComparing(HintRow::code));
            for (HintRow r : close) {
                if (shown.size() >= HINTS_SHOWN) {
                    break;
                }
                shown.put(r.id(), r);
            }
        }
        List<NameHint> hints = shown.values().stream()
                .map(r -> new NameHint(r.id(), r.code(), r.description(), r.unit(), r.categoryId(), r.category()))
                .toList();
        return new NameHints(hints, categoryHint(key));
    }

    /** The words of a name key worth matching on: 3 characters or more, at most six, in order. */
    static List<String> words(String key) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : key.split(" ")) {
            if (w.length() >= SHORTEST_WORD && out.size() < MOST_WORDS) {
                out.add(w);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The category most of the items sharing the most words with the name are in (ties to the one with more such items in
     * all, then by name). Null when no item shares a word.
     */
    private CategoryHint categoryHint(String key) {
        List<String> words = words(key);
        if (words.isEmpty()) {
            return null;
        }
        List<HintRow> rows = repo.sharingWords(words, CANDIDATES);
        return bestCategory(words, rows);
    }

    static CategoryHint bestCategory(List<String> words, List<HintRow> rows) {
        int best = 0;
        Map<HintRow, Integer> scores = new LinkedHashMap<>();
        for (HintRow r : rows) {
            Set<String> own = new LinkedHashSet<>(Arrays.asList(r.nameKey().split(" ")));
            int score = (int) words.stream().filter(own::contains).count();
            scores.put(r, score);
            best = Math.max(best, score);
        }
        if (best == 0) {
            return null;
        }
        Map<UUID, List<HintRow>> top = new LinkedHashMap<>();
        Map<UUID, Integer> any = new HashMap<>();
        for (Map.Entry<HintRow, Integer> e : scores.entrySet()) {
            UUID category = e.getKey().categoryId();
            if (e.getValue() > 0) {
                any.merge(category, 1, Integer::sum);
            }
            if (e.getValue() == best) {
                top.computeIfAbsent(category, c -> new ArrayList<>()).add(e.getKey());
            }
        }
        return top.entrySet().stream()
                .min(Comparator.comparingInt((Map.Entry<UUID, List<HintRow>> e) ->
                                -e.getValue().size())
                        .thenComparingInt(e -> -any.getOrDefault(e.getKey(), 0))
                        .thenComparing(e -> e.getValue().getFirst().category()))
                .map(e -> new CategoryHint(
                        e.getKey(),
                        e.getValue().getFirst().category(),
                        e.getValue().size(),
                        e.getValue().getFirst().description()))
                .orElse(null);
    }

    /** Writes the confirmed warning to the audit log, in the caller's transaction; nothing else changes. */
    @Transactional
    void confirm(Confirmation c) {
        if (repo.baseUnit(c.productId()) == null) {
            throw ApiException.notFound();
        }
        // The branch is only a label on the record; one the caller cannot work at is left out rather than refused.
        UUID branch = c.branchId() != null && CurrentPrincipal.require().may("retail.stock.read", c.branchId())
                ? c.branchId()
                : null;
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("screen", c.screen());
        after.put("kind", c.kind());
        putIf(after, "entered_qty", c.enteredQty());
        putIf(after, "entered_unit_id", c.enteredUnitId());
        putIf(after, "median_qty", c.medianQty());
        putIf(after, "entered_price_minor", c.enteredPriceMinor());
        putIf(after, "last_price_minor", c.lastPriceMinor());
        audit.record(AuditLog.Entry.created(
                "retail.entry_warning.confirmed", CatalogueService.PRODUCT, c.productId(), branch, after));
    }

    private static void putIf(Map<String, Object> map, String key, Object value) {
        if (Objects.nonNull(value)) {
            map.put(key, value);
        }
    }
}
