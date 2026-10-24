-- V012: the report recipients allow-list kept by ADMIN (ADM-09, Q-08; ADR 0016) and the
-- anonymisation mark of a customer erased on request (NFR-03; ADR 0024).

-- Addresses, besides the office address and the customer the report is about, that a report
-- e-mail (EML-07) may be sent to. Managed in /gestione/impostazioni/ by an ADMIN; every change
-- writes the audit log.
CREATE TABLE report_recipient (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email       varchar(254) NOT NULL,
    label       varchar(100),
    added_by    bigint       NOT NULL REFERENCES staff_user (id),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_report_recipient_email_normalised
        CHECK (email = lower(btrim(email)) AND email !~ '\s' AND email LIKE '%@%')
);

CREATE UNIQUE INDEX ux_report_recipient_email ON report_recipient (email);

COMMENT ON TABLE report_recipient IS
    'Allow-list of extra report recipients (ADM-09, Q-08): the office address and the customer the report is about are always allowed; any other address must be here or the send is refused.';
COMMENT ON COLUMN report_recipient.email IS 'Trimmed and lower-case; unique.';
COMMENT ON COLUMN report_recipient.label IS 'A short description typed by the ADMIN (e.g. the role of the mailbox); optional.';

-- NFR-03 erasure = anonymisation (chapter 10 §5): the customer row stays (its orders keep their
-- snapshots for the legal retention period) with every personal value replaced and this mark
-- set; the login identity is disabled. An anonymised customer is DECLINED and never shown in
-- the back-office lists.
ALTER TABLE customer ADD COLUMN anonymised_at timestamptz;

COMMENT ON COLUMN customer.anonymised_at IS
    'When the erasure tool anonymised the row (NFR-03): e-mail, name, address and phone replaced by placeholders, status DECLINED, login disabled. Null for a live customer.';
