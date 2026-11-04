package com.rincoltech.bms.retail.stock;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Whether a branch's till of a day is closed (issue #812, the lead's decision on the second review of #839), answered by the
 * cash book, which owns the till: the holder of {@code retail.till.close} ("Can close the till and do the banking") closes a
 * day's till by banking its cash, so a day with an active banking record at the branch is closed. An entry kept on the phone
 * offline that moves a closed day's cash (an expense, savings, banking, a withdrawal, or Buy stock paid in cash or by mobile
 * money, which the cash book reads as money out of the till) is the owner's only (ADR-067 decision 13).
 */
public interface ClosedTills {

    /** The till of {@code day} at {@code branch} was closed: its cash was banked. */
    boolean closed(UUID branch, LocalDate day);

    /**
     * Refuses an entry kept offline that lands on a closed day before today at any of {@code branches}, for anyone without
     * {@link OfflineEntry#BACKDATE} at that branch: 422 {@code offline_day_closed} on {@code offline.occurred_at}, in the
     * words the phone shows. A day closed earlier today is not counted: the till of today may be banked more than once, as
     * online. Nothing for an entry that was not kept offline.
     */
    default void requireOpen(OfflineEntry offline, LocalDate day, LocalDate today, Collection<UUID> branches) {
        if (offline == null || !day.isBefore(today)) {
            return;
        }
        Principal principal = CurrentPrincipal.require();
        for (UUID branch : branches) {
            if (closed(branch, day) && !principal.may(OfflineEntry.BACKDATE, branch)) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "offline.occurred_at",
                        "offline_day_closed",
                        "That day's till is closed. Ask the owner to enter it.")));
            }
        }
    }
}
