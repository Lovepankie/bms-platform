package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.help.DraftJson;
import com.rincoltech.bms.core.help.SetupDrafts;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a model's answer into draft rows of the item import (issue #626, ADR-102). Deterministic, so the same answer always
 * gives the same rows: names are trimmed and cut to the import's lengths, a category or unit the shop has is written the
 * shop's way (ignoring case and a plural s), a price becomes minor units of the shop's currency, a pack is kept only with a
 * unit other than the item's and a size of at least 1, the same name in the same unit is kept once, and the buying price is
 * dropped for a caller who may not see buying prices. Nothing here decides what is added: the rows fill the import's
 * check, which keeps its own rules.
 */
final class ItemDraftReader implements SetupDrafts.Reader<List<ItemDraftReader.Row>> {

    static final int MAX_ROWS = 500;

    /** One drafted item: what a row of the import file holds. Amounts in minor units; a missing price is null. */
    record Row(
            String name,
            String category,
            String unit,
            Long sellMinor,
            Long buyMinor,
            String packUnit,
            String packSize) {}

    private final ObjectMapper mapper;
    private final Map<String, String> categories = new HashMap<>();
    private final Map<String, String> units = new HashMap<>();
    private final int exponent;
    private final boolean withCost;
    private final boolean withPacks;

    ItemDraftReader(ObjectMapper mapper, List<String> categories, List<String> units, int exponent, boolean withCost) {
        this(mapper, categories, units, exponent, withCost, true);
    }

    /** {@code withPacks} false while the shop's pack capability is off: no pack is kept, as the import would refuse it. */
    ItemDraftReader(
            ObjectMapper mapper,
            List<String> categories,
            List<String> units,
            int exponent,
            boolean withCost,
            boolean withPacks) {
        this.mapper = mapper;
        categories.forEach(c -> this.categories.putIfAbsent(key(c), c));
        units.forEach(u -> this.units.putIfAbsent(key(u), u));
        this.exponent = exponent;
        this.withCost = withCost;
        this.withPacks = withPacks;
    }

    @Override
    public Optional<List<Row>> read(String answer) {
        JsonNode items = DraftJson.array(mapper, answer, "items");
        if (items == null) {
            return Optional.empty();
        }
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : items) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            String name = DraftJson.text(item, "name", 300);
            if (name == null) {
                continue;
            }
            String unit = known(units, DraftJson.text(item, "unit", 30));
            if (unit == null) {
                unit = known(units, "Piece");
            }
            if (!seen.add(key(name) + "\0" + key(unit))) {
                continue;
            }
            String category = known(categories, DraftJson.text(item, "category", 100));
            String packUnit = known(units, DraftJson.text(item, "pack_unit", 30));
            BigDecimal size = DraftJson.number(item, "pack_size");
            size = size == null ? null : size.stripTrailingZeros();
            String packSize = null;
            if (withPacks
                    && packUnit != null
                    && size != null
                    && !key(packUnit).equals(key(unit))
                    && size.compareTo(BigDecimal.ONE) >= 0
                    && size.compareTo(BigDecimal.valueOf(100_000)) <= 0
                    && size.scale() <= 3) {
                packSize = size.stripTrailingZeros().toPlainString();
            } else {
                packUnit = null;
            }
            rows.add(new Row(
                    name,
                    category == null ? "General" : category,
                    unit,
                    minor(DraftJson.number(item, "sell_price")),
                    withCost ? minor(DraftJson.number(item, "buy_price")) : null,
                    packUnit,
                    packSize));
        }
        return Optional.of(List.copyOf(rows));
    }

    @Override
    public int rows(List<Row> draft) {
        return draft.size();
    }

    /** An amount above 0 in minor units, rounded to the currency's decimals; anything else is no price. */
    private Long minor(BigDecimal major) {
        if (major == null || major.signum() <= 0) {
            return null;
        }
        long minor = major.setScale(exponent, RoundingMode.HALF_UP)
                .movePointRight(exponent)
                .longValue();
        return minor <= 0 || minor > RetailCatalogue.MAX_AMOUNT_MINOR ? null : minor;
    }

    /** The shop's own spelling of a name it has, else the name as drafted. */
    private static String known(Map<String, String> names, String name) {
        if (name == null) {
            return null;
        }
        String k = key(name);
        String found = names.get(k);
        if (found == null && k.endsWith("s")) {
            found = names.get(k.substring(0, k.length() - 1));
        }
        if (found == null) {
            found = names.get(k + "s");
        }
        return found != null ? found : name;
    }

    static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
