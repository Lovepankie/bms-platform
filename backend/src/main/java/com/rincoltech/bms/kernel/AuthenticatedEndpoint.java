package com.rincoltech.bms.kernel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a route that any signed-in principal of the given kind may call, without a matrix
 * permission: {@code /me}, sign-out, the caller's own MFA settings (chapter 7 section 7.11.2,
 * "authenticated"). Every route carries exactly one of this, {@link RequiresPermission} or
 * {@link PublicEndpoint} (FR-IAM-03).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedEndpoint {

    /** {@code staff}, {@code member} or {@code platform}. */
    String kind();
}
