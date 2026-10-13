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
        if (sale.paymentMethod().equals("credit")) {
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
            m.put("pack", l.baseQty() == null ? null : "= " + plain(l.baseQty()) + " " + l.baseUnit());
            m.put("unitPrice", l.unitPriceMinor() == null ? "" : money(l.unitPriceMinor(), s.currency()));
            m.put("total", money(l.totalMinor(), s.currency()));
            m.put("priceList", l.priceList());
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
            default -> l.kind();
        };
    }

    // ---- Shared ----------------------------------------------------------------------------------------

    /** The letterhead, the branch block and the number and date of the header (layout.html). */
    Map<String, Object> common(DocumentSnapshot.Place branch, String title, String number, LocalDate on) {
        Letterhead lh = letterheads.current();
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
