package com.rincoltech.bms.core.notifications;

import java.time.LocalTime;

/**
 * What the bound tenant chose about its everyday push notices (issue #412, ADR-057): each kind can be switched off, and the
 * daily closing summary goes at a time the business sets. Stored in the tenant settings group {@code notifications}, written
 * through {@code PUT /settings/notifications}. A module that publishes a scheduled notice reads the time here; the switches
 * are applied by the notifications module itself, so a module never has to ask before it publishes.
 */
public interface PushPreferences {

    /** New phone, WhatsApp and other remote orders, to the staff of the order's shop. */
    String NEW_ORDERS = "new_orders";

    /** An order out for delivery and delivered, to the person who took it. */
    String ORDER_UPDATES = "order_updates";

    /** An item below its low-stock level at a shop or store. */
    String LOW_STOCK = "low_stock";

    /** Credit payments due today or overdue. */
    String CREDIT_DUE = "credit_due";

    /** The daily closing summary to the owner and the administrators. */
    String DAILY_SUMMARY = "daily_summary";

    /** True when this server sends push at all (its VAPID keys are set); with it false nothing is worth preparing. */
    boolean available();

    /** Whether the bound tenant has this kind of notice on (every kind is on until switched off). */
    boolean on(String kind);

    /** When the bound tenant's daily closing summary goes, on the business's own clock (20:00 unless changed). */
    LocalTime dailySummaryTime();
}
