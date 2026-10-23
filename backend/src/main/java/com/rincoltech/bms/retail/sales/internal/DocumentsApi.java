package com.rincoltech.bms.retail.sales.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bodies of the optional document routes (documents capability; ADR-033, issue #197; chapter 7 section 7.11.20).
 * Money is integer minor units; dates are business dates in the tenant's zone. A cost never appears here: a document
 * is what the buyer sees.
 */
final class DocumentsApi {

    static final String TYPES = "quotation|invoice|delivery_note";

    private DocumentsApi() {}

    @Schema(name = "RetailDocumentRequest")
    record IssueRequest(
            @NotNull @Pattern(regexp = "invoice|delivery_note")
            String type,

            @Schema(
                    description = "Delivery note only: print the unit prices and amounts. Off by default; a delivery"
                            + " note of a transfer never shows prices")
            Boolean showPrices) {}

    @Schema(name = "RetailDocument")
    record IssuedDocument(
            UUID id,
            @Schema(description = TYPES) String type,
            String number,
            UUID branchId,
            LocalDate issueDate,

            @JsonInclude(JsonInclude.Include.NON_NULL) UUID saleId,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID transferId,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID quotationId,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The quotation document an invoice was made from")
            UUID sourceDocumentId,

            @JsonInclude(JsonInclude.Include.NON_NULL) String buyerName,
            String currency,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long totalMinor,

            @Schema(
                    description = "issued, or void when its sale was voided, its transfer cancelled or voided, or its"
                            + " quotation cancelled. The stored PDF is as issued and never changes; show it as void")
            String status,

            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate validUntil,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "A quotation: open, converted, cancelled or expired (open past its valid-until date)")
            String quotationStatus,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "The stored PDF; download it with POST /documents/{id}/download-url. Absent until rendered")
            UUID pdfDocumentId,

            @Schema(description = "True when this request issued the document; false when it had been issued already")
            boolean created,

            Instant createdAt) {}

    @Schema(name = "RetailDocumentList")
    record DocumentList(List<IssuedDocument> items) {}

    @Schema(name = "RetailDocumentPage")
    record DocumentPage(List<IssuedDocument> items, String nextCursor) {}

    // ---- Quotations ---------------------------------------------------------------------------

    @Schema(name = "RetailQuotationLineRequest")
    record QuotationLineRequest(
            @NotNull UUID productId,

            @NotNull @Positive @Digits(integer = 11, fraction = 3) @Schema(type = "string", example = "2.500")
            BigDecimal qty,

            @PositiveOrZero
            @Max(RetailCatalogue.MAX_AMOUNT_MINOR)
            @Schema(
                    description =
                            "Per unit_id when one is given, otherwise per base unit. Defaults to the price a sale would take")
            Long unitPriceMinor,

            @Schema(
                    description =
                            "A pack unit of the product (units capability); absent or the base unit means the base unit")
            UUID unitId,

            @Size(max = 300)
            @Schema(description = "Why the price differs from the one the price list resolved; as on a sale")
            String priceOverrideReason) {}

    @Schema(name = "RetailQuotationRequest")
    record QuotationRequest(
            @Schema(description = "Defaults to the caller's one branch")
            UUID branchId,

            UUID customerId,
            @Size(max = 200) String buyerName,
            @Size(max = 100) String buyerContact,

            @Schema(description = "Price the lines from this list; as on a sale (price_lists capability)")
            UUID priceListId,

            @NotNull @Schema(description = "Not before the quotation date")
            LocalDate validUntil,

            @Size(max = 300) String notes,
            @NotEmpty @Size(max = 200) List<@Valid @NotNull QuotationLineRequest> lines) {}

    @Schema(name = "RetailQuotationLine")
    record QuotationLine(
            int lineNo,
            UUID productId,
            String code,
            String description,

            @Schema(description = "In the unit the line was quoted in")
            String qty,

            String unit,
            long unitPriceMinor,
            long lineTotalMinor,
            @JsonInclude(JsonInclude.Include.NON_NULL) String baseQty,
            @JsonInclude(JsonInclude.Include.NON_NULL) String baseUnit) {}

    @Schema(name = "RetailQuotation")
    record Quotation(
            UUID id,
            String number,
            UUID documentId,
            UUID branchId,
            LocalDate quoteDate,
            LocalDate validUntil,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID customerId,
            String buyerName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String buyerContact,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID priceListId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String priceList,
            String currency,
            long totalMinor,
            @JsonInclude(JsonInclude.Include.NON_NULL) String notes,

            @Schema(description = "open, converted or cancelled")
            String status,

            @Schema(description = "Open and past its valid-until date: it can no longer be converted")
            boolean expired,

            @JsonInclude(JsonInclude.Include.NON_NULL) UUID convertedSaleId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String convertedSaleNo,
            @JsonInclude(JsonInclude.Include.NON_NULL) String cancelReason,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID pdfDocumentId,
            List<QuotationLine> lines,
            Instant createdAt,
            int version) {}

    @Schema(name = "RetailQuotationPage")
    record QuotationPage(List<Quotation> items, String nextCursor) {}

    @Schema(name = "RetailQuotationCancelRequest")
    record CancelRequest(@NotBlank @Size(max = 300) String reason) {}

    @Schema(name = "RetailQuotationConvertRequest")
    record ConvertRequest(
            @NotNull @Pattern(regexp = SalesApi.SALE_METHODS)
            String paymentMethod,

            @Schema(description = "Proposed payment date of a credit sale")
            LocalDate dueDate,

            @Schema(
                    description =
                            "Charge the quoted prices instead of pricing the lines again today. A quoted price that"
                                    + " differs from today's resolves like a typed price on a sale: it needs"
                                    + " retail.price.override while price lists are on")
            Boolean keepQuotedPrices,

            @Schema(description = "Also issue the invoice of the new sale, linked to this quotation")
            Boolean issueInvoice,

            @Size(max = 300) String creditOverrideReason) {}

    @Schema(name = "RetailQuotationConverted")
    record Converted(
            UUID quotationId,
            UUID saleId,
            String saleNo,
            @JsonInclude(JsonInclude.Include.NON_NULL) IssuedDocument invoice,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description =
                            "Said plainly when a feature switched off since the quotation was made changed how it was sold")
            String note) {}

    // ---- Numbered series ----------------------------------------------------------------------

    @Schema(name = "RetailDocumentSeriesBranch")
    record SeriesBranch(
            UUID branchId,
            String branchCode,

            @Schema(description = "Documents issued in this series so far")
            int issued,

            @Schema(description = "The number the next document of this series takes")
            String next) {}

    @Schema(name = "RetailDocumentSeries")
    record Series(
            String type,
            String prefix,

            @Schema(description = "The prefix a type has until an administrator changes it")
            String defaultPrefix,

            List<SeriesBranch> branches) {}

    @Schema(name = "RetailDocumentSeriesList")
    record SeriesList(List<Series> items) {}

    @Schema(name = "RetailDocumentSeriesRequest")
    record SeriesRequest(
            @NotNull
            @Pattern(regexp = "[A-Z][A-Z0-9]{0,7}")
            @Schema(description = "One to eight capital letters or digits, starting with a letter")
            String prefix) {}

    // JUSTIFICATION-A3: the facts of the receipt to share (issue #439); the screen's caption reads the same figures as
    // the PDF.
    @Schema(name = "RetailSaleReceipt")
    record SaleReceipt(
            UUID saleId,

            @Schema(description = "The sale's number, which the receipt carries")
            String receiptNo,

            @Schema(description = "The business's own name from its letterhead")
            String businessName,

            @JsonInclude(JsonInclude.Include.NON_NULL) String buyerName,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The buyer's number: the record's when the sale names one, else the sale's")
            String buyerContact,

            @JsonInclude(JsonInclude.Include.NON_NULL) UUID customerId,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The buyer record's version, for a save of its number under If-Match")
            Integer customerVersion,

            @Schema(description = "Whether the buyer's record already has a number")
            boolean customerHasContact,

            String paymentMethod,
            String currency,
            long totalMinor,

            @Schema(description = "The money the buyer actually paid: a return taken off the balance is not counted")
            long paidMinor,

            @Schema(description = "What the buyer still owes, as on the sale: returns and payments taken into account")
            long balanceMinor,

            @Schema(description = "The value of the goods taken back by completed returns")
            long returnedMinor,

            @Schema(description = "The total less the goods taken back")
            long netMinor,

            @Schema(description = "The credit notes of the completed returns, oldest first")
            List<String> creditNotes,

            @Schema(description = "The sale's version; it changes with a payment or a return")
            int saleVersion) {}

    /**
     * The printed receipt for the screen's preview (issue #500), asked for only when View receipt is tapped, so the facts
     * above stay a cheap read for Share and WhatsApp. Without {@code printed} the receipt could not be laid out for the
     * screen, and the screen offers the PDF instead.
     */
    @Schema(name = "RetailReceiptPreview")
    record ReceiptPreview(
            UUID saleId,

            @Schema(description = "The sale's version the receipt was laid out at")
            int saleVersion,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "What the receipt PDF prints, in its order and already formatted; absent when it"
                            + " could not be laid out, and the screen then offers the PDF")
            ReceiptPrint printed) {}

    /**
     * What the receipt PDF prints (issue #500), read from the very model its template renders, so the screen shows what
     * the buyer gets: the letterhead, the sale, the lines, the totals, the returns and the footer. Money is formatted as
     * on the PDF (UGX without decimals).
     */
    @Schema(name = "RetailReceiptPrint")
    record ReceiptPrint(
            @Schema(description = "RECEIPT, or RECEIPT AFTER RETURNS")
            String title,

            String number,

            @Schema(description = "The day the receipt was made, as printed")
            String date,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(
                    description = "The same-origin URL of the logo the receipt PDF prints: the shop's own when its"
                            + " stored file can be read, else the business logo; absent when the PDF prints none."
                            + " Versioned so the browser caches it, and public, so an image tag loads it")
            String logoUrl,

            String businessName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String branchName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String branchLocation,
            String buyerName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String buyerContact,
            String saleNo,
            String saleDate,
            String payment,
            @JsonInclude(JsonInclude.Include.NON_NULL) String dueDate,
            @JsonInclude(JsonInclude.Include.NON_NULL) String priceList,
            @JsonInclude(JsonInclude.Include.NON_NULL) String quotation,
            String currency,
            List<ReceiptPrintLine> lines,
            String total,
            String totalWords,
            @JsonInclude(JsonInclude.Include.NON_NULL) String status,
            @JsonInclude(JsonInclude.Include.NON_NULL) String due,

            @Schema(description = "One line for each credit note, oldest first")
            List<String> returns,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The net after returns")
            String net,

            @JsonInclude(JsonInclude.Include.NON_NULL) String notes,

            String issuedBy,
            String issuedOn,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The received signature line as printed, such as Received by Test Buyer")
            String received,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "The receipt footer")
            String footer) {}

    @Schema(name = "RetailReceiptPrintLine")
    record ReceiptPrintLine(
            int no,
            String code,
            String description,

            @Schema(description = "The line under the description as printed: the code, and the price list after a bar")
            String detail,

            @Schema(description = "The quantity and unit as sold, such as 5 carton, or 5 alone without a unit")
            String quantity,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "A pack in base units, such as = 60 piece")
            String pack,

            @JsonInclude(JsonInclude.Include.NON_NULL) String priceList,
            String unitPrice,
            String amount) {}
}
