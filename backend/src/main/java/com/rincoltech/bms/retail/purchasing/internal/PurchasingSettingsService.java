package com.rincoltech.bms.retail.purchasing.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two tolerances of the purchasing capability (ADR-033, issue #198), the switch that holds an uncosted receipt back
 * from transfers (issue #199) and the read-only view of the order approval threshold. A tenant that never saved them reads zero for both, the strictest setting: a receipt cannot exceed its order
 * and every difference on a supplier invoice needs a reason. Saved under {@code If-Match} on the version, audited with the
 * old and new values; changing them needs {@code core.settings.manage}.
 */
@Service
class PurchasingSettingsService {

    @Schema(name = "RetailPurchasingSettings")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Settings(
            @Schema(
                    description =
                            "How far above the ordered quantity a receipt line may go, in basis points of the ordered"
                                    + " quantity. 0 means a receipt never exceeds its order")
            int overReceiptBp,

            @Schema(
                    description =
                            "How far a supplier invoice may differ in value from the receipts it clears, in basis points"
                                    + " of the value cleared, before it needs a reason. 0 means any difference needs one")
            int billToleranceBp,

            @Schema(
                    description =
                            "Read only: the approval threshold for sending a purchase order (action retail_purchase_order),"
                                    + " present only when one is set; while it is absent an order needs no second administrator")
            Long orderApprovalThresholdMinor,

            @Schema(
                    description =
                            "Issue #199: while on, stock of a product on a goods received note that is held as received and not"
                                    + " costed cannot be moved out of the receiving branch by a transfer. Off by default")
            boolean requireCostingBeforeSale,

            int version) {}

    @Schema(name = "RetailPurchasingSettingsUpdate", description = "Omitted fields are unchanged")
    record Update(
            @Min(0) @Max(10000) Integer overReceiptBp,
            @Min(0) @Max(10000) Integer billToleranceBp,
            Boolean requireCostingBeforeSale) {}

    private final JdbcClient jdbc;
    private final TenantSettings tenantSettings;
    private final RetailCapabilities capabilities;
    private final AuditLog audit;
    private final CurrentTenant tenant;

    PurchasingSettingsService(
            JdbcClient jdbc,
            TenantSettings tenantSettings,
            RetailCapabilities capabilities,
            AuditLog audit,
            CurrentTenant tenant) {
        this.jdbc = jdbc;
        this.tenantSettings = tenantSettings;
        this.capabilities = capabilities;
        this.audit = audit;
        this.tenant = tenant;
    }

    /** What is in force, for the receipt and bill rules; zero for both when nothing was saved. */
    Settings current() {
        return jdbc.sql("""
                        SELECT over_receipt_bp, bill_tolerance_bp, require_costing_before_sale, version
                          FROM retail_purchasing_settings
                        """)
                .query((rs, n) -> new Settings(rs.getInt(1), rs.getInt(2), threshold(), rs.getBoolean(3), rs.getInt(4)))
                .optional()
                .orElseGet(() -> new Settings(0, 0, threshold(), false, 1));
    }

    @Transactional(readOnly = true)
    Settings get() {
        capabilities.require(RetailCapabilities.PURCHASING);
        return current();
    }

    @Transactional
    Settings update(String ifMatch, Update u) {
        capabilities.require(RetailCapabilities.PURCHASING);
        int expected = Versions.fromIfMatch(ifMatch);
        // The row exists from the first save; creating it is the same lock-then-compare as an update.
        jdbc.sql("""
                        INSERT INTO retail_purchasing_settings (tenant_id) VALUES (current_setting('app.tenant_id')::uuid)
                        ON CONFLICT DO NOTHING
                        """).update();
        Settings before = jdbc.sql("""
                        SELECT over_receipt_bp, bill_tolerance_bp, require_costing_before_sale, version
                          FROM retail_purchasing_settings FOR UPDATE
                        """)
                .query((rs, n) -> new Settings(rs.getInt(1), rs.getInt(2), threshold(), rs.getBoolean(3), rs.getInt(4)))
                .single();
        // A tenant that never saved reads version 1, and its first save sends 1; the row it just created is version 1.
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        int over = u.overReceiptBp() == null ? before.overReceiptBp() : u.overReceiptBp();
        int tolerance = u.billToleranceBp() == null ? before.billToleranceBp() : u.billToleranceBp();
        boolean costing =
                u.requireCostingBeforeSale() == null ? before.requireCostingBeforeSale() : u.requireCostingBeforeSale();
        if (over < 0 || over > 10000 || tolerance < 0 || tolerance > 10000) {
            throw ApiException.validation(
                    List.of(new FieldProblem("tolerance", "invalid", "Tolerances are 0 to 10000 basis points.")));
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        if (over != before.overReceiptBp()) {
            was.put("over_receipt_bp", before.overReceiptBp());
            now.put("over_receipt_bp", over);
        }
        if (tolerance != before.billToleranceBp()) {
            was.put("bill_tolerance_bp", before.billToleranceBp());
            now.put("bill_tolerance_bp", tolerance);
        }
        if (costing != before.requireCostingBeforeSale()) {
            was.put("require_costing_before_sale", before.requireCostingBeforeSale());
            now.put("require_costing_before_sale", costing);
        }
        if (now.isEmpty()) {
            return before;
        }
        UUID by = CurrentPrincipal.require().userId();
        jdbc.sql("""
                        UPDATE retail_purchasing_settings SET over_receipt_bp = ?, bill_tolerance_bp = ?,
                               require_costing_before_sale = ?, updated_at = now(), updated_by = ?, version = version + 1
                        """).params(over, tolerance, costing, by).update();
        audit.record(new AuditLog.Entry(
                "retail.purchasing.settings_changed",
                "retail.purchasing_settings",
                tenant.profile().id(),
                null,
                was,
                now));
        return current();
    }

    private Long threshold() {
        long t = tenantSettings.approvalThresholdMinor(PurchaseOrderAction.TYPE);
        return t > 0 ? t : null;
    }
}
