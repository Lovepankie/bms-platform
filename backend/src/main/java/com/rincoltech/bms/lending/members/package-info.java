/**
 * Lending: members (FR-MEM, chapter 6 table {@code lending_members}, chapter 7 section 7.11.11).
 *
 * <p>This is the reference vertical slice every other module copies:
 *
 * <ul>
 *   <li>public API in this package ({@link com.rincoltech.bms.lending.members.MemberLookup}),
 *       everything else in {@code internal};
 *   <li>controller: one declared permission per route, request and response records in
 *       snake_case, problem details for every failure;
 *   <li>service: one {@code @Transactional} method per use case; validation, branch scope,
 *       writes and the audit row all inside that one transaction; the tenant always comes from
 *       the request context, never from the request body;
 *   <li>repository: plain SQL through {@code JdbcClient}; no tenant predicate in the SQL,
 *       because row-level security applies it (ADR-003), but {@code tenant_id} is written
 *       explicitly on insert from the bound tenant.
 * </ul>
 */
@ApplicationModule(
        id = "lending.members",
        displayName = "Lending: Members",
        allowedDependencies = {"kernel", "core.tenancy", "core.identity", "core.audit", "core.documents"})
package com.rincoltech.bms.lending.members;

import org.springframework.modulith.ApplicationModule;
