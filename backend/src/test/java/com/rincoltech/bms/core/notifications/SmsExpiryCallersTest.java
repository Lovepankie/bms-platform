package com.rincoltech.bms.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Issue #772: since #231 a transactional text's own expiry shortens its hour ({@link SmsEvents.MemberSms#expiresAt}).
 * No receipt passes one: the only callers of the forms that take an expiry are the loan reminders and arrears notices
 * (category reminder or arrears, never transactional: {@code LoanRemindersTest}) and the payment link, which expires with
 * its intent. A new caller
 * fails this test until someone decides whether its text may expire early, and adds it here.
 */
class SmsExpiryCallersTest {

    static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.rincoltech.bms");

    @Test
    void onlyTheRemindersAndThePaymentLinkPassAnExpiry() {
        Stream<JavaAccess<?>> constructor = CLASSES
                .get(SmsEvents.MemberSms.class)
                .getConstructor(
                        String.class,
                        UUID.class,
                        String.class,
                        Map.class,
                        String.class,
                        String.class,
                        LocalDate.class,
                        Instant.class)
                .getAccessesToSelf()
                .stream()
                .map(a -> a);
        Stream<JavaAccess<?>> queue = CLASSES
                .get(SmsEvents.class)
                .getMethod(
                        "queueWhenActive",
                        String.class,
                        UUID.class,
                        String.class,
                        String.class,
                        LocalDate.class,
                        Instant.class,
                        Supplier.class,
                        Supplier.class)
                .getAccessesToSelf()
                .stream()
                .map(a -> a);
        Set<String> callers = new TreeSet<>();
        Stream.concat(constructor, queue)
                // A call moved into a lambda counts as its method's: lambda$remind$0 reads remind.
                .map(a -> a.getOrigin().getOwner().getName() + "."
                        + a.getOrigin().getName().replaceAll("^lambda\\$(.+)\\$\\d+$", "$1"))
                // The port itself: its shorter forms pass a null expiry on, and its service passes the caller's on.
                .filter(c -> !c.startsWith(SmsEvents.class.getName())
                        && !c.startsWith("com.rincoltech.bms.core.notifications.internal.SmsEventsService."))
                .forEach(callers::add);
        assertThat(callers)
                .containsExactlyInAnyOrder(
                        "com.rincoltech.bms.lending.loans.internal.LoanNotices.remind",
                        "com.rincoltech.bms.core.payments.internal.PaymentLinkTexts.sendLocked");
    }
}
