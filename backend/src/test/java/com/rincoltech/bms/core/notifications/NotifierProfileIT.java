package com.rincoltech.bms.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.core.notifications.internal.LogOnlyNotifier;
import com.rincoltech.bms.core.notifications.internal.RecordingNotifier;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * Raw one-time links are never kept in memory outside dev and test (issue #324, ADR-085): the recording adapter, which
 * keeps the last messages with their links, exists only in the dev and test profiles; the server profile of staging and
 * production gets the log-only adapter, which keeps nothing and never logs a link or a full recipient.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotifierProfileIT extends IntegrationTest {

    static final String LINK = "https://shop-test.example.test/accept-invitation#token=fabricated-token-0000";

    @Autowired
    Notifier notifier;

    /** Every {@link Notifier} the application would create under these profiles, found as component scanning finds it. */
    static Map<String, Notifier> notifiersUnder(String... profiles) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            var scanner = new ClassPathBeanDefinitionScanner(context, false, context.getEnvironment());
            scanner.addIncludeFilter(new AssignableTypeFilter(Notifier.class));
            scanner.scan("com.rincoltech.bms");
            context.refresh();
            return context.getBeansOfType(Notifier.class);
        }
    }

    @Test
    void theTestContextRecords() {
        assertThat(notifier).isInstanceOf(RecordingNotifier.class);
    }

    @Test
    void theServerProfileHasNoRecordingNotifier() {
        assertThat(notifiersUnder("server").values()).singleElement().isInstanceOf(LogOnlyNotifier.class);
        assertThat(notifiersUnder().values()).singleElement().isInstanceOf(LogOnlyNotifier.class);
        assertThat(notifiersUnder("dev").values()).singleElement().isInstanceOf(RecordingNotifier.class);
        assertThat(notifiersUnder("test").values()).singleElement().isInstanceOf(RecordingNotifier.class);
    }

    @Test
    void theLogOnlyNotifierLogsNeitherTheLinkNorTheRecipient(CapturedOutput output) {
        new LogOnlyNotifier()
                .send(new Notifier.Message(
                        "sms",
                        "+256700000045",
                        "core.staff_invitation",
                        Map.of("link", LINK, "tenant_name", "Test Shop")));

        assertThat(output.getAll())
                .contains("notification not sent, no provider")
                .contains("template=core.staff_invitation");
        assertThat(output.getAll())
                .doesNotContain(LINK)
                .doesNotContain("fabricated-token-0000")
                .doesNotContain("+256700000045");
        assertThat(LogOnlyNotifier.class.getDeclaredFields())
                .as("the adapter has nowhere to keep a message")
                .allSatisfy(f -> assertThat(f.getType()).isNotIn(java.util.List.class, java.util.Collection.class));
    }
}
