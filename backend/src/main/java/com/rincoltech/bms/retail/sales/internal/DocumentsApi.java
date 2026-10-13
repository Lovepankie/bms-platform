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
}
