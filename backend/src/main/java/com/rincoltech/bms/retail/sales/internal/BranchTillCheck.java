package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.tenancy.BranchSellingCheck;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What retail still has open at a branch's till, for the tenancy module's check before a branch is marked as a Store (issue #366,
 * ADR-048): the completed credit sales with a balance owing, since the payments on them are taken at the branch. A day's cash is
 * not "closed" in this product (the cash book is read by date, never locked), so nothing else can hang on the till. Imported
 * (historical) credit sales are left out: nothing can be paid on them (#360), so they never block a branch becoming a store.
 * Credit sales that were written off (#349, ADR-043) are left out too: the debt is gone. Read only.
 */
@Component
class BranchTillCheck implements BranchSellingCheck {

    private final JdbcClient jdbc;

    BranchTillCheck(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public int openCreditSales(UUID branchId) {
        return jdbc.sql("""
                        SELECT count(*) FROM retail_sales
                         WHERE branch_id = ? AND payment_method = 'credit' AND status = 'completed'
                           AND paid_minor < total_minor AND NOT historical
                           AND NOT EXISTS (SELECT 1 FROM retail_sale_writeoffs w WHERE w.sale_id = retail_sales.id)
                        """).param(branchId).query(Integer.class).single();
    }
}
