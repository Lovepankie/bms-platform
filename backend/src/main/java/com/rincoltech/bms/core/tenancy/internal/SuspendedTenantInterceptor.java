package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.kernel.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A suspended tenant is read only (FR-TEN-06): every state-changing request returns 423
 * {@code tenant_suspended}, except staff sign-in, MFA, refresh and sign-out, so staff can still
 * sign in to read. Member portal sign-in is refused like any write, and so is every export that is
 * a POST: a business the operator suspended takes nothing away (ADR-073, ADR-074).
 *
 * <p>A business whose Owner cancelled and whose end date has passed is read only the same way, with 423
 * {@code subscription_ended} (ADR-073): besides the sign-in routes, the exports that are a POST (the audit CSV,
 * a report run, and the business's own workbook of issue #334) still work, so the business can take its data away.
 * The signed address of a file is passed on marked with {@link Documents#ENDED_BUSINESS_ATTRIBUTE}, and the documents
 * module decides: it signs only the data export's own files and answers 423 {@code subscription_ended} for member ID
 * images, receipts and every other document (ADR-074 decision 7).
 *
 * <p>A business whose Owner asked to close it is read only the same way from the moment of the request, with 423
 * {@code business_closing} (issue #571, ADR-090): the sign-in routes and the exports still work, every file of the business
 * may still be fetched (a read), and the Owner may still cancel the request. An ended business may also ask to close.
 */
class SuspendedTenantInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object status = request.getAttribute(TenantResolutionFilter.STATUS_ATTRIBUTE);
        if (!"suspended".equals(status) && !"ended".equals(status) && !"closing".equals(status)
                || SAFE_METHODS.contains(request.getMethod())
                || staffSessionPath(request.getRequestURI())) {
            return true;
        }
        if ("closing".equals(status)) {
            String path = request.getRequestURI();
            if (exportPath(path)
                    || dataExportPath(path)
                    || CLOSURE_CANCEL.equals(path)
                    || DOWNLOAD_URL.matcher(path).matches()) {
                return true;
            }
            throw new ApiException(
                    HttpStatus.LOCKED,
                    "business_closing",
                    "Business closing",
                    "The owner asked to close this business, so it is read only: you can sign in, look at everything and"
                            + " download your data, but not add or change anything. The owner can cancel the closing in"
                            + " Business set-up.");
        }
        if ("ended".equals(status)) {
            if (CLOSURE_REQUEST.equals(request.getRequestURI())) {
                return true;
            }
            // When member portal sign-in (FR-IAM-09) is built, its routes must be let through here, for an ended
            // business
            // only (ADR-073 decision 5): its members still sign in to read. A suspension by the operator refuses them.
            if (exportPath(request.getRequestURI()) || dataExportPath(request.getRequestURI())) {
                return true;
            }
            if (DOWNLOAD_URL.matcher(request.getRequestURI()).matches()) {
                request.setAttribute(Documents.ENDED_BUSINESS_ATTRIBUTE, Boolean.TRUE);
                return true;
            }
            throw new ApiException(
                    HttpStatus.LOCKED,
                    "subscription_ended",
                    "Subscription ended",
                    "The subscription of this business has ended, so it is read only: you can sign in, look at"
                            + " everything and download your data, but not add or change anything. Contact the platform"
                            + " operator to start it again.");
        }
        throw new ApiException(
                HttpStatus.LOCKED,
                "tenant_suspended",
                "Tenant suspended",
                "This tenant is suspended and read only. Contact the platform operator.");
    }

    static boolean exportPath(String path) {
        return path.equals("/api/v1/audit-events/export") || path.equals("/api/v1/report-runs");
    }

    /** Asking for the data export changes none of the business's data (ADR-074). */
    static boolean dataExportPath(String path) {
        return path.equals("/api/v1/data-export/runs");
    }

    /** The Owner's request to close an ended business, and the cancel of a request while the business is closing. */
    static final String CLOSURE_REQUEST = "/api/v1/closure";

    static final String CLOSURE_CANCEL = "/api/v1/closure/cancel";

    /** Passed on only so the documents module decides which document an ended business may sign (ADR-074). */
    private static final Pattern DOWNLOAD_URL = Pattern.compile("^/api/v1/documents/[0-9a-fA-F-]{36}/download-url$");

    static boolean staffSessionPath(String path) {
        return path.startsWith("/api/v1/auth/staff/")
                || path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/logout");
    }
}
