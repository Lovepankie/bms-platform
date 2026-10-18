package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.payments.internal.NotificationReader.Malformed;
import com.rincoltech.bms.core.payments.internal.NotificationReader.Notification;
import com.rincoltech.bms.core.payments.internal.NotificationReader.TooLarge;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a notification may carry and what is read from it (spec 3.6 step 2, 3.12, the callback endpoint threat model):
 * three names, from the query or from a JSON body whatever the content type says, nothing else, each checked for shape.
 */
class NotificationReaderTest {

    static final String ID = "aaaaaaaa-0000-4000-8000-0000000000d1";
    static final String REF = "BMS-ST-000000000000000000000000000000d1";
    final ObjectMapper mapper = JsonMapper.builder().build();

    Notification read(MockHttpServletRequest request) throws Exception {
        return NotificationReader.read(request, mapper, 4096, "staging", "IPNCHANGE");
    }

    static MockHttpServletRequest get(String query) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/payments/callbacks/pesapal/x");
        r.setQueryString(query);
        return r;
    }

    static MockHttpServletRequest post(String contentType, String body) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/payments/callbacks/pesapal/x");
        if (contentType != null) {
            r.setContentType(contentType);
        }
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        return r;
    }

    @Test
    void aGetCarriesTheThreeNamesInTheQueryInAnyCase() throws Exception {
        Notification n = read(
                get("OrderTrackingId=" + ID + "&OrderMerchantReference=" + REF + "&OrderNotificationType=IPNCHANGE"));
        assertThat(n).isEqualTo(new Notification("IPNCHANGE", ID, REF));
        assertThat(read(get("ordertrackingid=" + ID + "&ORDERMERCHANTREFERENCE=" + REF
                                + "&ordernotificationtype=CALLBACKURL"))
                        .type())
                .isEqualTo("CALLBACKURL");
    }

    @Test
    void aPostCarriesThemAsJsonWhateverTheContentTypeSays() throws Exception {
        String json = "{\"OrderNotificationType\":\"IPNCHANGE\",\"OrderTrackingId\":\"" + ID
                + "\",\"OrderMerchantReference\":\"" + REF + "\"}";
        for (String type : new String[] {
            "application/json",
            "text/plain",
            "application/x-www-form-urlencoded",
            "application/xml",
            "multipart/form-data; boundary=x",
            null,
            "garbage"
        }) {
            assertThat(read(post(type, json)))
                    .as("content type %s", type)
                    .isEqualTo(new Notification("IPNCHANGE", ID, REF));
        }
    }

    @Test
    void aBodyThatIsNotAJsonObjectCarriesNothingAndAFormBodyIsNotRead() {
        for (String body : new String[] {
            "",
            "OrderTrackingId=" + ID + "&OrderMerchantReference=" + REF,
            "<xml/>",
            "[1,2]",
            "null",
            "{broken",
            "{\"OrderTrackingId\":\"" + ID + "\""
        }) {
            assertThatThrownBy(() -> read(post("application/x-www-form-urlencoded", body)))
                    .as(body)
                    .isInstanceOf(Malformed.class);
        }
    }

    @Test
    void everyOtherNameIsIgnoredSoAnAmountOrAStatusInAForgedBodyIsNeverLookedAt() throws Exception {
        String json = "{\"OrderTrackingId\":\"" + ID + "\",\"OrderMerchantReference\":\"" + REF
                + "\",\"status\":\"COMPLETED\",\"amount\":\"1\",\"payment_status_description\":\"Completed\",\"OrderNotificationType\":\"IPNCHANGE\"}";
        assertThat(read(post("application/json", json))).isEqualTo(new Notification("IPNCHANGE", ID, REF));
        // Not even to refuse the request: extra names do not make a notification malformed.
        assertThat(read(get("OrderTrackingId=" + ID + "&OrderMerchantReference=" + REF + "&status=3&amount=9&evil=%00"))
                        .trackingId())
                .isEqualTo(ID);
    }

    @Test
    void theTypeDefaultsToTheEndpointsOwnWhenTheProviderLeavesItOut() throws Exception {
        assertThat(read(get("OrderTrackingId=" + ID + "&OrderMerchantReference=" + REF))
                        .type())
                .isEqualTo("IPNCHANGE");
    }

    @Test
    void aMadeUpValueIsMalformedAndNeverEchoed() {
        for (String query : new String[] {
            "",
            "OrderTrackingId=" + ID,
            "OrderMerchantReference=" + REF,
            "OrderTrackingId=not-a-guid&OrderMerchantReference=" + REF,
            "OrderTrackingId=" + ID + "&OrderMerchantReference=BMS-PR-000000000000000000000000000000d1",
            "OrderTrackingId=" + ID + "&OrderMerchantReference=" + REF + "x",
            "OrderTrackingId=" + ID + "&OrderMerchantReference=" + REF + "&OrderNotificationType=IPN%20CHANGE",
            "OrderTrackingId=" + ID + "&OrderMerchantReference=" + REF + "&OrderNotificationType=%3Cscript%3E",
            "OrderTrackingId=" + ID + "%0d%0aX-Evil:1&OrderMerchantReference=" + REF,
            "OrderTrackingId=%zz&OrderMerchantReference=" + REF
        }) {
            assertThatThrownBy(() -> read(get(query))).as(query).isInstanceOf(Malformed.class);
        }
    }

    @Test
    void aNameSentTwiceWithDifferentValuesIsParameterPollutionAndRefused() throws Exception {
        String other = "aaaaaaaa-0000-4000-8000-0000000000d2";
        assertThatThrownBy(() -> read(
                        get("OrderTrackingId=" + ID + "&OrderTrackingId=" + other + "&OrderMerchantReference=" + REF)))
                .isInstanceOf(Malformed.class);
        // The same value twice, or the same value in the query and the body, is harmless.
        assertThat(read(get("OrderTrackingId=" + ID + "&OrderTrackingId=" + ID + "&OrderMerchantReference=" + REF))
                        .trackingId())
                .isEqualTo(ID);
        MockHttpServletRequest both = post(
                "application/json", "{\"OrderTrackingId\":\"" + ID + "\",\"OrderMerchantReference\":\"" + REF + "\"}");
        both.setQueryString("OrderTrackingId=" + ID);
        assertThat(read(both).trackingId()).isEqualTo(ID);
        MockHttpServletRequest clash = post(
                "application/json",
                "{\"OrderTrackingId\":\"" + other + "\",\"OrderMerchantReference\":\"" + REF + "\"}");
        clash.setQueryString("OrderTrackingId=" + ID);
        assertThatThrownBy(() -> read(clash)).isInstanceOf(Malformed.class);
    }

    @Test
    void aBodyOverTheLimitIsTooLargeAndAnOverlongQueryIsMalformed() {
        String big = "{\"OrderTrackingId\":\"" + ID + "\",\"pad\":\"" + "x".repeat(5000) + "\"}";
        assertThatThrownBy(() -> read(post("application/json", big))).isInstanceOf(TooLarge.class);
        String exactly = "x".repeat(4096);
        assertThatThrownBy(() -> read(post("application/json", exactly))).isNotInstanceOf(TooLarge.class);
        assertThatThrownBy(() -> read(get("pad=" + "x".repeat(NotificationReader.MAX_QUERY))))
                .isInstanceOf(Malformed.class);
    }

    @Test
    void aGetNeverReadsABodyEvenIfOneIsSent() throws Exception {
        MockHttpServletRequest r = get("OrderTrackingId=" + ID + "&OrderMerchantReference=" + REF);
        r.setContent(
                ("{\"OrderTrackingId\":\"aaaaaaaa-0000-4000-8000-0000000000ff\"}").getBytes(StandardCharsets.UTF_8));
        assertThat(read(r).trackingId()).isEqualTo(ID);
    }
}
