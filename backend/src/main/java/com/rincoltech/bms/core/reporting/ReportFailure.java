package com.rincoltech.bms.core.reporting;

/**
 * A report run that must end {@code failed} with a stable, safe code (chapter 7 section 7.11.8):
 * {@code report_too_large}, {@code snapshot_unavailable}, {@code timeout}. The code is what the run row
 * keeps and the screen explains; the message is for the log and never carries a row or a person's data.
 */
public class ReportFailure extends RuntimeException {

    private final String code;

    public ReportFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
