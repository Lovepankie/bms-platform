/**
 * Identity and access (chapter 7 section 7.4, chapter 8). Today it carries the principal, the
 * route permission declarations and the development authentication stub (AUTH_MODE=dev, section
 * 7.4.3). Staff and member sign-in, sessions and the seeded permission matrix land here next.
 */
@ApplicationModule(id = "core.identity", displayName = "Core: Identity and Access", allowedDependencies = "kernel")
package com.rincoltech.bms.core.identity;

import org.springframework.modulith.ApplicationModule;
