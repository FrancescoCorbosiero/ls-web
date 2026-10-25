-- V007: the e-mail outbox and the report runs (chapter 08 §2.3 "supporting tables";
-- chapter 10 §2 "E-mail"; ADM-09, EML-01…EML-09, NFR-04; ADR 0001: no S3, the report PDF
-- is regenerated on demand for the authenticated download).

CREATE TABLE email_outbox (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind                varchar(8)   NOT NULL,
    recipient           varchar(254) NOT NULL,
    reply_to            varchar(254),
    subject             varchar(200) NOT NULL,
    body_text           text,
    body_html           text,
    login_token_id      bigint REFERENCES login_token (id),
    token_ciphertext    bytea,
    status              varchar(12)  NOT NULL DEFAULT 'QUEUED',
    attempts            integer      NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz  NOT NULL DEFAULT now(),
    last_error          varchar(500),
    sent_at             timestamptz,
    provider_message_id varchar(120),
    bodies_purged_at    timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_email_outbox_kind CHECK (kind ~ '^EML-0[1-9]$'),
    CONSTRAINT ck_email_outbox_status CHECK (status IN ('QUEUED', 'SENT', 'FAILED')),
    CONSTRAINT ck_email_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_email_outbox_queued_has_body CHECK (status <> 'QUEUED' OR body_text IS NOT NULL),
    CONSTRAINT ck_email_outbox_sent_has_time CHECK (status <> 'SENT' OR sent_at IS NOT NULL),
    CONSTRAINT ck_email_outbox_token_pair CHECK (token_ciphertext IS NULL OR login_token_id IS NOT NULL)
);

CREATE INDEX ix_email_outbox_queue ON email_outbox (next_attempt_at) WHERE status = 'QUEUED';
CREATE INDEX ix_email_outbox_status_created ON email_outbox (status, created_at);
CREATE INDEX ix_email_outbox_purge ON email_outbox (sent_at) WHERE status = 'SENT' AND bodies_purged_at IS NULL;

CREATE TRIGGER trg_email_outbox_updated_at
    BEFORE UPDATE ON email_outbox
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE email_outbox IS
    'Every e-mail is written here in the business transaction and sent by a worker with retries (chapter 10 §2); nothing is sent directly from a controller. Outside production no row reaches a real recipient.';
COMMENT ON COLUMN email_outbox.kind IS 'The catalogue entry of chapter 09 (EML-01 … EML-09).';
COMMENT ON COLUMN email_outbox.body_text IS 'Plain-text body; nulled with body_html by the retention job 30 days after delivery (Q-18), metadata kept.';
COMMENT ON COLUMN email_outbox.login_token_id IS 'For sign-in, verification, registration and e-mail-change links: the token the e-mail carries.';
COMMENT ON COLUMN email_outbox.token_ciphertext IS
    'The raw one-time token, encrypted with a key from the secret store; the worker decrypts it to build the link and wipes it after delivery. Never logged or shown.';
COMMENT ON COLUMN email_outbox.status IS 'QUEUED (to send or retry), SENT, FAILED (retries exhausted; alert, NFR-04).';
COMMENT ON COLUMN email_outbox.next_attempt_at IS 'When the worker may try next; grows with each failed attempt.';
COMMENT ON COLUMN email_outbox.provider_message_id IS 'The SES message id, for bounce and complaint handling (NFR-04).';
COMMENT ON COLUMN email_outbox.bodies_purged_at IS 'When the retention job removed the bodies and the token ciphertext.';

CREATE TABLE report_run (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id         bigint      NOT NULL REFERENCES customer (id),
    requested_by        bigint      NOT NULL REFERENCES staff_user (id),
    date_from           date        NOT NULL,
    date_to             date        NOT NULL,
    only_delivered      boolean     NOT NULL DEFAULT false,
    send_to_customer    boolean     NOT NULL DEFAULT false,
    recipients          text[]      NOT NULL,
    row_count           integer     NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_report_run_range CHECK (date_from <= date_to),
    CONSTRAINT ck_report_run_row_count CHECK (row_count >= 0)
);

CREATE INDEX ix_report_run_customer ON report_run (customer_id, created_at DESC);
CREATE INDEX ix_report_run_created_at ON report_run (created_at);

COMMENT ON TABLE report_run IS
    'A report requested by staff (ADM-09): one customer, an inclusive date range in Europe/Rome, optionally only delivered orders. The PDF is regenerated on demand for the authenticated download (no stored file); EML-07 links to it. Rows are kept 24 months (PD-16).';
COMMENT ON COLUMN report_run.recipients IS 'The addresses the report e-mail went to: the office address, the customer when chosen, allow-listed addresses (Q-08).';
COMMENT ON COLUMN report_run.row_count IS 'Number of orders in the report when it was requested (written to the audit log too).';
