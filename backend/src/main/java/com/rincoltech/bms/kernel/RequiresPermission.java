package com.rincoltech.bms.kernel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The one permission a route requires (chapter 8 section 8.3.3). Every handler method carries
 * this or {@link PublicEndpoint}; a test enumerates the routes and fails on any that has neither
 * (FR-IAM-03).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    String value();
}
