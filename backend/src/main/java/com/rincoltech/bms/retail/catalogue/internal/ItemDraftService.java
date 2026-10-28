package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.help.SetupDrafts;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Category;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Unit;
import com.rincoltech.bms.retail.catalogue.internal.ItemDraftApi.ItemDraft;
import com.rincoltech.bms.retail.catalogue.internal.ItemDraftApi.ItemDraftRow;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * The setup helper's step for a shop (issue #626, owner decision D62, ADR-102): the owner's list, photo or file is read by
 * the free tier models into draft rows of the item import, with the shop's kind, categories, units and items as context so
 * names are matched and not repeated. Nothing is saved here: the rows go into the import's check on the screen, and only
 * the owner's tap on Add there adds them. The context is read in a short transaction of its own, then the providers are
 * asked with no connection held. Administrators only, like the import; the buying price only for a holder of
 * {@code retail.profit.read}.
 */
@Service
class ItemDraftService {

    static final String ADMIN = ProductImportService.ADMIN;

    /** How many of the shop's item names the model reads, so it does not draft them again. */
    static final int KNOWN_ITEMS = 300;

    static String instructions(boolean withCost, boolean withPacks) {
        return """
                You read a shop's list of goods for the BMS app: typed text, a photo of a stock book, a price list or a \
                supplier invoice, or the cells of a spreadsheet. Draft the shop's items from it. Use ONLY what the list \
                says: never invent an item, a price, a pack or a size. For each item give its name as the shop writes it \
                (with its size or brand when written), a category (one of the shop's own when it fits, else a short plain \
                name such as Drinks), its unit, the smallest unit it is sold in (one of the shop's own when it fits, such \
                as Piece, Kg or Litre), its selling price for one unit as a plain number, or null when the list has none, \
                """
                + (withCost
                        ? "its buying price for one unit, or null (on a supplier invoice the prices are buying prices: put"
                                + " them there and leave the selling price null), "
                        : "")
                + (withPacks
                        ? "and, when the list shows a bigger pack it comes in (a carton of 24, a bale of 10), that pack's"
                                + " unit and how many units are in it, else null. "
                        : "")
                + """
                Leave out the items the shop has already, totals, dates, names of \
                people, phone numbers and anything that is not an item. Reply with one JSON object only: \
                {"items": [{"name": "", "category": "", "unit": "", "sell_price": null\
                """
                + (withCost ? ", \"buy_price\": null" : "")
                + (withPacks ? ", \"pack_unit\": null, \"pack_size\": null" : "")
                + "}]}";
    }

    private final CatalogueRepository repo;
    private final SetupDrafts drafts;
    private final CurrentTenant tenant;
    private final ModuleSettings settings;
    private final ObjectMapper mapper;
    private final TransactionTemplate reads;

    ItemDraftService(
            CatalogueRepository repo,
            SetupDrafts drafts,
            CurrentTenant tenant,
            ModuleSettings settings,
            ObjectMapper mapper,
            PlatformTransactionManager transactions) {
        this.repo = repo;
        this.drafts = drafts;
        this.tenant = tenant;
        this.settings = settings;
        this.mapper = mapper;
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
    }

    private record Context(List<String> categories, List<String> units, String words, int exponent) {}

    ItemDraft draft(String text, MultipartFile file, String shopType) {
        Principal principal = CurrentPrincipal.require();
        if (!principal.hasPermission(ADMIN)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Only an administrator can add items from a list.");
        }
        drafts.requireOn();
        SetupDrafts.Upload upload = SetupDrafts.Upload.of(file);
        boolean withCost = principal.hasPermission("retail.profit.read");
        Context context = reads.execute(tx -> context(shopType));
        // A pack needs the shop's pack capability; while it is off the import refuses one, so none is asked for or
        // kept.
        boolean withPacks = Boolean.TRUE.equals(
                settings.group("retail").get(com.rincoltech.bms.retail.catalogue.RetailCatalogue.UNITS_CAPABILITY));
        SetupDrafts.Result<List<ItemDraftReader.Row>> result = drafts.draft(
                new SetupDrafts.Request("retail", instructions(withCost, withPacks), context.words(), text, upload),
                new ItemDraftReader(
                        mapper, context.categories(), context.units(), context.exponent(), withCost, withPacks));
        return new ItemDraft(
                result.reason(),
                result.drafted().orElse(List.of()).stream()
                        .map(r -> new ItemDraftRow(
                                r.name(),
                                r.category(),
                                r.unit(),
                                r.sellMinor(),
                                r.buyMinor(),
                                r.packUnit(),
                                r.packSize()))
                        .toList(),
                context.exponent());
    }

    /** What the shop has, in words for the model: its kind, active categories, units with their usual packs, and items. */
    private Context context(String shopType) {
        List<String> categories = repo.categories().stream()
                .filter(Category::active)
                .map(Category::name)
                .toList();
        List<Unit> units = repo.units().stream().filter(Unit::active).toList();
        List<String> items = repo.productLabels(KNOWN_ITEMS);
        Object kind = settings.group("retail").get("profile");
        StringBuilder words = new StringBuilder();
        if (shopType != null && !shopType.isBlank()) {
            words.append("Kind of shop: ")
                    .append(shopType.strip(), 0, Math.min(60, shopType.strip().length()))
                    .append(".\n");
        } else if (kind instanceof String k) {
            words.append("Kind of business: ").append(k.replace('_', ' ')).append(".\n");
        }
        words.append("Categories: ")
                .append(categories.isEmpty() ? "none yet" : String.join(", ", categories))
                .append(".\n");
        words.append("Units: ")
                .append(
                        units.isEmpty()
                                ? "none yet"
                                : units.stream()
                                        .map(u -> u.usualPack() == null
                                                ? u.name()
                                                : u.name() + " (usually "
                                                        + new java.math.BigDecimal(u.usualPack()
                                                                        .size())
                                                                .stripTrailingZeros()
                                                                .toPlainString()
                                                        + " " + u.usualPack().ofUnit() + ")")
                                        .collect(Collectors.joining(", ")))
                .append(".\n");
        words.append("Items the shop has already: ")
                .append(items.isEmpty() ? "none yet" : String.join("; ", items))
                .append('.');
        return new Context(
                categories,
                units.stream().map(Unit::name).toList(),
                words.toString(),
                repo.currencyExponent(tenant.profile().currency()));
    }
}
