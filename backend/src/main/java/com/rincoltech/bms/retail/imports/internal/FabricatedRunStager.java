package com.rincoltech.bms.retail.imports.internal;

import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportRuns.PolicyDecision;
import com.rincoltech.bms.core.imports.ImportTemplate.Policy;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stages a fabricated run of the import screens on staging (issue #138, review of #882): a run marked fabricated, one batch
 * per file of an {@code import-retail} export directory read with the {@code retail_v1} export mapping, validated, reviewed
 * as an operator would (a row with an open blocking issue, or one whose code repeats an earlier row's, left out; every
 * other warning overridden with a note; the receivable decision recorded as treated as settled), dry run and made ready.
 * It is the run the rehearsal's acceptance check rehearses until the operator screens (#141) can upload one. Only on
 * staging ({@code BMS_ENVIRONMENT} exactly {@code staging}) and only the repository's fabricated
 * {@code fixtures/retail/import-sample/}, checked file by file against {@link #FIXTURE}, so a real export can never be
 * marked fabricated. No person: every id it records is the nil id.
 */
@Service
public class FabricatedRunStager {

    /** Who stages the run: no person, the nil id, as a run of the import-retail command is opened by. */
    static final UUID OPERATOR = new UUID(0L, 0L);

    static final String MAPPING = "rehearsal-fabricated-export";

    static final String NOTE = "Fabricated run staged for a rehearsal (issue #138)";

    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    /**
     * The SHA-256 of each file of the repository's fabricated {@code fixtures/retail/import-sample/}, the only export this
     * stages (review of #882). A change to the fixture changes these; {@code StageImportRunIT} stages the fixture itself.
     */
    static final Map<String, String> FIXTURE = Map.ofEntries(
            Map.entry("balances", "d5d8424f43e2b3eba2a0318708cd6c87c0cd4d9d6fe7a07f753d83f9d13921c4"),
            Map.entry("branches", "098c584d28a1807aa87fabf602c504d5fda34528cc40e09f674d8d9ff6c0b59e"),
            Map.entry("categories", "4b32b16f834cbecaa088819c60052627b940ab26927e52aaa09a5437625c1840"),
            Map.entry("customers", "4f714fb78629716ecefb44645b1a0879a8dcabc1da613481a2a13e3385dbc90f"),
            Map.entry("products", "311ee979c6f1d0e76e0cef742806cf3a1b3a1d7f52415109ecc8d02b1af7dbcc"),
            Map.entry("purchases", "d932d604fc502b2b2bb55f1be3432ef2af1669080ecba9ae1a2d39dea85e9665"),
            Map.entry("sales", "b400b844e2123f3b618d71ef9fe7e9bfcc3f91a75367ef0f3432a728eebdb8fe"),
            Map.entry("suppliers", "4b1891efb32aed839ff8b287ea63fd4f7cfb40817de125a458ee2e1a5880d278"),
            Map.entry("units", "9800a125b341be2ea7e8e02b0d0b127875de9e704eb7a16f97b1f3fc0479b5c9"),
            Map.entry("usage", "ab95400c0b4ec904511c858b8e922354fecb829d7a059b9b74e163e2fdf1ce61"));

    private final TenantJobs tenants;
    private final ImportRuns runs;
    private final ImportMappings mappings;
    private final ImportUploads uploads;
    private final ImportValidation validation;
    private final ImportReview review;
    private final BusinessClock clock;
    private final String environment;

    FabricatedRunStager(
            TenantJobs tenants,
            ImportRuns runs,
            ImportMappings mappings,
            ImportUploads uploads,
            ImportValidation validation,
            ImportReview review,
            BusinessClock clock,
            @Value("${bms.environment:}") String environment) {
        this.clock = clock;
        this.tenants = tenants;
        this.runs = runs;
        this.mappings = mappings;
        this.uploads = uploads;
        this.validation = validation;
        this.review = review;
        this.environment = environment == null ? "" : environment.strip();
    }

    /**
     * Stages the run for the business with this slug, which must have retail switched on and not trade yet (the dry run
     * of a trading business needs the approved override); answers the run's id, ready. {@link IllegalArgumentException}
     * anywhere but staging, for an unknown business, or for a directory that is not the fabricated sample, unchanged.
     */
    public UUID stage(String slug, Path dir) {
        // An allowlist (review of #882): an unset, blank or misspelt environment is refused like production.
        if (!"staging".equals(environment)) {
            throw new IllegalArgumentException("only on staging (BMS_ENVIRONMENT=staging), never anywhere else");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String set : RetailV1Template.SETS) {
            Path file = dir.resolve(set + ".jsonl");
            if (Files.isRegularFile(file)) {
                files.put(set, read(file));
            }
        }
        // Only the repository's fabricated sample, byte for byte, so a real export can never be marked fabricated.
        boolean everyFile = files.keySet().equals(FIXTURE.keySet());
        boolean unchanged = files.entrySet().stream().noneMatch(FabricatedRunStager::altered);
        if (!everyFile || !unchanged) {
            throw new IllegalArgumentException("only the fabricated import-sample fixture, unchanged, is staged");
        }
        return tenants.callAsTenant(slug, "retail", () -> {
            Map<String, Object> document = RetailV1Template.exportMapping(KAMPALA);
            UUID mapping = mappings.save(MAPPING, document, OPERATOR, NOTE).id();
            UUID run = runs.open(OPERATOR, true).id();
            files.forEach((set, bytes) -> upload(run, mapping, set, bytes));
            validation.validate(run);
            settle(run);
            runs.dryRun(run, OPERATOR);
            runs.transition(run, "ready");
            return run;
        });
    }

    private void upload(UUID run, UUID mapping, String set, byte[] bytes) {
        ImportUploads.ParseOptions options = mappings.parseOptions(mapping, set);
        ByteArrayInputStream content = new ByteArrayInputStream(bytes);
        String name = set + ".jsonl";
        ImportUploads.Upload upload = new ImportUploads.Upload(name, content, bytes.length, OPERATOR, options);
        ImportUploads.Batch batch = uploads.upload(run, upload);
        if (!"parsed".equals(batch.status())) {
            throw new IllegalStateException("the file " + set + ".jsonl was not read: " + batch.status());
        }
    }

    /** The review of the run, issue by issue, then the receivable decision. */
    private void settle(UUID run) {
        for (int pass = 0; pass < 5_000; pass++) {
            ImportValidation.Issue next = openIssue(run);
            if (next == null) {
                LocalDate day = clock.today(KAMPALA);
                PolicyDecision decision = new PolicyDecision(Policy.TREAT_AS_SETTLED, day, NOTE, OPERATOR, null, null);
                runs.recordPolicy(run, Policy.RECEIVABLE, decision);
                return;
            }
            String kind = "blocking".equals(next.severity()) || "CODE_DUPLICATE".equals(next.code())
                    ? "exclude_row"
                    : "override";
            int version = next.version();
            ImportReview.Resolution resolution = new ImportReview.Resolution(kind, version, null, null, null, NOTE);
            review.resolve(next.id(), resolution, OPERATOR);
        }
        throw new IllegalStateException("the review of the fabricated run did not settle");
    }

    /** An open blocking issue first, then an open warning; null when none is open. */
    private ImportValidation.Issue openIssue(UUID run) {
        ImportValidation.Issue warning = null;
        for (ImportUploads.Batch batch : uploads.batches(run)) {
            for (ImportValidation.Issue issue : validation.issues(batch.id())) {
                if (!"open".equals(issue.status())) {
                    continue;
                }
                if ("blocking".equals(issue.severity())) {
                    return issue;
                }
                if ("warning".equals(issue.severity()) && warning == null) {
                    warning = issue;
                }
            }
        }
        return warning;
    }

    private static boolean altered(Map.Entry<String, byte[]> file) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(file.getValue());
            return !HexFormat.of().formatHex(digest).equals(FIXTURE.get(file.getKey()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
