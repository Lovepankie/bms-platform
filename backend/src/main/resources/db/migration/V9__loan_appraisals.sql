-- V9: loan appraisals (MVP increment 4, issue #42).
--
-- Additive only (chapter 6 section 6.9). An appraisal is a snapshot of every input, the weights
-- used, the exposure and the result (FR-ORG-04, FR-ORG-05), so a later change to the member never
-- changes a recorded appraisal: the table is append-only. Several appraisals per loan are allowed;
-- the latest counts.

CREATE TABLE lending_loan_appraisals (
    id                            uuid        NOT NULL PRIMARY KEY,
    tenant_id                     uuid        NOT NULL REFERENCES tenants (id),
    created_at                    timestamptz NOT NULL DEFAULT now(),
    loan_id                       uuid        NOT NULL,
    appraised_by                  uuid        NOT NULL,
    declared_monthly_income_minor bigint      CHECK (declared_monthly_income_minor >= 0),
    monthly_obligations_minor     bigint      CHECK (monthly_obligations_minor >= 0),
    visit_notes                   text,
    score                         smallint    NOT NULL CHECK (score BETWEEN 0 AND 100),
    band                          char(1)     NOT NULL CHECK (band IN ('A', 'B', 'C', 'D')),
    components                    jsonb       NOT NULL,
    flags                         text[]      NOT NULL,
    exposure                      jsonb       NOT NULL,
    weights                       jsonb       NOT NULL,
    recommendation                text        NOT NULL CHECK (recommendation IN ('approve', 'review', 'decline')),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id)
);
CREATE INDEX lending_loan_appraisals_loan ON lending_loan_appraisals (tenant_id, loan_id, created_at DESC);
SELECT bms_apply_tenant_rls('lending_loan_appraisals');
SELECT bms_grant_app('lending_loan_appraisals', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_loan_appraisals');
