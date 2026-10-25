package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.tenancy.Letterheads;
import com.rincoltech.bms.core.tenancy.Letterheads.Letterhead;
import com.rincoltech.bms.kernel.AmountInWords;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.Statement;
import com.rincoltech.bms.retail.sales.internal.AccountsApi.StatementLine;
import com.rincoltech.bms.retail.sales.internal.DocumentSnapshot.Line;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns an issued document's snapshot, or a customer statement, into the model its Thymeleaf template reads
 * (ADR-034): every value already formatted, money and dates by the same helpers for every document, the tenant's
 * letterhead on top. Nothing here reads the sale, the product or the customer: what a document shows is what its
 * snapshot says.
 */
@Component
class DocumentModels {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Map<String, String> METHODS = Map.of(
            "cash", "Cash",
            "mobile_money", "Mobile money",
            "bank", "Bank",
            "credit", "Credit");
    static final Map<String, String> TITLES = Map.of(
            "quotation", "QUOTATION",
            "invoice", "INVOICE",
            "delivery_note", "DELIVERY NOTE",
            "credit_note", "CREDIT NOTE");

    private final Letterheads letterheads;

    DocumentModels(Letterheads letterheads) {
        this.letterheads = letterheads;
    }

    // ---- Documents of a snapshot ---------------------------------------------------------------------

    Map<String, Object> model(DocumentSnapshot s) {
        return model(s, null);
    }

    /**
     * The model of a document; with a void reason it carries the VOID banner of every page (a void document is never
     * printed clean: its sale was voided, its stock move cancelled or its quotation cancelled since it was issued).
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> model(DocumentSnapshot s, String voidReason) {
        Map<String, Object> m = common(s.branch(), TITLES.get(s.type()), s.number(), s.issueDate());
        if (voidReason != null) {
            ((Map<String, Object>) m.get("doc"))
                    .put("voided", "VOID: " + voidReason + ". Do not accept this document.");
        }
        m.put("currency", s.currency());
        m.put("buyer", buyer(s));
        m.put("lines", lines(s));
        m.put("notes", s.notes());
        Map<String, Object> signs = new LinkedHashMap<>();
        signs.put("issuedBy", s.issuedBy());
        signs.put("issuedOn", date(s.issueDate()));
        m.put("signs", signs);
        switch (s.type()) {
            case "invoice" -> invoice(s, m);
            case "delivery_note" -> deliveryNote(s, m);
            case "quotation" -> quotation(s, m);
            default -> throw new IllegalArgumentException("no template for " + s.type());
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private void invoice(DocumentSnapshot s, Map<String, Object> m) {
        DocumentSnapshot.SaleRef sale = s.sale();
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("saleNo", sale.no());
        ref.put("saleDate", date(sale.date()));
        ref.put("payment", METHODS.getOrDefault(sale.paymentMethod(), sale.paymentMethod()));
        ref.put("dueDate", sale.dueDate() == null ? null : date(sale.dueDate()));
        ref.put("priceList", s.priceList());
        ref.put("quotation", s.quotation() == null ? null : s.quotation().number());
        m.put("ref", ref);
        Map<String, Object> totals = totals(s);
        long balance = s.totalMinor() - sale.paidMinor();
        if (sale.writtenOffMinor() != null) {
            // Issue #354, ADR-043: the write-off raised the paid amount to the total, but nobody paid the written-off
            // part.
            long paidBefore = sale.paidMinor() - sale.writtenOffMinor();
            totals.put("status", "Unpaid part written off as a loss. Nothing is owed.");
            totals.put(
                    "due",
                    (paidBefore > 0 ? "Paid: " + money(paidBefore, s.currency()) + ". " : "Nothing was paid. ")
                            + "Written off on " + date(sale.writtenOffOn()) + ": "
                            + money(sale.writtenOffMinor(), s.currency()) + " " + s.currency());
        } else if (sale.paymentMethod().equals("credit")) {
            boolean partPaid = sale.paidMinor() > 0 && balance > 0;
            totals.put(
                    "status",
                    balance <= 0
                            ? "Paid in full when issued."
                            : partPaid ? "Part paid when issued." : "Sold on credit.");
            totals.put(
                    "due",
                    balance <= 0
                            ? null
                            : (partPaid ? "Paid when issued: " + money(sale.paidMinor(), s.currency()) + ". " : "")
                                    + "Balance due when issued: " + money(balance, s.currency()) + " " + s.currency());
        } else {
            totals.put(
                    "status",
                    "Paid in full by " + METHODS.getOrDefault(sale.paymentMethod(), sale.paymentMethod()) + ".");
            totals.put("due", null);
        }
        m.put("totals", totals);
        // The received line as invoice.html prints it, which the receipt on the screen reads too (issue #500).
        ((Map<String, Object>) m.get("signs"))
                .put("received", "Received by " + ((Map<String, Object>) m.get("buyer")).get("name"));
    }

    // JUSTIFICATION-A3: the receipt to share (issue #439) is the invoice model with the sale's own balance and returns.
    /** The business's own name, as its letterhead prints it: the caption of a shared receipt names it, never the platform. */
    String businessName() {
        return letterheads.current().displayName();
    }

