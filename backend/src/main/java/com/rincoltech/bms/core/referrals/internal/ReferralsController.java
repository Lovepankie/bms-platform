package com.rincoltech.bms.core.referrals.internal;

import com.rincoltech.bms.core.referrals.Referrals.Total;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/referrals} (chapter 7 section 7.11.26, issue #464, ADR-069): the business's own referral link and the
 * businesses it referred, for its owner and administrators ({@code core.referrals.read}).
 */
@RestController
@RequestMapping(path = "/api/v1/referrals", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "referrals")
class ReferralsController {

    private final ReferralService service;

    ReferralsController(ReferralService service) {
        this.service = service;
    }

    @Schema(
            name = "MyReferral",
            description = "A business this business referred: its name, when it joined, never more")
    record MyReferral(
            String referredName,

            @Schema(description = "When the business was created, or the application sent")
            Instant joinedAt,

            @Schema(description = "applied, active, paying or ended")
            String status,

            List<Total> totals) {}

    @Schema(name = "MyReferrals")
    record MyReferrals(
            @Schema(description = "The business's referral code")
            String code,

            @Schema(description = "The public sign-up with the code filled in")
            String link,

            @Schema(description = "Newest first") List<MyReferral> items,

            @Schema(description = "Every referral together, one row per currency")
            List<Total> totals) {}

    @GetMapping
    @RequiresPermission("core.referrals.read")
    @Operation(
            summary = "The business's referral code and link, the businesses it referred, and the commission earned,"
                    + " paid and due (ADR-069)",
            operationId = "myReferrals")
    MyReferrals mine() {
        return service.mine();
    }
}
