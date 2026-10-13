package com.rincoltech.bms.retail.sales.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything an issued document shows, stored once as JSON in {@code retail_documents.snapshot} (ADR-033 decision 7,
 * spec 6.2): the business header, the buyer, the lines as sold, the totals and the currency. A reprint, or a PDF made
 * after the first one failed, is built from this and never from the live sale, so a price, a product name or a
 * customer changed since cannot alter a document that was issued. Amounts are integer minor units; dates are the
 * business dates of the tenant's zone. Fields are only ever added, never changed, so an old snapshot still reads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
record DocumentSnapshot(
        int v,
        String type,
        String number,
        LocalDate issueDate,
        String currency,
        String business,
        Place branch,
        Buyer buyer,
        Place deliverTo,
        SaleRef sale,
        TransferRef transfer,
        QuotationRef quotation,
        String invoiceNumber,
        String priceList,
        List<Line> lines,
        long totalMinor,
        String issuedBy,
        boolean showPrices,
        String notes) {

    static final int VERSION = 1;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Place(String name, String location, String code) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Buyer(String name, String contact) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SaleRef(String no, LocalDate date, String paymentMethod, LocalDate dueDate, long paidMinor) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TransferRef(LocalDate date, String note) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record QuotationRef(String number, LocalDate date, LocalDate validUntil) {}

    /**
     * One line as it was sold: the quantity and price in the unit the seller entered (a pack or the base unit), and for
     * a pack the base units it came to, so the printed quantity is never a surprise.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Line(
            int no,
            String code,
            String description,
            String qty,
            String unit,
            Long unitPriceMinor,
            long totalMinor,
            String baseQty,
            String baseUnit,
            String priceList) {}
}
