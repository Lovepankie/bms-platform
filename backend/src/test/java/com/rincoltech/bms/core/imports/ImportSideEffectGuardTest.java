package com.rincoltech.bms.core.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.kernel.ImportedRecords;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Why no side effect escapes an import batch (issue #133, review of f3be0a6 on #872). V143's triggers refuse a message, a
 * task or an approval request written inside a batch's transaction, while {@code app.import_commit} is on. That covers every
 * path only while no work runs after the batch commits or outside its transaction: today every event listener of the
 * application is a plain {@code @EventListener}, run inside the publisher's transaction (and skips an event an import
 * batch published, which carries the marker; Lead's note on the review of 8e570b0), there is no
 * {@code @TransactionalEventListener}, no {@code @ApplicationModuleListener} or {@code @Async} listener, no persisted
 * event publication (Spring Modulith's events starters), and the only after-commit work is the documents module's object
 * storage clean-up. This fails as soon as one of those appears, so whoever adds it extends the import's guard first.
 * Beyond the triggers, an event published in a batch carries the {@code imported} marker ({@code ImportedRecords}, Lead
 * ruling 3 on #872), which its listeners skip, so a listener moved to a persisted {@code event_publication} would still
 * see that the record was imported.
 */
class ImportSideEffectGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** Work that would run after a batch commits, or on another thread, where the import's setting no longer holds. */
    private static final Pattern LATER = Pattern.compile(
            "@TransactionalEventListener|@ApplicationModuleListener|@Async\\b|\\bafterCommit\\(|\\bafterCompletion\\(");

    /**
     * The after-commit work that sends nothing: the documents module removes stored objects after its transaction, and the
     * import's own mark of a batch's transaction is let go when it ends.
     */
    private static final Set<String> STORAGE_ONLY =
            Set.of("DocumentService.java", "DocumentErasure.java", "ImportedRecords.java");

    @Test
    void noWorkRunsAfterABatchCommitsWhereTheImportGuardCannotSeeIt() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (STORAGE_ONLY.contains(name) || file.toString().contains("core/imports")) {
                    continue;
                }
                if (LATER.matcher(Files.readString(file)).find()) {
                    found.add(file.toString());
                }
            }
        }

        assertThat(found)
                .as("work after commit or on another thread escapes V143's guard: extend it before adding this")
                .isEmpty();
    }

    /** An event published: {@code publishEvent(new Name(}. */
    private static final Pattern PUBLISHED = Pattern.compile("publishEvent\\(\\s*new\\s+([A-Z][A-Za-z0-9]*)\\(");

    /**
     * Every use of a new transaction, each with why nothing it writes is a message, a task or an approval an import batch
     * must not make (Lead's note on the review of 8e570b0, #872): {@code SET LOCAL app.import_commit} does not carry into
     * a new transaction, so the triggers would not see it. A new one fails the build until it is listed here with a reason.
     */
    private static final Map<String, String> NEW_TRANSACTIONS = Map.ofEntries(
            Map.entry("MemberService.java", "a read-only check of the plan's member limit"),
            Map.entry("RunExecutor.java", "a read-only poll for a report run's cancel"),
            Map.entry("AiCallLog.java", "the AI gateway's call log, which refuses to run inside a transaction"),
            Map.entry("SmsOutboxChannel.java", "the SMS channel's mode; its delivery row is the outbox worker's"),
            Map.entry("SmsDeliveryReports.java", "a daily count of the provider's delivery reports"),
            Map.entry("PaymentsSettingsGroup.java", "a read-only read of the payment settings"),
            Map.entry("AccountCredentials.java", "a read-only read of a payment account's secret"),
            Map.entry("PaymentCounters.java", "a daily count of payment callbacks"),
            Map.entry("AskStore.java", "the help assistant's question log"),
            Map.entry("SetupStore.java", "the setup guide's progress"),
            Map.entry("DocumentService.java", "a stored document's own row, written whatever the caller does"));

    // JUSTIFICATION-A3: the review of 8e570b0 and the Lead's note on #872: every event, listener and new transaction.
    @Test
    void everyPublishedEventCarriesTheImportedMarker() throws Exception {
        Map<String, Path> sources = new HashMap<>();
        Set<String> published = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sources.put(file.getFileName().toString().replace(".java", ""), file);
                Matcher m = PUBLISHED.matcher(Files.readString(file));
                while (m.find()) {
                    published.add(m.group(1));
                }
            }
        }
        List<String> unmarked = new ArrayList<>();
        for (String name : published) {
            String className = MAIN.relativize(sources.get(name))
                    .toString()
                    .replace(".java", "")
                    .replace('/', '.');
            if (!ImportedRecords.Marked.class.isAssignableFrom(Class.forName(className))) {
                unmarked.add(className);
            }
        }

        assertThat(published).contains("PushNotice", "ApprovalAmountsChanged");
        assertThat(unmarked)
                .as("an event an import batch may publish says so: implement ImportedRecords.Marked")
                .isEmpty();
    }

    @Test
    void everyEventListenerSkipsAnImportedEvent() throws IOException {
        List<String> listeners = new ArrayList<>();
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (text.contains("@EventListener")) {
                    listeners.add(file.getFileName().toString());
                    if (!text.contains(".imported()")) {
                        found.add(file.toString());
                    }
                }
            }
        }

        assertThat(listeners).contains("PushNotices.java", "ApprovalAmountsListener.java");
        assertThat(found)
                .as("a listener may run inside an import batch: it skips an imported event")
                .isEmpty();
    }

    @Test
    void everyNewTransactionIsListedWithWhyAnImportCannotMisuseIt() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (!NEW_TRANSACTIONS.containsKey(file.getFileName().toString())
                        && Files.readString(file).contains("REQUIRES_NEW")) {
                    found.add(file.toString());
                }
            }
        }

        assertThat(found)
                .as("a new transaction escapes app.import_commit: list it with why it writes nothing an import refuses")
                .isEmpty();
    }

    @Test
    void noEventPublicationIsPersistedForLater() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .as("a persisted event publication would be delivered after the batch commits")
                .doesNotContain("spring-modulith-starter-jdbc")
                .doesNotContain("spring-modulith-starter-jpa")
                .doesNotContain("spring-modulith-events");
    }
}
