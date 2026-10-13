package com.rincoltech.bms.core.approvals;

import com.rincoltech.bms.kernel.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link ApprovalAction#execute} when the facts the request was approved on have changed
 * in a way the maker must see again (for example collateral handed back since the request). The
 * decision is rolled back, the request becomes {@code stale} with this message as its note (and is
 * audited), so the maker may request again at once, and the caller receives this error as it is.
 */
public class RequestSupersededException extends ApiException {

    public RequestSupersededException(String code, String title, String detail, Map<String, Object> extensions) {
        super(HttpStatus.CONFLICT, code, title, detail, java.util.List.of(), extensions);
    }
}