    /**
     * The model of the receipt a seller shares (issue #439, ADR-061): the invoice of the snapshot, titled RECEIPT, signed by
     * the seller who made the sale, with the money the buyer actually paid ({@code paidMinor}: a return taken off the balance
     * is a return, not a payment) and what the sale still owes as the sale carries it (payments and returns taken off),
     * and, when goods were taken back, each credit note, the net and a title that says so.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> receipt(
            DocumentSnapshot s,
            String soldBy,
            long paidMinor,
            long balanceMinor,
            List<ReturnsRepository.Row> returned,
            long netMinor) {
        Map<String, Object> m = model(s);
        ((Map<String, Object>) m.get("doc")).put("title", returned.isEmpty() ? "RECEIPT" : "RECEIPT AFTER RETURNS");
        ((Map<String, Object>) m.get("signs")).put("issuedBy", soldBy);
        Map<String, Object> totals = (Map<String, Object>) m.get("totals");
        DocumentSnapshot.SaleRef sale = s.sale();
        String method = METHODS.getOrDefault(sale.paymentMethod(), sale.paymentMethod());
        if (!sale.paymentMethod().equals("credit")) {
            totals.put("status", "Paid in full by " + method + ".");
            totals.put("due", null);
        } else if (balanceMinor <= 0) {
            totals.put("status", "Paid in full.");
            totals.put("due", null);
        } else {
            totals.put("status", paidMinor > 0 ? "Part paid." : "Sold on credit.");
            totals.put(
                    "due",
                    (paidMinor > 0 ? "Paid so far: " + money(paidMinor, s.currency()) + ". " : "")
                            + "Balance outstanding on " + date(s.issueDate()) + ": "
                            + money(balanceMinor, s.currency()) + " " + s.currency());
        }
        if (!returned.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (ReturnsRepository.Row r : returned) {
                StringBuilder line = new StringBuilder("Credit note " + r.creditNoteNo() + ", " + date(r.returnedOn())
                        + ": goods worth " + money(r.totalMinor(), s.currency()) + " taken back");
                if (r.refundMinor() > 0) {
                    line.append(", ")
                            .append(money(r.refundMinor(), s.currency()))
                            .append(" paid back");
                }
                if (r.accountCreditMinor() > 0) {
                    line.append(", ")
                            .append(money(r.accountCreditMinor(), s.currency()))
                            .append(" taken off the balance");
                }
                lines.add(line.append('.').toString());
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("lines", lines);
            after.put("net", money(netMinor, s.currency()));
            m.put("returns", after);
        }
        return m;
    }

    /**
     * What the receipt PDF prints, read from its model (issue #500): the screen's preview shows the same values, every one
     * the invoice template and its letterhead print. Read defensively, as the template does: a missing map or value is
     * blank, never an error. The logo is given by the URL of the very logo the PDF prints ({@code lh.logoUrl}: the shop's
     * own when its file can be read, else the business logo, else none), which the browser caches, not its bytes.
     */
    DocumentsApi.ReceiptPrint printed(Map<String, Object> m) {
        Map<String, Object> lh = map(m.get("lh"));
        Map<String, Object> branch = map(m.get("branch"));
        Map<String, Object> doc = map(m.get("doc"));
        Map<String, Object> buyer = map(m.get("buyer"));
        Map<String, Object> ref = map(m.get("ref"));
        Map<String, Object> totals = map(m.get("totals"));
        Map<String, Object> signs = map(m.get("signs"));
        Map<String, Object> returns = map(m.get("returns"));
        List<DocumentsApi.ReceiptPrintLine> lines = new ArrayList<>();
        for (Object o : list(m.get("lines"))) {
            Map<String, Object> l = map(o);
            lines.add(new DocumentsApi.ReceiptPrintLine(
                    l.get("no") instanceof Number n ? n.intValue() : lines.size() + 1,
                    text(l.get("code")),
                    text(l.get("description")),
                    text(l.get("detail")),
                    text(l.get("quantity")),
                    blank(l.get("pack")),
                    blank(l.get("priceList")),
                    text(l.get("unitPrice")),
                    text(l.get("total"))));
        }
        List<String> returned = new ArrayList<>();
        for (Object o : list(returns.get("lines"))) {
            returned.add(text(o));
        }
        return new DocumentsApi.ReceiptPrint(
                text(doc.get("title")),
                text(doc.get("number")),
                text(doc.get("date")),
                blank(lh.get("logoUrl")),
                text(lh.get("name")),
                blank(branch.get("name")),
                blank(branch.get("location")),
                text(buyer.get("name")),
                blank(buyer.get("contact")),
                text(ref.get("saleNo")),
                text(ref.get("saleDate")),
                text(ref.get("payment")),
                blank(ref.get("dueDate")),
                blank(ref.get("priceList")),
                blank(ref.get("quotation")),
                text(m.get("currency")),
                lines,
                text(totals.get("total")),
                text(totals.get("words")),
                blank(totals.get("status")),
                blank(totals.get("due")),
                returned,
                returns.isEmpty() ? null : blank(returns.get("net")),
                blank(m.get("notes")),
                text(signs.get("issuedBy")),
                text(signs.get("issuedOn")),
                blank(signs.get("received")),
                blank(lh.get("footer")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> found ? (Map<String, Object>) found : Map.of();
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> found ? found : List.of();
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String blank(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString();
    }

    private void deliveryNote(DocumentSnapshot s, Map<String, Object> m) {
        Map<String, Object> ref = new LinkedHashMap<>();
        Map<String, Object> to = new LinkedHashMap<>();
        if (s.transfer() != null) {
            ref.put("kind", "Stock move");
            ref.put("number", "Between branches");
            ref.put("date", date(s.transfer().date()));
            ref.put("note", s.transfer().note());
            to.put("name", s.deliverTo().name());
            to.put("detail", s.deliverTo().location());
        } else {
            ref.put("kind", "Sale");
            ref.put("number", s.sale().no());
            ref.put("date", date(s.sale().date()));
            ref.put("note", null);
            to.put("name", s.buyer().name());
            to.put("detail", s.buyer().contact());
        }
        ref.put("from", s.branch().name());
        ref.put("invoice", s.invoiceNumber());
        m.put("ref", ref);
        m.put("to", to);
        m.put("prices", s.showPrices());
        Map<String, Object> totals = totals(s);
        totals.put("count", s.lines().size() + (s.lines().size() == 1 ? " line" : " lines"));
        m.put("totals", totals);
    }

    private void quotation(DocumentSnapshot s, Map<String, Object> m) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("date", date(s.quotation().date()));
        ref.put("validUntil", date(s.quotation().validUntil()));
        ref.put("priceList", s.priceList());
        m.put("ref", ref);
        m.put("totals", totals(s));
    }

    private Map<String, Object> totals(DocumentSnapshot s) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("total", money(s.totalMinor(), s.currency()));
        t.put("words", AmountInWords.of(s.totalMinor(), s.currency()));
        return t;
    }

    private Map<String, Object> buyer(DocumentSnapshot s) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", s.buyer() == null ? "" : s.buyer().name());
        b.put("contact", s.buyer() == null ? null : s.buyer().contact());
        return b;
    }

