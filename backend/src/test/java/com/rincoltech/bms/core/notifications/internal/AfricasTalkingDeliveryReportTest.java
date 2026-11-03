package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.notifications.internal.SmsProvider.DeliveryReport;
import com.rincoltech.bms.core.notifications.internal.SmsProvider.UnreadableReport;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Africa's Talking delivery report reader (issue #215, spec section 4.7) on the recorded fabricated bodies of
 * {@code src/test/resources/africastalking}: each provider status maps to ours, the failure reason is kept only for a
 * report that did not deliver and only as a short word, and a body without a usable id or status is refused. The
 * recipient's number in each body is never part of what the reader returns.
 */
class AfricasTalkingDeliveryReportTest {

    static final String ID = "ATXid_fab0000000000000000000001";

    /** A recorded body with its placeholder id replaced, as the form fields the servlet would give. */
    static Map<String, String> recorded(String name, String id) throws IOException {
        String body;
        try (InputStream in = AfricasTalkingDeliveryReportTest.class.getResourceAsStream(
                "/africastalking/delivery-report-" + name + ".txt")) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip().replace("MESSAGE_ID", id);
        }
        Map<String, String> fields = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            fields.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return fields;
    }

    static DeliveryReport read(Map<String, String> fields) throws UnreadableReport {
        return AfricasTalkingSmsProvider.readDeliveryReport(fields::get);
    }

    @Test
    void eachRecordedStatusMapsToOurs() throws Exception {
        assertThat(read(recorded("success", ID))).isEqualTo(new DeliveryReport(ID, "delivered", null));
        assertThat(read(recorded("failed", ID))).isEqualTo(new DeliveryReport(ID, "undelivered", "DeliveryFailure"));
        assertThat(read(recorded("rejected", ID))).isEqualTo(new DeliveryReport(ID, "undelivered", "UserInBlackList"));
        // The provider or the network still holds the message: an interim report.
        assertThat(read(recorded("sent", ID))).isEqualTo(new DeliveryReport(ID, "sent", null));
        assertThat(read(recorded("buffered", ID))).isEqualTo(new DeliveryReport(ID, "sent", null));
        Map<String, String> submitted = recorded("sent", ID);
        submitted.put("status", "Submitted");
        assertThat(read(submitted).status()).isEqualTo("sent");
    }

    @Test
    void theNumberIsNeverReturnedAndAReasonOnlyForAFailureAndOnlyAsAWord() throws Exception {
        DeliveryReport failed = read(recorded("failed", ID));
        assertThat(failed.toString()).doesNotContain("256700000102").doesNotContain("700000102");
        // A reason on a delivered report is dropped; a reason that is not a plain word is "other".
        Map<String, String> success = recorded("success", ID);
        success.put("failureReason", "DeliveryFailure");
        assertThat(read(success).reason()).isNull();
        Map<String, String> odd = recorded("failed", ID);
        odd.put("failureReason", "call +256700000102 <script>");
        assertThat(read(odd).reason()).isEqualTo("other");
    }

    @Test
    void aBodyWithoutAUsableIdOrStatusIsRefusedAndAnUnknownWordIsNamed() throws Exception {
        Map<String, String> noId = recorded("success", ID);
        noId.remove("id");
        assertThatThrownBy(() -> read(noId))
                .isInstanceOf(UnreadableReport.class)
                .hasMessage("malformed");
        Map<String, String> badId = recorded("success", "not an id; DROP TABLE");
        assertThatThrownBy(() -> read(badId)).hasMessage("malformed");
        Map<String, String> noStatus = recorded("success", ID);
        noStatus.remove("status");
        assertThatThrownBy(() -> read(noStatus)).hasMessage("malformed");
        Map<String, String> newWord = recorded("success", ID);
        newWord.put("status", "Expired");
        assertThatThrownBy(() -> read(newWord)).hasMessage("unknown_status");
    }
}
