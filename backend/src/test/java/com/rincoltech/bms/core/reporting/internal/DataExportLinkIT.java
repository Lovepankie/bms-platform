package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.notifications.EmailAttachments;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The weekly copy that is too large to attach (ADR-074 decisions 6 and 9), with the attachment limit set to one byte: the
 * email carries no file and no link that works without signing in, only the business's own Business set-up, where the
 * signed-in Owner makes the full copy. And the documents module never hands the sender a report object larger than it asked
 * for. Every name is fabricated.
 */
@Import(TestReports.class)
@TestPropertySource(
        properties = {
            "bms.reports.max-concurrent-runs=1",
            "bms.reports.timeout=PT30S",
            "bms.reports.runner-enabled=false",
            "bms.data-export.attach-max-bytes=1"
        })
class DataExportLinkIT extends ReportsItSupport {

    @Autowired
    DataExportWeekly weekly;

    @Autowired
    PlatformHost hosts;

    @Autowired
    Documents documents;

    @Test
    void aWorkbookOverTheAttachmentLimitIsEmailedAsTheSignInLinkToBusinessSetUpWithNoFile() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("dxl", false, true);
        DataExportIT.owner(t);
        weekly.tick();
        drain();
        weekly.tick();

        Map<String, Object> row = TestDatabase.owner()
                .sql(
                        "SELECT template_key, params->>'link' AS link, jsonb_exists(params, ?) AS attached FROM notification_outbox"
                                + " WHERE recipient = ?")
                .params(EmailAttachments.PARAM, DataExportIT.ownerEmail(t))
                .query()
                .singleRow();
        assertThat(row.get("template_key")).isEqualTo(DataExportWeekly.LINKED);
        assertThat(row.get("link"))
                .as("the business's own sign-in page, never a signed or public file address")
                .isEqualTo(hosts.tenantOrigin(t.slug()) + "/staff/setup");
        assertThat(row.get("attached")).isEqualTo(false);
        assertThat(TestDatabase.owner()
                        .sql("SELECT status || ' ' || attached FROM data_export_weekly WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .endsWith(" false");
    }

    @Test
    void theDocumentsModuleRefusesToReadAReportObjectLargerThanAsked() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("dxo", false, true);
        DataExportIT.owner(t);
        weekly.tick();
        drain();
        UUID documentId = UUID.fromString(DataExportIT.weeklyDocument(t));
        assertThatThrownBy(() -> tenantJobs.callAs(t.tenantId(), () -> documents.readReportObject(documentId, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("larger");
        // Within the limit it is the stored workbook.
        assertThat(tenantJobs.callAs(t.tenantId(), () -> documents.readReportObject(documentId, 50_000_000L)))
                .hasValueSatisfying(bytes -> assertThat(bytes).startsWith((byte) 'P', (byte) 'K'));
    }
}
