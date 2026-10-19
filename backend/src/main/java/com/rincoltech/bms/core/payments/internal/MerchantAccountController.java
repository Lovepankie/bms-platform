package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.internal.MerchantAccountService.Credentials;
import com.rincoltech.bms.core.payments.internal.MerchantAccountService.View;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/payments/merchant-account} (chapter 7 section 7.11.10, ADR-035 amendment of 2026-10-18): the lender's
 * own payment provider account, the screen "Receive payments". A tenant administrator saves the consumer key and secret
 * (the secret is write-only: no response, log line or audit row ever carries it), tests the connection, and can remove
 * the account. Reading needs {@code core.settings.read} (the key's last four characters only go to a holder of the manage
 * permission); saving, testing and removing need {@code core.payment_account.manage}, the tenant admin's alone, because
 * changing it redirects where every later repayment is paid; saving and removing also take a current second-factor (TOTP)
 * code in the body (step-up). Every response is {@code no-store}.
 */
@RestController
@RequestMapping(path = "/api/v1/payments/merchant-account", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "payments")
class MerchantAccountController {

    private final MerchantAccountService service;

    MerchantAccountController(MerchantAccountService service) {
        this.service = service;
    }

    /** Both fields are required. {@link #toString} names no value. */
    @Schema(name = "SaveMerchantAccountRequest")
    record SaveRequest(
            @Schema(description = "The consumer key of the lender's own Pesapal merchant account") @Size(max = 300)
            String consumerKey,

            @Schema(
                    description = "The consumer secret. Write-only: it is sealed on arrival and never returned",
                    format = "password")
            @Size(max = 300)
            String consumerSecret,

            @Schema(
                    description =
                            "A current code of the signed-in administrator's own authenticator (step-up), used once")
            @Size(max = 20)
            String totpCode) {

        @Override
        public String toString() {
            return "SaveRequest[]";
        }
    }

    /** The second-factor code that removing the account asks for. */
    @Schema(name = "RemoveMerchantAccountRequest")
    record RemoveRequest(
            @Schema(
                    description =
                            "A current code of the signed-in administrator's own authenticator (step-up), used once")
            @Size(max = 20)
            String totpCode) {

        @Override
        public String toString() {
            return "RemoveRequest[]";
        }
    }

    @GetMapping
    @RequiresPermission("core.settings.read")
    @Operation(
            summary = "Whether this business receives payments into its own account (status only, never the secret)",
            operationId = "getMerchantAccount")
    View get(HttpServletResponse response) {
        NoStore.apply(response);
        return service.view();
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(MerchantAccountService.MANAGE)
    @Operation(
            summary =
                    "Save the consumer key and secret of this business's own account; no call is made to the provider",
            operationId = "saveMerchantAccount")
    View save(@Valid @RequestBody SaveRequest request, HttpServletResponse response) {
        NoStore.apply(response);
        return service.save(new Credentials(request.consumerKey(), request.consumerSecret(), request.totpCode()));
    }

    @PostMapping("/test")
    @RequiresPermission(MerchantAccountService.MANAGE)
    @Operation(
            summary = "Test connection: get a token and register this account's notification address",
            operationId = "testMerchantAccount")
    View test(HttpServletResponse response) {
        NoStore.apply(response);
        return service.test();
    }

    @DeleteMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(MerchantAccountService.MANAGE)
    @Operation(
            summary = "Remove the saved account; refused while payments still need it",
            operationId = "removeMerchantAccount")
    View remove(@Valid @RequestBody RemoveRequest request, HttpServletResponse response) {
        NoStore.apply(response);
        return service.disconnect(request.totpCode());
    }
}
