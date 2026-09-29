package com.rincoltech.bms.core.tenancy;

/**
 * The bound tenant's plan limits (FR-TEN-04). Call inside the transaction that creates the
 * branch, staff user or member, with the count before the creation.
 */
public interface PlanLimits {

    String MAX_BRANCHES = "max_branches";
    String MAX_STAFF_USERS = "max_staff_users";
    String MAX_ACTIVE_MEMBERS = "max_active_members";

    /**
     * Refuses with 422 {@code plan_limit_reached}, naming the limit, when {@code currentCount}
     * already equals or exceeds it. A NULL limit is unlimited.
     */
    void checkRoomFor(String limitKey, long currentCount);
}
