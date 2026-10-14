package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.purchasing.internal.LotRepository.HeldLine;
import com.rincoltech.bms.retail.purchasing.internal.LotRepository.Lot;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.TransferOutGuard;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The mitigation of trade-capabilities.md section 7.2 (issue #199): while the purchasing capability is on and the tenant
 * requires costing (the setting {@code require_costing_before_sale}), the units of a product that belong to a goods received
 * note held as received and not costed cannot be transferred out of the receiving branch. Stock is not tracked by lot, so
 * the held units are the quantity the held receipt lines received, up to what the branch holds; stock of the same
 * product from other deliveries can still move, as far as it goes. The hold is released by the landed cost that
 * allocates to the receipt, or by an administrator with a reason. With the capability or the setting off this does
 * nothing, and no query runs beyond reading the two switches.
 */
@Component
class CostingTransferGuard implements TransferOutGuard {

    private final RetailCapabilities capabilities;
    private final PurchasingSettingsService settings;
    private final LotRepository lotRepo;
    private final ReceiptCosting costing;
    private final StockLedger stock;

    CostingTransferGuard(
            RetailCapabilities capabilities,
            PurchasingSettingsService settings,
            LotRepository lotRepo,
            ReceiptCosting costing,
            StockLedger stock) {
        this.capabilities = capabilities;
        this.settings = settings;
        this.lotRepo = lotRepo;
        this.costing = costing;
        this.stock = stock;
    }

    @Override
    public void check(UUID fromBranchId, Map<UUID, BigDecimal> quantities) {
        if (!capabilities.enabled(RetailCapabilities.PURCHASING)
                || !settings.current().requireCostingBeforeSale()) {
            return;
        }
        List<HeldLine> held = lotRepo.heldLines(fromBranchId, quantities.keySet());
        if (held.isEmpty()) {
            return;
        }
        Map<UUID, Lot> lots =
                lotRepo.lots(held.stream().map(HeldLine::receiptLineId).toList());
        // The units a held receipt reserves are the quantity it received, whatever first in, first out would say about
        // them
        // later: if the oldest units were read as the ones that left, moving the newer stock would free the held units.
        Map<UUID, BigDecimal> heldUnits = new HashMap<>();
        Map<UUID, String> firstReceipt = new HashMap<>();
        for (HeldLine h : held) {
            Lot lot = lots.get(h.receiptLineId());
            heldUnits.merge(h.productId(), lot.qty().subtract(lot.returnedQty()), BigDecimal::add);
            firstReceipt.putIfAbsent(h.productId(), h.receiptNo());
        }
        for (Map.Entry<UUID, BigDecimal> e : quantities.entrySet()) {
            BigDecimal reserved = heldUnits.get(e.getKey());
            if (reserved == null || reserved.signum() == 0) {
                continue;
            }
            BigDecimal balance = stock.balance(fromBranchId, e.getKey());
            BigDecimal free = balance.subtract(reserved.min(balance));
            if (e.getValue().compareTo(free) > 0) {
                throw ApiException.rule(
                        "receipt_not_costed",
                        "Goods received note " + firstReceipt.get(e.getKey())
                                + " is held as received and not costed, and"
                                + " this business requires costing before stock moves on. Only "
                                + free.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString()
                                + " of this item can go now. Add the landed cost, or release the hold with a reason.");
            }
        }
    }
}
