/**
 * Shared kernel (ADR-002): the tenant context, money, the business clock, request context,
 * error types and the problem-details mapping, phone and NIN normalisation, masking. Types and
 * primitives only; it depends on no other module and every module may use it.
 */
@ApplicationModule(
        id = "kernel",
        displayName = "Kernel",
        type = ApplicationModule.Type.OPEN,
        allowedDependencies = {})
package com.rincoltech.bms.kernel;

import org.springframework.modulith.ApplicationModule;
