package com.rincoltech.bms.core.reporting;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One report a vertical offers (chapter 5 section 5.4.3, FR-RPT-01, ADR-038). A vertical declares a bean of
 * this type; the core lists it in the catalogue for a caller who holds {@link #permission()} in a tenant that
 * has {@link #module()} on, and runs it as a queued run.
 *
 * <p>A report has two moments, and the split is the safety design of the framework:
 *
 * <ul>
 *   <li>{@link #prepare} runs in the request, with the signed-in user. It validates and normalises the
 *       parameters and freezes whatever the user's permissions decide (for example the branches they may see)
 *       into a plain value that the run stores.
 *   <li>{@link #query} runs later in the job, inside the run's one read-only snapshot transaction with the
 *       tenant bound, and no user. It reads only from the stored request and writes rows to a
 *       {@link ReportOutput}; it must not open a transaction of its own or read the clock.
 * </ul>
 *
 * Money is integer minor units and rates are basis points, in every cell (ADR-004).
 */
public interface ReportDefinition {

    /** The stable key, for example {@code lending.par}; also the {@code report_key} of a run. */
    String key();

    String title();

    /** One sentence for the catalogue. */
    String description();

    /** The module that must be switched on for the tenant, for example {@code lending}. */
    String module();

    /** The permission (chapter 8) that lets a user run it. */
    String permission();

    /** The parameters it accepts; a request naming any other is refused. */
    List<Param> params();

    /** The output formats it supports, a subset of {@code json} and {@code csv} until the PDF and Excel slices. */
    Set<String> formats();

    /**
     * Request time, with the principal bound. Returns the normalised parameters (every default filled in, so two
     * identical requests are equal and so the output can print them) and the frozen scope. Throws an
     * {@code ApiException} (422) for an invalid parameter.
     *
     * @param params the request's parameters as sent
     */
    Prepared prepare(Map<String, Object> params);

    /**
     * Download time, with the downloader signed in: may they still have a file made under {@code run}'s frozen scope?
     * A report whose scope depends on the requester's branches or officers answers false when their access is now
     * narrower than the scope the file was made under. The default is true (a report with no such scope).
     */
    default boolean stillAllowed(Prepared run) {
        return true;
    }

    /**
     * Job time, in the snapshot transaction. Reads the data and writes it to the output. Throws a
     * {@link ReportFailure} or an {@code ApiException} with a stable code for a failure the user should
     * understand; any other exception is reported as {@code run_failed} and logged without its message.
     */
    void query(Prepared prepared, ReportOutput out);

    /**
     * @param type {@code date}, {@code uuid_list}, {@code enum}, {@code integer} or {@code text}
     * @param values the allowed values of an {@code enum}, else empty
     */
    record Param(String name, String type, boolean required, String description, List<String> values) {

        public Param {
            values = List.copyOf(values);
        }

        public Param(String name, String type, boolean required, String description) {
            this(name, type, required, description, List.of());
        }
    }

    /**
     * What {@link #prepare} returns and a run stores.
     *
     * @param params the normalised parameters, printed on the output (FR-RPT-02); JSON-compatible values only
     * @param scope the frozen scope, JSON-compatible values only; opaque to the core
     * @param asOf the business date the figures stand at (chapter 14 section 14.1)
     */
    record Prepared(Map<String, Object> params, Map<String, Object> scope, LocalDate asOf) {

        /** Copies with nulls allowed (a scope of "every branch" is a null list), and read only. */
        public Prepared {
            params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
            scope = Collections.unmodifiableMap(new LinkedHashMap<>(scope));
        }
    }
}
