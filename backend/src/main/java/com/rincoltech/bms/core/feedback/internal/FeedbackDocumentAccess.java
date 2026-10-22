package com.rincoltech.bms.core.feedback.internal;

import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.kernel.Principal;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * This module's registration as the reader of {@code core.feedback} documents (the screenshot of a feedback item, ADR-065):
 * the staff user who sent it, and a platform operator who may read tenants (the operator's read binds the item's tenant
 * first, so it never reaches another business's documents). Nobody else inside the business, administrators included.
 */
@Component
class FeedbackDocumentAccess implements DocumentAccess {

    static final String SUBJECT = "core.feedback";

    private final JdbcClient jdbc;

    FeedbackDocumentAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public boolean hideDenial() {
        return true;
    }

    @Override
    public boolean canRead(Principal principal, UUID subjectId) {
        if ("platform".equals(principal.kind())) {
            return principal.hasPermission("platform.tenants.read");
        }
        return "staff".equals(principal.kind())
                && jdbc.sql("SELECT count(*) FROM feedback_items WHERE id = ? AND sender_user_id = ?")
                                .params(subjectId, principal.userId())
                                .query(Long.class)
                                .single()
                        > 0;
    }
}
