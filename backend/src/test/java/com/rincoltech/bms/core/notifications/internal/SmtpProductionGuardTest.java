package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.notifications.internal.NotificationProperties.Smtp;
import org.junit.jupiter.api.Test;

/**
 * Launch readiness M10, ADR-059, issue #426: the API refuses to start in production without SMTP, naming the unset
 * variables and never a value. Every host, account and address is fabricated.
 */
class SmtpProductionGuardTest {

    static final Smtp FULL = new Smtp(
            "smtp.example.test", 465, "mailer", "secret-value-1", "BMS by Rincol Tech <no-reply@example.test>");

    @Test
    void productionWithEveryValueStarts() {
        assertThatCode(() -> FULL.requireInProduction("production")).doesNotThrowAnyException();
        assertThatCode(() -> FULL.requireInProduction(" Production ")).doesNotThrowAnyException();
    }

    @Test
    void productionWithoutSmtpRefusesToStartAndNamesWhatIsMissing() {
        assertThatThrownBy(() -> new Smtp(null, null, null, null, null).requireInProduction("production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "BMS_ENVIRONMENT=production needs email: set BMS_SMTP_HOST, BMS_SMTP_USER, BMS_SMTP_PASSWORD,"
                                + " BMS_MAIL_FROM");
        assertThatThrownBy(() -> new Smtp("smtp.example.test", 465, "mailer", "secret-value-1", " ")
                        .requireInProduction("production"))
                .hasMessage("BMS_ENVIRONMENT=production needs email: set BMS_MAIL_FROM")
                .hasMessageNotContaining("secret-value-1");
    }

    @Test
    void productionRefusesAFromThatIsNotAnAddress() {
        assertThatThrownBy(() -> new Smtp("smtp.example.test", 465, "mailer", "secret-value-1", "no reply at example")
                        .requireInProduction("production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BMS_MAIL_FROM is not an email address (BMS_ENVIRONMENT=production)");
    }

    /**
     * Review of #433: BMS_ENVIRONMENT is read as SmsSettings and PesapalSettings read it (trimmed, lower case, exact), so one
     * value turns every production guard on or none.
     */
    @Test
    void productionIsReadAsTheOtherGuardsReadIt() {
        Smtp none = new Smtp(null, null, null, null, null);
        for (String production : new String[] {"production", "Production", " PRODUCTION ", "\tproduction\n"}) {
            assertThatThrownBy(() -> none.requireInProduction(production))
                    .as("'%s'", production)
                    .isInstanceOf(IllegalStateException.class);
        }
        for (String other : new String[] {"prod", "production-eu", "pre-production", "staging"}) {
            assertThatCode(() -> none.requireInProduction(other))
                    .as("'%s'", other)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void stagingALaptopAndAnUnsetEnvironmentStayQuietlyOff() {
        Smtp none = new Smtp(null, null, null, null, null);
        assertThatCode(() -> none.requireInProduction("staging")).doesNotThrowAnyException();
        assertThatCode(() -> none.requireInProduction("")).doesNotThrowAnyException();
        assertThatCode(() -> none.requireInProduction(null)).doesNotThrowAnyException();
    }

    /** The guard runs where the application builds its email sender, so a production start without SMTP fails there. */
    @Test
    void theEmailChannelBeanRunsTheGuard() {
        NotificationProperties unset = new NotificationProperties(null, null, null);
        assertThatThrownBy(() -> new SmtpEmailChannel(unset, null, null, null, null, null, null, "production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("BMS_ENVIRONMENT=production needs email");
    }
}
