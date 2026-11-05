package com.rincoltech.bms.core.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Issue #852 (ADR-026 decision 5): every call of {@link Idempotency#once} in the main sources, with the test that
 * proves, in the caller's own module, that another person's request with a stored key is refused
 * ({@code KeyOwnership.refused}). A new call site, or one more in a listed class, fails here until it is listed with a
 * cross-person test of its module; a listed test that no longer makes that check fails too.
 */
class IdempotencyCallersTest {

    private static final Path MAIN = Path.of("src/main/java/com/rincoltech/bms");
    private static final Path TEST = Path.of("src/test/java/com/rincoltech/bms");
    /** A call of {@code once}, or of {@code onceAsJob} by a job or a callback. */
    private static final Pattern ONCE = Pattern.compile("\\.once(?:AsJob)?\\(");

    /** Caller (path under com/rincoltech/bms) to its number of call sites and its module's cross-person test. */
    private static final Map<String, Caller> CALLERS = new TreeMap<>(Map.ofEntries(
            caller("core/payments/internal/IntentService", 1, "core/payments/internal/UnallocatedReceiptsIT"),
            caller("core/payments/internal/ReceiptService", 2, "core/payments/internal/UnallocatedReceiptsIT"),
            caller("core/reporting/internal/RunService", 1, "core/reporting/internal/ReportRunsIT"),
            caller("core/reporting/internal/DataExportService", 1, "core/reporting/internal/ReportRunsIT"),
            caller("core/platform/internal/BusinessService", 1, "core/platform/OperatorScreensIT"),
            caller("core/referrals/internal/ReferralService", 1, "core/referrals/ReferralsIT"),
            caller("lending/investments/internal/InvestmentService", 6, "lending/investments/InvestmentsIT"),
            caller("lending/loans/internal/ServicingService", 5, "lending/loans/LoanServicingIT"),
            caller("lending/loans/internal/ExistingLoanService", 1, "lending/loans/LoanServicingIT"),
            caller("lending/loans/internal/ItemWaiverService", 1, "lending/loans/LoanServicingIT"),
            caller("lending/savings/internal/SavingsService", 4, "lending/savings/SavingsIT"),
            caller("retail/cashbook/internal/AdvanceService", 4, "retail/cashbook/RetailCashbookKeyPerPersonIT"),
            caller("retail/cashbook/internal/BankingService", 4, "retail/cashbook/RetailCashbookKeyPerPersonIT"),
            caller("retail/cashbook/internal/ExpenseService", 3, "retail/cashbook/RetailCashbookKeyPerPersonIT"),
            caller("retail/cashbook/internal/SavingsService", 2, "retail/cashbook/RetailCashbookKeyPerPersonIT"),
            caller("retail/sales/internal/SalesService", 2, "retail/sales/RetailSalesKeyPerPersonIT"),
            caller("retail/sales/internal/CustomerOrdersService", 4, "retail/sales/RetailSalesKeyPerPersonIT"),
            caller("retail/sales/internal/QuotationsService", 2, "retail/sales/RetailSalesKeyPerPersonIT"),
            caller("retail/sales/internal/DocumentsService", 2, "retail/sales/RetailSalesKeyPerPersonIT"),
            caller("retail/sales/internal/ReturnsService", 2, "retail/sales/RetailSalesKeyPerPersonIT"),
            caller("retail/sales/internal/AccountsService", 2, "retail/sales/RetailSalesKeyPerPersonIT"),
            caller("retail/sales/internal/WriteOffsService", 1, "retail/sales/RetailSalesKeyPerPersonIT"),
            caller("retail/stock/internal/TransferService", 3, "retail/stock/RetailUsageKeyPerPersonIT"),
            caller("retail/stock/internal/UsageService", 1, "retail/stock/RetailUsageKeyPerPersonIT"),
            caller("retail/stock/internal/StockCorrectionService", 1, "retail/stock/RetailUsageKeyPerPersonIT"),
            caller("retail/stock/internal/QuickAddService", 1, "retail/stock/RetailUsageKeyPerPersonIT"),
            caller("retail/purchasing/internal/PurchasingService", 3, "retail/purchasing/RetailRestockKeyPerPersonIT"),
            caller("retail/purchasing/internal/OrdersService", 3, "retail/purchasing/RetailRestockKeyPerPersonIT"),
            caller("retail/purchasing/internal/ReceiptsService", 3, "retail/purchasing/RetailRestockKeyPerPersonIT"),
            caller("retail/purchasing/internal/BillsService", 2, "retail/purchasing/RetailRestockKeyPerPersonIT"),
            caller("retail/purchasing/internal/CreditsService", 2, "retail/purchasing/RetailRestockKeyPerPersonIT"),
            caller("retail/purchasing/internal/LandedCostService", 2, "retail/purchasing/RetailRestockKeyPerPersonIT"),
            caller("retail/purchasing/internal/PaymentsService", 2, "retail/purchasing/RetailRestockKeyPerPersonIT")));

    record Caller(int sites, String test) {}

    private static Map.Entry<String, Caller> caller(String type, int sites, String test) {
        return Map.entry(type, new Caller(sites, test));
    }

    @Test
    void everyCallOfOnceIsListedWithACrossPersonTestOfItsModule() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(f -> f.toString().endsWith(".java")).forEach(f -> {
                int sites = 0;
                for (Matcher m = ONCE.matcher(read(f)); m.find(); ) {
                    sites++;
                }
                if (sites > 0) {
                    String name = MAIN.relativize(f).toString().replace('\\', '/');
                    found.put(name.substring(0, name.length() - ".java".length()), sites);
                }
            });
        }

        Map<String, Integer> listed = new TreeMap<>();
        CALLERS.forEach((type, c) -> listed.put(type, c.sites()));
        assertThat(found)
                .as("each Idempotency.once call site, listed here with a cross-person test of its module")
                .isEqualTo(listed);

        CALLERS.forEach((type, c) -> {
            assertThat(module(c.test()))
                    .as("%s is proven in its own module", type)
                    .isEqualTo(module(type));
            assertThat(read(TEST.resolve(c.test() + ".java")))
                    .as("%s refuses another person's key", c.test())
                    .contains("KeyOwnership.refused(");
        });
    }

    /** {@code retail/sales} of {@code retail/sales/internal/SalesService}. */
    private static String module(String path) {
        String[] parts = path.split("/");
        return parts[0] + "/" + parts[1];
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