    private List<Map<String, Object>> lines(DocumentSnapshot s) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Line l : s.lines()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("no", l.no());
            m.put("code", l.code());
            m.put("description", l.description());
            m.put("qty", plain(l.qty()));
            m.put("unit", l.unit());
            m.put("quantity", quantity(l.qty(), l.unit()));
            m.put("pack", l.baseQty() == null ? null : "= " + plain(l.baseQty()) + " " + l.baseUnit());
            m.put("unitPrice", l.unitPriceMinor() == null ? "" : money(l.unitPriceMinor(), s.currency()));
            m.put("total", money(l.totalMinor(), s.currency()));
            m.put("priceList", l.priceList());
            m.put("detail", detail(l.code(), l.priceList()));
            out.add(m);
        }
        return out;
    }

    // ---- The customer statement ----------------------------------------------------------------------

    Map<String, Object> statement(
            Statement st, String contact, String terms, Long limitMinor, String scope, LocalDate on, String number) {
        String cur = st.currency();
        Map<String, Object> m =
                common(new DocumentSnapshot.Place(scope, null, null), "STATEMENT OF ACCOUNT", number, on);
        m.put("currency", cur);
        Map<String, Object> buyer = new LinkedHashMap<>();
        buyer.put("name", st.name());
        buyer.put("contact", contact);
        m.put("buyer", buyer);
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("from", date(st.from()));
        period.put("to", date(st.to()));
        m.put("period", period);
        m.put("terms", terms);
        m.put("limit", limitMinor == null ? null : money(limitMinor, cur));
        m.put("scope", scope);
        List<Map<String, Object>> lines = new ArrayList<>();
        long charged = 0;
        long paid = 0;
        for (StatementLine l : st.lines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date(l.date()));
            row.put("reference", l.reference());
            row.put("description", describe(l));
            row.put("charged", l.debitMinor() == 0 ? "-" : money(l.debitMinor(), cur));
            row.put("paid", l.creditMinor() == 0 ? "-" : money(l.creditMinor(), cur));
            row.put("balance", money(l.balanceMinor(), cur));
            charged += l.debitMinor();
            paid += l.creditMinor();
            lines.add(row);
        }
        m.put("lines", lines);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("opening", money(st.openingMinor(), cur));
        totals.put("charged", money(charged, cur));
        totals.put("paid", money(paid, cur));
        totals.put("closing", money(st.closingMinor(), cur));
        totals.put(
                "closingWords",
                st.closingMinor() < 0
                        ? "In credit: " + AmountInWords.of(-st.closingMinor(), cur)
                        : AmountInWords.of(st.closingMinor(), cur));
        m.put("totals", totals);
        if (st.beforeSystemStart() == null) {
            m.put("imported", null);
        } else {
            Map<String, Object> imported = new LinkedHashMap<>();
            List<Map<String, Object>> sales = new ArrayList<>();
            st.beforeSystemStart().sales().forEach(h -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", date(h.saleDate()));
                row.put("reference", h.saleNo());
                row.put("amount", money(h.totalMinor(), cur));
                sales.add(row);
            });
            imported.put("sales", sales);
            imported.put("total", money(st.beforeSystemStart().totalMinor(), cur));
            imported.put("balance", money(st.balanceMinor(), cur) + " " + cur);
            m.put("imported", imported);
        }
        return m;
    }

    private static String describe(StatementLine l) {
        String method = l.method() == null ? "" : " (" + METHODS.getOrDefault(l.method(), l.method()) + ")";
        return switch (l.kind()) {
            case "sale" -> "Sale on credit";
            case "payment" -> "Payment" + method;
            case "account_payment" -> "Payment on account" + method;
            case "reversal" -> "Payment reversed";
            case "void" -> "Sale voided";
            case "return" -> "Goods returned (credit note)";
            case "return_void" -> "Credit note voided";
            case "write_off" -> "Written off as a loss";
            default -> l.kind();
        };
    }

    // ---- Shared ----------------------------------------------------------------------------------------

    /**
     * The letterhead, the branch block and the number and date of the header (layout.html). A document of one shop carries
     * that shop's own logo when it has one, else the business logo (issue #461, ADR-068).
     */
    Map<String, Object> common(DocumentSnapshot.Place branch, String title, String number, LocalDate on) {
        Letterhead lh = branch.code() == null ? letterheads.current() : letterheads.forBranchCode(branch.code());
        Map<String, Object> letterhead = new LinkedHashMap<>();
        letterhead.put("name", lh.displayName());
        letterhead.put("colour", lh.brandColour());
        letterhead.put("onColour", lh.textOnBrand());
        letterhead.put("tint", tint(lh.brandColour()));
        letterhead.put("footer", lh.receiptFooter());
        letterhead.put(
                "logo",
                lh.logo()
                        .map(l -> "data:" + l.contentType() + ";base64,"
                                + Base64.getEncoder().encodeToString(l.bytes()))
                        .orElse(null));
        letterhead.put("logoUrl", lh.logo().map(Letterheads.Logo::path).orElse(null));
        Map<String, Object> place = new LinkedHashMap<>();
        place.put("name", branch.name());
        place.put("location", branch.location());
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("title", title);
        doc.put("number", number);
        doc.put("date", date(on));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lh", letterhead);
        m.put("branch", place);
        m.put("doc", doc);
        return m;
    }

    static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    /** Thousands separated; currencies with a minor unit show two decimals (UGX has none). */
    static String money(long minor, String currency) {
        if ("UGX".equals(currency)) {
            return String.format(Locale.ENGLISH, "%,d", minor);
        }
        long abs = Math.abs(minor);
        return (minor < 0 ? "-" : "") + String.format(Locale.ENGLISH, "%,d.%02d", abs / 100, abs % 100);
    }

    /** A quantity without trailing zeros: 5.000 prints as 5 and 2.500 as 2.5. */
    static String plain(String qty) {
        return qty == null ? "" : new BigDecimal(qty).stripTrailingZeros().toPlainString();
    }

    /** A quantity as a document prints it, with its unit when it has one: 5 carton, or 5 alone. */
    static String quantity(String qty, String unit) {
        String q = plain(qty);
        return unit == null || unit.isBlank() ? q : q + " " + unit;
    }

    /** The muted line under a line's description on an invoice: the code, and the price list when it has one. */
    static String detail(String code, String priceList) {
        String c = code == null ? "" : code;
        return priceList == null ? c : c + "  |  " + priceList;
    }

    /** A pale tint of the brand colour for alternate table rows: 88% white over it. */
    static String tint(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        int[] c = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
        StringBuilder out = new StringBuilder("#");
        for (int v : c) {
            out.append(String.format("%02X", Math.round(v + (255 - v) * 0.88f)));
        }
        return out.toString();
    }
}
