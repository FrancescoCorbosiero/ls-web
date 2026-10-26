-- V018: saved quotes (ENH-20, flag quotes.saved, ADR 0037). Staff prepare a quote from a website
-- request (ENH-06) for the customer it belongs to: the lines, the amount, the date it is valid
-- until; the customer receives a link (only its SHA-256 is stored) and turns the quote into a
-- pending order after signing in. The quote e-mail is EML-11.

CREATE TABLE quote (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lead_id                 bigint        NOT NULL REFERENCES lead (id),
    customer_id             bigint        NOT NULL REFERENCES customer (id),
    token_hash              varchar(64)   NOT NULL,
    status                  varchar(10)   NOT NULL DEFAULT 'SENT',
    amount                  numeric(10,2) NOT NULL,
    valid_until             date          NOT NULL,
    accessory_service_id    integer       REFERENCES accessory_service (id),
    recipient_city          varchar(100),
    recipient_postal_code   varchar(10),
    note                    varchar(1000),
    created_by              bigint        NOT NULL REFERENCES staff_user (id),
    created_at              timestamptz   NOT NULL DEFAULT now(),
    order_id                bigint        REFERENCES shipment_order (id),
    accepted_at             timestamptz,
    withdrawn_at            timestamptz,
    CONSTRAINT uq_quote_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_quote_status CHECK (status IN ('SENT', 'ACCEPTED', 'WITHDRAWN')),
    CONSTRAINT ck_quote_amount CHECK (amount > 0),
    CONSTRAINT ck_quote_accepted CHECK ((status = 'ACCEPTED') = (order_id IS NOT NULL))
);

CREATE INDEX ix_quote_lead ON quote (lead_id);
CREATE INDEX ix_quote_customer ON quote (customer_id, status);
CREATE UNIQUE INDEX ux_quote_order ON quote (order_id) WHERE order_id IS NOT NULL;

COMMENT ON TABLE quote IS
    'A saved quote (ENH-20, ADR 0037) prepared by staff from a website request for its customer: SENT until the customer turns it into a pending order (ACCEPTED, order_id) or staff withdraw it (WITHDRAWN); usable until valid_until (Europe/Rome day, inclusive).';
COMMENT ON COLUMN quote.token_hash IS 'SHA-256 (hex) of the unguessable token of the link e-mailed to the customer; the token itself is never stored.';
COMMENT ON COLUMN quote.note IS 'Conditions for the customer, printed on the page and the PDF.';

CREATE TABLE quote_line (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    quote_id    bigint        NOT NULL REFERENCES quote (id),
    line_no     smallint      NOT NULL,
    kind        varchar(8)    NOT NULL,
    height_cm   integer       NOT NULL,
    length_cm   integer       NOT NULL,
    width_cm    integer       NOT NULL,
    weight_kg   numeric(9,2)  NOT NULL,
    quantity    integer       NOT NULL,
    CONSTRAINT uq_quote_line_no UNIQUE (quote_id, line_no),
    CONSTRAINT ck_quote_line_no CHECK (line_no >= 1),
    CONSTRAINT ck_quote_line_kind CHECK (kind IN ('GENERIC', 'EPAL')),
    CONSTRAINT ck_quote_line_positive CHECK (height_cm > 0 AND length_cm > 0 AND width_cm > 0 AND weight_kg > 0 AND quantity > 0)
);

COMMENT ON TABLE quote_line IS 'The lines of a saved quote, as the order lines (OP-R4): they become the lines of the order made from it.';

-- EML-11 «Il tuo preventivo» (ENH-20); the next numbers stay reserved for the M2 e-mails to come.
ALTER TABLE email_outbox DROP CONSTRAINT ck_email_outbox_kind;
ALTER TABLE email_outbox ADD CONSTRAINT ck_email_outbox_kind CHECK (kind ~ '^EML-(0[1-9]|1[0-9]|S[1-9])$');
